package com.no5.helm.video

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES
import android.opengl.GLES30.*
import android.opengl.GLUtils

/** EGL owner thread only. The only bitmap upload is the static aperture mask on resize. */
internal class VideoGl(assets: AssetManager) {
    val videoTexture: Int
    private val maskTexture: Int
    private val program: Int
    private val locations: Map<String,Int>
    init {
        val count=IntArray(1);glGetIntegerv(GL_NUM_EXTENSIONS,count,0)
        check((0 until count[0]).any { glGetStringi(GL_EXTENSIONS,it)=="GL_OES_EGL_image_external_essl3" }) { "GLES external-video texture extension unavailable" }
        fun shader(type: Int,file: String): Int {
            val id=glCreateShader(type)
            try {
                glShaderSource(id,assets.open(file).bufferedReader().use{it.readText()});glCompileShader(id)
                val ok=IntArray(1);glGetShaderiv(id,GL_COMPILE_STATUS,ok,0)
                check(ok[0]!=0) { "$file: ${glGetShaderInfoLog(id)}" };return id
            } catch(t: Throwable) {glDeleteShader(id);throw t}
        }
        val v=shader(GL_VERTEX_SHADER,"ocean/gl/sky.vert")
        var f=0;var p=0
        try {
            f=shader(GL_FRAGMENT_SHADER,"video/panorama.frag");p=glCreateProgram()
            glAttachShader(p,v);glAttachShader(p,f);glLinkProgram(p)
            val ok=IntArray(1);glGetProgramiv(p,GL_LINK_STATUS,ok,0)
            check(ok[0]!=0){glGetProgramInfoLog(p)};program=p
        } catch(t: Throwable){if(p!=0)glDeleteProgram(p);throw t}
        finally {glDeleteShader(v);if(f!=0)glDeleteShader(f)}
        val ids=IntArray(2);glGenTextures(2,ids,0);videoTexture=ids[0];maskTexture=ids[1]
        for((target,id) in listOf(GL_TEXTURE_EXTERNAL_OES to videoTexture,GL_TEXTURE_2D to maskTexture)) {
            glBindTexture(target,id)
            glTexParameteri(target,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(target,GL_TEXTURE_MAG_FILTER,GL_LINEAR)
            glTexParameteri(target,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(target,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE)
        }
        locations=listOf("uVideo","uMask","uTextureMatrix","uHeading","uAspect","uFog").associateWith{glGetUniformLocation(program,it)}
    }
    fun resize(w: Int,h: Int,mask: Bitmap) {
        glViewport(0,0,w,h);glBindTexture(GL_TEXTURE_2D,maskTexture);GLUtils.texImage2D(GL_TEXTURE_2D,0,mask,0)
    }
    fun draw(w: Int,h: Int,matrix: FloatArray,heading: Float,fog: Float) {
        glViewport(0,0,w,h);glDisable(GL_DEPTH_TEST);glDisable(GL_BLEND);glDisable(GL_CULL_FACE)
        glUseProgram(program)
        glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_EXTERNAL_OES,videoTexture);glUniform1i(locations.getValue("uVideo"),0)
        glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,maskTexture);glUniform1i(locations.getValue("uMask"),1)
        glUniformMatrix4fv(locations.getValue("uTextureMatrix"),1,false,matrix,0)
        glUniform1f(locations.getValue("uHeading"),heading);glUniform1f(locations.getValue("uAspect"),w.toFloat()/h)
        glUniform1f(locations.getValue("uFog"),fog)
        glBindVertexArray(0);glDrawArrays(GL_TRIANGLES,0,3)
        check(glGetError()==GL_NO_ERROR){"Video projection GL error"}
    }
    fun close(){glDeleteTextures(2,intArrayOf(videoTexture,maskTexture),0);glDeleteProgram(program)}
}
