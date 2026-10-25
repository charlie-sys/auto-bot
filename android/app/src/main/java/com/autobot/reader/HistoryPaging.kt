package com.autobot.reader

internal data class HistoryPage<T>(val items:List<T>,val index:Int,val pages:Int,val total:Int)
internal object HistoryPaging {
    fun <T> page(items:List<T>,requested:Int,size:Int=10):HistoryPage<T>{
        require(size>0)
        val pages=((items.size+size-1)/size).coerceAtLeast(1)
        val index=requested.coerceIn(0,pages-1)
        val start=(index*size).coerceAtMost(items.size)
        return HistoryPage(items.subList(start,minOf(start+size,items.size)),index,pages,items.size)
    }
}
