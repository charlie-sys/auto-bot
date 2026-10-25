package com.autobot.reader

import android.content.Intent
import android.os.SystemClock
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AppDialogAndroidTest {
    private lateinit var dialog:AppDialog
    @Test fun actionsMetricsAndValidation(){
        val test=InstrumentationRegistry.getInstrumentation()
        val context=test.targetContext
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        test.waitForIdleSync()
        val deadline=SystemClock.elapsedRealtime()+15_000
        while(test.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("本次解答").isNullOrEmpty()&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(120)
        lateinit var field:EditText
        var confirmed=0
        fun snapshot(name:String){
            test.waitForIdleSync()
            SystemClock.sleep(350)
            val title=if(name=="dialog-confirmation.png")"清空普通历史？" else "订阅额度"
            val limit=SystemClock.elapsedRealtime()+5_000
            while(test.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(title).isNullOrEmpty()&&SystemClock.elapsedRealtime()<limit)SystemClock.sleep(150)
            assertFalse("Expected app dialog must be foreground before screenshot",test.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(title).isNullOrEmpty())
            val bitmap=test.uiAutomation.takeScreenshot()!!
            try{File(context.getExternalFilesDir(null),"ui-previews").apply{mkdirs()}.resolve(name).outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}
        }
        try {
            test.runOnMainSync {
                val activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().first()
                dialog=AppDialog.Builder(activity).setTitle("清空普通历史？").setMessage("普通识题记录将被清空，收藏题目和它们的题图会保留。")
                    .setNegativeButton("取消",null).setPositiveButton("清空"){_,_->confirmed++}.show()
            }
            snapshot("dialog-confirmation.png")
            test.runOnMainSync{dialog.getButton(AppDialog.BUTTON_NEGATIVE).performClick();assertFalse(dialog.isShowing);assertEquals(0,confirmed)}
            test.runOnMainSync {
                val activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().first()
                dialog=AppDialog.Builder(activity).setTitle("订阅额度").setMessage("调用次数：120\n题目数量：500\nToken：100000\n\n演示数据 · 实际可用额度以账号查询结果为准。")
                    .setPositiveButton("知道了",null).show()
            }
            snapshot("dialog-subscription-demo.png")
            test.runOnMainSync{dialog.getButton(AppDialog.BUTTON_POSITIVE).performClick();assertFalse(dialog.isShowing)}
            test.runOnMainSync {
                val activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().first()
                field=EditText(activity)
                dialog=AppDialog.Builder(activity).setTitle("输入校验测试").setView(field).setNegativeButton("取消",null).setPositiveButton("提交"){_,_->confirmed++}.create()
                dialog.setOnShowListener{dialog.getButton(AppDialog.BUTTON_POSITIVE).setOnClickListener{
                    if(field.text.isBlank())field.error="请填写内容" else {confirmed++;dialog.dismiss()}
                }}
                dialog.show()
            }
            test.waitForIdleSync()
            test.runOnMainSync {
                dialog.getButton(AppDialog.BUTTON_POSITIVE).performClick()
                assertTrue(dialog.isShowing);assertEquals(0,confirmed)
                field.setText("fixture");dialog.getButton(AppDialog.BUTTON_POSITIVE).performClick()
                assertFalse(dialog.isShowing);assertEquals(1,confirmed)
            }
        }finally{test.runOnMainSync{if(::dialog.isInitialized)dialog.dismiss()}}
    }
}
