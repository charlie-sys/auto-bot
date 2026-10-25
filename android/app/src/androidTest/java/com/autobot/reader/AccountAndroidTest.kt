package com.autobot.reader

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AccountAndroidTest {
    private fun sandbox(): Context {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val prefix="account_test_"+UUID.randomUUID().toString()+"_"
        return object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int) = target.getSharedPreferences(prefix+name,mode)
            override fun getFilesDir() = File(target.cacheDir,prefix+"files").apply {mkdirs()}
            override fun getCacheDir() = File(target.cacheDir,prefix+"cache").apply {mkdirs()}
        }.apply {getSharedPreferences("settings",Context.MODE_PRIVATE).edit().putString("base_url","http://127.0.0.1:8099").commit()}
    }
    private fun response(id: Int=1, server: String="a".repeat(32), name: String="reader", role: String="user") = JSONObject()
        .put("access_token","private-session-$id").put("server_id",server)
        .put("user",JSONObject().put("id",id).put("username",name).put("role",role))

    @Test fun sessionIsEncryptedAndBoundToServiceAddress() {
        val context=sandbox();val account=AccountStore(context)
        account.save(response(),"http://127.0.0.1:8099")
        assertEquals("private-session-1",account.token())
        assertFalse(context.getSharedPreferences("account",Context.MODE_PRIVATE).getString("session","")!!.contains("private-session"))
        context.getSharedPreferences("settings",Context.MODE_PRIVATE).edit().putString("base_url","http://another-server:8080").commit()
        assertEquals("",account.token())
        account.clear();assertEquals(0,account.id())
    }

    @Test fun accountAndServerHistoriesAndPhotosAreIsolated() {
        val context=sandbox();val account=AccountStore(context)
        account.save(response(),"http://127.0.0.1:8099")
        account.history().edit().putString("history","first-account-private-history").commit()
        val first=File(account.photoFolder().apply {mkdirs()},"photo.jpg").apply {writeText("test fixture")}
        assertNotNull(QuestionImages.resolve(context,first.path))
        account.save(response(2),"http://127.0.0.1:8099")
        assertNull(account.history().getString("history",null))
        assertNull(QuestionImages.resolve(context,first.path))
        account.save(response(server="b".repeat(32)),"http://127.0.0.1:8099")
        assertNull(account.history().getString("history",null))
        assertNull(QuestionImages.resolve(context,first.path))
        account.save(response(),"http://127.0.0.1:8099")
        assertEquals("first-account-private-history",account.history().getString("history",null))
    }

    @Test fun legacyHistoryMigratesOnlyToInitialAdministrator() {
        val context=sandbox();val account=AccountStore(context)
        val source=File(File(context.filesDir,"photos").apply {mkdirs()},"legacy.jpg").apply {writeText("test fixture")}
        val old=JSONArray().put(JSONObject().put("full","legacy-question").put("photo",source.path))
        val settings=context.getSharedPreferences("settings",Context.MODE_PRIVATE)
        settings.edit().putString("history",old.toString()).commit()
        account.save(response(),"http://127.0.0.1:8099");account.migrateLegacyHistory()
        assertTrue(settings.contains("history"))
        assertNull(account.history().getString("history",null))
        account.save(response(name="admin",role="superadmin"),"http://127.0.0.1:8099");account.migrateLegacyHistory()
        assertFalse(settings.contains("history"))
        val saved=JSONArray(account.history().getString("history","[]")).getJSONObject(0)
        assertEquals("legacy-question",saved.getString("full"))
        assertNotNull(QuestionImages.resolve(context,saved.getString("photo")))
        assertFalse(source.exists())
    }

    @Test fun nativeClientCanLoginAndReadUserConfigFromQaBackend() {
        val value=AccountApi.request("http://127.0.0.1:8099","/v1/auth/login","POST",JSONObject().put("username","qaowner").put("password","TestAdminPass123!"))
        assertEquals("superadmin",value.getJSONObject("user").getString("role"))
        assertEquals(32,value.getString("server_id").length)
        val token=value.getString("access_token")
        val config=AccountApi.request("http://127.0.0.1:8099","/v1/me/model",token=token)
        assertFalse(config.has("api_key"))
        val subscription=AccountApi.request("http://127.0.0.1:8099","/v1/me/subscription",token=token)
        for(unit in listOf("requests","questions","tokens"))assertTrue(subscription.getJSONObject("balances").has(unit))
        val options=AccountApi.request("http://127.0.0.1:8099","/v1/auth/options")
        assertTrue(options.getBoolean("registration_review"))
        AccountApi.request("http://127.0.0.1:8099","/v1/auth/logout","POST",token=token)
        try {AccountApi.request("http://127.0.0.1:8099","/v1/me",token=token);fail("Logged-out token should be rejected")}
        catch(error: ApiException) {assertEquals(401,error.status)}
    }
}
