package com.autobot.reader

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal data class HistoryEntry(
    val id:String,val time:Long,val brief:String,val full:String,val answer:String,
    val solution:String,val uncertainty:String,val photo:String,val fast:Boolean,val favorite:Boolean
){
    val searchText=(brief+"\n"+full+"\n"+answer).lowercase()
    fun question()=SolveResult(true,brief,full,answer,solution,uncertainty,photo,fast)
}
internal data class HistorySnapshot(val entries:List<HistoryEntry>,val favorites:Int)

/** Atomic history/favorite updates share a lock with saving and photo cleanup. */
internal class HistoryRepository(
    private val prefs:SharedPreferences,private val photos:File,private val owner:()->Boolean={true}
){
    fun isCurrent()=owner()
    private fun checkOwner(){check(owner()){ "账号已变化，请重新打开识题记录" }}
    private fun read(key:String)=JSONArray(prefs.getString(key,"[]"))
    private fun normalize(items:JSONArray):Boolean {
        var changed=false
        for(i in 0 until items.length()){
            val item=items.getJSONObject(i)
            if(item.optString("id").isBlank()){item.put("id",UUID.randomUUID().toString());changed=true}
        }
        return changed
    }
    private fun arrays():Pair<JSONArray,JSONArray>{
        checkOwner()
        val history=read("history");val favorites=read("favorites")
        val changed=normalize(history) or normalize(favorites)
        if(changed)write(history,favorites)
        return history to favorites
    }
    private fun write(history:JSONArray,favorites:JSONArray){
        checkOwner()
        check(prefs.edit().putString("history",history.toString()).putString("favorites",favorites.toString()).commit()){"记录保存失败，请重试"}
    }
    private fun entry(item:JSONObject,favorite:Boolean)=HistoryEntry(
        item.getString("id"),item.optLong("time"),item.optString("brief").ifBlank{item.optString("full").take(70)},
        item.optString("full"),item.optString("answer"),item.optString("solution"),item.optString("uncertainty"),item.optString("photo"),item.optBoolean("fast"),favorite)
    private fun snapshot(history:JSONArray,favorites:JSONArray):HistorySnapshot{
        val starred=(0 until favorites.length()).associate{favorites.getJSONObject(it).getString("id") to favorites.getJSONObject(it)}
        val items=linkedMapOf<String,JSONObject>()
        for(i in 0 until history.length()){val item=history.getJSONObject(i);items[item.getString("id")]=item}
        starred.forEach{(id,item)->if(id !in items)items[id]=item}
        return HistorySnapshot(items.values.map{entry(it,it.getString("id") in starred)}.sortedByDescending{it.time},starred.size)
    }
    fun load():HistorySnapshot=synchronized(lock){val(h,f)=arrays();snapshot(h,f)}
    fun favorite(id:String,selected:Boolean):HistorySnapshot=synchronized(lock){
        val(history,favorites)=arrays()
        val item=(0 until history.length()).map{history.getJSONObject(it)}.firstOrNull{it.optString("id")==id}
            ?:(0 until favorites.length()).map{favorites.getJSONObject(it)}.firstOrNull{it.optString("id")==id}
            ?:error("题目已不存在，请刷新记录")
        val updated=JSONArray()
        for(i in 0 until favorites.length())if(favorites.getJSONObject(i).optString("id")!=id)updated.put(favorites.getJSONObject(i))
        if(selected)updated.put(JSONObject(item.toString()))
        write(history,updated);cleanup(history,updated)
        snapshot(history,updated)
    }
    fun append(fresh:JSONArray)=synchronized(lock){
        val(history,favorites)=arrays();normalize(fresh)
        val merged=JSONArray();val seen=mutableSetOf<String>()
        for(source in listOf(fresh,history))for(i in 0 until source.length()){
            val item=source.getJSONObject(i)
            if(merged.length()<50&&seen.add(item.getString("id")))merged.put(item)
        }
        write(merged,favorites);cleanup(merged,favorites)
    }
    fun updateDetails(original:SolveResult,detail:SolveResult)=synchronized(lock){
        val(history,favorites)=arrays()
        for(items in listOf(history,favorites))for(i in 0 until items.length()){
            val item=items.getJSONObject(i)
            if(item.optString("full")==original.full&&item.optString("answer")==original.answer)
                item.put("full",detail.full).put("brief",detail.brief).put("answer",detail.answer).put("solution",detail.solution).put("uncertainty",detail.uncertainty).put("fast",false)
        }
        write(history,favorites)
    }
    fun clearHistory():HistorySnapshot=synchronized(lock){
        val(_,favorites)=arrays();val empty=JSONArray();write(empty,favorites);cleanup(empty,favorites);snapshot(empty,favorites)
    }
    private fun cleanup(history:JSONArray,favorites:JSONArray){
        val referenced=mutableSetOf<String>()
        for(items in listOf(history,favorites))for(i in 0 until items.length())referenced.add(items.getJSONObject(i).optString("photo"))
        photos.listFiles()?.filter{it.isFile&&it.absolutePath !in referenced}?.forEach{it.delete()}
    }
    companion object{
        private val lock=Any()
        fun <T> withStorageLock(action:()->T):T=synchronized(lock){action()}
        fun account(context:Context):HistoryRepository{
            val app=context.applicationContext;val account=AccountStore(app);val namespace=account.history()
            return HistoryRepository(namespace,account.photoFolder()){AccountStore(app).id()>0&&AccountStore(app).history()===namespace}
        }
    }
}
