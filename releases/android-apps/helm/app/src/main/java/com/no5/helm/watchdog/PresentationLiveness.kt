package com.no5.helm.watchdog

/** Component diagnosis may wait for demonstrated finite CPU progress, never
 * beyond the unchanged2-second presentation boundary. Enqueue activity is NOT progress. */
internal object PresentationLiveness {
    fun missing(firstSubmitted:Long,lastSubmitted:Long,lastPresented:Long,lastWorkerProgress:Long,now:Long):Boolean {
        if(firstSubmitted<=0||lastSubmitted<=lastPresented)return false
        val age=now-maxOf(firstSubmitted,lastPresented)
        if(age>2_000_000_000L)return true
        return age>=1_000_000_000L&&now-lastWorkerProgress>=1_000_000_000L
    }
    fun progressing(lastSubmitted:Long,lastPresented:Long,lastWorkerProgress:Long,now:Long):Boolean =
        lastSubmitted>lastPresented&&lastWorkerProgress>0&&now>=lastWorkerProgress&&now-lastWorkerProgress<250_000_000L
}
