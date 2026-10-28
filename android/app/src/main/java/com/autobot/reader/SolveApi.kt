package com.autobot.reader

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

data class SolveResult(val readable:Boolean,val brief:String,val full:String,val answer:String,val solution:String,val uncertainty:String,val photoPath:String="",val fast:Boolean=false)
data class SolveBatch(val questions:List<SolveResult>,val uncertainty:String,val interrupted:Boolean=false,val firstAnswerMs:Long=0,val elapsedMs:Long=0)
class PartialSolveException(val partial:SolveBatch,message:String):IOException(message)

/** Each generation owns its own call; an old cancel cannot affect a newer generation. */
class SolveRequest {
    val id=UUID.randomUUID().toString().replace("-","")
    private val stopped=AtomicBoolean(false)
    val cancelled get()=stopped.get()
    private var call:Call?=null
    private var base=""
    private var token=""
    @Synchronized internal fun bind(value:Call,address:String,secret:String) {
        if(cancelled){value.cancel();throw IOException("请求已取消")}
        call=value;base=address;token=secret
    }
    @Synchronized internal fun release(value:Call){if(call===value){call=null;base="";token=""}}
    fun cancel() {
        if(!stopped.compareAndSet(false,true))return
        val state=synchronized(this){Triple(call,base,token)}
        state.first?.cancel()
        if(state.second.isNotBlank())SolveApi.cancelRemote(state.second,state.third,id)
    }
}

object SolveApi {
    private val channel=UUID.randomUUID().toString().replace("-","")
    private val client=OkHttpClient.Builder().connectTimeout(12,TimeUnit.SECONDS).readTimeout(150,TimeUnit.SECONDS)
        .writeTimeout(30,TimeUnit.SECONDS).callTimeout(165,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    private val cancelClient=client.newBuilder().callTimeout(5,TimeUnit.SECONDS).build()
    @Volatile private var active:SolveRequest?=null
    fun cancelActive(){active?.cancel()}
    internal fun cancelRemote(base:String,token:String,id:String) {
        val body=JSONObject().put("request_id",id).toString().toRequestBody("application/json".toMediaType())
        val request=Request.Builder().url(base.trimEnd('/')+"/v1/solve-cancel").header("Authorization","Bearer $token").post(body).build()
        cancelClient.newCall(request).enqueue(object:Callback{
            override fun onFailure(call:Call,e:IOException){}
            override fun onResponse(call:Call,response:Response){response.close()}
        })
    }
    private fun question(item:JSONObject,fast:Boolean)=SolveResult(true,item.getString("question_brief"),item.getString("question_full"),item.getString("answer_brief"),item.getString("solution"),item.getString("uncertainty"),fast=fast)
    private fun body(photo:File,target:String?=null):RequestBody {
        val form=MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("image","question.jpg",photo.asRequestBody("image/jpeg".toMediaType()))
        if(target!=null)form.addFormDataPart("question",target)
        return form.build()
    }
    private fun call(base:String,token:String,path:String,body:RequestBody,request:SolveRequest):Call {
        require(base.startsWith("http://")||base.startsWith("https://")){"请设置后端地址"}
        val call=client.newCall(Request.Builder().url(base.trimEnd('/')+path).header("Authorization","Bearer $token")
            .header("X-Solve-Id",request.id).header("X-Solve-Channel",channel).post(body).build())
        request.bind(call,base,token);active=request;return call
    }
    private fun checkResponse(response:Response) {
        if(!response.isSuccessful){val detail=runCatching {JSONObject(response.body?.string().orEmpty()).optString("detail")}.getOrDefault("");throw ApiException(response.code,detail.ifBlank {"服务错误 HTTP ${response.code}"})}
    }
    fun solveBatch(baseUrl:String,token:String,photo:File,fast:Boolean=false,streaming:Boolean=false,onQuestion:(SolveResult,Int)->Unit={_,_->},cancelled:()->Boolean={false},request:SolveRequest=SolveRequest()):SolveBatch {
        val start=android.os.SystemClock.elapsedRealtime()
        if(cancelled()||request.cancelled)throw IOException("请求已取消")
        val call=call(baseUrl,token,(if(streaming)"/v1/solve-stream" else "/v1/solve-batch")+"?mode="+(if(fast)"fast" else "full"),body(photo),request)
        try {
            call.execute().use {response->
                checkResponse(response)
                if(streaming){
                    val found=mutableListOf<SolveResult>();var first=0L
                    try {
                        response.body!!.charStream().buffered().use {reader->
                            var total=0
                            while(true){
                                if(cancelled()||request.cancelled)throw IOException("请求已取消")
                                val line=reader.readLine()?:break
                                total+=line.length;if(total>2*1024*1024)throw IOException("返回内容过大")
                                if(line.isBlank())continue
                                val event=JSONObject(line)
                                when(event.getString("type")){
                                    "question"->{check(found.size<30&&event.getInt("index")==found.size+1){"题目序号异常"};val q=question(event.getJSONObject("question"),fast);found.add(q);if(first==0L)first=android.os.SystemClock.elapsedRealtime()-start;if(!request.cancelled&&!cancelled())onQuestion(q,found.size)}
                                    "done"->{check(event.getInt("count")==found.size){"返回题数不一致"};if(request.cancelled||cancelled())throw IOException("请求已取消");return SolveBatch(found.toList(),event.optString("uncertainty"),firstAnswerMs=first,elapsedMs=android.os.SystemClock.elapsedRealtime()-start)}
                                    "error"->throw ApiException(event.optInt("code",502),event.optString("message","逐题返回中断"))
                                }
                            }
                        }
                        throw IOException("逐题连接提前结束")
                    }catch(error:Exception){if(found.isNotEmpty()&&!request.cancelled&&!cancelled())throw PartialSolveException(SolveBatch(found.toList(),"连接中断，仅保留已完成的题目",true,first),error.message?:"连接中断");throw error}
                }
                val json=JSONObject(response.body!!.string());if(request.cancelled||cancelled())throw IOException("请求已取消")
                val array=json.getJSONArray("questions");val questions=(0 until array.length()).map {question(array.getJSONObject(it),fast)}
                val elapsed=android.os.SystemClock.elapsedRealtime()-start
                return SolveBatch(questions,json.getString("uncertainty"),firstAnswerMs=if(questions.isNotEmpty())elapsed else 0,elapsedMs=elapsed)
            }
        }finally{request.release(call);if(active===request)active=null}
    }
    fun explain(baseUrl:String,token:String,photo:File,target:String,request:SolveRequest=SolveRequest()):SolveResult {
        val call=call(baseUrl,token,"/v1/solve-detail",body(photo,target),request)
        try {call.execute().use {response->checkResponse(response);val json=JSONObject(response.body!!.string());if(request.cancelled)throw IOException("请求已取消");if(!json.optBoolean("readable"))throw IOException(json.optString("uncertainty","无法确定目标题目"));return question(json,false)}}finally{request.release(call);if(active===request)active=null}
    }
    fun solveText(baseUrl:String,token:String,text:String,fast:Boolean,request:SolveRequest):SolveBatch {
        val start=android.os.SystemClock.elapsedRealtime()
        val payload=JSONObject().put("text",text).put("mode",if(fast)"fast" else "full").toString().toRequestBody("application/json".toMediaType())
        val call=call(baseUrl,token,"/v1/solve-text",payload,request)
        try{call.execute().use{response->
            if(response.code==404)throw IOException("后端尚未更新，请先部署支持语音文本解题的新版本")
            checkResponse(response);if(request.cancelled)throw IOException("请求已取消")
            val json=JSONObject(response.body!!.string());val values=json.getJSONArray("questions")
            check(values.length()<=30)
            val questions=(0 until values.length()).map{question(values.getJSONObject(it),fast)}
            val elapsed=android.os.SystemClock.elapsedRealtime()-start
            return SolveBatch(questions,json.optString("uncertainty"),firstAnswerMs=elapsed,elapsedMs=elapsed)
        }}finally{request.release(call);if(active===request)active=null}
    }
}
