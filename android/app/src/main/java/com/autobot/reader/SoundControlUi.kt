package com.autobot.reader

import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout

/** Enrollment and rehearsal own the microphone only while this visible panel is in use. */
internal class SoundControlUi(private val activity:MainActivity,private val engine:ReaderEngine,private val reading:(Boolean)->Unit):AutoCloseable {
    private val handler=Handler(Looper.getMainLooper())
    private val sheet=SettingsSheet(activity,"声音录入与试用","录入一个 0.2—2 秒、容易重复的独特短声音。避免背景音乐和普通单次杂声。所有处理都在手机本机完成。")
    private val store=SoundTemplateStore(activity)
    private var capture:SoundCapture?=null
    private var monitor:SoundMonitor?=null
    private var generation=0
    private var closed=false
    private var candidate:SoundTemplate?=null
    private val card=sheet.card("我的触发声音","录入时前后保持安静，中间发出一次触发声。首次使用“保存此声音”；再次录入后可保存为补充样本。只保存匹配特征，不保存原始录音。")
    private val status=sheet.label("",14f,AppPalette.primaryStrong).apply{setPadding(0,0,0,sheet.dp(12))}
    private val saved=sheet.label("",12f,AppPalette.muted)
    private val kind=android.widget.Spinner(activity).apply{
        adapter=android.widget.ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,listOf("通用短声音 / 短口令","咳嗽声（含相近清嗓）"))
        setSelection(if(store.load()?.kind=="cough")1 else 0)
    }
    private val save:android.widget.Button=sheet.button("保存此声音",true){
        val value=candidate?:return@button
        stopAudio()
        runCatching{store.save(value)}.onSuccess{candidate=null;save.isEnabled=false;refresh();status.text="声音已保存，可试用匹配；返回偏好页后开启开关并保存"}.onFailure{status.text=it.message}
    }
    private fun refresh(){val templates=store.loadAll();val template=templates.lastOrNull();saved.text=if(template==null)"当前账号尚未录入触发声音" else "已保存 ${templates.size} 份${if(template.kind=="cough")"咳嗽" else "声音"}样本 · ${String.format(java.util.Locale.US,"%.1f",template.durationMs/1000f)} 秒"+if(template.embedding==null)"（旧样本，请重录以使用模型匹配）" else " · 本地模型匹配"}
    fun stopAudio(){generation++;capture?.close();capture=null;monitor?.close();monitor=null;if(!closed)status.text="检测已停止；可以重新录入或试用"}
    fun show():Dialog {
        engine.stopScanning();card.addView(saved);card.addView(status);card.addView(sheet.label("声音类型",12f,AppPalette.muted));card.addView(kind);refresh()
        fun add(value:String,action:()->Unit){card.addView(sheet.button(value,action=action),LinearLayout.LayoutParams(-1,sheet.dp(48)).apply{topMargin=sheet.dp(10)})}
        add("开始录入（约 3 秒）"){
            stopAudio();candidate=null;save.isEnabled=false;val ticket=generation
            val selectedKind=if(kind.selectedItemPosition==1)"cough" else "generic"
            status.text="请准备，1 秒后开始录入…"
            handler.postDelayed({if(!closed&&ticket==generation){
                val data=ArrayList<Short>();var done=false;var lastSecond=-1
                val session=SoundCapture({chunk->
                    if(!done){data.addAll(chunk.asList());val second=data.size/SoundFeatures.RATE
                        if(second!=lastSecond){lastSecond=second;handler.post{if(!closed&&ticket==generation)status.text="正在录入，请发出一次短声音… ${3-second} 秒"}}
                        if(data.size>=SoundFeatures.RATE*3){done=true;capture?.close()
                            val result=runCatching{SoundModelExtractor(activity).use{it.extract(data.toShortArray(),selectedKind)}};data.clear()
                            handler.post{if(!closed&&ticket==generation){capture=null;result.onSuccess{candidate=it;save.isEnabled=true;status.text="录入成功，可保存或先试用当前样本"}.onFailure{status.text=it.message?:"录入失败，请重试"}}}
                        }
                    }
                },{message->handler.post{if(!closed&&ticket==generation){capture=null;status.text=message}}})
                capture=session;session.start()
            }},1000)
        }
        card.addView(save,LinearLayout.LayoutParams(-1,sheet.dp(48)).apply{topMargin=sheet.dp(10)});save.isEnabled=false
        add("保存为补充样本（最多 3 份）"){
            val value=candidate
            if(value==null){status.text="请先重新录入同一个声音，再保存为补充样本";return@add}
            stopAudio();runCatching{store.save(value,append=true)}.onSuccess{candidate=null;save.isEnabled=false;refresh();status.text="补充样本已保存；再次录入同类声音可继续补充"}.onFailure{status.text=it.message}
        }
        add("试用匹配（不拍照、不调用模型）"){
            stopAudio();val template=candidate?:store.load()
            if(template==null){status.text="请先录入声音";return@add}
            val ticket=generation;var hits=0
            status.text="先保持安静约 1 秒，再重复触发声音；仅试用，不拍照"
            val threshold=activity.getSharedPreferences("settings",0).getInt("sound_threshold",80)/100f
            val templates=if(candidate!=null)listOf(template)+store.loadAll().filter{it.kind==template.kind} else store.loadAll()
            monitor=SoundMonitor(activity,templates,threshold,{!closed},{score->handler.post{if(!closed&&ticket==generation){hits++;status.text="匹配成功 $hits 次 · 模型综合匹配 ${(score*100).toInt()}%（仅试用）"}}},
                {message->handler.post{if(!closed&&ticket==generation)status.text=if(hits>0)"已匹配 $hits 次 · $message" else message}}).also{it.start()}
        }
        add("停止录入 / 试用"){stopAudio()}
        add("删除已保存的声音"){
            AppDialog.Builder(activity).setTitle("删除触发声音？").setMessage("只删除当前账号本机保存的匹配特征，同时关闭声音控制。")
                .setNegativeButton("取消",null).setPositiveButton("删除"){_,_->stopAudio();runCatching{store.delete()}.onSuccess{
                    candidate=null;save.isEnabled=false;activity.getSharedPreferences("settings",0).edit().putBoolean("sound_capture",false).apply();refresh();status.text="声音已删除，声音控制已关闭"
                }.onFailure{status.text=it.message}}.show()
        }
        sheet.footer("完成"){close()}
        sheet.dialog.setOnDismissListener{if(!closed){closed=true;stopAudio();handler.removeCallbacksAndMessages(null);candidate=null;reading(false)}}
        reading(true);sheet.show();return sheet.dialog
    }
    override fun close(){sheet.dialog.dismiss()}
}
