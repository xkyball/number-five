package com.no5.domain

/** Bounded primitive IMAGE storage. Only touched 32-KiB pages consume payload memory.
 * Snapshots share immutable pages; either owner copies a shared page before writing.
 * There is no resizing copy, quantization, sample interpretation or implicit eviction.
 */
class SonarShortStorage private constructor(val size:Int,private val pageSize:Int,initial:ShortArray?) {
    constructor(size:Int):this(size,minOf(size,16384),null)
    init { require(size in 1..4194304&&pageSize in 1..size) }
    private val pages=arrayOfNulls<ShortArray>((size+pageSize-1)/pageSize)
    private val shared=BooleanArray(pages.size)
    init { if(initial!=null){require(initial.size==size);pages[0]=initial} }
    val allocatedBytes:Long get()=pages.sumOf{(it?.size?:0).toLong()*2}
    fun offset(index:Int):Int {require(index in 0 until size);return index%pageSize}
    fun page(index:Int):ShortArray? {require(index in 0 until size);return pages[index/pageSize]}
    fun writablePage(index:Int):ShortArray {
        require(index in 0 until size);val slot=index/pageSize
        var page=pages[slot]
        if(page==null){page=ShortArray(minOf(pageSize,size-slot*pageSize));pages[slot]=page}
        else if(shared[slot]){page=page.copyOf();pages[slot]=page;shared[slot]=false}
        return page
    }
    operator fun get(index:Int):Short=page(index)?.get(offset(index))?:0
    operator fun set(index:Int,value:Short){writablePage(index)[offset(index)]=value}
    fun copyInto(destination:ShortArray,destinationOffset:Int,start:Int,end:Int){
        require(start in 0..size&&end in start..size&&destinationOffset>=0&&destinationOffset<=destination.size-(end-start))
        var source=start;var into=destinationOffset
        while(source<end){val amount=minOf(end-source,pageSize-source%pageSize);val p=page(source)
            if(p==null)java.util.Arrays.fill(destination,into,into+amount,0.toShort())
            else p.copyInto(destination,into,source%pageSize,source%pageSize+amount)
            source+=amount;into+=amount}
    }
    fun copyFrom(source:ShortArray,destinationOffset:Int,start:Int=0,end:Int=source.size){
        require(start in 0..source.size&&end in start..source.size&&destinationOffset>=0&&destinationOffset<=size-(end-start))
        var from=start;var into=destinationOffset
        while(from<end){val amount=minOf(end-from,pageSize-into%pageSize)
            source.copyInto(writablePage(into),into%pageSize,from,from+amount);from+=amount;into+=amount}
    }
    fun zero(start:Int,end:Int){
        require(start in 0..size&&end in start..size);var at=start
        while(at<end){val amount=minOf(end-at,pageSize-at%pageSize)
            java.util.Arrays.fill(writablePage(at),at%pageSize,at%pageSize+amount,0.toShort());at+=amount}
    }
    fun copy():SonarShortStorage=SonarShortStorage(size,pageSize,null).also{next->
        for(index in pages.indices)if(pages[index]!=null){shared[index]=true;next.pages[index]=pages[index];next.shared[index]=true}
    }
    fun release(){pages.fill(null);shared.fill(false)}
    companion object {
        /** Existing explicit allocation hooks remain useful for deterministic failure tests. */
        fun fromArray(array:ShortArray)=SonarShortStorage(array.size,array.size,array)
    }
}
