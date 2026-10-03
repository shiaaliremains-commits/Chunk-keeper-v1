package the.block.is_.awake.modid

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class ConfigScreen(private val parent: Screen?) : Screen(Component.literal("The Blocks Keep Ticking")) {
    private lateinit var minBtn: Button
    private lateinit var maxBtn: Button

    private fun label(name: String, v: Int): Component =
        Component.literal("$name: $v chunks (${v * 16} blocks)")

    private fun next(v: Int) = if (v >= 16) 1 else v + 1

    private fun refresh() {
        minBtn.setMessage(label("Min zone side", ModConfig.minChunksPerSide))
        maxBtn.setMessage(label("Max zone side", ModConfig.maxChunksPerSide))
    }

    override fun init() {
        val cx = width / 2
        val y = height / 2 - 50

        val title = Button.builder(Component.literal("Zone Wand settings")) { }
            .bounds(cx - 130, y, 260, 20).build()
        title.active = false
        addRenderableWidget(title)

        minBtn = Button.builder(label("Min zone side", ModConfig.minChunksPerSide)) {
            ModConfig.setMin(next(ModConfig.minChunksPerSide))
            refresh()
        }.bounds(cx - 130, y + 30, 260, 20).build()
        addRenderableWidget(minBtn)

        maxBtn = Button.builder(label("Max zone side", ModConfig.maxChunksPerSide)) {
            ModConfig.setMax(next(ModConfig.maxChunksPerSide))
            refresh()
        }.bounds(cx - 130, y + 55, 260, 20).build()
        addRenderableWidget(maxBtn)

        addRenderableWidget(
            Button.builder(Component.literal("Done")) { onClose() }
                .bounds(cx - 130, y + 90, 260, 20).build()
        )
    }

    override fun onClose() {
        ModConfig.save()
        Minecraft.getInstance().screen = parent
    }
}