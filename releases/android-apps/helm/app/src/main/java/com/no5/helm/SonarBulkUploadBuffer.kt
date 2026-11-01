package com.no5.helm

/** One fixed 256-KiB row-major stripe. Allocation failure keeps exact single-column upload. */
internal class SonarBulkUploadBuffer(allocate:()->IntArray={IntArray(64*1024)}) {
    val pixels:IntArray?=try{allocate().also{require(it.size==64*1024)}}catch(_:OutOfMemoryError){null}
    val maxColumns:Int get()=if(pixels==null)1 else 64
    val unavailable:Boolean get()=pixels==null
}
