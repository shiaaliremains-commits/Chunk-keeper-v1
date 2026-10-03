package the.block.is_.awake.modid

import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader

/**
 * المسار: src/main/kotlin/the/block/is_/awake/modid/ModConfig.kt  (ملف جديد)
 *
 * ملف الإعدادات: <مجلد اللعبة>/config/theblockkeepsticking.json
 * الوحدة هي Chunk (الـ Chunk الواحد = 16 بلوك).
 * عدل الأرقام في الملف ثم أعد تشغيل اللعبة.
 */
object ModConfig {
    private class Data {
        /** أصغر ضلع للمنطقة (بالـ Chunks). 1 = 16 بلوك */
        var minChunksPerSide: Int = 1
        /** أكبر ضلع للمنطقة (بالـ Chunks). 5 = 80 بلوك */
        var maxChunksPerSide: Int = 5
    }

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private var data = Data()

    val minChunksPerSide: Int get() = data.minChunksPerSide
    val maxChunksPerSide: Int get() = data.maxChunksPerSide

    fun load() {
        val file = FabricLoader.getInstance().configDir.resolve("theblockkeepsticking.json").toFile()
        try {
            if (file.exists()) {
                data = gson.fromJson(file.readText(), Data::class.java) ?: Data()
            }
        } catch (e: Exception) {
            Theblockkeepsticking.LOGGER.warn("Could not read config, using defaults", e)
            data = Data()
        }
        // تصحيح القيم الغلط
        data.minChunksPerSide = data.minChunksPerSide.coerceIn(1, 16)
        data.maxChunksPerSide = data.maxChunksPerSide.coerceIn(data.minChunksPerSide, 16)
        try {
            file.parentFile.mkdirs()
            file.writeText(gson.toJson(data))
        } catch (e: Exception) {
            Theblockkeepsticking.LOGGER.warn("Could not write config", e)
        }
    }
}
