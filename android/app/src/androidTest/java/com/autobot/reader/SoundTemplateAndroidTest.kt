package com.autobot.reader

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SoundTemplateAndroidTest {
    @Test fun templatesAreIsolatedByAccountAndDeletionPreservesHistory(){
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val prefix="sound_test_${UUID.randomUUID()}_";val names=mutableSetOf<String>()
        val context=object:ContextWrapper(target){override fun getSharedPreferences(name:String,mode:Int)=target.getSharedPreferences(prefix+name,mode).also{names.add(prefix+name)}}
        val account=AccountStore(context)
        fun login(id:Int){account.save(JSONObject().put("access_token","fixture-sound-session").put("server_id","a".repeat(32)).put("user",JSONObject().put("id",id)),"http://127.0.0.1:8080")}
        val template=SoundTemplate(List(8){FloatArray(SoundFeatures.BANDS){b->if(b==0)1f else 0f}},180,1234)
        try {
            login(1);val first=SoundTemplateStore(context);first.save(template)
            assertEquals(180,first.load()!!.durationMs)
            repeat(4){first.save(template.copy(durationMs=180+it,recordedAt=1235L+it),append=true)}
            assertEquals(3,first.loadAll().size)
            first.save(template)
            account.history().edit().putString("history","[]").commit()
            login(2);val second=SoundTemplateStore(context);assertNull(second.load());assertNull(first.load())
            assertTrue(runCatching{first.save(template)}.isFailure)
            second.save(template.copy(durationMs=240));assertEquals(240,second.load()!!.durationMs)
            login(1);assertEquals(180,first.load()!!.durationMs);first.delete();assertNull(first.load());assertEquals("[]",account.history().getString("history",null))
            account.history().edit().putString("sound_template","broken-json").commit();assertNull(first.load())
        } finally {names.forEach{target.deleteSharedPreferences(it)}}
    }
}
