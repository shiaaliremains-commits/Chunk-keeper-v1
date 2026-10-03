package the.block.is_.awake.modid

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.TextComponent
import the.block.is_.awake.modid.ModConfig

/**
 * Simple settings screen opened from Mod Menu.
 * Click a button to raise the value by 1 (after 16 it goes back to 1).
 */
class ConfigScreen(private val parent: Screen?) : Screen(TextComponent("The Blocks Keep Ticking")) {
    private lateinit var minBtn: Button
    private lateinit var maxBtn: Button

    private fun label(name: String, v: Int): TextComponent =
        TextComponent("$name: $v chunks (${v * 16} blocks)")

    private fun next(v: Int) = if (v >= 16) 1 else v + 1

    private fun refresh() {
        minBtn.message = label("Min zone side", ModConfig.minChunksPerSide)
        maxBtn.message = label("Max zone side", ModConfig.maxChunksPerSide)
    }

    override fun init() {
        val cx = width / 2
        val y = height / 2 - 50

        val title = Button(cx - 130, y, 260, 20, TextComponent("Zone Wand settings")) { }
        title.active = false
        addRenderableWidget(title)

        minBtn = Button(cx - 130, y + 30, 260, 20, label("Min zone side", ModConfig.minChunksPerSide)) {
            ModConfig.setMin(next(ModConfig.minChunksPerSide))
            refresh()
        }
        addRenderableWidget(minBtn)

        maxBtn = Button(cx - 130, y + 55, 260, 20, label("Max zone side", ModConfig.maxChunksPerSide)) {
            ModConfig.setMax(next(ModConfig.maxChunksPerSide))
            refresh()
        }
        addRenderableWidget(maxBtn)

        addRenderableWidget(
            Button(cx - 130, y + 90, 260, 20, TextComponent("Done")) { onClose() }
        )
    }

    override fun onClose() {
        ModConfig.save()
        Minecraft.getInstance().setScreen(parent)
    }
}
