package the.block.is_.awake.modid.client

import com.mojang.blaze3d.platform.InputConstants
import kotlin.math.sin
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

object TheblockkeepstickingClient : ClientModInitializer {
    private lateinit var toggleKey: KeyMapping
    private var lastTargetPos: BlockPos? = null
    private var lastSelectTime = 0L
    private var lastRemoveTime = 0L

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
                val now = System.currentTimeMillis()
                if (now - lastSelectTime > 300) {
                    lastSelectTime = now
                    ClientPlayNetworking.send(WandActionPayload(WandActionPayload.SELECT, pos))
                }
                InteractionResult.FAIL
            } else {
                InteractionResult.PASS
            }
        }

        UseBlockCallback.EVENT.register { player, level, hand, hit ->
            if (level.isClientSide && hand == InteractionHand.MAIN_HAND &&
                WandState.clientActive && player.mainHandItem.item == Items.STICK
            ) {
                val now = System.currentTimeMillis()
                if (now - lastRemoveTime > 300) {
                    lastRemoveTime = now
                    ClientPlayNetworking.send(WandActionPayload(WandActionPayload.REMOVE, hit.blockPos))
                }
                InteractionResult.SUCCESS
            } else {
                InteractionResult.PASS
            }
        }
    }

    private fun argb(alpha: Int, rgb: Int): Int = (alpha.coerceIn(0, 255) shl 24) or (rgb and 0xFFFFFF)

    private fun drawFrames() {
        val e = 0.004
        val t = System.currentTimeMillis().toDouble()

        // slow breathing white (exact selection) and a faster, stronger one (flash after saving)
        val slow = 0.5 + 0.5 * sin(t / 220.0)
        val fast = 0.5 + 0.5 * sin(t / 90.0)
        val selFill = argb((35 + 70 * slow).toInt(), 0xFFFFFF)
        val selLine = argb((170 + 85 * slow).toInt(), 0xFFFFFF)
        val flashFill = argb((70 + 110 * fast).toInt(), 0xFFFFFF)
        val flashLine = argb(255, 0xFFFFFF)

        for (b in WandState.boxes) {
            val box = AABB(b.x0 - e, b.y0 - e, b.z0 - e, b.x1 + e, b.y1 + e, b.z1 + e)

            when (b.kind) {
                0 -> { // saved zone: cyan frame + very faint fill
                    Gizmos.cuboid(box, GizmoStyle.fill(argb(18, 0x00E5FF))).persistForMillis(100)
                    Gizmos.cuboid(box, GizmoStyle.stroke(argb(255, 0x00E5FF))).persistForMillis(100)
                }
                1 -> { // corner 1: yellow pillar
                    Gizmos.cuboid(box, GizmoStyle.fill(argb(70, 0xFFFF00))).persistForMillis(100)
                    Gizmos.cuboid(box, GizmoStyle.stroke(argb(255, 0xFFFF00))).persistForMillis(100)
                }
                2 -> { // chunks that will stay loaded: green outline only
                    Gizmos.cuboid(box, GizmoStyle.stroke(argb(255, 0x39FF14))).persistForMillis(100)
                }
                3 -> { // invalid size: red outline only
                    Gizmos.cuboid(box, GizmoStyle.stroke(argb(255, 0xFF3030))).persistForMillis(100)
                }
                5 -> { // exact selection: breathing translucent white
                    Gizmos.cuboid(box, GizmoStyle.fill(selFill)).persistForMillis(100)
                    Gizmos.cuboid(box, GizmoStyle.stroke(selLine)).persistForMillis(100)
                }
                6 -> { // flash after saving
                    Gizmos.cuboid(box, GizmoStyle.fill(flashFill)).persistForMillis(100)
                    Gizmos.cuboid(box, GizmoStyle.stroke(flashLine)).persistForMillis(100)
                }
                else -> { // hovered block (before corner 1)
                    Gizmos.cuboid(box, GizmoStyle.fill(selFill)).persistForMillis(100)
                    Gizmos.cuboid(box, GizmoStyle.stroke(selLine)).persistForMillis(100)
                }
            }
        }
    }
}
