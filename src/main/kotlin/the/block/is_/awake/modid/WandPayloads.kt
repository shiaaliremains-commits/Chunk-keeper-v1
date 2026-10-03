package the.block.is_.awake.modid

import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import kotlin.math.abs

/**
 * kind:
 * 0 = منطقة محفوظة (Saved Zone)
 * 1 = بلوكة الزاوية المحددة الأولى (Corner 1)
 * 2 = معاينة صحيحة (Preview Valid)
 * 3 = معاينة بحجم خاطئ (Preview Invalid)
 * 4 = التشانك المستهدف حالياً (Looked-at chunk)
 */
data class WandBox(
    val kind: Int,
    val x0: Int, val y0: Int, val z0: Int,
    val x1: Int, val y1: Int, val z1: Int
) {
    val blockCountX: Int get() = abs(x1 - x0)
    val blockCountY: Int get() = abs(y1 - y0)
    val blockCountZ: Int get() = abs(z1 - z0)
    val totalBlocks: Long get() = blockCountX.toLong() * blockCountY.toLong() * blockCountZ.toLong()
}

object WandState {
    @Volatile
    var clientActive: Boolean = false

    @Volatile
    var boxes: List<WandBox> = emptyList()

    fun encode(boxes: List<WandBox>): String =
        boxes.joinToString(";") { "${it.kind},${it.x0},${it.y0},${it.z0},${it.x1},${it.y1},${it.z1}" }

    fun decode(s: String): List<WandBox> {
        if (s.isEmpty()) return emptyList()
        return s.split(";").mapNotNull { part ->
            val p = part.split(",").mapNotNull { it.toIntOrNull() }
            if (p.size == 7) WandBox(p[0], p[1], p[2], p[3], p[4], p[5], p[6]) else null
        }
    }
}

/** client -> server: إرسال الأوامر وتحديث موقع المعاينة المباشر */
class WandActionPayload(val action: Int, val pos: BlockPos) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        const val TOGGLE = 0
        const val SELECT = 1
        const val REMOVE = 2
        const val PREVIEW = 3

        val TYPE: CustomPacketPayload.Type<WandActionPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Theblockkeepsticking.MOD_ID, "wand_action"))

        val CODEC: StreamCodec<RegistryFriendlyByteBuf, WandActionPayload> =
            StreamCodec.composite(
                ByteBufCodecs.INT, { it.action },
                BlockPos.STREAM_CODEC, { it.pos },
                { a, b -> WandActionPayload(a, b) }
            )
    }
}

/** server -> client: تفعيل أو إيقاف الأداة */
class WandStatePayload(val active: Boolean) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<WandStatePayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Theblockkeepsticking.MOD_ID, "wand_state"))

        val CODEC: StreamCodec<RegistryFriendlyByteBuf, WandStatePayload> =
            StreamCodec.composite(
                ByteBufCodecs.BOOL, { it.active },
                { b -> WandStatePayload(b) }
            )
    }
}

/** server -> client: بيانات الصناديق والمناطق لرسمها */
class WandBoxesPayload(val data: String) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<WandBoxesPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Theblockkeepsticking.MOD_ID, "wand_boxes"))

        val CODEC: StreamCodec<RegistryFriendlyByteBuf, WandBoxesPayload> =
            StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, { it.data },
                { s -> WandBoxesPayload(s) }
            )
    }
}