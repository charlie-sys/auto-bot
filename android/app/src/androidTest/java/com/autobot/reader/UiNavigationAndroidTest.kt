package com.autobot.reader

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Verifies page navigation does not resume scanning while a user reads history. */
@RunWith(AndroidJUnit4::class)
class UiNavigationAndroidTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun node(text:String):AccessibilityNodeInfo?=instrumentation.uiAutomation.rootInActiveWindow
        ?.findAccessibilityNodeInfosByText(text)?.firstOrNull{it.text?.toString()==text&&it.isVisibleToUser}
    private fun await(text:String){
        val deadline=SystemClock.elapsedRealtime()+12_000
        while(SystemClock.elapsedRealtime()<deadline){if(node(text)!=null)return;SystemClock.sleep(120)}
        fail("Missing visible UI: $text")
    }
    private fun click(text:String){
        await(text);var item=node(text)
        while(item!=null&&!item.isClickable)item=item.parent
        assertTrue("Unable to click $text",item?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)
        instrumentation.waitForIdleSync()
    }
    private fun engine():ReaderEngine{
        var engine:ReaderEngine?=null
        instrumentation.runOnMainSync{
            val activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().first()
            engine=MainActivity::class.java.getDeclaredField("engine").apply{isAccessible=true}.get(activity) as ReaderEngine
        }
        return engine!!
    }
    private fun activity()=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().first()
    private fun views(root:android.view.View):List<android.view.View> = listOf(root)+if(root is android.view.ViewGroup)(0 until root.childCount).flatMap{views(root.getChildAt(it))}else emptyList()
    private fun preferences():SettingsSheet=MainActivity::class.java.getDeclaredField("preferencesSheet").apply{isAccessible=true}.get(activity()) as SettingsSheet
    private fun tapPreference(title:String){instrumentation.runOnMainSync{
        val target=views(preferences().dialog.window!!.decorView).filterIsInstance<android.widget.TextView>().first{it.text.toString()==title}
        assertTrue(target.performClick())
    };instrumentation.waitForIdleSync()}
    private fun snapshot(name:String){
        SystemClock.sleep(350)
        val bitmap=instrumentation.uiAutomation.takeScreenshot()?:error("Screenshot unavailable")
        val folder=File(instrumentation.targetContext.getExternalFilesDir(null),"ui-previews").apply{mkdirs()}
        try{File(folder,name).outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}
    }
    @Test fun tabsSearchAndNestedSettingsKeepReadingState(){
        val context=instrumentation.targetContext
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        await("识题助手");await("我的")
        assertFalse(engine().running)
        snapshot("app-home.png")
        click("历史");await("识题记录");assertTrue(engine().readingResults)
        val saved=HistoryRepository.account(context).load()
        await("${saved.entries.size} 道题目 · 收藏 ${saved.favorites} 道 · 每页 10 道")
        snapshot("app-history.png")
        val queue=java.util.ArrayDeque<AccessibilityNodeInfo>()
        instrumentation.uiAutomation.rootInActiveWindow?.let{queue.add(it)}
        var search:AccessibilityNodeInfo?=null
        while(queue.isNotEmpty()){
            val item=queue.removeFirst()
            if(item.className?.toString()=="android.widget.EditText"&&item.isVisibleToUser){search=item;break}
            for(i in 0 until item.childCount)item.getChild(i)?.let{queue.add(it)}
        }
        assertNotNull("History search field missing",search)
        if(search!=null){
            val argument=Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"ui-search-no-match-6724")}
            search.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,argument);await("没有找到相关题目")
            search.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"")})
        }
        click("我的");await("管理账号，让识题更顺手");assertTrue(engine().readingResults)
        snapshot("app-profile.png")
        click("使用偏好");await("答题速度")
        await("保存偏好");snapshot("app-preferences.png")
        val originalSettings=context.getSharedPreferences("settings",android.content.Context.MODE_PRIVATE).all
        instrumentation.runOnMainSync{
            val sheet=preferences()
            val switches=views(sheet.dialog.window!!.decorView).filterIsInstance<android.widget.Switch>()
            assertTrue(switches.size>=10)
            switches.forEach{control->val original=control.isChecked;control.performClick();assertEquals(!original,control.isChecked);assertTrue(sheet.dialog.isShowing);control.performClick()}
        }
        tapPreference("账号与个人模型  ›");await("个人资料")
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        await("保存偏好");assertTrue(engine().readingResults)
        tapPreference("连接设置  ›")
        tapPreference("局域网直连 / 更换后端");await("测试连接（不发送账号令牌）")
        snapshot("dialog-connection.png")
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        await("保存偏好");assertTrue(engine().readingResults)
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        await("管理账号，让识题更顺手");assertTrue(engine().readingResults)
        assertEquals(originalSettings,context.getSharedPreferences("settings",android.content.Context.MODE_PRIVATE).all)
        click("使用偏好");await("保存偏好");click("保存偏好")
        await("答题速度");await("保存偏好");assertTrue(engine().readingResults)
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        await("管理账号，让识题更顺手")
        click("账号、模型与订阅");await("个人资料");snapshot("app-account.png")
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        await("管理账号，让识题更顺手");assertTrue(engine().readingResults)
        click("识题");await("本次解答");assertFalse(engine().readingResults)
        assertFalse(engine().running)
        click("锁定");await("已锁定 · 长按 2 秒解锁")
        lateinit var unlock:android.widget.TextView
        var x=0f;var y=0f
        instrumentation.runOnMainSync{
            val ui=MainActivity::class.java.getDeclaredField("ui").apply{isAccessible=true}.get(activity()) as ReaderUi
            val shield=ReaderUi::class.java.getDeclaredField("touchShield").apply{isAccessible=true}.get(ui) as android.widget.FrameLayout
            assertEquals(android.graphics.Color.TRANSPARENT,(shield.background as android.graphics.drawable.ColorDrawable).color)
            assertEquals(1,shield.childCount)
            unlock=shield.getChildAt(0) as android.widget.TextView
            assertTrue("Unlock must stay in the bottom navigation strip",unlock.top>=shield.height-(72*context.resources.displayMetrics.density).toInt())
            val primary=ReaderUi::class.java.getDeclaredField("primary").apply{isAccessible=true}.get(ui) as android.view.View
            val location=IntArray(2);primary.getLocationOnScreen(location);x=location[0]+primary.width/2f;y=location[1]+primary.height/2f
        }
        fun touch(action:Int,time:Long){val event=android.view.MotionEvent.obtain(time,android.os.SystemClock.uptimeMillis(),action,x,y,0);try{instrumentation.uiAutomation.injectInputEvent(event,true)}finally{event.recycle()}}
        var down=android.os.SystemClock.uptimeMillis();touch(android.view.MotionEvent.ACTION_DOWN,down);touch(android.view.MotionEvent.ACTION_UP,down)
        assertFalse("Locked screen must block the start button",engine().running)
        snapshot("app-touch-locked.png")
        instrumentation.runOnMainSync{val location=IntArray(2);unlock.getLocationOnScreen(location);x=location[0]+unlock.width/2f;y=location[1]+unlock.height/2f}
        down=android.os.SystemClock.uptimeMillis();touch(android.view.MotionEvent.ACTION_DOWN,down);SystemClock.sleep(2200);touch(android.view.MotionEvent.ACTION_UP,down)
        await("本次解答");assertFalse(context.getSharedPreferences("settings",android.content.Context.MODE_PRIVATE).getBoolean("touch_locked",true))
    }

    @Test fun isolatedHistoryPagingAndFavorites(){
        val context=instrumentation.targetContext
        val name="history_ui_test_"+java.util.UUID.randomUUID()
        val prefs=context.getSharedPreferences(name,android.content.Context.MODE_PRIVATE)
        val repo=HistoryRepository(prefs,File(context.cacheDir,name))
        repo.append(org.json.JSONArray().apply{for(n in 1..23)put(org.json.JSONObject().put("time",n).put("brief","Fixture question $n").put("full","Fixture question $n").put("answer","A"))})
        var screen:HistoryScreen?=null
        var parent:android.widget.LinearLayout?=null
        try {
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            await("识题助手")
            instrumentation.runOnMainSync {
                val activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().first()
                parent=android.widget.LinearLayout(activity).apply{orientation=1}
                activity.setContentView(parent)
                screen=HistoryScreen(activity).apply{show(parent!!,repo){_,_->}}
            }
            await("1 / 3 页 · 共 23 题");await("Fixture question 23")
            click("下一页");await("2 / 3 页 · 共 23 题");await("Fixture question 13")
            var oldY=0
            instrumentation.runOnMainSync{
                val scroll=views(parent!!).filterIsInstance<android.widget.ScrollView>().single()
                val content=scroll.getChildAt(0) as android.view.ViewGroup
                scroll.scrollTo(0,content.getChildAt(3).top)
                oldY=scroll.scrollY;assertTrue(oldY>0)
                views(content.getChildAt(3)).filterIsInstance<android.widget.Button>().single().performClick()
            }
            await("★ 已收藏");SystemClock.sleep(250)
            instrumentation.runOnMainSync{assertEquals("Favorite must preserve scroll position",oldY,views(parent!!).filterIsInstance<android.widget.ScrollView>().single().scrollY)}
            await("2 / 3 页 · 共 23 题")
            click("★ 已收藏");await("我的收藏 (0)")
            click("下一页");await("3 / 3 页 · 共 23 题");await("Fixture question 3")
            click("☆ 收藏");await("★ 已收藏")
            click("我的收藏 (1)");await("1 / 1 页 · 共 1 题");await("Fixture question 3")
            click("★ 已收藏");await("还没有收藏题目");await("1 / 1 页 · 共 0 题")
            assertEquals(0,repo.load().favorites)
        } finally {
            instrumentation.runOnMainSync{
                screen?.close()
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()?.recreate()
            }
            context.deleteSharedPreferences(name)
            await("识题助手")
        }
    }
}
