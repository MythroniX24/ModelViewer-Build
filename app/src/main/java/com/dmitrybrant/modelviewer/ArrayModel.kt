package com.dmitrybrant.modelviewer

import android.opengl.GLES20
import android.opengl.Matrix
import com.dmitrybrant.modelviewer.util.Util.compileProgram
import java.nio.FloatBuffer

open class ArrayModel : Model() {
    var vertexCount = 0
    var vertexBuffer: FloatBuffer? = null
    var normalBuffer: FloatBuffer? = null

    // Needed by PlyModel
    protected var colorBuffer: FloatBuffer? = null
    protected var useColorBuffer = false

    // VBO handles — uploaded to GPU once, reused every frame
    private var vboVertex = 0
    private var vboNormal = 0
    private var vboReady  = false

    fun setMinMax(minX: Float, minY: Float, minZ: Float,
                  maxX: Float, maxY: Float, maxZ: Float) {
        this.minX=minX; this.minY=minY; this.minZ=minZ
        this.maxX=maxX; this.maxY=maxY; this.maxZ=maxZ
    }

    fun setCenterMass(cx: Float, cy: Float, cz: Float) {
        this.centerMassX=cx; this.centerMassY=cy; this.centerMassZ=cz
    }

    override fun setup(boundSize: Float) {
        if (glProgram >= 0) return
        glProgram = compileProgram(
            R.raw.model_vertex, R.raw.single_light_fragment,
            arrayOf("a_Position", "a_Normal")
        )
        initModelMatrix(boundSize)
    }

    private fun uploadVBO() {
        val vBuf = vertexBuffer ?: return
        val nBuf = normalBuffer ?: return

        val handles = IntArray(2)
        GLES20.glGenBuffers(2, handles, 0)
        vboVertex = handles[0]
        vboNormal = handles[1]

        vBuf.position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboVertex)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vBuf.limit() * 4, vBuf, GLES20.GL_STATIC_DRAW)

        nBuf.position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboNormal)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, nBuf.limit() * 4, nBuf, GLES20.GL_STATIC_DRAW)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        vboReady = true
    }

    override fun draw(viewMatrix: FloatArray, projectionMatrix: FloatArray, light: Light) {
        if (vertexCount == 0) return
        if (glProgram < 0) return

        // Upload to GPU on first draw
        if (!vboReady && vertexBuffer != null) uploadVBO()

        GLES20.glUseProgram(glProgram)

        val mvp = FloatArray(16)
        Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvp, 0, projectionMatrix, 0, mvMatrix, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(glProgram,"u_MVP"), 1, false, mvp, 0)
        GLES20.glUniform3fv(GLES20.glGetUniformLocation(glProgram,"u_LightPos"), 1, light.positionInEyeSpace, 0)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(glProgram,"u_ambientColor"),  0.12f,0.12f,0.16f,1f)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(glProgram,"u_diffuseColor"),  0.55f,0.60f,0.85f,1f)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(glProgram,"u_specularColor"), 0.45f,0.45f,0.55f,1f)

        val posH = GLES20.glGetAttribLocation(glProgram, "a_Position")
        val normH = GLES20.glGetAttribLocation(glProgram, "a_Normal")

        if (vboReady) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboVertex)
            GLES20.glEnableVertexAttribArray(posH)
            GLES20.glVertexAttribPointer(posH, 3, GLES20.GL_FLOAT, false, 0, 0)

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboNormal)
            GLES20.glEnableVertexAttribArray(normH)
            GLES20.glVertexAttribPointer(normH, 3, GLES20.GL_FLOAT, false, 0, 0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        } else {
            val vBuf = vertexBuffer ?: return
            val nBuf = normalBuffer ?: return
            vBuf.position(0)
            GLES20.glEnableVertexAttribArray(posH)
            GLES20.glVertexAttribPointer(posH, 3, GLES20.GL_FLOAT, false, 0, vBuf)
            nBuf.position(0)
            GLES20.glEnableVertexAttribArray(normH)
            GLES20.glVertexAttribPointer(normH, 3, GLES20.GL_FLOAT, false, 0, nBuf)
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)
        GLES20.glDisableVertexAttribArray(posH)
        GLES20.glDisableVertexAttribArray(normH)
    }

    // Needed by subclasses (PlyModel, ObjModel use this via drawFunc)
    open fun drawFunc() {}

    companion object {
        const val BYTES_PER_FLOAT = 4
        const val COORDS_PER_VERTEX = 3
        const val VERTEX_STRIDE = COORDS_PER_VERTEX * BYTES_PER_FLOAT
        const val INPUT_BUFFER_SIZE = 0x10000
    }
}
