package the.block.is_.awake.modid

import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/** one frame to draw: kind 0 = saved zone, 1 = corner block, 2 = preview OK, 3 = preview invalid, 4 = looked-at chunk */
data class WandBox(
    val kind: Int,
    val x0: Int, val y0: Int, val z0: Int,
    val x1: Int, val y1: Int, val z1: Int
)

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

/** client -> server: toggle / select corner / remove zone / preview position */
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
            StreamCodec.composite<RegistryFriendlyByteBuf, WandActionPayload, Int, BlockPos>(
                ByteBufCodecs.INT, { p: WandActionPayload -> p.action },
                BlockPos.STREAM_CODEC, { p: WandActionPayload -> p.pos },
                { a: Int, b: BlockPos -> WandActionPayload(a, b) }
            )
    }
}

/** server -> client: wand ON/OFF */
class WandStatePayload(val active: Boolean) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<WandStatePayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Theblockkeepsticking.MOD_ID, "wand_state"))

        val CODEC: StreamCodec<RegistryFriendlyByteBuf, WandStatePayload> =
            StreamCodec.composite<RegistryFriendlyByteBuf, WandStatePayload, Boolean>(
                ByteBufCodecs.BOOL, { p: WandStatePayload -> p.active },
                { b: Boolean -> WandStatePayload(b) }
            )
    }
}

/** server -> client: the frames to draw (text encoded, see WandState.encode) */
class WandBoxesPayload(val data: String) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<WandBoxesPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Theblockkeepsticking.MOD_ID, "wand_boxes"))

        val CODEC: StreamCodec<RegistryFriendlyByteBuf, WandBoxesPayload> =
            StreamCodec.composite<RegistryFriendlyByteBuf, WandBoxesPayload, String>(
                ByteBufCodecs.STRING_UTF8, { p: WandBoxesPayload -> p.data },
                { s: String -> WandBoxesPayload(s) }
            )
    }
}
