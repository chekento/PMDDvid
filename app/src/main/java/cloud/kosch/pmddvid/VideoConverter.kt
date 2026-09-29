package cloud.kosch.pmddvid

import android.content.Context
import android.net.Uri
import android.opengl.GLES20.*
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.transformer.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@androidx.annotation.OptIn(UnstableApi::class)
class PmddVideoEffect(private val context:Context,private val recipe:Recipe,val kind:String,private val frames:AtomicInteger,private val canceled:AtomicBoolean):GlEffect {
    override fun toGlShaderProgram(context:Context,useHdr:Boolean):GlShaderProgram {
        check(!useHdr){"Bitte SDR-Tonemapping für den Export aktivieren."}
        return object:BaseGlShaderProgram(false,1){
            private val render=PmddGl(false,true)
            private val engine=DepthEngine(context.applicationContext)
            private var width=0;private var height=0
            override fun configure(inputWidth:Int,inputHeight:Int):Size{width=inputWidth;height=inputHeight;return Size(if(kind=="stereo")width*2 else width,height)}
            override fun drawFrame(inputTexId:Int,presentationTimeUs:Long){
                try{
                    check(!canceled.get()){ "Konversion abgebrochen" }
                    val identity=PmddGl.identity();val sample=render.sample(inputTexId,identity)
                    val map=try{engine.analyze(sample,identity,presentationTimeUs*1000,recipe.detectObjects)}finally{sample.recycle()}
                    check(!canceled.get()){ "Konversion abgebrochen" }
                    if(kind=="stereo"){
                        glViewport(0,0,width,height);render.draw(inputTexId,identity,width,height,recipe,map,eye=-1f)
                        glViewport(width,0,width,height);render.draw(inputTexId,identity,width,height,recipe,map,eye=1f)
                        glViewport(0,0,width*2,height)
                    }else{glViewport(0,0,width,height);render.draw(inputTexId,identity,width,height,recipe,map,if(kind=="depth")1 else 0)}
                    frames.incrementAndGet()
                }catch(e:Exception){throw VideoFrameProcessingException(e,presentationTimeUs)}
            }
            override fun release(){try{engine.close();render.release()}finally{super.release()}}
        }
    }
}

/** Transformer keeps source timestamps and audio; the effect outputs one frame per input.
 * No resize, frame-rate override, frame dropping effect, or encoder resolution fallback. */
@androidx.annotation.OptIn(UnstableApi::class)
class VideoConverter(private val context:Context) {
    private var transformer:Transformer?=null
    private var destination:File?=null
    private val canceled=AtomicBoolean(false)
    val frames=AtomicInteger()
    fun start(uri:Uri,file:File,recipe:Recipe,kind:String,done:(File)->Unit,error:(String)->Unit){
        check(transformer==null);canceled.set(false);frames.set(0);destination=file
        val effect=PmddVideoEffect(context,recipe.copy(),kind,frames,canceled)
        val settings=VideoEncoderSettings.Builder().experimentalSetEnableHighQualityTargeting(true).build()
        val factory=DefaultEncoderFactory.Builder(context).setRequestedVideoEncoderSettings(settings).setEnableFallback(false).build()
        val listener=object:Transformer.Listener {
            override fun onCompleted(composition:Composition,exportResult:ExportResult){
                transformer=null
                if(canceled.get()){file.delete();return}
                if(file.length()==0L||frames.get()==0){file.delete();error("Keine vollständige Videodatei erzeugt.");return}
                done(file)
            }
            override fun onError(composition:Composition,exportResult:ExportResult,exportException:ExportException){
                transformer=null;file.delete();if(!canceled.get())error("Der Gerätedecoder oder Encoder konnte diese Datei nicht verarbeiten: ${exportException.message}. Das Original bleibt unverändert.")
            }
        }
        val media=EditedMediaItem.Builder(MediaItem.fromUri(uri)).setEffects(Effects(emptyList(),listOf(effect))).build()
        val composition=Composition.Builder(EditedMediaItemSequence(media)).setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL).build()
        transformer=Transformer.Builder(context).setEncoderFactory(factory).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC).addListener(listener).build()
        try{transformer!!.start(composition,file.absolutePath)}catch(e:Exception){transformer=null;file.delete();throw e}
    }
    fun progress():Int? {val t=transformer?:return null;val holder=ProgressHolder();return if(t.getProgress(holder)==Transformer.PROGRESS_STATE_AVAILABLE)holder.progress else null}
    fun cancel(){canceled.set(true);transformer?.cancel();transformer=null;destination?.delete();destination=null}
}
