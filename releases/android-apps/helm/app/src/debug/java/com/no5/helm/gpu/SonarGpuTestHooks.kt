package com.no5.helm.gpu
/** Private fixture SO exports only. No native implementation in the release build. */
internal object SonarGpuTestHooks {
    external fun colorColumn(handle:Long,slot:Int,output:IntArray)
}
