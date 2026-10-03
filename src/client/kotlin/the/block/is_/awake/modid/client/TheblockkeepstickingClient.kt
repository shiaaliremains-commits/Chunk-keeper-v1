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
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.resources.Identifier
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Items
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import the.block.is_.awake.modid.Theblockkeepsticking
import the.block.is_.awake.modid.WandActionPayload
import the.block.is_.awake.modid.WandBoxesPayload
import the.block.is_.awake.modid.WandState
import the.block.is_.awake.modid.WandStatePayload
import kotlin.math.sin

object TheblockkeepstickingClient : ClientModInitializer {
    private lateinit var toggleKey: KeyMapping
    private var lastTargetPos: BlockPos? = null

    override fun onInitializeClient() {
        val category = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(Theblockkeepsticking.MOD_ID, "main")
        )
        toggleKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping(
                "key.theblockkeepsticking.toggle_wand",
                InputConstants.KEY_K,
                category
            )
        )

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            while (toggleKey.consumeClick()) {
                if (client.player != null) {
                    ClientPlayNetworking.send(WandActionPayload(WandActionPayload.TOGGLE, BlockPos.ZERO))
                }
            }

            if (WandState.clientActive) {
                val player = client.player
                val hit = client.hitResult
                if (player != null && player.mainHandItem.item == Items.STICK &&
                    hit is BlockHitResult && hit.type == HitResult.Type.BLOCK
                ) {
                    val currentPos = hit.blockPos
                    if (currentPos != lastTargetPos) {
                        lastTargetPos = currentPos
                        ClientPlayNetworking.send(WandActionPayload(WandActionPayload.PREVIEW, currentPos))
                    }
                }
                drawFrames()
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(WandStatePayload.TYPE) { payload, _ ->
            WandState.clientActive = payload.active
            if (!payload.active) WandState.boxes = emptyList()
        }
        ClientPlayNetworking.registerGlobalReceiver(WandBoxesPayload.TYPE) { payload, _ ->
            WandState.boxes = WandState.decode(payload.data)
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            WandState.clientActive = false
            WandState.boxes = emptyList()
        }

        AttackBlockCallback.EVENT.register { player, level, _, pos, _ ->
            if (level.isClientSide && WandState.clientActive && player.mainHandItem.item == Items.STICK) {
                ClientPlayNetworking.send(WandActionPayload(WandActionPayload.SELECT, pos))
                InteractionResult.FAIL
            } else {
                InteractionResult.PASS
            }
        }

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

    /** Draws solid glass fill + pulsing wireframe borders using Minecraft's Gizmos */
    private fun drawFrames() {
        val e = 0.004
        val time = System.currentTimeMillis()
        val pulseAlpha = (110 + 90 * sin(time / 130.0)).toInt()

        val PULSING_WHITE_LINE = (pulseAlpha shl 24) or 0xFFFFFF
        val PULSING_WHITE_FILL = ((pulseAlpha / 4) shl 24) or 0xFFFFFF

        for (b in WandState.boxes) {
            val (lineColor, fillColor) = when (b.kind) {
                0 -> Pair(0xFF00E5FF.toInt(), 0x2500E5FF.toInt())  // Saved Zone: Cyan line + translucent cyan glass
                1 -> Pair(0xFFFFD500.toInt(), 0x60FFD500.toInt())  // Corner 1 Marker: Glowing Yellow
                3 -> Pair(0xFFFF3030.toInt(), 0x40FF3030.toInt())  // Invalid Selection: Red
                else -> Pair(PULSING_WHITE_LINE, PULSING_WHITE_FILL) // Live Preview
            }
            val box = AABB(b.x0 - e, b.y0 - e, b.z0 - e, b.x1 + e, b.y1 + e, b.z1 + e)

            // رسم التظليل الزجاجي الشفاف الداخلي
            Gizmos.cuboid(box, GizmoStyle.fill(fillColor)).persistForMillis(50)
            // رسم الإطار الخارجي المضيء
            Gizmos.cuboid(box, GizmoStyle.stroke(lineColor)).persistForMillis(50)
        }
    }
}
