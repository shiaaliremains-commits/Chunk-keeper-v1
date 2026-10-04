package the.block.is_.awake.modid

import java.util.UUID
import kotlin.math.abs
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.Items

/**
 * Frame kinds sent to the client:
 *  0 saved zone (chunk frame)      1 corner-1 pillar
 *  2 chunks kept loaded (valid)    3 chunks kept loaded (invalid size)
 *  4 hovered block                 5 exact selection (corner 1 -> looked block)
 *  6 flash over the exact selection right after saving
 */
object ZoneManager {
    private const val SYNC_INTERVAL = 1
    private const val FLASH_TICKS = 60L

    private class Session {
        var active = false
        var corner: BlockPos? = null
        var preview: BlockPos? = null
        var lastSent = ""
        var flashBox: WandBox? = null
        var flashUntil = 0L
    }

    private data class Zone(val minX: Int, val minZ: Int, val maxX: Int, val maxZ: Int) {
        fun contains(cx: Int, cz: Int) = cx in minX..maxX && cz in minZ..maxZ
        fun width() = maxX - minX + 1
        fun depth() = maxZ - minZ + 1
        fun encode() = "$minX,$minZ,$maxX,$maxZ"

        companion object {
            fun decode(s: String): Zone? {
                val p = s.split(",").mapNotNull { it.toIntOrNull() }
                return if (p.size == 4) Zone(p[0], p[1], p[2], p[3]) else null
            }

            fun between(a: BlockPos, b: BlockPos) = Zone(
                minOf(a.x shr 4, b.x shr 4), minOf(a.z shr 4, b.z shr 4),
                maxOf(a.x shr 4, b.x shr 4), maxOf(a.z shr 4, b.z shr 4)
            )
        }
    }

    private enum class Kind(val symbol: String, val color: ChatFormatting) {
        OK("\u2714", ChatFormatting.GREEN),
        ERR("\u2716", ChatFormatting.RED),
        INFO("\u00BB", ChatFormatting.AQUA)
    }

    private val sessions = HashMap<UUID, Session>()
    private var tickCounter = 0

    fun init() {
        ModConfig.load()
        PayloadTypeRegistry.serverboundPlay().register(WandActionPayload.TYPE, WandActionPayload.CODEC)
        PayloadTypeRegistry.clientboundPlay().register(WandStatePayload.TYPE, WandStatePayload.CODEC)
        PayloadTypeRegistry.clientboundPlay().register(WandBoxesPayload.TYPE, WandBoxesPayload.CODEC)

        ServerPlayNetworking.registerGlobalReceiver(WandActionPayload.TYPE) { payload, context ->
            handle(context.player(), payload)
        }
        ServerLifecycleEvents.SERVER_STOPPED.register { sessions.clear() }

        ServerTickEvents.END_SERVER_TICK.register { server ->
            if (sessions.isNotEmpty() && ++tickCounter >= SYNC_INTERVAL) {
                tickCounter = 0
                syncAll(server)
            }
        }
    }

    private fun say(player: ServerPlayer, kind: Kind, text: String) {
        val msg = Component.literal(kind.symbol + " ").withStyle(kind.color, ChatFormatting.BOLD)
            .append(Component.literal(text).withStyle(ChatFormatting.WHITE))
        player.sendSystemMessage(msg, false)
    }

    private fun sendHud(player: ServerPlayer, text: String) {
        val msg = Component.literal(text).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
        player.sendSystemMessage(msg, true)
    }

    private fun holdingStick(player: ServerPlayer) = player.mainHandItem.item == Items.STICK

    private fun load(level: ServerLevel): List<Zone> =
        (level.getAttached(ModAttachments.ZONES) ?: emptyList()).mapNotNull { Zone.decode(it) }

    private fun save(level: ServerLevel, zones: List<Zone>) {
        level.setAttached(ModAttachments.ZONES, zones.map { it.encode() })
    }

    private fun sizeError(zone: Zone): String? {
        val min = ModConfig.minChunksPerSide
        val max = ModConfig.maxChunksPerSide
        if (zone.width() > max || zone.depth() > max) {
            return "Too big: ${zone.width()}x${zone.depth()} chunks (max ${max}x$max)"
        }
        if (zone.width() < min || zone.depth() < min) {
            return "Too small: ${zone.width()}x${zone.depth()} chunks (min ${min}x$min)"
        }
        return null
    }

    private fun handle(player: ServerPlayer, payload: WandActionPayload) {
        val level = player.level() as ServerLevel
        val s = sessions.computeIfAbsent(player.uuid) { Session() }

        when (payload.action) {
            WandActionPayload.TOGGLE -> {
                if (!holdingStick(player)) {
                    say(player, Kind.ERR, "Hold a Stick to use the zone wand")
                    return
                }
                s.active = !s.active
                s.corner = null
                s.preview = null
                s.lastSent = ""
                s.flashBox = null
                ServerPlayNetworking.send(player, WandStatePayload(s.active))
                if (s.active) {
                    level.playSound(null, player.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.8f, 1.2f)
                    say(player, Kind.OK, "Wand ON: left-click 2 corners | right-click a zone to delete")
                } else {
                    ServerPlayNetworking.send(player, WandBoxesPayload(""))
                    say(player, Kind.INFO, "Wand OFF")
                }
            }

            WandActionPayload.SELECT -> {
                if (!s.active || !holdingStick(player)) {
                    ServerPlayNetworking.send(player, WandStatePayload(false))
                    return
                }
                val first = s.corner
                if (first == null) {
                    s.corner = payload.pos
                    level.playSound(null, payload.pos, SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.PLAYERS, 0.9f, 1.5f)
                    say(player, Kind.INFO, "Corner 1 set (${payload.pos.x}, ${payload.pos.z}). Look around and click Corner 2")
                    return
                }
                s.corner = null
                createZone(player, level, s, first, payload.pos)
            }

            WandActionPayload.REMOVE -> {
                if (!s.active || !holdingStick(player)) {
                    ServerPlayNetworking.send(player, WandStatePayload(false))
                    return
                }
                removeZones(player, level, payload.pos)
            }

            WandActionPayload.PREVIEW -> {
                if (s.active && holdingStick(player)) {
                    s.preview = payload.pos
                    syncPlayer(player, s)
                }
            }
        }
    }

    private fun exactBox(kind: Int, a: BlockPos, b: BlockPos) = WandBox(
        kind,
        minOf(a.x, b.x), minOf(a.y, b.y), minOf(a.z, b.z),
        maxOf(a.x, b.x) + 1, maxOf(a.y, b.y) + 1, maxOf(a.z, b.z) + 1
    )

    private fun createZone(player: ServerPlayer, level: ServerLevel, s: Session, a: BlockPos, b: BlockPos) {
        val zone = Zone.between(a, b)
        val error = sizeError(zone)
        if (error != null) {
            level.playSound(null, player.blockPosition(), SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 0.8f, 1.0f)
            say(player, Kind.ERR, error)
            return
        }
        save(level, load(level) + zone)
        for (x in zone.minX..zone.maxX) for (z in zone.minZ..zone.maxZ) level.setChunkForced(x, z, true)

        // white pulse from corner 1 to corner 2
        s.flashBox = exactBox(6, a, b)
        s.flashUntil = level.gameTime + FLASH_TICKS

        level.playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0f, 1.2f)
        val centerX = ((zone.minX + zone.maxX + 1) * 16) / 2.0
        val centerZ = ((zone.minZ + zone.maxZ + 1) * 16) / 2.0
        level.sendParticles(ParticleTypes.END_ROD, centerX, player.y + 1.0, centerZ, 40, 3.0, 1.0, 3.0, 0.1)

        say(player, Kind.OK, "Zone saved & keep-loaded: ${zone.width()}x${zone.depth()} chunks (${zone.width() * 16}x${zone.depth() * 16} blocks)")
    }

    private fun removeZones(player: ServerPlayer, level: ServerLevel, pos: BlockPos) {
        val cx = pos.x shr 4
        val cz = pos.z shr 4
        val all = load(level)
        val hit = all.filter { it.contains(cx, cz) }
        if (hit.isEmpty()) {
            level.playSound(null, player.blockPosition(), SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 0.6f, 1.0f)
            say(player, Kind.ERR, "No zone here")
            return
        }
        val remaining = all - hit.toSet()
        save(level, remaining)
        for (z in hit) {
            for (x in z.minX..z.maxX) for (y in z.minZ..z.maxZ) {
                if (remaining.none { it.contains(x, y) }) level.setChunkForced(x, y, false)
            }
        }

        level.playSound(null, player.blockPosition(), SoundEvents.UI_STONECUTTER_TAKE_RESULT, SoundSource.PLAYERS, 0.9f, 0.8f)
        level.sendParticles(ParticleTypes.SMOKE, pos.x + 0.5, pos.y + 1.0, pos.z + 0.5, 25, 0.5, 0.5, 0.5, 0.05)

        say(player, Kind.OK, "Zone removed")
    }

    private fun zoneBox(kind: Int, z: Zone, y0: Int, y1: Int) =
        WandBox(kind, z.minX * 16, y0, z.minZ * 16, (z.maxX + 1) * 16, y1, (z.maxZ + 1) * 16)

    private fun collectBoxes(level: ServerLevel, player: ServerPlayer, s: Session): List<WandBox> {
        val py = player.blockPosition().y
        val y0 = Math.floorDiv(py, 8) * 8 - 12
        val y1 = y0 + 48
        val out = ArrayList<WandBox>()

        for (z in load(level)) out.add(zoneBox(0, z, y0, y1))

        val c1 = s.corner
        val pv = s.preview

        if (c1 != null) {
            out.add(WandBox(1, c1.x, c1.y - 1, c1.z, c1.x + 1, c1.y + 10, c1.z + 1))
            if (pv != null) {
                // chunks that will stay loaded (outline only) + the exact blocks you selected
                val zone = Zone.between(c1, pv)
                out.add(zoneBox(if (sizeError(zone) == null) 2 else 3, zone, y0, y1))
                out.add(exactBox(5, c1, pv))
            }
        } else if (pv != null) {
            out.add(WandBox(4, pv.x, pv.y, pv.z, pv.x + 1, pv.y + 1, pv.z + 1))
        }

        val flash = s.flashBox
        if (flash != null) {
            if (level.gameTime < s.flashUntil) out.add(flash) else s.flashBox = null
        }
        return out
    }

    private fun hudText(level: ServerLevel, s: Session): String {
        val savedCount = load(level).size
        val a = s.corner
        val b = s.preview
        if (a != null && b != null) {
            val dx = abs(a.x - b.x) + 1
            val dy = abs(a.y - b.y) + 1
            val dz = abs(a.z - b.z) + 1
            val total = dx.toLong() * dy * dz
            val zone = Zone.between(a, b)
            val err = sizeError(zone)
            val base = "Selection ${dx}x${dy}x${dz} = $total blocks | ${zone.width()}x${zone.depth()} chunks stay loaded"
            return if (err == null) base else "$base | $err"
        }
        if (a != null) return "Corner 1 set (${a.x}, ${a.y}, ${a.z}) | look at the opposite corner and click"
        return "Wand ON | saved zones: $savedCount"
    }

    private fun syncPlayer(player: ServerPlayer, s: Session) {
        val level = player.level() as ServerLevel
        val isHolding = holdingStick(player)
        val boxes = if (isHolding) collectBoxes(level, player, s) else emptyList()
        val text = WandState.encode(boxes)

        if (text != s.lastSent) {
            s.lastSent = text
            ServerPlayNetworking.send(player, WandBoxesPayload(text))
        }

        if (s.active && isHolding) sendHud(player, hudText(level, s))
    }

    private fun syncAll(server: MinecraftServer) {
        for ((uuid, s) in sessions) {
            if (!s.active) continue
            val player = server.playerList.getPlayer(uuid) ?: continue
            syncPlayer(player, s)
        }
    }
}
