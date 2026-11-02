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
import kotlin.math.max
import kotlin.math.min

/** CameraX effect that routes the preview (and, in SDR video, the recording) through [ZoomBlurProcessor]. */
class ZoomBlurEffect(targets: Int, processor: ZoomBlurProcessor) :
    CameraEffect(targets, processor.executor, processor, { error -> Log.e(TAG, "Effect error", error) })

/**
 * OpenGL ES processor between the camera and its outputs.
 *
 * Preview output: radial zoom blur in (approximately) linear light, its strength following zoom speed, plus a
 * temporal blend with the previous frame (scaled to match the zoom change). When the logical camera switches
 * physical lens, a short blur + cross-fade pulse hides the perspective jump.
 *
 * Video output: a clean frame (no blur), with any digital zoom beyond the hardware maximum applied as a crop.
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

    // Written from the main / camera threads, read on the GL thread.
    @Volatile private var zoom = 1f
    @Volatile private var velocity = 0f
    @Volatile private var digitalZoom = 1f
    @Volatile private var lensSwitchAt = 0L

    // GL-thread state.
    private var blur = 0f
    private var lastZoom = 1f
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

        // Motion parameters, shared by every output this frame.
        val currentZoom = zoom
        val speed = abs(velocity)
        val sinceSwitch = (SystemClock.uptimeMillis() - lensSwitchAt) / 1000f
        val pulse = if (lensSwitchAt > 0L && sinceSwitch in 0f..0.6f) exp(-sinceSwitch / 0.14f) else 0f
        val targetBlur = min(0.14f, speed * 0.022f) + pulse * 0.02f
        blur += (targetBlur - blur) * (if (targetBlur > blur) 0.6f else 0.35f)
        if (blur < 0.0015f) blur = 0f
        val historyMix = max(min(0.4f, speed * 0.07f), pulse * 0.75f)
        val historyScale = if (currentZoom > 0f) lastZoom / currentZoom else 1f
        lastZoom = currentZoom
        frameCount++
        val seed = (frameCount % 64) * 0.618034f
        val digital = digitalZoom

        for ((output, target) in outputs) {
            output.updateTransformMatrix(outputMatrix, textureMatrix)
            if (!EGL14.eglMakeCurrent(display, target.surface, target.surface, context)) continue
            if (target.isPreview) {
                drawPreview(target, digital, blur, historyMix, historyScale, seed)
            } else {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                drawCamera(target.width, target.height, digital, 0f, 0f, 1f, seed, dummyTexture)
                EGLExt.eglPresentationTimeANDROID(display, target.surface, timestamp)
                EGL14.eglSwapBuffers(display, target.surface)
            }
        }
    }

    private fun drawPreview(target: Target, digital: Float, blur: Float, mix: Float, historyScale: Float, seed: Float) {
        ensureHistory(target)
        val current = target.index
        val previous = 1 - current

        // Pass 1: camera (+ blur + history) into this frame's history texture.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.framebuffers[current])
        drawCamera(target.width, target.height, digital, blur, if (target.historyReady) mix else 0f,
            historyScale, seed, target.textures[previous])

        // Pass 2: copy it to the screen.
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
        width: Int, height: Int, digital: Float, blur: Float, historyMix: Float,
        historyScale: Float, seed: Float, historyTexture: Int,
    ) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(mainProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTexture)
        GLES20.glUniform1i(uniform(mainProgram, "uCamera"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, historyTexture)
        GLES20.glUniform1i(uniform(mainProgram, "uHistory"), 1)
        GLES20.glUniformMatrix4fv(uniform(mainProgram, "uTexMatrix"), 1, false, outputMatrix, 0)
        GLES20.glUniform1f(uniform(mainProgram, "uDigitalZoom"), max(1f, digital))
        GLES20.glUniform1f(uniform(mainProgram, "uBlur"), blur)
        GLES20.glUniform1f(uniform(mainProgram, "uHistoryMix"), historyMix)
        GLES20.glUniform1f(uniform(mainProgram, "uHistoryScale"), historyScale)
        GLES20.glUniform1f(uniform(mainProgram, "uSeed"), seed)
        GLES20.glUniform1f(uniform(mainProgram, "uSamples"), if (blur > 0f) 12f else 1f)
        drawQuad(mainProgram)
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
            EGLExt.EGL_RECORDABLE_ANDROID, 1,          // needed to draw into the video encoder's surface
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

        const val CAMERA_SHADER = """
#extension GL_OES_EGL_image_external : require
precision highp float;
varying vec2 vUv;
uniform samplerExternalOES uCamera;
uniform sampler2D uHistory;
uniform mat4 uTexMatrix;
uniform float uDigitalZoom;
uniform float uBlur;
uniform float uHistoryMix;
uniform float uHistoryScale;
uniform float uSeed;
uniform float uSamples;

vec3 toLinear(vec3 c) { return pow(max(c, vec3(0.0)), vec3(2.2)); }
vec3 toGamma(vec3 c) { return pow(max(c, vec3(0.0)), vec3(1.0 / 2.2)); }

vec3 cameraAt(vec2 uv) {
    vec2 zoomed = vec2(0.5) + (uv - vec2(0.5)) / uDigitalZoom;
    vec2 tex = (uTexMatrix * vec4(zoomed, 0.0, 1.0)).xy;
    return toLinear(texture2D(uCamera, tex).rgb);
}

void main() {
    vec2 center = vec2(0.5);
    vec3 color;
    if (uBlur <= 0.0) {
        color = cameraAt(vUv);
    } else {
        // Radial zoom blur: jittered taps along the ray to the centre, blended in linear light.
        float noise = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))) + uSeed);
        vec3 acc = vec3(0.0);
        float weightSum = 0.0;
        for (int i = 0; i < 16; i++) {
            if (float(i) >= uSamples) break;
            float t = (float(i) + noise) / uSamples;
            float scale = 1.0 - uBlur * t;
            float w = 1.0 - 0.5 * t;
            acc += cameraAt(center + (vUv - center) * scale) * w;
            weightSum += w;
        }
        color = acc / weightSum;
    }
    if (uHistoryMix > 0.0) {
        vec3 previous = toLinear(texture2D(uHistory, center + (vUv - center) * uHistoryScale).rgb);
        color = mix(color, previous, uHistoryMix);
    }
    gl_FragColor = vec4(toGamma(color), 1.0);
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
