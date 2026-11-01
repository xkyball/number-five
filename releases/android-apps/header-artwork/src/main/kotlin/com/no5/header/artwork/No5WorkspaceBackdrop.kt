package com.no5.header.artwork

import android.content.res.AssetManager
import android.graphics.Canvas

/** Shared D03 painter; the native Control/info layers use the same Java implementation. */
class No5WorkspaceBackdrop(assets:AssetManager):AutoCloseable {
    private val painter=com.no5.ui.No5PanelBackdrop(assets)
    fun draw(canvas:Canvas,night:Boolean)=painter.draw(canvas,night)
    override fun close()=painter.close()
}
