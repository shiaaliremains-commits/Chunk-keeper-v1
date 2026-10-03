package the.block.is_.awake.modid

import java.util.UUID
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.Items

/**
 * المسار: src/main/kotlin/the/block/is_/awake/modid/ZoneManager.kt  (ملف جديد)
 *
 * منطق السيرفر لعصا المناطق:
 *  - الزر + عصا بيدك = تشغيل/إيقاف الوضع.
 *  - كلك أيسر على بلوك (الوضع شغّال) = زاوية 1 ثم زاوية 2 -> تُحفظ منطقة وتبقى محمّلة (forceload).
 *  - كلك أيمن على بلوك داخل منطقة = حذفها.
 */
object ZoneManager {
    private class Session {
        var active = false
        var corner: BlockPos? = null
    }

    private data class Zone(val minX: Int, val minZ: Int, val maxX: Int, val maxZ: Int) {
        fun contains(cx: Int, cz: Int) = cx in minX..maxX && cz in minZ..maxZ
        fun width() = maxX - minX + 1
        fun depth() = maxZ - minZ + 1
        fun chunkCount() = width() * depth()
        fun encode() = "$minX,$minZ,$maxX,$maxZ"

        companion object {
            fun decode(s: String): Zone? {
                val p = s.split(",").mapNotNull { it.toIntOrNull() }
                return if (p.size == 4) Zone(p[0], p[1], p[2], p[3]) else null
            }
        }
    }

    private val sessions = HashMap<UUID, Session>()

    fun init() {
        ModConfig.load()
        PayloadTypeRegistry.serverboundPlay().register(WandActionPayload.TYPE, WandActionPayload.CODEC)
        PayloadTypeRegistry.clientboundPlay().register(WandStatePayload.TYPE, WandStatePayload.CODEC)

        ServerPlayNetworking.registerGlobalReceiver(WandActionPayload.TYPE) { payload, context ->
            handle(context.player(), payload)
        }
        ServerLifecycleEvents.SERVER_STOPPED.register { sessions.clear() }
    }

    private fun say(player: ServerPlayer, text: String) {
        player.sendSystemMessage(Component.literal(text), true)
    }

    private fun holdingStick(player: ServerPlayer) = player.mainHandItem.item == Items.STICK

    private fun load(level: ServerLevel): List<Zone> =
        (level.getAttached(ModAttachments.ZONES) ?: emptyList()).mapNotNull { Zone.decode(it) }

    private fun save(level: ServerLevel, zones: List<Zone>) {
        level.setAttached(ModAttachments.ZONES, zones.map { it.encode() })
    }

    private fun handle(player: ServerPlayer, payload: WandActionPayload) {
        val level = player.level() as ServerLevel
        val s = sessions.computeIfAbsent(player.uuid) { Session() }

        when (payload.action) {
            WandActionPayload.TOGGLE -> {
                if (!holdingStick(player)) {
                    say(player, "Hold a Stick to use the zone wand")
                    return
                }
                s.active = !s.active
                s.corner = null
                ServerPlayNetworking.send(player, WandStatePayload(s.active))
                say(player, if (s.active) "Zone wand ON: left-click 2 corners, right-click a zone to remove" else "Zone wand OFF")
            }

            WandActionPayload.SELECT -> {
                if (!s.active || !holdingStick(player)) {
                    ServerPlayNetworking.send(player, WandStatePayload(false))
                    return
                }
                val first = s.corner
                if (first == null) {
                    s.corner = payload.pos
                    say(player, "Corner 1 set. Left-click corner 2")
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
        }
    }

    private fun createZone(player: ServerPlayer, level: ServerLevel, a: BlockPos, b: BlockPos) {
        val zone = Zone(
            minOf(a.x shr 4, b.x shr 4), minOf(a.z shr 4, b.z shr 4),
            maxOf(a.x shr 4, b.x shr 4), maxOf(a.z shr 4, b.z shr 4)
        )
        val min = ModConfig.minChunksPerSide
        val max = ModConfig.maxChunksPerSide
        if (zone.width() > max || zone.depth() > max) {
            say(player, "Zone too big: ${zone.width()}x${zone.depth()} chunks (max ${max}x$max = ${max * 16}x${max * 16} blocks)")
            return
        }
        if (zone.width() < min || zone.depth() < min) {
            say(player, "Zone too small: ${zone.width()}x${zone.depth()} chunks (min ${min}x$min)")
            return
        }
        save(level, load(level) + zone)
        for (x in zone.minX..zone.maxX) for (z in zone.minZ..zone.maxZ) level.setChunkForced(x, z, true)
        say(player, "Zone saved: ${zone.width()}x${zone.depth()} chunks (${zone.width() * 16}x${zone.depth() * 16} blocks)")
    }

    private fun removeZones(player: ServerPlayer, level: ServerLevel, pos: BlockPos) {
        val cx = pos.x shr 4
        val cz = pos.z shr 4
        val all = load(level)
        val hit = all.filter { it.contains(cx, cz) }
        if (hit.isEmpty()) {
            say(player, "No zone here")
            return
        }
        val remaining = all - hit.toSet()
        save(level, remaining)
        for (z in hit) {
            for (x in z.minX..z.maxX) for (y in z.minZ..z.maxZ) {
                // لا نلغي تحميل Chunk تابع لمنطقة أخرى
                if (remaining.none { it.contains(x, y) }) level.setChunkForced(x, y, false)
            }
        }
        say(player, "Zone removed")
    }
}
