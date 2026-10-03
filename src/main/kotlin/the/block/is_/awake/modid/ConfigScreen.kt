package the.block.is_.awake.modid.client

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import the.block.is_.awake.modid.ModConfig

/**
 * Simple settings screen opened from Mod Menu.
 * Click a button to raise the value by 1 (after 16 it goes back to 1).
 */
class ConfigScreen(private val parent: Screen?) : Screen(Component.literal("The Blocks Keep Ticking")) {
    private lateinit var minBtn: Button
    private lateinit var maxBtn: Button

    private fun label(name: String, v: Int): Component =
        Component.literal("$name: $v chunks (${v * 16} blocks)")

    private fun next(v: Int) = if (v >= 16) 1 else v + 1

    private fun refresh() {
        minBtn.message = label("Min zone side", ModConfig.minChunksPerSide)
        maxBtn.message = label("Max zone side", ModConfig.maxChunksPerSide)
    }

    override fun init() {
        val cx = width / 2
        val y = height / 2 - 50

        val title = Button.builder(Component.literal("Zone Wand settings")) { _ -> }
            .bounds(cx - 130, y, 260, 20).build()
        title.active = false
        addRenderableWidget(title)

        minBtn = Button.builder(label("Min zone side", ModConfig.minChunksPerSide)) { _ ->
            ModConfig.setMin(next(ModConfig.minChunksPerSide))
            refresh()
        }.bounds(cx - 130, y + 30, 260, 20).build()
        addRenderableWidget(minBtn)

        maxBtn = Button.builder(label("Max zone side", ModConfig.maxChunksPerSide)) { _ ->
            ModConfig.setMax(next(ModConfig.maxChunksPerSide))
            refresh()
        }.bounds(cx - 130, y + 55, 260, 20).build()
        addRenderableWidget(maxBtn)

        addRenderableWidget(
            Button.builder(Component.literal("Done")) { _ -> onClose() }
                .bounds(cx - 130, y + 90, 260, 20).build()
        )
    }

    override fun onClose() {
        ModConfig.save()
        // the screen-switch method was renamed in newer versions, so look it up by name
        val mc = Minecraft.getInstance()
        mc.javaClass.methods
            .firstOrNull { (it.name == "setScreenAndShow" || it.name == "setScreen") && it.parameterCount == 1 }
            ?.invoke(mc, parent)
    }
}
