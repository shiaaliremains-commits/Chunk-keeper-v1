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
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.network.chat.Component
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
    private var previewTimer = 0

    // ألوان الإطار الخارجي (ARGB)
    private val CYAN = 0xFF00E5FF.toInt()         // منطقة محفوظة
    private val CORNER_COLOR = 0xFFFF9100.toInt()   // زاوية التحديد الأولى (برتقالي ذهبي ساطع)
    private val GREEN = 0xFF39FF14.toInt()        // معاينة مقبولة
    private val RED = 0xFFFF3333.toInt()          // معاينة بحجم مرفوض
    private val BLUE = 0xFF2979FF.toInt()         // المعاينة الأولية للبلوكة قبل البدء

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
                // إرسال موقع البلوكة التي ينظر إليها اللاعب للمعاينة المباشرة
                if (++previewTimer >= 2) {
                    previewTimer = 0
                    val player = client.player
                    val hit = client.hitResult
                    if (player != null && player.mainHandItem.item == Items.STICK &&
                        hit is BlockHitResult && hit.type == HitResult.Type.BLOCK
                    ) {
                        ClientPlayNetworking.send(WandActionPayload(WandActionPayload.PREVIEW, hit.blockPos))
                    }
                }

                // رسم الصناديق وعرض عداد البلوكات
                renderBoxesAndHud(client)
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

    private fun renderBoxesAndHud(client: Minecraft) {
        val e = 0.005
        val time = System.currentTimeMillis() / 250.0

        // نبض الشفافية الأبيض (تتراوح بين 15 و 95)
        val pulseAlpha = (45 + (35 * sin(time))).toInt().coerceIn(15, 95)
        val pulsingWhite = (pulseAlpha shl 24) or 0x00FFFFFF

        var activeSelectionBox: the.block.is_.awake.modid.WandBox? = null

        for (b in WandState.boxes) {
            val strokeColor = when (b.kind) {
                0 -> CYAN
                1 -> CORNER_COLOR
                2 -> GREEN
                3 -> RED
                else -> BLUE
            }

            val box = AABB(b.x0 - e, b.y0 - e, b.z0 - e, b.x1 + e, b.y1 + e, b.z1 + e)

            // 1. رسم الإطار الخارجي الملون الصلب
            Gizmos.cuboid(box, GizmoStyle.stroke(strokeColor)).persistForMillis(100)

            // 2. تعبئة داخلية بلون أبيض شفاف ينبض
            if (b.kind == 2 || b.kind == 4) {
                Gizmos.cuboid(box, GizmoStyle.fill(pulsingWhite)).persistForMillis(100)
                activeSelectionBox = b
            } else if (b.kind == 1) {
                // بلوكة الزاوية بلون مميز شفاف
                val cornerFill = (60 shl 24) or (CORNER_COLOR and 0x00FFFFFF)
                Gizmos.cuboid(box, GizmoStyle.fill(cornerFill)).persistForMillis(100)
            }
        }

        // عرض عدد البلوكات والأبعاد مباشرة في شريط الـ Actionbar
        if (activeSelectionBox != null && client.player != null) {
            val b = activeSelectionBox
            val text = Component.literal("§fالتحديد: §e${b.blockCountX}x${b.blockCountZ} §7بلوكة ")
                .append(Component.literal("§8| §fالمجموع: §a${b.blockCountX * b.blockCountZ} §7بلوكة مسطحة "))
                .append(Component.literal("§8(§b${b.blockCountX / 16}x${b.blockCountZ / 16} Chunks§8)"))

            client.player?.displayClientMessage(text, true)
        }
    }
}