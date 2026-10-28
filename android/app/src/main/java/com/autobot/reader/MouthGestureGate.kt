package com.autobot.reader

/** A fresh closed -> held-open transition is required for each shot. No queued gestures. */
internal class MouthGestureGate(private val holdMs:Long=600,private val threshold:Float=.22f,private val cooldownMs:Long=2500){
    data class State(val fire:Boolean=false,val progress:Float=0f,val message:String)
    private var closedSince:Long?=null
    private var openSince:Long?=null
    private var armed=false
    private var lastSample:Long?=null
    private var lastFire:Long?=null
    fun reset(){closedSince=null;openSince=null;armed=false;lastSample=null}
    fun observe(now:Long,aperture:Float?,eligible:Boolean=true):State {
        val previous=lastSample
        if(previous!=null&&(now<previous||now-previous>400))reset()
        lastSample=now
        if(!eligible||aperture==null||!aperture.isFinite()||aperture<0){reset();return State(message=if(!eligible)"当前任务处理中，闭嘴后再试" else "请正对前置镜头，让嘴部完整入镜")}
        if(lastFire?.let{now-it<cooldownMs}==true){closedSince=null;openSince=null;armed=false;return State(message="拍照冷却中，请先闭嘴")}
        val closeThreshold=threshold*.55f
        if(aperture<=closeThreshold){
            openSince=null
            if(closedSince==null)closedSince=now
            if(now-closedSince!!>=250)armed=true
            return State(message=if(armed)"已就绪，张嘴保持即可拍照" else "请先闭嘴，正在确认")
        }
        closedSince=null
        if(!armed){openSince=null;return State(message="请先闭嘴，再张嘴拍照")}
        if(aperture<threshold){openSince=null;return State(message="请张大嘴并保持")}
        if(openSince==null)openSince=now
        val elapsed=now-openSince!!
        if(elapsed>=holdMs){lastFire=now;armed=false;openSince=null;return State(fire=true,progress=1f,message="已触发拍照，请闭嘴复位")}
        return State(progress=elapsed.toFloat()/holdMs,message="保持张嘴 ${(elapsed*100/holdMs).coerceIn(0,99)}%")
    }
}

internal data class MouthPoint(val x:Float,val y:Float)
internal object MouthAperture {
    fun measure(upper:List<MouthPoint>,lower:List<MouthPoint>):Float? {
        if(upper.size<5||lower.size<5||(upper+lower).any{!it.x.isFinite()||!it.y.isFinite()})return null
        fun center(points:List<MouthPoint>):MouthPoint {
            val middle=points.size/2;val sample=points.subList(middle-1,middle+2)
            return MouthPoint(sample.map{it.x}.average().toFloat(),sample.map{it.y}.average().toFloat())
        }
        fun distance(a:MouthPoint,b:MouthPoint)=kotlin.math.hypot(a.x-b.x,a.y-b.y)
        val width=maxOf(distance(upper.first(),upper.last()),distance(lower.first(),lower.last()))
        if(width<20)return null
        return (distance(center(upper),center(lower))/width).takeIf{it<=1.5f}
    }
}
