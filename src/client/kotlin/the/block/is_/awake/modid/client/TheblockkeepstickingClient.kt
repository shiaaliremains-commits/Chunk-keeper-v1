package the.block.is_.awake.modid.client

import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.client.KeyMapping
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Items
import the.block.is_.awake.modid.Theblockkeepsticking
import the.block.is_.awake.modid.WandActionPayload
import the.block.is_.awake.modid.WandState
import the.block.is_.awake.modid.WandStatePayload

/**
 * المسار: src/client/kotlin/the/block/is_/awake/modid/client/TheblockkeepstickingClient.kt
 * (استبدل ملف الكلاينت الموجود، واحتفظ باسم الكلاس إذا كان مختلفاً)
 */
object TheblockkeepstickingClient : ClientModInitializer {
    private lateinit var toggleKey: KeyMapping

    override fun onInitializeClient() {
        val category = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(Theblockkeepsticking.MOD_ID, "main")
        )
        toggleKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping(
                "key.theblockkeepsticking.toggle_wand",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_K,
                category
            )
        )

        // الزر: تشغيل/إيقاف وضع العصا (السيرفر يتأكد أن بيدك Stick)
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            while (toggleKey.consumeClick()) {
                if (client.player != null) {
                    ClientPlayNetworking.send(WandActionPayload(WandActionPayload.TOGGLE, BlockPos.ZERO))
                }
            }
        }

        // حالة الوضع من السيرفر
        ClientPlayNetworking.registerGlobalReceiver(WandStatePayload.TYPE) { payload, _ ->
            WandState.clientActive = payload.active
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> WandState.clientActive = false }

        // كلك أيسر على بلوك = اختيار زاوية (نلغي الكسر ونرسل للسيرفر)
        AttackBlockCallback.EVENT.register { player, level, _, pos, _ ->
            if (level.isClientSide && WandState.clientActive && player.mainHandItem.item == Items.STICK) {
                ClientPlayNetworking.send(WandActionPayload(WandActionPayload.SELECT, pos))
                InteractionResult.FAIL
            } else {
                InteractionResult.PASS
            }
        }

        // كلك أيمن على بلوك = حذف المنطقة
        UseBlockCallback.EVENT.register { player, level, hand, hit ->
            if (level.isClientSide && hand == InteractionHand.MAIN_HAND &&
                WandState.clientActive && player.mainHandItem.item == Items.STICK
            ) {
                ClientPlayNetworking.send(WandActionPayload(WandActionPayload.REMOVE, hit.blockPos))
                InteractionResult.SUCCESS
            } else {
                InteractionResult.PASS
            }
        }
    }
}
