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
import net.minecraft.world.item.Items

/**
 * Server logic of the zone wand.
 *  - key + Stick in hand = wand ON/OFF
 *  - left-click block = corner 1, then corner 2 -> zone saved and kept loaded (forceload)
 *  - right-click block inside a zone = delete it
 *  - particles show: saved zones (white), corner 1 (flame), live preview (green / red if invalid)
 */
object ZoneManager {
    private const val PARTICLE_INTERVAL = 10
    private const val EDGE_STEP = 2.5
    private const val SHOW_DISTANCE_SQ = 40.0 * 40.0

    private class Session {
        var active = false
        var corner: BlockPos? = null
        var preview: BlockPos? = null
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

        ServerPlayNetworking.registerGlobalReceiver(WandActionPayload.TYPE) { payload, context ->
            handle(context.player(), payload)
        }
        ServerLifecycleEvents.SERVER_STOPPED.register { sessions.clear() }

        ServerTickEvents.END_SERVER_TICK.register { server ->
            if (sessions.isNotEmpty() && ++tickCounter >= PARTICLE_INTERVAL) {
                tickCounter = 0
                drawAll(server)
            }
        }
    }

    // ---------------------------------------------------------------- messages

    private fun say(player: ServerPlayer, kind: Kind, text: String) {
        val msg = Component.literal(kind.symbol + " ").withStyle(kind.color, ChatFormatting.BOLD)
            .append(Component.literal(text).withStyle(ChatFormatting.WHITE))
        player.sendSystemMessage(msg, true)
    }

    // ---------------------------------------------------------------- helpers

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
            return "Too big: ${zone.width()}x${zone.depth()} chunks (max ${max}x$max = ${max * 16}x${max * 16} blocks)"
        }
        if (zone.width() < min || zone.depth() < min) {
            return "Too small: ${zone.width()}x${zone.depth()} chunks (min ${min}x$min)"
        }
        return null
    }

    // ---------------------------------------------------------------- actions

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
                ServerPlayNetworking.send(player, WandStatePayload(s.active))
                if (s.active) say(player, Kind.OK, "Wand ON: left-click 2 corners | right-click a zone to delete")
                else say(player, Kind.INFO, "Wand OFF")
            }

            WandActionPayload.SELECT -> {
                if (!s.active || !holdingStick(player)) {
                    ServerPlayNetworking.send(player, WandStatePayload(false))
                    return
                }
                val first = s.corner
                if (first == null) {
                    s.corner = payload.pos
                    say(player, Kind.INFO, "Corner 1 set (${payload.pos.x}, ${payload.pos.z}). Left-click the opposite corner")
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
                if (s.active && holdingStick(player)) s.preview = payload.pos
            }
        }
    }

    private fun createZone(player: ServerPlayer, level: ServerLevel, a: BlockPos, b: BlockPos) {
        val zone = Zone.between(a, b)
        val error = sizeError(zone)
        if (error != null) {
            say(player, Kind.ERR, error)
            return
        }
        save(level, load(level) + zone)
        for (x in zone.minX..zone.maxX) for (z in zone.minZ..zone.maxZ) level.setChunkForced(x, z, true)
        say(player, Kind.OK, "Zone saved: ${zone.width()}x${zone.depth()} chunks (${zone.width() * 16}x${zone.depth() * 16} blocks) stays active")
    }

    private fun removeZones(player: ServerPlayer, level: ServerLevel, pos: BlockPos) {
        val cx = pos.x shr 4
        val cz = pos.z shr 4
        val all = load(level)
        val hit = all.filter { it.contains(cx, cz) }
        if (hit.isEmpty()) {
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
        say(player, Kind.OK, "Zone removed")
    }

    // ---------------------------------------------------------------- particles

    private fun drawAll(server: MinecraftServer) {
        for ((uuid, s) in sessions) {
            if (!s.active) continue
            val player = server.playerList.getPlayer(uuid) ?: continue
            if (!holdingStick(player)) continue
            val level = player.level() as ServerLevel

            for (z in load(level)) outline(level, player, z, ParticleTypes.END_ROD)

            val c1 = s.corner
            val pv = s.preview
            if (c1 != null) {
                box(level, c1, ParticleTypes.FLAME)
                if (pv != null) {
                    val zone = Zone.between(c1, pv)
                    outline(level, player, zone, if (sizeError(zone) == null) ParticleTypes.HAPPY_VILLAGER else ParticleTypes.ANGRY_VILLAGER)
                }
            } else if (pv != null) {
                outline(level, player, Zone.between(pv, pv), ParticleTypes.HAPPY_VILLAGER)
            }
        }
    }

    private fun dot(level: ServerLevel, type: ParticleOptions, x: Double, y: Double, z: Double) {
        level.sendParticles(type, x, y, z, 1, 0.0, 0.0, 0.0, 0.0)
    }

    /** rectangle border of a zone around the player's height */
    private fun outline(level: ServerLevel, player: ServerPlayer, zone: Zone, type: ParticleOptions) {
        val x0 = (zone.minX * 16).toDouble()
        val x1 = ((zone.maxX + 1) * 16).toDouble()
        val z0 = (zone.minZ * 16).toDouble()
        val z1 = ((zone.maxZ + 1) * 16).toDouble()
        val py = player.y
        val heights = doubleArrayOf(py + 0.4, py + 1.8)

        fun pt(x: Double, z: Double) {
            if (player.distanceToSqr(x, py, z) > SHOW_DISTANCE_SQ) return
            for (h in heights) dot(level, type, x, h, z)
        }

        var x = x0
        while (x <= x1) {
            pt(x, z0)
            pt(x, z1)
            x += EDGE_STEP
        }
        var z = z0
        while (z <= z1) {
            pt(x0, z)
            pt(x1, z)
            z += EDGE_STEP
        }
    }

    /** edges of the selected corner block */
    private fun box(level: ServerLevel, pos: BlockPos, type: ParticleOptions) {
        val bx = pos.x.toDouble()
        val by = pos.y.toDouble()
        val bz = pos.z.toDouble()
        val t = doubleArrayOf(0.0, 0.5, 1.0)
        val e = doubleArrayOf(0.0, 1.0)
        for (a in t) for (b in e) for (c in e) {
            dot(level, type, bx + a, by + b, bz + c)
            dot(level, type, bx + b, by + a, bz + c)
            dot(level, type, bx + b, by + c, bz + a)
        }
    }
}
