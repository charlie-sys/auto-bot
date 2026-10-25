package com.autobot.reader

import org.junit.Assert.*
import org.junit.Test

class HistoryPagingTest {
    @Test fun pageBoundsAndShrinkingResults(){
        val items=(1..51).toList()
        assertEquals((1..10).toList(),HistoryPaging.page(items,0).items)
        assertEquals(listOf(51),HistoryPaging.page(items,99).items)
        assertEquals(5,HistoryPaging.page(items,99).index)
        assertEquals(0,HistoryPaging.page(items.take(3),5).index)
        assertEquals(0,HistoryPaging.page(items,-1).index)
        assertEquals(1,HistoryPaging.page(emptyList<Int>(),9).pages)
        assertTrue(HistoryPaging.page(emptyList<Int>(),9).items.isEmpty())
    }
}
