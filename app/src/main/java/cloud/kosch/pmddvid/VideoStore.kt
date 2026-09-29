package cloud.kosch.pmddvid

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoStore(private val context:Context){
    val folder=File(context.filesDir,"videos").apply{mkdirs()}
    private val pending=File(context.filesDir,"pending-videos").apply{mkdirs()}
    fun commit(file:File):File{val result=File(folder,file.name);check(file.renameTo(result)){"Fertiges Video konnte nicht gespeichert werden"};return result}
    fun newFile(prefix:String)=File(pending,"$prefix-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS",Locale.US).format(Date())}.mp4")
    fun all()=folder.listFiles()?.filter{it.extension=="mp4"&&it.length()>0}?.sortedByDescending{it.lastModified()}?:emptyList()
    fun uri(file:File):Uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file)
    fun saveToGallery(file:File):Uri {
        if(Build.VERSION.SDK_INT>=29){
            val values=ContentValues().apply{put(MediaStore.Video.Media.DISPLAY_NAME,file.name);put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/PMDDvid");put(MediaStore.Video.Media.IS_PENDING,1)}
            val resolver=context.contentResolver;val target=resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values)?:error("Galerieeintrag konnte nicht angelegt werden")
            try{resolver.openOutputStream(target)?.use{output->file.inputStream().use{it.copyTo(output)}}?:error("Galerie nicht beschreibbar");resolver.update(target,ContentValues().apply{put(MediaStore.Video.Media.IS_PENDING,0)},null,null);return target}
            catch(e:Exception){resolver.delete(target,null,null);throw e}
        }
        error("Unter Android 8/9 bitte „Datei speichern“ oder „Teilen“ verwenden.")
    }
    fun description(file:File):String {
        val meta=MediaMetadataRetriever()
        return try{meta.setDataSource(file.path);val w=meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);val h=meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);val seconds=(meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:0L)/1000;"${w} × ${h} · ${seconds}s · %.1f MB".format(file.length()/1048576.0)}catch(_:Exception){"%.1f MB".format(file.length()/1048576.0)}finally{meta.release()}
    }
}
