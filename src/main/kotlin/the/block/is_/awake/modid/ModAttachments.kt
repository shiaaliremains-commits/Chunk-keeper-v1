package the.block.is_.awake.modid

import com.mojang.serialization.Codec
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry
import net.fabricmc.fabric.api.attachment.v1.AttachmentType
import net.minecraft.resources.Identifier

/**
 * المسار: src/main/kotlin/the/block/is_/awake/modid/ModAttachments.kt  (ملف جديد)
 * يخزّن وقت آخر مرة كان فيها الـ Chunk محمّلاً، ويُحفظ تلقائياً مع العالم.
 */
object ModAttachments {
    val LAST_SEEN_TICK: AttachmentType<Long> = AttachmentRegistry.create<Long>(
        Identifier.fromNamespaceAndPath(Theblockkeepsticking.MOD_ID, "last_seen_tick")
    ) { builder -> builder.persistent(Codec.LONG) }

    /** مجموع الـ ticks التي غبتها عن العالم (وأنت خارجه). محفوظ مع العالم. */
    val OFFLINE_TICKS: AttachmentType<Long> = AttachmentRegistry.create<Long>(
        Identifier.fromNamespaceAndPath(Theblockkeepsticking.MOD_ID, "offline_ticks")
    ) { builder -> builder.persistent(Codec.LONG) }

    /** وقت إغلاق العالم الحقيقي (millis). */
    val SHUTDOWN_MILLIS: AttachmentType<Long> = AttachmentRegistry.create<Long>(
        Identifier.fromNamespaceAndPath(Theblockkeepsticking.MOD_ID, "shutdown_millis")
    ) { builder -> builder.persistent(Codec.LONG) }

    /** المناطق التي حدّدتها بالعصا (نصوص "minX,minZ,maxX,maxZ" بإحداثيات الـ Chunk). */
    val ZONES: AttachmentType<List<String>> = AttachmentRegistry.create<List<String>>(
        Identifier.fromNamespaceAndPath(Theblockkeepsticking.MOD_ID, "zones")
    ) { builder -> builder.persistent(Codec.STRING.listOf()) }

    /** لضمان تحميل الكلاس وتسجيل الـ Attachment. */
    fun init() {}
}
