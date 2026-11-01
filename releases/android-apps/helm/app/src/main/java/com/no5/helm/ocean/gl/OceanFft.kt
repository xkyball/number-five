package com.no5.helm.ocean.gl

import android.content.res.AssetManager
import android.opengl.GLES30.*
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** EGL-thread-only, fixed 128² spectral water: one evolution and fourteen butterfly passes. */
internal class OceanFft(private val assets: AssetManager) : AutoCloseable {
    private val textures = IntArray(4)
    private val framebuffers = IntArray(3)
    private val programs = mutableListOf<Int>()
    private var spectrum = 0
    private var butterfly = 0
    private var timeLocation = -1
    private var stageLocation = -1
    private var horizontalLocation = -1
    var displacement = 0; private set
    fun prepare() {
        // GLES3 alone does not imply float render targets. Allocate and check each real FBO.
        val count = IntArray(1); glGetIntegerv(GL_NUM_EXTENSIONS, count, 0)
        val extensions = (0 until count[0]).map { glGetStringi(GL_EXTENSIONS,it) }.toSet()
        check("GL_EXT_color_buffer_float" in extensions || "GL_EXT_color_buffer_half_float" in extensions) {
            "FFT needs renderable RGBA16F; ${glGetString(GL_VERSION)} has no float color extension"
        }
        glGenTextures(4,textures,0); glGenFramebuffers(3,framebuffers,0)
        val initial = OceanSpectrum.initial()
        val data = ByteBuffer.allocateDirect(initial.size*4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().put(initial).apply { position(0) }
        for (i in textures.indices) {
            glBindTexture(GL_TEXTURE_2D,textures[i])
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST)
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_REPEAT)
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_REPEAT)
            glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA16F,OceanSpectrum.SIZE,OceanSpectrum.SIZE,0,GL_RGBA,GL_FLOAT,if(i==0) data else null)
            if(i>0) {
                glBindFramebuffer(GL_FRAMEBUFFER,framebuffers[i-1])
                glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,textures[i],0)
                check(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE) { "FFT RGBA16F framebuffer $i incomplete" }
            }
        }
        spectrum=program("spectrum.frag"); butterfly=program("fft.frag")
        for(p in programs) { glUseProgram(p); glUniform1i(glGetUniformLocation(p,"uInput"),0) }
        timeLocation=glGetUniformLocation(spectrum,"uTime")
        stageLocation=glGetUniformLocation(butterfly,"uStage")
        horizontalLocation=glGetUniformLocation(butterfly,"uHorizontal")
        check(glGetError()==GL_NO_ERROR) { "FFT resource probe failed" }
        Log.i("No5Ocean","FFT128 RGBA16F framebuffer probe PASS; 15 passes; bounded textures < 1 MiB; ${glGetString(GL_VERSION)}")
    }
    fun draw(time: Float) {
        glDisable(GL_DEPTH_TEST); glDisable(GL_BLEND); glDisable(GL_CULL_FACE)
        glBindVertexArray(0); glViewport(0,0,OceanSpectrum.SIZE,OceanSpectrum.SIZE)
        glActiveTexture(GL_TEXTURE0)
        glUseProgram(spectrum); glUniform1f(timeLocation,time)
        glBindTexture(GL_TEXTURE_2D,textures[0]); glBindFramebuffer(GL_FRAMEBUFFER,framebuffers[0])
        glDrawArrays(GL_TRIANGLES,0,3)
        glUseProgram(butterfly)
        var input=1
        for(axis in 1 downTo 0) {
            glUniform1i(horizontalLocation,axis)
            var stage=2
            while(stage<=OceanSpectrum.SIZE) {
                val output=if(input==2) 3 else 2
                glBindTexture(GL_TEXTURE_2D,textures[input])
                glBindFramebuffer(GL_FRAMEBUFFER,framebuffers[output-1])
                glUniform1i(stageLocation,stage); glDrawArrays(GL_TRIANGLES,0,3)
                input=output; stage*=2
            }
        }
        displacement=textures[input]
        glBindFramebuffer(GL_FRAMEBUFFER,0)
        glBindTexture(GL_TEXTURE_2D,displacement)
        glGenerateMipmap(GL_TEXTURE_2D)
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR_MIPMAP_LINEAR)
    }
    private fun program(fragment: String): Int {
        fun shader(file: String,kind: Int): Int {
            val shader=glCreateShader(kind)
            try {
                val source=assets.open("ocean/gl/$file").bufferedReader().use { it.readText() }
                glShaderSource(shader,source); glCompileShader(shader)
                val status=IntArray(1); glGetShaderiv(shader,GL_COMPILE_STATUS,status,0)
                check(status[0]!=0) { "$file: ${glGetShaderInfoLog(shader)}" }
                return shader
            } catch(t: Throwable) { glDeleteShader(shader); throw t }
        }
        val v=shader("sky.vert",GL_VERTEX_SHADER)
        var f=0; var p=0
        try {
            f=shader(fragment,GL_FRAGMENT_SHADER); p=glCreateProgram()
            glAttachShader(p,v); glAttachShader(p,f); glLinkProgram(p)
            val status=IntArray(1); glGetProgramiv(p,GL_LINK_STATUS,status,0)
            check(status[0]!=0) { "FFT link: ${glGetProgramInfoLog(p)}" }
            programs.add(p); return p
        } catch(t: Throwable) { if(p!=0) glDeleteProgram(p); throw t }
        finally { glDeleteShader(v); if(f!=0) glDeleteShader(f) }
    }
    override fun close() {
        glDeleteFramebuffers(3,framebuffers,0); glDeleteTextures(4,textures,0)
        programs.forEach { glDeleteProgram(it) }; programs.clear()
        framebuffers.fill(0); textures.fill(0)
    }
}
