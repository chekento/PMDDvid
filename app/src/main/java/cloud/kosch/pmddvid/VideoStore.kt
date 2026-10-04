package cloud.kosch.pmddvid

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class StoredVideo(
    val uri: Uri,
    val name: String,
    val modifiedMs: Long,
    val sizeBytes: Long,
    val localFile: File? = null,
)

class VideoStore(private val context: Context) {
    val folder = File(context.filesDir, "videos").apply { mkdirs() }
    private val pending = File(context.filesDir, "pending-videos").apply { mkdirs() }
    val publicFolder = "Movies/PMDDvid"

    fun newFile(prefix: String) =
        File(
            pending,
            "${prefix}-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS",Locale.US).format(Date())}.mp4",
        )

    fun commit(file: File): StoredVideo {
        check(hasVideo(file)) { "Fertiges Video ist nicht lesbar" }
        if (Build.VERSION.SDK_INT >= 29) {
            val item = publish(file)
            if (!file.delete() && file.exists()) file.deleteOnExit()
            return item
        }
        val result = File(folder, file.name)
        check(file.renameTo(result)) { "Fertiges Video konnte nicht gespeichert werden" }
        return StoredVideo(
            uri = uri(result),
            name = result.name,
            modifiedMs = result.lastModified(),
            sizeBytes = result.length(),
            localFile = result,
        )
    }

    private fun publish(file: File): StoredVideo {
        val values =
            ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, publicFolder)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        val resolver = context.contentResolver
        val target =
            resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Movies/PMDDvid konnte nicht angelegt werden")
        try {
            resolver.openOutputStream(target, "w")?.use { output ->
                file.inputStream().use { it.copyTo(output) }
            } ?: error("Movies/PMDDvid ist nicht beschreibbar")
            resolver.update(
                target,
                ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                null,
                null,
            )
            val stored =
                resolver.query(
                    target,
                    arrayOf(MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_MODIFIED),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) file.length() to System.currentTimeMillis()
                    else cursor.getLong(0) to (cursor.getLong(1) * 1000L)
                } ?: (file.length() to System.currentTimeMillis())
            return StoredVideo(target, file.name, stored.second, stored.first)
        } catch (e: Exception) {
            resolver.delete(target, null, null)
            throw e
        }
    }

    fun all(): List<StoredVideo> {
        if (Build.VERSION.SDK_INT >= 29) {
            val result = mutableListOf<StoredVideo>()
            val projection =
                arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DATE_MODIFIED,
                    MediaStore.Video.Media.SIZE,
                )
            context.contentResolver
                .query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
                    arrayOf("$publicFolder%"),
                    "${MediaStore.Video.Media.DATE_MODIFIED} DESC",
                )
                ?.use { cursor ->
                    val id = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val name = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val modified = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                    val size = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    while (cursor.moveToNext()) {
                        val itemUri =
                            ContentUris.withAppendedId(
                                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                                cursor.getLong(id),
                            )
                        result +=
                            StoredVideo(
                                itemUri,
                                cursor.getString(name) ?: "PMDDvid.mp4",
                                cursor.getLong(modified) * 1000L,
                                cursor.getLong(size),
                            )
                    }
                }
            return result
        }
        return folder
            .listFiles()
            ?.filter { it.extension.equals("mp4", true) && it.length() > 0 }
            ?.sortedByDescending { it.lastModified() }
            ?.map {
                StoredVideo(
                    uri = uri(it),
                    name = it.name,
                    modifiedMs = it.lastModified(),
                    sizeBytes = it.length(),
                    localFile = it,
                )
            } ?: emptyList()
    }

    fun uri(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    fun hasVideo(file: File): Boolean {
        if (file.length() == 0L) return false
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.path)
            val track =
                (0 until extractor.trackCount).firstOrNull {
                    extractor
                        .getTrackFormat(it)
                        .getString(MediaFormat.KEY_MIME)
                        ?.startsWith("video/") == true
                } ?: return false
            extractor.selectTrack(track)
            extractor.sampleTime >= 0L
        } catch (_: Exception) {
            false
        } finally {
            extractor.release()
        }
    }

    fun description(video: StoredVideo): String {
        val meta = MediaMetadataRetriever()
        return try {
            if (video.localFile != null) meta.setDataSource(video.localFile.path)
            else meta.setDataSource(context, video.uri)
            val w = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val h = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val seconds =
                (meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?: 0L) / 1000
            val min = seconds / 60
            val sec = seconds % 60
            "$w × $h · %02d:%02d · %.1f MB".format(min, sec, video.sizeBytes / 1048576.0)
        } catch (_: Exception) {
            "%.1f MB".format(video.sizeBytes / 1048576.0)
        } finally {
            meta.release()
        }
    }

    fun thumbnail(video: StoredVideo): Bitmap? {
        val meta = MediaMetadataRetriever()
        return try {
            if (video.localFile != null) meta.setDataSource(video.localFile.path)
            else meta.setDataSource(context, video.uri)
            meta.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: Exception) {
            null
        } finally {
            meta.release()
        }
    }

    fun rename(video: StoredVideo, requested: String): StoredVideo {
        val base =
            requested
                .trim()
                .removeSuffix(".mp4")
                .replace(Regex("""[\\/:*?"<>|]"""), "_")
                .take(80)
                .ifBlank { "PMDDvid" }
        val name = "$base.mp4"
        if (video.localFile != null) {
            val target = File(video.localFile.parentFile, name)
            check(video.localFile.renameTo(target)) { "Video konnte nicht umbenannt werden" }
            return video.copy(
                uri = uri(target),
                name = target.name,
                modifiedMs = target.lastModified(),
                sizeBytes = target.length(),
                localFile = target,
            )
        }
        val rows =
            context.contentResolver.update(
                video.uri,
                ContentValues().apply { put(MediaStore.Video.Media.DISPLAY_NAME, name) },
                null,
                null,
            )
        check(rows > 0) { "Video konnte nicht umbenannt werden" }
        return video.copy(name = name, modifiedMs = System.currentTimeMillis())
    }

    fun delete(video: StoredVideo): Boolean =
        if (video.localFile != null) video.localFile.delete()
        else context.contentResolver.delete(video.uri, null, null) > 0
}
