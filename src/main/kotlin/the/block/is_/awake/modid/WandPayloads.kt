package the.block.is_.awake.modid

import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/**
 * المسار: src/main/kotlin/the/block/is_/awake/modid/WandPayloads.kt  (ملف جديد)
 * رسائل الشبكة بين اللاعب (الكلاينت) والسيرفر.
 */

/** هل وضع العصا مفعّل عند هذا اللاعب؟ (يُحدَّث من السيرفر) */
object WandState {
    @Volatile
    var clientActive: Boolean = false
}

/** من الكلاينت للسيرفر: تبديل الوضع / اختيار زاوية / حذف منطقة. */
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

/** من السيرفر للكلاينت: حالة وضع العصا. */
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
