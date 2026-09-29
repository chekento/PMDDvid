package cloud.kosch.pmddvid

import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES20.*
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Shared photographic depth shader for CameraX recording and offline conversion.
 * No frame blending, painted depth contours, depth-normal shadows or moving waves. */
class PmddGl(private val external:Boolean,private val linearInput:Boolean=false) {
    companion object {
        fun identity()=FloatArray(16).also{Matrix.setIdentityM(it,0)}
        private const val VERTEX="""
            attribute vec2 aPosition;
            varying vec2 vUv;
            void main(){vUv=aPosition*.5+.5;gl_Position=vec4(aPosition,0.,1.);}
        """
        private const val BODY="""
            precision highp float;
            varying vec2 vUv;
            uniform SOURCE_TYPE uImage;
            uniform sampler2D uDepth;
            uniform mat4 uMatrix,uDepthMatrix;
            uniform vec2 uPixel;
            uniform float uLinear,uMode,uUseDepth,uDepthGain,uSeparation,uFocus,uRelief,uHaze,uBokeh,uSharp,uOcclusion,uExposure,uContrast,uSaturation,uVignette,uInvert,uEye;
            uniform float uTechnique,uWarmth,uStyleSaturation,uStyleContrast,uLevels,uStyleMix,uInk,uLift,uToning;
            uniform vec3 uTint,uShadow,uHighlight;
            float lum(vec3 c){return dot(c,vec3(.2126,.7152,.0722));}
            vec3 source(vec2 p){vec2 uv=(uMatrix*vec4(clamp(p,vec2(.001),vec2(.999)),0.,1.)).xy;vec3 c=texture2D(uImage,uv).rgb;return mix(c,pow(max(c,vec3(0.)),vec3(1./2.2)),uLinear);}
            vec4 depthInfo(vec2 p){vec2 uv=(uDepthMatrix*vec4(p,0.,1.)).xy;float valid=step(0.,uv.x)*step(uv.x,1.)*step(0.,uv.y)*step(uv.y,1.);vec4 d=texture2D(uDepth,vec2(uv.x,1.-uv.y));d.a=valid;return d;}
            vec3 quantize(vec3 c){vec3 a=clamp(c,0.,1.)*max(1.,uLevels-1.);return (floor(a)+smoothstep(vec3(.18),vec3(.82),fract(a)))/max(1.,uLevels-1.);}
            void main(){
              vec2 uv=vUv;vec3 original=source(uv);
              if(uMode>1.5){gl_FragColor=vec4(mix(original,pow(max(original,vec3(0.)),vec3(2.2)),uLinear),1.);return;}
              vec4 info=depthInfo(uv);
              float trust=(1.-smoothstep(.07,.28,abs(lum(original)-info.g)))*info.a*uUseDepth;
              float d=mix(.5,mix(info.r,1.-info.r,uInvert),trust);
              if(uMode>.5){vec3 c=vec3(d);gl_FragColor=vec4(mix(c,pow(c,vec3(2.2)),uLinear),1.);return;}
              // Optional stereo uses a bounded local view change, never a painted edge.
              if(abs(uEye)>.01){
                vec2 p=uv+vec2(uEye*(d-.5)*min(.025,uDepthGain*.012)*uSeparation,0.);
                float other=depthInfo(p).r;float safe=1.-smoothstep(.045,.15,abs(info.r-other));uv=mix(uv,p,safe);
              }
              vec3 rgb=source(uv);
              vec3 a=source(uv+vec2(uPixel.x,0.)),b=source(uv-vec2(uPixel.x,0.));
              vec3 c=source(uv+vec2(0.,uPixel.y)),e=source(uv-vec2(0.,uPixel.y));
              vec3 soft=rgb*.25+(a+b+c+e)*.125+(source(uv+uPixel)+source(uv-uPixel)+source(uv+vec2(uPixel.x,-uPixel.y))+source(uv+vec2(-uPixel.x,uPixel.y)))*.0625;
              float gray=lum(rgb),detail=gray-lum(soft),edge=abs(lum(a)-lum(b))+abs(lum(c)-lum(e));
              vec3 styled=rgb;float ink=1.-smoothstep(.025,.19,edge)*uInk;
              if(uTechnique>.5&&uTechnique<1.5)styled=quantize(mix(rgb,soft,.45))*ink;
              else if(uTechnique<2.5&&uTechnique>1.5)styled=quantize(mix(rgb,soft,.65))*.9+.1-edge*.06;
              else if(uTechnique<3.5&&uTechnique>2.5)styled=mix(vec3(1.-smoothstep(.015,.17,edge)),uTint,0.12);
              else if(uTechnique<4.5&&uTechnique>3.5)styled=mix(vec3(1.-smoothstep(.009,.18,edge)*.85-max(0.,-detail)*1.4-(1.-gray)*.1),rgb,min(.7,uStyleSaturation*.45));
              else if(uTechnique<5.5&&uTechnique>4.5)styled=quantize(mix(rgb,soft,.7))+detail*.2;
              else if(uTechnique<6.5&&uTechnique>5.5){vec2 q=floor(uv*vec2(130.,130.))+0.5;styled=quantize(source(q/130.));}
              else if(uTechnique<7.5&&uTechnique>6.5){styled=quantize(rgb);float dotTone=smoothstep(.25,.65,length(fract(uv*75.)-.5));styled*=mix(1.,dotTone,(1.-gray)*.5);}
              else if(uTechnique<8.5&&uTechnique>7.5)styled=rgb*.7+uTint*smoothstep(.018,.2,edge)*.65;
              else if(uTechnique<9.5&&uTechnique>8.5)styled=mix(uShadow,uHighlight,smoothstep(0.,1.,gray));
              else if(uTechnique<10.5&&uTechnique>9.5)styled=vec3(1.-smoothstep(.012,.2,edge)*.9-(1.-gray)*.18);
              else if(uTechnique>10.5)styled=abs(rgb*2.-1.);
              float sl=lum(styled);styled=(mix(vec3(sl),styled,uStyleSaturation)-.5)*uStyleContrast+.5+vec3(uWarmth*.12,0.,-uWarmth*.15);
              styled+=((uShadow-.5)*(1.-gray)*(1.-gray)+(uHighlight-.5)*gray*gray)*uToning*.3;
              styled=mix(styled,vec3(1.),uLift);styled=mix(rgb,styled,uStyleMix);
              float gain=uDepthGain*(.8+.65*uDepthGain);
              float local=clamp(detail*(uSharp*(.35+d*.85)+uRelief*gain*(.16+d*.52)+uSeparation*gain*(d-.25)*.35),-.18,.18);
              float contact=max(-.065,min(0.,detail)*uOcclusion*gain*.12*(.35+.65*d));
              float contrast=clamp(1.+uContrast+uSeparation*gain*(d-.5)*.16,.6,1.9);
              float haze=min(.65,uHaze*pow(1.-d,1.5)*(.2+.12*uSeparation)*gain);
              float blur=min(.8,smoothstep(0.,.7,uFocus-d)*uBokeh*(.18+.16*gain)*clamp(1.-edge*2.,.2,1.));
              float radial=length(vUv-.5)*1.414;
              vec3 result=(mix(styled,soft,blur)-.5)*contrast+.5+local+contact+uExposure*.3-uVignette*radial*radial*.22;
              result=mix(result,vec3(.8,.87,.94),haze);result=mix(vec3(lum(result)),result,uSaturation);
              result=clamp(result,0.,1.);gl_FragColor=vec4(mix(result,pow(result,vec3(2.2)),uLinear),1.);
            }
        """
        fun fragment(external:Boolean)=(if(external)"#extension GL_OES_EGL_image_external : require\n" else "")+BODY.replace("SOURCE_TYPE",if(external)"samplerExternalOES" else "sampler2D")
    }
    private val program:Int
    private val vertices=ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply{put(floatArrayOf(-1f,-1f,1f,-1f,-1f,1f,1f,1f));position(0)}
    private val uniforms=HashMap<String,Int>()
    private val depthTexture:Int
    private var currentDepth:DepthFrame?=null
    private var probeFbo=0;private var probeTexture=0
    init{
        fun compile(type:Int,source:String):Int{val s=glCreateShader(type);glShaderSource(s,source);glCompileShader(s);val ok=IntArray(1);glGetShaderiv(s,GL_COMPILE_STATUS,ok,0);check(ok[0]!=0){glGetShaderInfoLog(s)};return s}
        val vertex=compile(GL_VERTEX_SHADER,VERTEX);val frag=compile(GL_FRAGMENT_SHADER,fragment(external));program=glCreateProgram();glAttachShader(program,vertex);glAttachShader(program,frag);glLinkProgram(program);glDeleteShader(vertex);glDeleteShader(frag);val ok=IntArray(1);glGetProgramiv(program,GL_LINK_STATUS,ok,0);check(ok[0]!=0){glGetProgramInfoLog(program)}
        depthTexture=texture2d(1,1,ByteBuffer.allocateDirect(4).put(byteArrayOf(128.toByte(),128.toByte(),0,255.toByte())).apply{rewind()})
    }
    private fun uniform(name:String)=uniforms.getOrPut(name){glGetUniformLocation(program,name)}
    private fun f(name:String,value:Float){glUniform1f(uniform(name),value)}
    private fun color(name:String,value:Int){glUniform3f(uniform(name),((value shr 16)and 255)/255f,((value shr 8)and 255)/255f,(value and 255)/255f)}
    private fun texture2d(w:Int,h:Int,pixels:ByteBuffer?):Int{val ids=IntArray(1);glGenTextures(1,ids,0);glBindTexture(GL_TEXTURE_2D,ids[0]);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,w,h,0,GL_RGBA,GL_UNSIGNED_BYTE,pixels);return ids[0]}
    fun draw(texture:Int,matrix:FloatArray,width:Int,height:Int,recipe:Recipe,depth:DepthFrame?,mode:Int=0,eye:Float=0f){
        glUseProgram(program);val position=glGetAttribLocation(program,"aPosition");glEnableVertexAttribArray(position);glVertexAttribPointer(position,2,GL_FLOAT,false,8,vertices)
        glActiveTexture(GL_TEXTURE0);glBindTexture(if(external)GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GL_TEXTURE_2D,texture);glUniform1i(uniform("uImage"),0)
        glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,depthTexture)
        if(depth!=null&&depth!==currentDepth){glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,256,256,0,GL_RGBA,GL_UNSIGNED_BYTE,depth.pixels.duplicate().apply{rewind()});currentDepth=depth}
        glUniform1i(uniform("uDepth"),1);glUniformMatrix4fv(uniform("uMatrix"),1,false,matrix,0)
        val mapping=identity();val inverse=FloatArray(16)
        if(depth!=null&&Matrix.invertM(inverse,0,depth.matrix,0))Matrix.multiplyMM(mapping,0,inverse,0,matrix,0)
        glUniformMatrix4fv(uniform("uDepthMatrix"),1,false,mapping,0)
        f("uLinear",if(linearInput)1f else 0f);f("uMode",mode.toFloat());f("uUseDepth",if(depth==null)0f else (1f-((System.nanoTime()-depth.createdNs)/1e9f-2f)/3f).coerceIn(0f,1f))
        val r=recipe.normalized();val s=Styles.get(r.style)
        val radius=max(1f,min(width,height)/280f);glUniform2f(uniform("uPixel"),radius/width,radius/height)
        f("uDepthGain",r.depth);f("uSeparation",r.separation);f("uFocus",r.focus);f("uRelief",r.relief);f("uHaze",r.haze);f("uBokeh",r.bokeh);f("uSharp",r.sharpness);f("uOcclusion",r.occlusion);f("uExposure",r.exposure);f("uContrast",r.contrast);f("uSaturation",r.saturation);f("uVignette",r.vignette);f("uInvert",if(r.invertDepth)1f else 0f);f("uEye",eye)
        f("uTechnique",s.technique.ordinal.toFloat());f("uWarmth",s.warmth);f("uStyleSaturation",s.saturation);f("uStyleContrast",s.contrast);f("uLevels",s.levels.toFloat());f("uStyleMix",r.styleMix);f("uInk",s.ink);f("uLift",s.lift);f("uToning",s.toning);color("uTint",s.tint);color("uShadow",s.shadow);color("uHighlight",s.highlight)
        glDrawArrays(GL_TRIANGLE_STRIP,0,4);glDisableVertexAttribArray(position);check(glGetError()==GL_NO_ERROR){"OpenGL-Effekt konnte nicht gerendert werden"}
    }
    /** Same source sampling matrix as the output, so depth cannot rotate away from RGB. */
    fun sample(texture:Int,matrix:FloatArray):Bitmap {
        val oldFbo=IntArray(1);val viewport=IntArray(4);glGetIntegerv(GL_FRAMEBUFFER_BINDING,oldFbo,0);glGetIntegerv(GL_VIEWPORT,viewport,0)
        if(probeFbo==0){probeTexture=texture2d(256,256,null);val ids=IntArray(1);glGenFramebuffers(1,ids,0);probeFbo=ids[0]}
        try{
            glBindFramebuffer(GL_FRAMEBUFFER,probeFbo);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,probeTexture,0);check(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE);glViewport(0,0,256,256)
            draw(texture,matrix,256,256,Recipe(),null,2)
            val bytes=ByteBuffer.allocateDirect(256*256*4);glReadPixels(0,0,256,256,GL_RGBA,GL_UNSIGNED_BYTE,bytes)
            val colors=IntArray(65536)
            for(y in 0 until 256)for(x in 0 until 256){val offset=(y*256+x)*4;var r=(bytes.get(offset).toInt()and 255)/255f;var g=(bytes.get(offset+1).toInt()and 255)/255f;var b=(bytes.get(offset+2).toInt()and 255)/255f;if(linearInput){r=r.pow(1/2.2f);g=g.pow(1/2.2f);b=b.pow(1/2.2f)};colors[(255-y)*256+x]=0xff000000.toInt()or((r*255).roundToInt() shl 16)or((g*255).roundToInt() shl 8)or(b*255).roundToInt()}
            return Bitmap.createBitmap(colors,256,256,Bitmap.Config.ARGB_8888)
        }finally{glBindFramebuffer(GL_FRAMEBUFFER,oldFbo[0]);glViewport(viewport[0],viewport[1],viewport[2],viewport[3])}
    }
    fun release(){glDeleteProgram(program);glDeleteTextures(1,intArrayOf(depthTexture),0);if(probeTexture!=0)glDeleteTextures(1,intArrayOf(probeTexture),0);if(probeFbo!=0)glDeleteFramebuffers(1,intArrayOf(probeFbo),0);currentDepth=null}
}
