package com.autobot.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class NetworkFrameGate {
    private var previous=0L
    fun reset(){previous=0}
    fun accept(id:String,ageMs:Long,transferMs:Long):Boolean {
        val number=id.toLongOrNull()?:return false
        if(number<=previous || ageMs !in 0..2500 || transferMs !in 0..2500 || ageMs+transferMs>2500)return false
        previous=number;return true
    }
}

/** Account-authenticated latest-frame polling; one OCR consumer, no frame queue. */
internal class NetworkFrameClient(
    private val base:String,private val token:String,
    private val onFrame:(Bitmap,()->Unit)->Unit,
    private val onUnavailable:(Int,String)->Unit
):Closeable {
    private val handler=Handler(Looper.getMainLooper())
    @Volatile private var closed=false
    @Volatile private var call:Call?=null
    private val gate=NetworkFrameGate()
    private val client=OkHttpClient.Builder().connectTimeout(4,TimeUnit.SECONDS)
        .readTimeout(3,TimeUnit.SECONDS).callTimeout(5,TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    private val worker=Thread({loop()},"network-camera").apply{isDaemon=true}
    fun start(){worker.start()}
    private fun loop(){
        var failed=false
        var lastNew=SystemClock.elapsedRealtime()
        while(!closed){
            val started=SystemClock.elapsedRealtime()
            try{
                val request=Request.Builder().url(AccountApi.server(base)+"/v1/network-camera/frame")
                    .header("Authorization","Bearer $token").header("Cache-Control","no-cache").build()
                val active=client.newCall(request);call=active
                active.execute().use{response->
                    if(!response.isSuccessful)throw ApiException(response.code,when(response.code){
                        401->"登录已失效，请重新登录";409->"请先打开接流设置获取推流地址";else->"等待相机推流；请检查 DJI Mimo、网络与电脑接流服务"})
                    val body=response.body?:error("相机没有返回画面")
                    require(body.contentLength()<=2*1024*1024){"相机画面过大"}
                    val bytes=body.byteStream().use{it.readBytesBounded(2*1024*1024)}
                    val age=response.header("X-Camera-Age-Ms")?.toLongOrNull()?:error("相机缺少画面时间信息")
                    val fresh=gate.accept(response.header("X-Camera-Frame").orEmpty(),age,SystemClock.elapsedRealtime()-started)
                    if(fresh){
                        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                        require(bounds.outWidth>0&&bounds.outHeight>0&&bounds.outWidth.toLong()*bounds.outHeight<=12_000_000){"相机画面尺寸无效"}
                        val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size)?:error("无法读取相机画面")
                        val consumed=CountDownLatch(1)
                        handler.post{
                            if(closed){bitmap.recycle();consumed.countDown()}
                            else onFrame(bitmap){consumed.countDown()}
                        }
                        lastNew=SystemClock.elapsedRealtime();failed=false
                        while(!closed&&!consumed.await(250,TimeUnit.MILLISECONDS)){}
                    }else if(SystemClock.elapsedRealtime()-lastNew>2500){
                        if(!failed){failed=true;handler.post{if(!closed)onUnavailable(503,"相机画面已停止更新，正在重连")}}
                    }
                }
            }catch(e:InterruptedException){break}
            catch(e:Exception){
                val code=(e as? ApiException)?.status?:503
                if(!failed||code==401){failed=true;gate.reset();handler.post{if(!closed)onUnavailable(code,e.message?:"网络摄像头连接失败，正在重连")}}
                if(code==401)break
            }finally{call=null}
            try{Thread.sleep((if(failed)1500L else 333L).minus(SystemClock.elapsedRealtime()-started).coerceAtLeast(30))}
            catch(e:InterruptedException){break}
        }
    }
    override fun close(){closed=true;call?.cancel();worker.interrupt();client.connectionPool.evictAll()}
}

private fun java.io.InputStream.readBytesBounded(limit:Int):ByteArray {
    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(16384)
    while(true){val count=read(buffer);if(count<0)break;require(output.size()+count<=limit){"相机画面过大"};output.write(buffer,0,count)}
    return output.toByteArray()
}
