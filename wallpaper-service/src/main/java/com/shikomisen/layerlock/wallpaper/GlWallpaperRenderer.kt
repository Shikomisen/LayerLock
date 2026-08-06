package com.shikomisen.layerlock.wallpaper

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * GL compositor for scenes with a video background.
 *
 * A `WallpaperService` gets exactly one [Surface], and a decoded video frame arrives on that surface
 * as an external texture rather than as pixels a `Canvas` can blit. So either the wallpaper shows
 * video *or* it shows canvas layers — unless the two are composited in GL, which is what this does:
 * the video is drawn as a full-screen quad from a `SurfaceTexture`, and everything the shared canvas
 * renderer produces is uploaded as a single premultiplied RGBA texture and blended over the top.
 *
 * Scenes with no video background never touch this class — [SceneWallpaperEngine] uses a plain
 * `lockCanvas` path for those, which is both simpler and cheaper.
 */
internal class GlWallpaperRenderer(
    private val surface: Surface,
    private var width: Int,
    private var height: Int,
) {

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var videoProgram = 0
    private var overlayProgram = 0
    private var videoTextureId = 0
    private var overlayTextureId = 0

    private var overlayUploaded = false

    private val stMatrix = FloatArray(16)

    /** Texture coordinates, rewritten per frame to letterbox-free "cover" the video. */
    private val videoTexCoords = floatBuffer(
        floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f),
    )

    private val quadVertices = floatBuffer(
        floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f),
    )

    private val overlayTexCoords = floatBuffer(
        // The canvas bitmap has its origin top-left; GL samples bottom-up, so V is flipped here.
        floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f),
    )

    var surfaceTexture: SurfaceTexture? = null
        private set

    /** True once EGL and both shader programs are ready. */
    var isReady = false
        private set

    fun initialise(): Boolean {
        return runCatching {
            setUpEgl()
            videoProgram = buildProgram(VERTEX_SHADER, OES_FRAGMENT_SHADER)
            overlayProgram = buildProgram(VERTEX_SHADER, RGBA_FRAGMENT_SHADER)
            videoTextureId = createExternalTexture()
            overlayTextureId = createTexture2d()
            surfaceTexture = SurfaceTexture(videoTextureId)
            isReady = true
            true
        }.getOrElse { error ->
            Log.w(TAG, "GL setup failed; falling back to the canvas renderer", error)
            release()
            false
        }
    }

    private fun setUpEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }

        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

        val configAttributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val configCount = IntArray(1)
        check(
            EGL14.eglChooseConfig(display, configAttributes, 0, configs, 0, 1, configCount, 0) &&
                configCount[0] > 0,
        ) { "No suitable EGL config" }

        context = EGL14.eglCreateContext(
            display,
            configs[0],
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        eglSurface = EGL14.eglCreateWindowSurface(
            display,
            configs[0],
            surface,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent failed" }
    }

    fun resize(width: Int, height: Int) {
        this.width = width
        this.height = height
    }

    /** Uploads a newly rendered overlay. Call only when the canvas content actually changed. */
    fun setOverlay(bitmap: Bitmap?) {
        if (!isReady) return
        if (bitmap == null) {
            overlayUploaded = false
            return
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        overlayUploaded = true
    }

    /**
     * Draws one frame.
     *
     * @param videoWidth intrinsic video size, used to cover the surface without distortion.
     */
    fun drawFrame(videoWidth: Int, videoHeight: Int): Boolean {
        if (!isReady) return false

        return runCatching {
            surfaceTexture?.let { texture ->
                texture.updateTexImage()
                texture.getTransformMatrix(stMatrix)
            }

            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            updateVideoTexCoords(videoWidth, videoHeight)
            drawVideoQuad()

            if (overlayUploaded) {
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                drawOverlayQuad()
                GLES20.glDisable(GLES20.GL_BLEND)
            }

            EGL14.eglSwapBuffers(display, eglSurface)
            true
        }.getOrElse { error ->
            Log.w(TAG, "GL draw failed", error)
            false
        }
    }

    /**
     * Crops the video's texture coordinates so it covers the surface.
     *
     * Scaling the *quad* instead would push video off-screen; cropping the sampled region keeps the
     * geometry fixed at full-screen and is what "cover" means for a wallpaper.
     */
    private fun updateVideoTexCoords(videoWidth: Int, videoHeight: Int) {
        if (videoWidth <= 0 || videoHeight <= 0 || width <= 0 || height <= 0) return

        val surfaceAspect = width.toFloat() / height
        val videoAspect = videoWidth.toFloat() / videoHeight

        var cropX = 0f
        var cropY = 0f
        if (videoAspect > surfaceAspect) {
            // Video is wider than the screen: trim the sides.
            cropX = (1f - surfaceAspect / videoAspect) / 2f
        } else {
            cropY = (1f - videoAspect / surfaceAspect) / 2f
        }

        val left = cropX
        val right = 1f - cropX
        val top = cropY
        val bottom = 1f - cropY

        videoTexCoords.clear()
        videoTexCoords.put(
            floatArrayOf(left, bottom, right, bottom, left, top, right, top),
        )
        videoTexCoords.position(0)
    }

    private fun drawVideoQuad() {
        GLES20.glUseProgram(videoProgram)
        bindQuad(videoProgram, videoTexCoords)

        GLES20.glUniformMatrix4fv(
            GLES20.glGetUniformLocation(videoProgram, "uSTMatrix"),
            1,
            false,
            stMatrix,
            0,
        )

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(videoProgram, "sTexture"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawOverlayQuad() {
        GLES20.glUseProgram(overlayProgram)
        bindQuad(overlayProgram, overlayTexCoords)

        // The overlay needs no stream transform, so the identity matrix keeps one shared vertex shader.
        GLES20.glUniformMatrix4fv(
            GLES20.glGetUniformLocation(overlayProgram, "uSTMatrix"),
            1,
            false,
            IDENTITY,
            0,
        )

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(overlayProgram, "sTexture"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun bindQuad(program: Int, texCoords: FloatBuffer) {
        val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        quadVertices.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, quadVertices)
        GLES20.glEnableVertexAttribArray(positionHandle)

        val texHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        texCoords.position(0)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glEnableVertexAttribArray(texHandle)
    }

    private fun createExternalTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        applyClampedLinearFilter(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        return ids[0]
    }

    private fun createTexture2d(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        applyClampedLinearFilter(GLES20.GL_TEXTURE_2D)
        return ids[0]
    }

    private fun applyClampedLinearFilter(target: Int) {
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun buildProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) {
            "Program link failed: ${GLES20.glGetProgramInfoLog(program)}"
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) {
            "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}"
        }
        return shader
    }

    fun release() {
        isReady = false
        runCatching { surfaceTexture?.release() }
        surfaceTexture = null

        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    private fun floatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }

    private companion object {
        const val TAG = "LayerLockGl"

        val IDENTITY = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )

        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uSTMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uSTMatrix * aTexCoord).xy;
            }
        """

        const val OES_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """

        const val RGBA_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D sTexture;
            void main() {
                vec4 colour = texture2D(sTexture, vTexCoord);
                gl_FragColor = vec4(colour.rgb * colour.a, colour.a);
            }
        """
    }
}
