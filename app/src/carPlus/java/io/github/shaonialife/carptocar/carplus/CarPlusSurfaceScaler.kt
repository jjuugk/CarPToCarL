package io.github.shaonialife.carptocar.carplus

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Fits the entire decoded ICCOA frame into the CarPlay encoder without stretching it. */
internal class CarPlusSurfaceScaler(
    val output: Surface,
    private val width: Int,
    private val height: Int,
    private val onRenderError: () -> Unit,
) : AutoCloseable {
    private val thread = HandlerThread("iccoa-scale").apply { start() }
    private val handler = Handler(thread.looper)
    private val closed = AtomicBoolean(false)
    @Volatile var sourceSize = CarPlusVideo.defaultSize
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var window = EGL14.EGL_NO_SURFACE
    private var texture: SurfaceTexture? = null
    private var input: Surface? = null

    fun open(): Surface {
        val task = FutureTask(::initialize)
        handler.post(task)
        return try {
            task.get(3, TimeUnit.SECONDS)
        } catch (error: Exception) {
            close()
            throw IllegalStateException("ICCOA 缩放初始化失败", error)
        }
    }

    private fun initialize(): Surface {
        val eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY && EGL14.eglInitialize(eglDisplay, version, 0, version, 1))
        display = eglDisplay
        val attributes = intArrayOf(
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            0x3142, 1, // EGL_RECORDABLE_ANDROID
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0)
        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        window = EGL14.eglCreateWindowSurface(display, configs[0], output, intArrayOf(EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, window, window, context))

        val names = IntArray(1)
        GLES20.glGenTextures(1, names, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, names[0])
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val source = SurfaceTexture(names[0]).also { texture = it }
        val initialSize = sourceSize
        source.setDefaultBufferSize(initialSize.width, initialSize.height)

        val vertex = shader(GLES20.GL_VERTEX_SHADER, """
            attribute vec2 uv;
            uniform mat4 transform;
            varying vec2 sampleAt;
            void main() {
                gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
                sampleAt = (transform * vec4(uv, 0.0, 1.0)).xy;
            }
        """.trimIndent())
        val fragment = shader(GLES20.GL_FRAGMENT_SHADER, """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 sampleAt;
            uniform samplerExternalOES source;
            void main() { gl_FragColor = texture2D(source, sampleAt); }
        """.trimIndent())
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] != 0) { GLES20.glGetProgramInfoLog(program) }
        GLES20.glUseProgram(program)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "source"), 0)
        val uv = GLES20.glGetAttribLocation(program, "uv")
        val transform = GLES20.glGetUniformLocation(program, "transform")
        val matrix = FloatArray(16)
        val vertices = ByteBuffer.allocateDirect(8 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply {
                put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
                position(0)
            }
        GLES20.glEnableVertexAttribArray(uv)
        val outputSize = CarPlusVideo.Size(width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        source.setOnFrameAvailableListener({ frame ->
            if (!closed.get()) {
                try {
                    frame.updateTexImage()
                    frame.getTransformMatrix(matrix)
                    val viewport = CarPlusVideo.fit(sourceSize, outputSize)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    GLES20.glViewport(viewport.x, height - viewport.y - viewport.height, viewport.width, viewport.height)
                    GLES20.glUniformMatrix4fv(transform, 1, false, matrix, 0)
                    GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 0, vertices)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                    EGLExt.eglPresentationTimeANDROID(display, window, frame.timestamp)
                    check(EGL14.eglSwapBuffers(display, window))
                } catch (error: RuntimeException) {
                    Log.e("CarPlusVideo", "ICCOA 缩放送帧失败", error)
                    close()
                    onRenderError()
                }
            }
        }, handler)
        return Surface(source).also { input = it }
    }

    private fun shader(type: Int, code: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, code)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] != 0) { GLES20.glGetShaderInfoLog(shader) }
        return shader
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        handler.post {
            input?.release()
            texture?.release()
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
                EGL14.eglReleaseThread()
            }
            thread.quitSafely()
        }
    }
}
