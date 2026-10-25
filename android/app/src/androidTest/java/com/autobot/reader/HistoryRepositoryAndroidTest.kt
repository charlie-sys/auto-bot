package com.autobot.reader

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class HistoryRepositoryAndroidTest {
    private fun item(n:Int,photo:String="")=JSONObject().put("time",n.toLong()).put("full","question $n").put("answer","answer $n").put("photo",photo)
    @Test fun migrationFavoritesRetentionDetailsAndPhotoOwnership(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="history_test_"+UUID.randomUUID()
        val prefs=context.getSharedPreferences(name,Context.MODE_PRIVATE)
        val folder=File(context.cacheDir,name).apply{mkdirs()}
        val photo=File(folder,"favorite.jpg").apply{writeText("fixture")}
        val repo=HistoryRepository(prefs,folder)
        try {
            prefs.edit().putString("history",JSONArray().put(item(1,photo.absolutePath)).toString()).commit()
            val original=repo.load().entries.single()
            assertEquals(original.id,repo.load().entries.single().id)
            assertEquals(1,repo.favorite(original.id,true).favorites)
            assertEquals(1,repo.favorite(original.id,true).favorites)
            repo.append(JSONArray().apply{for(n in 2..65)put(item(n))})
            assertEquals(51,repo.load().entries.size)
            assertTrue(photo.exists())
            val detail=original.question().copy(solution="detailed explanation",fast=false)
            repo.updateDetails(original.question(),detail)
            assertEquals("detailed explanation",repo.load().entries.first{it.id==original.id}.solution)
            val retained=repo.clearHistory()
            assertEquals(1,retained.entries.size);assertTrue(retained.entries.single().favorite);assertTrue(photo.exists())
            assertEquals(0,repo.favorite(original.id,false).entries.size)
            assertFalse(photo.exists())
        } finally {prefs.edit().clear().commit();photo.delete();folder.delete();context.deleteSharedPreferences(name)}
    }
    @Test fun revokedOwnerCannotReadOrMutate(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="history_test_"+UUID.randomUUID()
        val prefs=context.getSharedPreferences(name,Context.MODE_PRIVATE)
        var current=true
        val repo=HistoryRepository(prefs,File(context.cacheDir,name)){current}
        try {
            repo.append(JSONArray().put(item(1)))
            val saved=prefs.getString("history","")
            current=false
            assertTrue(runCatching{repo.clearHistory()}.isFailure)
            assertTrue(runCatching{repo.load()}.isFailure)
            assertEquals(saved,prefs.getString("history",""))
        } finally {context.deleteSharedPreferences(name)}
    }
}
