package the.block.is_.awake.modid.client

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import me.shedaniel.clothconfig2.api.ConfigBuilder
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import the.block.is_.awake.modid.ModConfig

class ModMenuIntegration : ModMenuApi {

    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> {
        return ConfigScreenFactory { parent: Screen ->
            val builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.literal("The Block Keep Sticking - Settings"))

            val entryBuilder = builder.entryBuilder()
            val general = builder.getOrCreateCategory(Component.literal("General"))

            general.addEntry(
                entryBuilder.startIntSlider(
                    Component.literal("Min Chunks per Side"),
                    ModConfig.minChunksPerSide,
                    1, 16
                )
                .setDefaultValue(1)
                .setSaveConsumer { ModConfig.minChunksPerSide = it }
                .build()
            )

            general.addEntry(
                entryBuilder.startIntSlider(
                    Component.literal("Max Chunks per Side"),
                    ModConfig.maxChunksPerSide,
                    1, 64
                )
                .setDefaultValue(16)
                .setSaveConsumer { ModConfig.maxChunksPerSide = it }
                .build()
            )

            builder.setSavingRunnable {
                ModConfig.save()
            }

            builder.build()
        }
    }
}
