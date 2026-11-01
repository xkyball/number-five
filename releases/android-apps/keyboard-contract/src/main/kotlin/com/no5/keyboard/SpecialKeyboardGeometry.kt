package com.no5.keyboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build

/** No editor text, key, credential or renderer state crosses this boundary. */
object SpecialKeyboardGeometry {
    const val PERMISSION="com.no5.permission.SPECIAL_KEYBOARD_GEOMETRY"
    private const val ACTION="com.no5.keyboard.GEOMETRY"
    private const val BOUNDS="screen_bounds_px"
    private const val VISIBLE="visible"
    fun publish(context:Context,targetPackage:String?,bounds:Rect?) {
        if(targetPackage.isNullOrBlank())return
        val intent=Intent(ACTION).setPackage(targetPackage).putExtra(VISIBLE,bounds!=null)
        bounds?.let{intent.putExtra(BOUNDS,intArrayOf(it.left,it.top,it.right,it.bottom))}
        context.sendBroadcast(intent,PERMISSION)
    }
    class Subscription(context:Context,private val changed:(Rect?)->Unit):AutoCloseable {
        private val app=context.applicationContext
        private val receiver=object:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){
            if(intent.action!=ACTION)return
            if(!intent.getBooleanExtra(VISIBLE,false)){changed(null);return}
            val b=intent.getIntArrayExtra(BOUNDS)?:return
            if(b.size!=4||b.any{it !in 0..32768}||b[2]<=b[0]||b[3]<=b[1])return
            changed(Rect(b[0],b[1],b[2],b[3]))
        }}
        init {
            if(Build.VERSION.SDK_INT>=33)app.registerReceiver(receiver,IntentFilter(ACTION),PERMISSION,null,Context.RECEIVER_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") app.registerReceiver(receiver,IntentFilter(ACTION),PERMISSION,null)
        }
        override fun close(){app.unregisterReceiver(receiver)}
    }
}
