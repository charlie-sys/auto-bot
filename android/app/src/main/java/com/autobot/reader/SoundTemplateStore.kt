package com.autobot.reader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Features, not original recordings, are stored under the current account namespace. */
internal class SoundTemplateStore(context:Context){
    private val account=AccountStore(context)
    private val prefs=account.history()
    private fun current()=account.id()>0&&account.history()===prefs
    fun load():SoundTemplate?=loadAll().firstOrNull()
    fun loadAll():List<SoundTemplate> =runCatching{
        check(current())
        val json=JSONObject(prefs.getString("sound_template","").orEmpty())
        val version=json.getInt("version");check(version in 1..2)
        if(version==1)listOf(parse(json))else {
            val templates=json.getJSONArray("templates");check(templates.length() in 1..3)
            (0 until templates.length()).map{parse(templates.getJSONObject(it))}
        }
    }.getOrDefault(emptyList())
    private fun parse(json:JSONObject):SoundTemplate {
        val rows=json.getJSONArray("frames");check(rows.length() in 5..310)
        val frames=(0 until rows.length()).map{i->val row=rows.getJSONArray(i);check(row.length()==SoundFeatures.BANDS)
            FloatArray(SoundFeatures.BANDS){b->row.getDouble(b).toFloat().also{check(it.isFinite()&&it in -1.01f..1.01f)}}}
        val duration=json.getInt("duration_ms");check(duration in 80..3100)
        fun vector(key:String,size:Int):FloatArray?{
            val values=json.optJSONArray(key)?:return null;check(values.length()==size)
            return FloatArray(size){values.getDouble(it).toFloat().also{v->check(v.isFinite()&&v in -1.01f..1.01f)}}
        }
        val kind=json.optString("kind","generic");check(kind in listOf("generic","cough"))
        return SoundTemplate(frames,duration,json.getLong("recorded_at"),vector("embedding",1024),vector("categories",521),kind)
    }
    fun save(value:SoundTemplate,append:Boolean=false){
        check(current()){"账号已变化，请重新录入"}
        val values=if(append)(loadAll().filter{it.kind==value.kind}.takeLast(2)+value) else listOf(value)
        val json=JSONObject().put("version",2).put("templates",JSONArray().apply{values.forEach{template->
            put(JSONObject().put("duration_ms",template.durationMs).put("recorded_at",template.recordedAt).put("kind",template.kind)
                .put("frames",JSONArray().apply{template.frames.forEach{row->put(JSONArray().apply{row.forEach{put(it.toDouble())}})}})
                .apply{template.embedding?.let{put("embedding",JSONArray(it.toList()))};template.categories?.let{put("categories",JSONArray(it.toList()))}})
        }})
        check(prefs.edit().putString("sound_template",json.toString()).commit()){"声音特征保存失败，请重试"}
    }
    fun delete(){check(current());check(prefs.edit().remove("sound_template").commit())}
}

internal class SoundMonitor(context:Context,private val templates:List<SoundTemplate>,private val threshold:Float,private val eligible:()->Boolean,private val match:(Float)->Unit,private val info:(String)->Unit):AutoCloseable {
    private val segmenter=SoundEventSegmenter()
    private val gate=SoundTriggerGate()
    @Volatile private var closed=false
    private val extractor=SoundModelExtractor(context)
    private val capture=SoundCapture({chunk->
        val allowed=eligible()
        val event=segmenter.feed(chunk,allowed)
        if(event!=null&&!closed){
            val candidate=runCatching{extractor.extract(event)}.getOrNull()
            val score=if(candidate==null)0f else templates.maxOfOrNull{SoundFeatures.similarity(it,candidate)}?:0f
            val cough=if(templates.any{it.kind=="cough"}&&candidate!=null)" · 咳嗽/清嗓证据 ${(maxOf(candidate.categories?.get(42)?:0f,candidate.categories?.get(43)?:0f)*100).toInt()}%" else ""
            info("模型综合匹配 ${(score*100).toInt()}% · 阈值 ${(threshold*100).toInt()}%$cough")
            if(!closed&&gate.accept(android.os.SystemClock.elapsedRealtime(),score,threshold,eligible()))match(score)
        }
    },{if(!closed)info(it)})
    fun start(){capture.start()}
    val listening get()=capture.isRunning
    override fun close(){closed=true;capture.close();extractor.close()}
}
