package the.block.is_.awake.modid

import java.util.UUID
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.Items

/**
 * Server logic for zone wand with accurate stretch-previewing and fixed click double-firing.
 */
object ZoneManager {
    private const val USE_PARTICLES = false
    private const val SYNC_INTERVAL = 1
    private const val EDGE_STEP = 2.5
    private const val SHOW_DISTANCE_SQ = 40.0 * 40.0

    private class Session {
        var active = false
        var corner: BlockPos? = null
        var preview: BlockPos? = null
        var lastSent = ""
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
                    say(player, Kind.INFO, "Corner 1 set (${payload.pos.x}, ${payload.pos.z}). Move crosshair & left-click Corner 2")
                    return
                }
                s.corner = null
                createZone(player, level, first, payload.pos)
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
                    syncPlayer(server = level.server, player = player, s = s)
                }
            }
        }
    }

    private fun createZone(player: ServerPlayer, level: ServerLevel, a: BlockPos, b: BlockPos) {
        val zone = Zone.between(a, b)
        val error = sizeError(zone)
        if (error != null) {
            level.playSound(null, player.blockPosition(), SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 0.8f, 1.0f)
            say(player, Kind.ERR, error)
            return
        }
        save(level, load(level) + zone)
        for (x in zone.minX..zone.maxX) for (z in zone.minZ..zone.maxZ) level.setChunkForced(x, z, true)
        
        level.playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0f, 1.2f)
        val centerX = ((zone.minX + zone.maxX + 1) * 16) / 2.0
        val centerZ = ((zone.minZ + zone.maxZ + 1) * 16) / 2.0
        level.sendParticles(ParticleTypes.END_ROD, centerX, player.y + 1.0, centerZ, 40, 3.0, 1.0, 3.0, 0.1)

        say(player, Kind.OK, "Zone saved: ${zone.width()}x${zone.depth()} chunks (${zone.width() * 16}x${zone.depth() * 16} blocks)")
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
        val y0 = Math.floorDiv(py, 8) * 8 - 8
        val y1 = y0 + 40
        val out = ArrayList<WandBox>()

        // 1. Zones المحفوظة باللون الأزرق
        for (z in load(level)) out.add(zoneBox(0, z, y0, y1))

        val c1 = s.corner
        val pv = s.preview

        if (c1 != null) {
            // رسم مؤشر الزاوية الأولى (Corner 1) أصفر ثابت
            out.add(WandBox(1, c1.x, c1.y, c1.z, c1.x + 1, c1.y + 1, c1.z + 1))

            if (pv != null) {
                // مَد منطقة الـ Chunk Preview بين الزاوية الأولى والبلوكة التي ينظر إليها اللاعب
                val zone = Zone.between(c1, pv)
                out.add(zoneBox(if (sizeError(zone) == null) 2 else 3, zone, y0, y1))
            }
        } else if (pv != null) {
            // المربع الأبيض الثابت/النابض على البلوكة المحددة قبل البدء
            out.add(WandBox(4, pv.x, pv.y, pv.z, pv.x + 1, pv.y + 1, pv.z + 1))
        }
        return out
    }

    private fun syncPlayer(server: MinecraftServer, player: ServerPlayer, s: Session) {
        val level = player.level() as ServerLevel
        val isHolding = holdingStick(player)
        val boxes = if (isHolding) collectBoxes(level, player, s) else emptyList()
        val text = WandState.encode(boxes)
        
        if (text != s.lastSent) {
            s.lastSent = text
            ServerPlayNetworking.send(player, WandBoxesPayload(text))
        }

        if (s.active && isHolding) {
            val statusText = if (s.corner != null && s.preview != null) {
                val z = Zone.between(s.corner!!, s.preview!!)
                "⚡ Wand: ACTIVE | Selecting: ${z.width()}x${z.depth()} Chunks (${z.width() * 16}x${z.depth() * 16} Blocks)"
            } else if (s.corner != null) {
                "⚡ Wand: ACTIVE | Corner 1 Set (${s.corner!!.x}, ${s.corner!!.z}) -> Click Corner 2"
            } else {
                "⚡ Wand: ACTIVE | Left-Click Corner 1"
            }
            sendHud(player, statusText)
        }
    }

    private fun syncAll(server: MinecraftServer) {
        for ((uuid, s) in sessions) {
            if (!s.active) continue
            val player = server.playerList.getPlayer(uuid) ?: continue
            syncPlayer(server, player, s)
        }
    }
}
