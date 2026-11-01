package com.no5.helm.gpu

internal object SonarGpuSnapshotFence {
    fun expired(expected:Long,actual:Long,snapshotNewest:Long):Boolean =
        expected!=Long.MIN_VALUE && expected<=snapshotNewest && actual>snapshotNewest
    fun retainedCount(head:Int,count:Int,uploadedIds:LongArray):Int {
        require(uploadedIds.isNotEmpty()&&head in uploadedIds.indices&&count in 0..uploadedIds.size)
        for(age in 0 until count)if(uploadedIds[(head-1-age+uploadedIds.size)%uploadedIds.size]==Long.MIN_VALUE)return age
        return count
    }
}
