package com.autobot.reader

/** Prevent ambient non-question speech and recent repeats from silently consuming solves. */
internal class VoiceQuestionPolicy {
    private var last=""
    private var lastAt=0L
    fun inspect(text:String,now:Long,force:Boolean=false):String? {
        val normalized=text.replace(Regex("[\\s\\p{P}]+"),"").lowercase()
        val arithmetic=Regex("[0-9一二三四五六七八九十].*[+*/=×÷加减乘除-].*[0-9一二三四五六七八九十]").containsMatchIn(text)
        if(normalized.length<3&&!arithmetic)return "转写太短，请补充完整题目"
        if(normalized.length>4000)return "转写过长，请拆分题目"
        if(!force&&!arithmetic&&!listOf("问","多少","几","哪","是否","什么","为何","如何","怎么","求","计算","等于","加","减","乘","除","下列","选择","正确","错误","解释","介绍","简述","分析","说明","题","比较","判断","化简").any{normalized.contains(it)})return "没有检测到明确题意，可编辑后手动提交"
        if(!force&&normalized==last&&now-lastAt<30_000)return "刚刚已提交相同题目，本段不重复解答"
        last=normalized;lastAt=now;return null
    }
    fun reset(){last="";lastAt=0}
}

/** Segment timing is based on sample count, independent of UI or recognizer callback timing. */
internal class VoiceEndpoint(private val automatic:Boolean,private val silenceSamples:Int=32000){
    var active=false;private set
    private var total=0
    private var silence=0
    private var voice=0
    private var floor=.002
    private var recentVoice=0
    fun reset(manual:Boolean=false){active=manual;total=0;silence=0;voice=0;recentVoice=0}
    fun feed(rms:Double,count:Int,hasText:Boolean):String? {
        val loud=rms>maxOf(.007,floor*2.5)
        recentVoice=if(loud)48000 else (recentVoice-count).coerceAtLeast(0)
        if(!active){if(!loud)floor=(floor*.97+rms*.03).coerceIn(.001,.04);if(automatic&&hasText&&recentVoice>0){active=true;total=0;voice=1600}else return null}
        total+=count
        if(loud){voice+=count;silence=0}else silence+=count
        if(total>=16000*60){reset();return "limit"}
        if(voice==0&&total>=16000*8){reset();return "empty"}
        if(voice>=1600&&silence>=silenceSamples){reset();return if(hasText)"done" else "empty"}
        return null
    }
}
