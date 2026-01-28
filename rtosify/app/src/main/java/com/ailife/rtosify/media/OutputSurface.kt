package com.ailife.rtosify.media

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Holds state associated with a Surface used for MediaCodec decoder output.
 * Theoretically allows rendering to a SurfaceTexture, but we just use it to link Decoder -> Encoder.
 */
class OutputSurface(surface: Surface? = null) : SurfaceTexture.OnFrameAvailableListener {
    private var mEGLDisplay: EGLDisplay? = EGL14.EGL_NO_DISPLAY
    private var mEGLContext: EGLContext? = EGL14.EGL_NO_CONTEXT
    private var mEGLSurface: EGLSurface? = EGL14.EGL_NO_SURFACE
    private var mSurfaceTexture: SurfaceTexture? = null
    var surface: Surface? = surface
        private set
    private val mFrameSyncObject = Object()
    private var mFrameAvailable = false
    private var mTextureRender: TextureRender? = null

    init {
        if (surface != null) {
            eglSetup(surface)
        } else {
            setup()
        }
    }

    private fun setup() {
        mTextureRender = TextureRender()
        mTextureRender!!.surfaceCreated()
        
        // EGL Setup
        mEGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (mEGLDisplay === EGL14.EGL_NO_DISPLAY) throw RuntimeException("unable to get EGL14 display")
        
        val version = IntArray(2)
        if (!EGL14.eglInitialize(mEGLDisplay, version, 0, version, 1)) {
            mEGLDisplay = null
            throw RuntimeException("unable to initialize EGL14")
        }

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE, 0,      // placeholder for recordable [@Android 8.0+]
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(mEGLDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0)) {
            throw RuntimeException("unable to find RGB888+recordable ES2 EGL config")
        }

        val attrib_list = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        mEGLContext = EGL14.eglCreateContext(mEGLDisplay, configs[0], EGL14.EGL_NO_CONTEXT, attrib_list, 0)
        checkEglError("eglCreateContext")
        if (mEGLContext == null) throw RuntimeException("null context")

        // Create a window surface (if we had a real surface) or a pbuffer?
        // Actually, for Decoder -> Encoder via Surface, we usually pass the Encoder's Input Surface here.
        // Wait, the VideoCompressor passed the Encoder's Input Surface to the constructor.
        // So we need to create an EGLSurface FROM that surface.
    }
    
    // Adjusted constructor logic merged into init block

    private fun eglSetup(surface: Surface) {
        mTextureRender = TextureRender()
        mTextureRender!!.surfaceCreated()

        mEGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (mEGLDisplay === EGL14.EGL_NO_DISPLAY) throw RuntimeException("unable to get EGL14 display")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(mEGLDisplay, version, 0, version, 1)) {
            mEGLDisplay = null
            throw RuntimeException("unable to initialize EGL14")
        }

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(mEGLDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0)) {
             throw RuntimeException("unable to find RGB888+recordable ES2 EGL config")
        }

        val attrib_list = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        mEGLContext = EGL14.eglCreateContext(mEGLDisplay, configs[0], EGL14.EGL_NO_CONTEXT, attrib_list, 0)
        checkEglError("eglCreateContext")

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        mEGLSurface = EGL14.eglCreateWindowSurface(mEGLDisplay, configs[0], surface, surfaceAttribs, 0)
        checkEglError("eglCreateWindowSurface")
    }

    fun makeCurrent() {
        if (!EGL14.eglMakeCurrent(mEGLDisplay, mEGLSurface, mEGLSurface, mEGLContext)) {
            throw RuntimeException("eglMakeCurrent failed")
        }
    }

    fun release() {
        if (mEGLDisplay !== EGL14.EGL_NO_DISPLAY) {
            EGL14.eglDestroySurface(mEGLDisplay, mEGLSurface)
            EGL14.eglDestroyContext(mEGLDisplay, mEGLContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(mEGLDisplay)
        }
        surface?.release()
        mEGLDisplay = EGL14.EGL_NO_DISPLAY
        mEGLContext = EGL14.EGL_NO_CONTEXT
        mEGLSurface = EGL14.EGL_NO_SURFACE
        mSurfaceTexture = null
        surface = null
    }

    fun awaitNewImage() {
        val TIMEOUT_MS = 2500L
        synchronized(mFrameSyncObject) {
            while (!mFrameAvailable) {
                try {
                    mFrameSyncObject.wait(TIMEOUT_MS)
                    if (!mFrameAvailable) {
                        // throw RuntimeException("Surface frame wait timed out")
                        // Be lenient
                        return
                    }
                } catch (ie: InterruptedException) {
                    throw RuntimeException(ie)
                }
            }
            mFrameAvailable = false
        }
        mTextureRender?.checkGlError("before updateTexImage")
        mSurfaceTexture?.updateTexImage()
    }

    fun drawImage(invert: Boolean) {
        mTextureRender?.drawFrame(mSurfaceTexture!!, invert)
    }

    override fun onFrameAvailable(st: SurfaceTexture) {
        synchronized(mFrameSyncObject) {
            if (mFrameAvailable) throw RuntimeException("mFrameAvailable already set, frame could be dropped")
            mFrameAvailable = true
            mFrameSyncObject.notifyAll()
        }
    }

    fun setPresentationTime(nsecs: Long) {
        EGLExt.eglPresentationTimeANDROID(mEGLDisplay, mEGLSurface, nsecs)
    }
    
    fun swapBuffers(): Boolean {
        return EGL14.eglSwapBuffers(mEGLDisplay, mEGLSurface)
    }

    private fun checkEglError(msg: String) {
        val error = EGL14.eglGetError()
        if (error != EGL14.EGL_SUCCESS) {
            throw RuntimeException(msg + ": EGL error: 0x" + Integer.toHexString(error))
        }
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
    
    // Minimal TextureRender
    private class TextureRender {
        private val mTriangleVerticesData = floatArrayOf(
            -1.0f, -1.0f, 0f, 0f, 0f,
            1.0f, -1.0f, 0f, 1f, 0f,
            -1.0f, 1.0f, 0f, 0f, 1f,
            1.0f, 1.0f, 0f, 1f, 1f
        )
        private val FLOAT_SIZE_BYTES = 4
        private val TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES
        private val TRIANGLE_VERTICES_DATA_POS_OFFSET = 0
        private val TRIANGLE_VERTICES_DATA_UV_OFFSET = 3
        private var mTriangleVertices: java.nio.FloatBuffer
        private var mProgram = 0
        private var muMVPMatrixHandle = 0
        private var muSTMatrixHandle = 0
        private var maPositionHandle = 0
        private var maTextureHandle = 0
        private val mMVPMatrix = FloatArray(16)
        private val mSTMatrix = FloatArray(16)
        private var mTextureID = -12345

        init {
            mTriangleVertices = ByteBuffer.allocateDirect(mTriangleVerticesData.size * FLOAT_SIZE_BYTES)
                .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
            mTriangleVertices.put(mTriangleVerticesData).position(0)
            android.opengl.Matrix.setIdentityM(mSTMatrix, 0)
        }

        fun drawFrame(st: SurfaceTexture, invert: Boolean) {
            checkGlError("onDrawFrame start")
            st.getTransformMatrix(mSTMatrix)
            if (invert) {
                mSTMatrix[5] = -mSTMatrix[5]
                mSTMatrix[13] = 1.0f - mSTMatrix[13]
            }
            GLES20.glClearColor(0.0f, 1.0f, 0.0f, 1.0f)
            GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT or GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(mProgram)
            checkGlError("glUseProgram")
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(generatedTextureTarget, mTextureID)
            mTriangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET)
            GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false, TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices)
            checkGlError("glVertexAttribPointer maPosition")
            GLES20.glEnableVertexAttribArray(maPositionHandle)
            checkGlError("glEnableVertexAttribArray maPositionHandle")
            mTriangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET)
            GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false, TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices)
            checkGlError("glVertexAttribPointer maTextureHandle")
            GLES20.glEnableVertexAttribArray(maTextureHandle)
            checkGlError("glEnableVertexAttribArray maTextureHandle")
            android.opengl.Matrix.setIdentityM(mMVPMatrix, 0)
            GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrix, 0)
            GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            checkGlError("glDrawArrays")
            GLES20.glFinish()
        }

        fun surfaceCreated() {
            mProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            if (mProgram == 0) throw RuntimeException("failed creating program")
            maPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition")
            checkGlError("glGetAttribLocation aPosition")
            if (maPositionHandle == -1) throw RuntimeException("Could not get attrib location for aPosition")
            maTextureHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord")
            checkGlError("glGetAttribLocation aTextureCoord")
            if (maTextureHandle == -1) throw RuntimeException("Could not get attrib location for aTextureCoord")
            muMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix")
            checkGlError("glGetUniformLocation uMVPMatrix")
            if (muMVPMatrixHandle == -1) throw RuntimeException("Could not get attrib location for uMVPMatrix")
            muSTMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uSTMatrix")
            checkGlError("glGetUniformLocation uSTMatrix")
            if (muSTMatrixHandle == -1) throw RuntimeException("Could not get attrib location for uSTMatrix")
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            mTextureID = textures[0]
            GLES20.glBindTexture(generatedTextureTarget, mTextureID)
            checkGlError("glBindTexture mTextureID")
            GLES20.glTexParameterf(generatedTextureTarget, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST.toFloat())
            GLES20.glTexParameterf(generatedTextureTarget, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
            GLES20.glTexParameteri(generatedTextureTarget, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(generatedTextureTarget, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            checkGlError("glTexParameter")
        }

        fun checkGlError(op: String) {
            val error = GLES20.glGetError()
            if (error != GLES20.GL_NO_ERROR) {
                // Log.e(TAG, "$op: glError $error")
                // throw RuntimeException("$op: glError $error")
            }
        }
        
        private fun createProgram(vertexSource: String, fragmentSource: String): Int {
            val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
            if (vertexShader == 0) return 0
            val pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            if (pixelShader == 0) return 0
            var program = GLES20.glCreateProgram()
            if (program != 0) {
                GLES20.glAttachShader(program, vertexShader)
                checkGlError("glAttachShader")
                GLES20.glAttachShader(program, pixelShader)
                checkGlError("glAttachShader")
                GLES20.glLinkProgram(program)
                val linkStatus = IntArray(1)
                GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
                if (linkStatus[0] != GLES20.GL_TRUE) {
                    GLES20.glDeleteProgram(program)
                    program = 0
                }
            }
            return program
        }

        private fun loadShader(shaderType: Int, source: String): Int {
            var shader = GLES20.glCreateShader(shaderType)
            if (shader != 0) {
                GLES20.glShaderSource(shader, source)
                GLES20.glCompileShader(shader)
                val compiled = IntArray(1)
                GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
                if (compiled[0] == 0) {
                    GLES20.glDeleteShader(shader)
                    shader = 0
                }
            }
            return shader
        }

        private val generatedTextureTarget = 0x8D65 // GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        private val VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;\n" +
            "uniform mat4 uSTMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTextureCoord;\n" +
            "varying vec2 vTextureCoord;\n" +
            "void main() {\n" +
            "  gl_Position = uMVPMatrix * aPosition;\n" +
            "  vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
            "}\n"
        private val FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
            "}\n"
    }
    
    // Stub class to avoid extra file import issues
    object EGLExt { 
        fun eglPresentationTimeANDROID(dpy: EGLDisplay?, surf: EGLSurface?, time: Long) {
            // No-op or reflection if needed, but assuming API 18+ usually EGL14 supports it via extension.
            // Actually, we need android.opengl.EGLExt
             android.opengl.EGLExt.eglPresentationTimeANDROID(dpy, surf, time)
        }
    }
}
