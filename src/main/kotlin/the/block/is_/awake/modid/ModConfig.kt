package the.block.is_.awake.modid

import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader

/**
 * Config file: <game folder>/config/theblockkeepsticking.json
 * Values are in chunks (1 chunk = 16 blocks).
 * You can also edit them in-game from Mod Menu.
 */
object ModConfig {
    private class Data {
        var minChunksPerSide: Int = 1
        var maxChunksPerSide: Int = 5
    }

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private var data = Data()

    val minChunksPerSide: Int get() = data.minChunksPerSide
    val maxChunksPerSide: Int get() = data.maxChunksPerSide

    private fun file() = FabricLoader.getInstance().configDir.resolve("theblockkeepsticking.json").toFile()

    fun load() {
        val f = file()
        try {
            if (f.exists()) {
                data = gson.fromJson(f.readText(), Data::class.java) ?: Data()
            }
        } catch (e: Exception) {
            Theblockkeepsticking.LOGGER.warn("Could not read config, using defaults", e)
            data = Data()
        }
        data.minChunksPerSide = data.minChunksPerSide.coerceIn(1, 16)
        data.maxChunksPerSide = data.maxChunksPerSide.coerceIn(data.minChunksPerSide, 16)
        save()
    }

    fun save() {
        try {
            val f = file()
            f.parentFile.mkdirs()
            f.writeText(gson.toJson(data))
        } catch (e: Exception) {
            Theblockkeepsticking.LOGGER.warn("Could not write config", e)
        }
    }

    fun setMin(v: Int) {
        data.minChunksPerSide = v.coerceIn(1, 16)
        if (data.maxChunksPerSide < data.minChunksPerSide) data.maxChunksPerSide = data.minChunksPerSide
    }

    fun setMax(v: Int) {
        data.maxChunksPerSide = v.coerceIn(1, 16)
        if (data.minChunksPerSide > data.maxChunksPerSide) data.minChunksPerSide = data.maxChunksPerSide
    }
}
