package the.block.is_.awake.modid

import java.util.ArrayDeque
import java.util.IdentityHashMap
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.gamerules.GameRules
import net.minecraft.world.level.block.AbstractFurnaceBlock
import net.minecraft.world.level.block.FireBlock
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity
import net.minecraft.world.level.block.entity.CampfireBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk

/**
 * Tracks the "ticking" state of every loaded chunk.
 *  - chunk stops ticking -> stamp current time
 *  - chunk ticks again   -> catch up random ticks + furnaces/brewing/campfire
 * While a chunk ticks, its stamp is refreshed every 30 s so it is never lost.
 */
object ChunkCatchUp {
    private const val MIN_ELAPSED_TICKS = 100L
    private const val MAX_TICKS_PER_BLOCK = 256
    private const val MAX_FURNACE_TICKS = 72_000L
    /** brewing stand / campfire: one cycle is enough */
    private const val MAX_STATION_TICKS = 1_200L
    private const val BUDGET_NANOS = 2_000_000L
    /** how often chunk states are checked (10 ticks = 0.5 s) */
    private const val POLL_INTERVAL = 10
    /** while a chunk is ticking, refresh its saved "last seen" time every 30 s (600 ticks) */
    private const val REFRESH_INTERVAL = 600L

    private const val DEBUG = false
    private var debugCount = 0
    private var pollTimer = 0

    private class Tracked(val chunk: LevelChunk) {
        var ticking = false
        var lastRefresh = 0L
    }

    private val QUEUE = ArrayDeque<Job>()
    private val LOADED = IdentityHashMap<ServerLevel, MutableMap<ChunkPos, Tracked>>()

    private fun debug(msg: String) {
        if (DEBUG && debugCount < 200) {
            debugCount++
            Theblockkeepsticking.LOGGER.info("[CatchUp] $msg")
        }
    }

    fun init() {
        // world opened: add the real offline time to the mod clock
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            val nowMs = System.currentTimeMillis()
            for (level in server.allLevels) {
                val shutdown: Long = level.getAttached(ModAttachments.SHUTDOWN_MILLIS) ?: continue
                level.removeAttached(ModAttachments.SHUTDOWN_MILLIS)
                val gapTicks = ((nowMs - shutdown) / 50L).coerceAtLeast(0L)
                val total = (level.getAttached(ModAttachments.OFFLINE_TICKS) ?: 0L) + gapTicks
                level.setAttached(ModAttachments.OFFLINE_TICKS, total)
                debug("offline gap = $gapTicks ticks, total offline = $total")
            }
        }
        ServerLifecycleEvents.SERVER_STOPPED.register { reset() }

        // world closing: stamp ticking chunks, keep old stamp for stopped ones
        ServerLifecycleEvents.SERVER_STOPPING.register { server ->
            for ((level, map) in LOADED) {
                val now = virtualTime(level)
                for (t in map.values) {
                    if (t.ticking) t.chunk.setAttached(ModAttachments.LAST_SEEN_TICK, now)
                }
            }
            val ms = System.currentTimeMillis()
            for (level in server.allLevels) level.setAttached(ModAttachments.SHUTDOWN_MILLIS, ms)
            reset()
        }

        ServerChunkEvents.CHUNK_LOAD.register { level, chunk, _ ->
            LOADED.computeIfAbsent(level) { HashMap() }[chunk.pos] = Tracked(chunk)
        }
        ServerChunkEvents.CHUNK_UNLOAD.register { level, chunk -> onUnload(level, chunk) }

        ServerTickEvents.END_SERVER_TICK.register {
            if (++pollTimer >= POLL_INTERVAL) {
                pollTimer = 0
                poll()
            }
            processQueue()
        }
    }

    /** mod clock = game time + time spent outside the world */
    private fun virtualTime(level: ServerLevel): Long =
        level.gameTime + (level.getAttached(ModAttachments.OFFLINE_TICKS) ?: 0L)

    private fun reset() {
        QUEUE.clear()
        LOADED.clear()
    }

    private fun onUnload(level: ServerLevel, chunk: LevelChunk) {
        val tracked = LOADED[level]?.remove(chunk.pos)
        if (tracked != null && tracked.ticking) {
            chunk.setAttached(ModAttachments.LAST_SEEN_TICK, virtualTime(level))
            debug("unload-while-ticking ${chunk.pos}")
        }
        QUEUE.removeIf { it.chunk === chunk }
    }

    private fun poll() {
        for ((level, map) in LOADED) {
            val now = virtualTime(level)
            for (t in map.values) {
                val c = t.chunk
                val nowTicking = level.shouldTickBlocksAt(BlockPos(c.pos.middleBlockX, 0, c.pos.middleBlockZ))
                if (nowTicking == t.ticking) {
                    // still ticking: keep the saved time fresh, so it is never lost
                    if (nowTicking && now - t.lastRefresh >= REFRESH_INTERVAL) {
                        c.setAttached(ModAttachments.LAST_SEEN_TICK, now)
                        t.lastRefresh = now
                    }
                    continue
                }
                t.ticking = nowTicking
                if (nowTicking) {
                    resume(level, c, now)
                    t.lastRefresh = now
                } else {
                    c.setAttached(ModAttachments.LAST_SEEN_TICK, now)
                    debug("stopped ${c.pos} stamp=$now")
                }
            }
        }
    }

    private fun resume(level: ServerLevel, chunk: LevelChunk, now: Long) {
        val last: Long? = chunk.getAttached(ModAttachments.LAST_SEEN_TICK)
        // from now on the stamp means "last time this chunk was ticking"
        chunk.setAttached(ModAttachments.LAST_SEEN_TICK, now)
        if (last == null) return

        val elapsed = now - last
        if (elapsed < MIN_ELAPSED_TICKS) return

        val rts: Int = level.gameRules.get(GameRules.RANDOM_TICK_SPEED)
        val expected = elapsed * (rts.coerceAtLeast(0) / 4096.0)
        debug("resume ${chunk.pos} elapsed=$elapsed expectedPerBlock=$expected")
        QUEUE.add(Job(level, chunk, elapsed, expected))
    }

    private fun processQueue() {
        if (QUEUE.isEmpty()) return
        val deadline = System.nanoTime() + BUDGET_NANOS
        while (QUEUE.isNotEmpty() && System.nanoTime() < deadline) {
            if (QUEUE.peek().run(deadline)) QUEUE.poll()
        }
    }

    /** supported block entities: add more types here */
    private fun isSupported(be: BlockEntity): Boolean =
        be is AbstractFurnaceBlockEntity || be is BrewingStandBlockEntity || be is CampfireBlockEntity

    /** runs one real tick of the block entity using the block's own ticker */
    @Suppress("UNCHECKED_CAST")
    private fun tickOnce(level: ServerLevel, be: BlockEntity): Boolean {
        if (be.isRemoved || level.getBlockEntity(be.blockPos) !== be) return false
        val state = level.getBlockState(be.blockPos)
        val ticker = state.getTicker(level, be.type as BlockEntityType<BlockEntity>)
            as BlockEntityTicker<BlockEntity>? ?: return false
        ticker.tick(level, be.blockPos, state, be)
        return true
    }

    private fun skip(state: BlockState): Boolean =
        !state.isRandomlyTicking || state.block is FireBlock

    private class Job(
        val level: ServerLevel,
        val chunk: LevelChunk,
        val elapsed: Long,
        val expectedPerBlock: Double
    ) {
        private val pos = BlockPos.MutableBlockPos()
        private val baseX = chunk.pos.minBlockX
        private val baseZ = chunk.pos.minBlockZ
        private var sectionIndex = 0
        private var blockIndex = 0
        private var randomDone = expectedPerBlock <= 0.0

        private var machines: List<BlockEntity>? = null
        private var machineIdx = 0
        private var machineTicks = 0L
        private var unlitStreak = 0

        fun run(deadline: Long): Boolean {
            if (!randomDone) {
                if (!runRandom(deadline)) return false
                randomDone = true
            }
            return runMachines(deadline)
        }

        private fun runRandom(deadline: Long): Boolean {
            val sections = chunk.sections
            while (sectionIndex < sections.size) {
                val section = sections[sectionIndex]
                if (section.hasOnlyAir() || !section.isRandomlyTickingBlocks) {
                    sectionIndex++
                    blockIndex = 0
                    continue
                }
                val baseY = chunk.getSectionYFromSectionIndex(sectionIndex) shl 4

                while (blockIndex < 4096) {
                    val i = blockIndex++
                    val x = i and 15
                    val z = (i shr 4) and 15
                    val y = i shr 8
                    var state = section.getBlockState(x, y, z)
                    if (!skip(state)) {
                        val n = rollTicks()
                        if (n > 0) {
                            pos.set(baseX + x, baseY + y, baseZ + z)
                            for (t in 0 until n) {
                                state.randomTick(level, pos, level.getRandom())
                                state = level.getBlockState(pos)
                                if (skip(state)) break
                            }
                        }
                    }
                    if ((i and 63) == 0 && System.nanoTime() >= deadline) return false
                }
                sectionIndex++
                blockIndex = 0
            }
            return true
        }

        private fun runMachines(deadline: Long): Boolean {
            val list = machines ?: chunk.blockEntities.values
                .filter { isSupported(it) }
                .also { machines = it }

            while (machineIdx < list.size) {
                val be = list[machineIdx]
                val isFurnace = be is AbstractFurnaceBlockEntity
                val limit = minOf(elapsed, if (isFurnace) MAX_FURNACE_TICKS else MAX_STATION_TICKS)

                while (machineTicks < limit) {
                    if (!tickOnce(level, be)) break
                    machineTicks++

                    if (isFurnace) {
                        val after = level.getBlockState(be.blockPos)
                        val lit = after.hasProperty(AbstractFurnaceBlock.LIT) &&
                            after.getValue(AbstractFurnaceBlock.LIT)
                        if (lit) unlitStreak = 0 else if (++unlitStreak >= 2) break
                    }
                    if ((machineTicks and 63L) == 0L && System.nanoTime() >= deadline) return false
                }
                machineIdx++
                machineTicks = 0
                unlitStreak = 0
            }
            return true
        }

        private fun rollTicks(): Int {
            var base = expectedPerBlock.toInt()
            val frac = expectedPerBlock - base
            if (frac > 0 && level.getRandom().nextDouble() < frac) base++
            return minOf(base, MAX_TICKS_PER_BLOCK)
        }
    }
}
