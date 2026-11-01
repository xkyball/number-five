package com.no5.helm.watchdog

/** Actual Window-commit events, retained outside disposable GPU Views.
 * A View replacement cannot erase a presentation gap. Pauses/source changes form
 * explicit admission boundaries; none of this changes the2000ms limit. */
internal class PresentationGapWatch {
    private var context:String?=null
    private var admitted=false
    private var previous=Long.MIN_VALUE
    private var maximum=0L
    var failures=0;private set
    fun admission(key:String,active:Boolean,now:Long) {
        if(!active){if(admitted)record(now);admitted=false;previous=Long.MIN_VALUE;return}
        if(!admitted||context!=key){if(admitted)record(now);context=key;previous=now;admitted=true}
    }
    fun presented(key:String,now:Long) {
        if(!admitted||context!=key)return
        record(now);previous=now
    }
    private fun record(now:Long):Long {
        if(previous==Long.MIN_VALUE)return maximum
        if(now<previous){failures++;maximum=Long.MAX_VALUE;return maximum}
        maximum=maxOf(maximum,now-previous);return maximum
    }
    fun maximumNs(now:Long):Long=if(admitted)record(now)else maximum
}
