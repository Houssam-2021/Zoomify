package com.zoomify.camera

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/** CameraX effect that routes the preview (and in Cinematic video, the recording) through [ZoomBlurProcessor]. */
class ZoomBlurEffect(targets: Int, processor: ZoomBlurProcessor) :
    CameraEffect(targets, processor.executor, processor, { error -> Log.e(TAG, "Effect error", error) })

/** How one output (preview or recording) is graded. */
data class OutputGrade(
    /** 0 = none, 1 = Zoomify Log -> display, 2 = display -> Zoomify Log (emulated log). */
    val transform: Int = 0,
    val look: Boolean = true,
    val lut: Boolean = false,
    /** Multiplier on the zoom motion blur (Velora bakes 65% of it into Cinematic recordings). */
    val blurScale: Float = 1f,
    /** Horizon lock (rotation + crop). */
    val horizon: Boolean = false,
)

/**
 * OpenGL ES 2 processor between the camera and its outputs. Zero-copy: camera frames arrive as an external
 * texture and are drawn straight into the preview / encoder surfaces, once per camera frame.
 */
class ZoomBlurProcessor : SurfaceProcessor {
    private val thread = HandlerThread("ZoomifyGL").apply { start() }
    private val handler = Handler(thread.looper)
    val executor: Executor = Executor { runnable -> handler.post(runnable) }

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE

    private var mainProgram = 0
    private var copyProgram = 0
    private var dummyTexture = 0
    private var lutTexture = 0
    private var lutSize = 0
    private val uniforms = HashMap<String, Int>()
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    private var inputTexture = 0
    private var inputSurfaceTexture: SurfaceTexture? = null
    private val outputs = LinkedHashMap<SurfaceOutput, Target>()
    private val textureMatrix = FloatArray(16)
    private val outputMatrix = FloatArray(16)

    private class Target(val surface: EGLSurface, val isPreview: Boolean, val width: Int, val height: Int) {
        val framebuffers = IntArray(2)
        val textures = IntArray(2)
        var index = 0
        var historyReady = false
        var hasHistory = false
    }

    // Written from other threads, read on the GL thread.
    @Volatile private var zoom = 1f
    @Volatile private var velocity = 0f
    @Volatile private var digitalZoom = 1f
    @Volatile private var lensSwitchAt = 0L
    @Volatile var blurStrength = 0.65f
    @Volatile var look = ColorLook.NATURAL
    @Volatile var previewGrade = OutputGrade()
    @Volatile var videoGrade = OutputGrade(blurScale = 0.65f)
    /** Content rotation for horizon lock, radians (counter-clockwise), and the crop that hides the corners. */
    @Volatile var horizonAngle = 0f
    @Volatile var horizonCrop = 1f
    /** Number of frames drawn to the preview, for the FPS readout. */
    @Volatile var previewFrames = 0L
        private set

    // GL-thread state.
    private var blur = 0f
    private var frameCount = 0
    private var released = false
    private var glReady = false

    init {
        handler.post { glReady = initGl() }
    }

    fun updateZoom(zoom: Float, velocity: Float, digitalZoom: Float) {
        this.zoom = zoom
        this.velocity = velocity
        this.digitalZoom = digitalZoom
    }

    fun triggerLensSwitch() {
        lensSwitchAt = SystemClock.uptimeMillis()
    }

    /** Uploads (or clears, with null) the LUT used by grades with `lut = true`. */
    fun setLut(lut: Lut?) {
        handler.post {
            if (!glReady) return@post
            makeCurrent(pbuffer)
            if (lut == null) {
                lutSize = 0
                return@post
            }
            val n = lut.size
            val bytes = ByteBuffer.allocateDirect(n * n * n * 4).order(ByteOrder.nativeOrder())
            // Atlas: n slices (blue) side by side, each n x n (red across, green down).
            for (g in 0 until n) for (b in 0 until n) for (r in 0 until n) {
                val i = ((b * n + g) * n + r) * 3
                bytes.put((lut.data[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte())
                bytes.put((lut.data[i + 1].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte())
                bytes.put((lut.data[i + 2].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte())
                bytes.put(255.toByte())
            }
            bytes.position(0)
            if (lutTexture == 0) {
                val ids = IntArray(1)
                GLES20.glGenTextures(1, ids, 0)
                lutTexture = ids[0]
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexture)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, n * n, n, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
            setTextureParameters(GLES20.GL_TEXTURE_2D)
            lutSize = n
        }
    }

    fun release() {
        handler.post {
            released = true
            for (output in outputs.keys.toList()) removeOutput(output)
            inputSurfaceTexture?.release()
            inputSurfaceTexture = null
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            thread.quitSafely()
        }
    }

    // region SurfaceProcessor

    override fun onInputSurface(request: SurfaceRequest) {
        if (released || !glReady) {
            request.willNotProvideSurface()
            return
        }
        makeCurrent(pbuffer)
        val texture = createExternalTexture()
        val surfaceTexture = SurfaceTexture(texture)
        surfaceTexture.setDefaultBufferSize(request.resolution.width, request.resolution.height)
        val surface = Surface(surfaceTexture)
        surfaceTexture.setOnFrameAvailableListener({ drawFrame(it) }, handler)
        inputSurfaceTexture = surfaceTexture
        inputTexture = texture
        request.provideSurface(surface, executor) {
            surfaceTexture.setOnFrameAvailableListener(null)
            surfaceTexture.release()
            surface.release()
            makeCurrent(pbuffer)
            GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            if (inputSurfaceTexture === surfaceTexture) {
                inputSurfaceTexture = null
                inputTexture = 0
            }
        }
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        if (released || !glReady) {
            surfaceOutput.close()
            return
        }
        val surface = surfaceOutput.getSurface(executor) { event ->
            if (event.eventCode == SurfaceOutput.Event.EVENT_REQUEST_CLOSE) removeOutput(surfaceOutput)
        }
        val eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "Could not create an EGL surface for ${surfaceOutput.size}")
            surfaceOutput.close()
            return
        }
        val isPreview = (surfaceOutput.targets and CameraEffect.PREVIEW) != 0
        outputs[surfaceOutput] = Target(eglSurface, isPreview, surfaceOutput.size.width, surfaceOutput.size.height)
    }

    // endregion

    private fun removeOutput(output: SurfaceOutput) {
        outputs.remove(output)?.let { target ->
            makeCurrent(pbuffer)
            if (target.hasHistory) {
                GLES20.glDeleteFramebuffers(2, target.framebuffers, 0)
                GLES20.glDeleteTextures(2, target.textures, 0)
            }
            EGL14.eglDestroySurface(display, target.surface)
        }
        output.close()
    }

    private fun drawFrame(surfaceTexture: SurfaceTexture) {
        if (released || surfaceTexture !== inputSurfaceTexture) return
        makeCurrent(pbuffer)
        surfaceTexture.updateTexImage()
        if (outputs.isEmpty()) return
        surfaceTexture.getTransformMatrix(textureMatrix)
        val timestamp = surfaceTexture.timestamp

        // Velora's blur: strength * |d log2(zoom) / dt| * 0.07, capped at 0.23. Released a little more gently.
        val speed = abs(velocity)
        val sinceSwitch = (SystemClock.uptimeMillis() - lensSwitchAt) / 1000f
        val pulse = if (lensSwitchAt > 0L && sinceSwitch in 0f..0.6f) exp(-sinceSwitch / 0.14f) else 0f
        val targetBlur = min(0.23f, blurStrength * speed * 0.07f) + pulse * 0.03f
        blur += (targetBlur - blur) * (if (targetBlur > blur) 0.7f else 0.4f)
        if (blur < 0.002f) blur = 0f
        frameCount++
        val seed = (frameCount % 64) * 0.618034f
        val digital = digitalZoom

        for ((output, target) in outputs) {
            output.updateTransformMatrix(outputMatrix, textureMatrix)
            if (!EGL14.eglMakeCurrent(display, target.surface, target.surface, context)) continue
            if (target.isPreview) {
                drawPreview(target, digital, previewGrade, pulse * 0.75f, seed)
                previewFrames++
            } else {
                val grade = videoGrade
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                drawCamera(target.width, target.height, digital, blur * grade.blurScale, grade, 0f, seed, dummyTexture)
                EGLExt.eglPresentationTimeANDROID(display, target.surface, timestamp)
                EGL14.eglSwapBuffers(display, target.surface)
            }
        }
    }

    private fun drawPreview(target: Target, digital: Float, grade: OutputGrade, mix: Float, seed: Float) {
        ensureHistory(target)
        val current = target.index
        val previous = 1 - current

        // Pass 1: camera (+ blur, grade, lens-switch cross-fade) into this frame's history texture.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.framebuffers[current])
        drawCamera(target.width, target.height, digital, blur * grade.blurScale, grade,
            if (target.historyReady) mix else 0f, seed, target.textures[previous])

        // Pass 2: copy to the screen.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, target.width, target.height)
        GLES20.glUseProgram(copyProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, target.textures[current])
        GLES20.glUniform1i(uniform(copyProgram, "uTexture"), 0)
        drawQuad(copyProgram)
        EGL14.eglSwapBuffers(display, target.surface)

        target.index = previous
        target.historyReady = true
    }

    private fun drawCamera(
        width: Int, height: Int, digital: Float, blur: Float, grade: OutputGrade,
        historyMix: Float, seed: Float, historyTexture: Int,
    ) {
        val p = mainProgram
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(p)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTexture)
        GLES20.glUniform1i(uniform(p, "uCamera"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, historyTexture)
        GLES20.glUniform1i(uniform(p, "uHistory"), 1)
        val lutOn = grade.lut && lutSize > 0
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (lutOn) lutTexture else dummyTexture)
        GLES20.glUniform1i(uniform(p, "uLut"), 2)

        GLES20.glUniformMatrix4fv(uniform(p, "uTexMatrix"), 1, false, outputMatrix, 0)
        GLES20.glUniform1f(uniform(p, "uDigitalZoom"), maxOf(1f, digital))
        GLES20.glUniform1f(uniform(p, "uBlur"), blur)
        GLES20.glUniform1f(uniform(p, "uHistoryMix"), historyMix)
        GLES20.glUniform1f(uniform(p, "uSeed"), seed)
        GLES20.glUniform1f(uniform(p, "uTransform"), grade.transform.toFloat())
        val currentLook = look
        GLES20.glUniform1f(uniform(p, "uSaturation"), if (grade.look) currentLook.saturation else 1f)
        GLES20.glUniform1f(uniform(p, "uShaped"), if (grade.look && currentLook.shaped) 1f else 0f)
        GLES20.glUniform1f(uniform(p, "uLutOn"), if (lutOn) 1f else 0f)
        GLES20.glUniform1f(uniform(p, "uLutSize"), maxOf(2, lutSize).toFloat())
        GLES20.glUniform1f(uniform(p, "uAngle"), if (grade.horizon) horizonAngle else 0f)
        GLES20.glUniform1f(uniform(p, "uCrop"), if (grade.horizon) horizonCrop else 1f)
        GLES20.glUniform1f(uniform(p, "uAspect"), width.toFloat() / height.toFloat())
        drawQuad(p)
    }

    private fun drawQuad(program: Int) {
        val location = GLES20.glGetAttribLocation(program, "aPosition")
        GLES20.glEnableVertexAttribArray(location)
        GLES20.glVertexAttribPointer(location, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(location)
    }

    private fun ensureHistory(target: Target) {
        if (target.hasHistory) return
        GLES20.glGenTextures(2, target.textures, 0)
        GLES20.glGenFramebuffers(2, target.framebuffers, 0)
        for (i in 0..1) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, target.textures[i])
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, target.width, target.height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            setTextureParameters(GLES20.GL_TEXTURE_2D)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.framebuffers[i])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, target.textures[i], 0)
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        target.hasHistory = true
        target.historyReady = false
    }

    // region GL setup

    private fun initGl(): Boolean {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return false
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) return false
        config = configs[0]
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        if (context == EGL14.EGL_NO_CONTEXT) return false
        pbuffer = EGL14.eglCreatePbufferSurface(display, config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        makeCurrent(pbuffer)

        mainProgram = buildProgram(VERTEX_SHADER, CAMERA_SHADER)
        copyProgram = buildProgram(VERTEX_SHADER, COPY_SHADER)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        dummyTexture = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, dummyTexture)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.allocateDirect(4))
        setTextureParameters(GLES20.GL_TEXTURE_2D)
        return mainProgram != 0 && copyProgram != 0
    }

    private fun makeCurrent(surface: EGLSurface) {
        EGL14.eglMakeCurrent(display, surface, surface, context)
    }

    private fun createExternalTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        setTextureParameters(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        return ids[0]
    }

    private fun setTextureParameters(target: Int) {
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun uniform(program: Int, name: String): Int =
        uniforms.getOrPut("$program:$name") { GLES20.glGetUniformLocation(program, name) }

    private fun buildProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (vertex == 0 || fragment == 0) return 0
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Program link failed: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    // endregion

    private companion object {
        const val VERTEX_SHADER = """
attribute vec4 aPosition;
varying vec2 vUv;
void main() {
    gl_Position = aPosition;
    vUv = aPosition.xy * 0.5 + 0.5;
}
"""

        // Zoomify Log constants: C = 0.03, L0 = log2(C), L1 = log2(1 + C). Keep in sync with ZoomifyLog.kt.
        const val CAMERA_SHADER = """
#extension GL_OES_EGL_image_external : require
precision highp float;
varying vec2 vUv;
uniform samplerExternalOES uCamera;
uniform sampler2D uHistory;
uniform sampler2D uLut;
uniform mat4 uTexMatrix;
uniform float uDigitalZoom;
uniform float uBlur;
uniform float uHistoryMix;
uniform float uSeed;
uniform float uTransform;
uniform float uSaturation;
uniform float uShaped;
uniform float uLutOn;
uniform float uLutSize;
uniform float uAngle;
uniform float uCrop;
uniform float uAspect;

const float LOG_C = 0.03;
const float LOG_L0 = -5.058894;
const float LOG_L1 = 0.042644;

vec3 toLinear(vec3 c) { return pow(max(c, vec3(0.0)), vec3(2.2)); }
vec3 toGamma(vec3 c) { return pow(max(c, vec3(0.0)), vec3(1.0 / 2.2)); }

// Output position -> camera texture: horizon-lock rotation + crop, then digital zoom, then CameraX's transform.
vec3 cameraAt(vec2 uv) {
    vec2 d = (uv - vec2(0.5)) * vec2(uAspect, 1.0);
    float s = sin(-uAngle);
    float c = cos(-uAngle);
    d = vec2(c * d.x - s * d.y, s * d.x + c * d.y);
    vec2 p = vec2(0.5) + d / vec2(uAspect, 1.0) / (uCrop * uDigitalZoom);
    vec2 tex = (uTexMatrix * vec4(p, 0.0, 1.0)).xy;
    return toLinear(texture2D(uCamera, tex).rgb);
}

vec3 linToLog(vec3 x) { return 0.06 + 0.88 * (log2(max(x, vec3(0.0)) + LOG_C) - LOG_L0) / (LOG_L1 - LOG_L0); }
vec3 logToLin(vec3 y) { return exp2(((y - 0.06) / 0.88) * (LOG_L1 - LOG_L0) + LOG_L0) - LOG_C; }
vec3 rolloff(vec3 x) { x = max(x, vec3(0.0)); return x * (1.0 + x / 16.0) / (1.0 + x); }
vec3 logToDisplay(vec3 y) {
    vec3 c = toGamma(clamp(rolloff(logToLin(y) * 2.0), 0.0, 1.0));
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    return mix(vec3(l), c, 1.05);
}

vec3 applyLut(vec3 c) {
    float n = uLutSize;
    vec3 s = clamp(c, 0.0, 1.0) * (n - 1.0);
    float b0 = floor(s.b);
    float b1 = min(b0 + 1.0, n - 1.0);
    vec2 rg = s.rg + 0.5;
    vec3 c0 = texture2D(uLut, vec2((b0 * n + rg.x) / (n * n), rg.y / n)).rgb;
    vec3 c1 = texture2D(uLut, vec2((b1 * n + rg.x) / (n * n), rg.y / n)).rgb;
    return mix(c0, c1, s.b - b0);
}

void main() {
    vec2 center = vec2(0.5);
    vec3 color;
    if (uBlur <= 0.0) {
        color = cameraAt(vUv);
    } else {
        // Velora's symmetric radial blur (taps from -0.5 to +0.5 along the ray, centre-weighted),
        // in linear light and jittered per pixel so it streaks smoothly instead of ghosting.
        float noise = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))) + uSeed);
        vec3 acc = vec3(0.0);
        float weightSum = 0.0;
        for (int i = 0; i < 8; i++) {
            float phase = (float(i) + noise) / 8.0 - 0.5;
            float w = 1.0 - abs(phase) * 0.65;
            acc += cameraAt(center + (vUv - center) * (1.0 + phase * uBlur)) * w;
            weightSum += w;
        }
        color = acc / weightSum;
    }

    vec3 c = toGamma(color);
    if (uTransform > 1.5) {
        c = linToLog(toLinear(c) * 0.5);
    } else if (uTransform > 0.5) {
        c = logToDisplay(c);
    }

    // Velora colour science.
    float luminance = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c = mix(vec3(luminance), c, uSaturation);
    if (uShaped > 0.5) {
        c *= vec3(1.004, 0.998, 0.992);
        c = mix(c, c * (0.93 + 0.12 * c), 0.26);
        c *= vec3(1.014, 1.004, 0.991);
    }
    if (uLutOn > 0.5) c = applyLut(c);
    c = clamp(c, 0.0, 1.0);

    if (uHistoryMix > 0.0) {
        c = mix(c, texture2D(uHistory, vUv).rgb, uHistoryMix);
    }
    gl_FragColor = vec4(c, 1.0);
}
"""

        const val COPY_SHADER = """
precision mediump float;
varying vec2 vUv;
uniform sampler2D uTexture;
void main() {
    gl_FragColor = texture2D(uTexture, vUv);
}
"""
    }
}

private const val TAG = "Zoomify"
