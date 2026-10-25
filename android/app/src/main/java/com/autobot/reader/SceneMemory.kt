package com.autobot.reader

/** Small local-only OCR memory; it never waits for a model or imposes a cooldown on new scenes. */
internal class SceneMemory {
    private data class Text(val plain:String,val grams:Set<String>,val numbers:List<String>,val labels:Set<String>,val operators:String,val constraints:List<String>)
    private val recent=ArrayDeque<MutableList<Text>>()
    private var candidate:Text?=null
    private var confirmations=0
    private var lastGoodAt=0L
    private var missedFrames=0
    var antiShake=false
    var sensitivity=1

    private fun text(raw:String):Text {
        val corrected=raw.replace('．','.').replace(Regex("(?<=\\d)[oO]|[oO](?=\\d)"),"0").replace(Regex("(?<=\\d)[lI]|[lI](?=\\d)"),"1")
        val labels=Regex("(?m)^\\s*(?:第\\s*)?(\\d{1,3})(?:[.．](?!\\d)|[、题])").findAll(corrected).map {it.groupValues[1]}.toSet()
        val body=corrected.replace(Regex("(?m)^\\s*(?:第\\s*)?\\d{1,3}(?:[.．](?!\\d)|[、题])\\s*"),"")
        val plain=body.lowercase().replace(Regex("[\\s，。；：！？、（）【】\\p{Punct}]+"),"")
        val numbers=Regex("\\d+(?:\\.\\d+)?").findAll(body).map {it.value}.toList()
        val operators=body.filter {it in "+-−×÷*/=<>≤≥≠"}.replace('−','-')
        val constraints=Regex("不正确|不属于|不符合|不包括|不成立|不可能|错误|不能|至少|至多|最大|最小|\\bincorrect\\b|\\bexcept\\b|\\bleast\\b|\\bmost\\b").findAll(body.lowercase()).map{it.value}.sorted().toList()
        return Text(plain,plain.windowed(2).toSet(),numbers,labels,operators,constraints)
    }
    private fun wordSimilarity(a:Text,b:Text):Double =
        2.0*a.grams.intersect(b.grams).size/(a.grams.size+b.grams.size).coerceAtLeast(1)

    private fun matches(a:Text,b:Text,confirmation:Boolean=false):Boolean {
        if(!confirmation && a.constraints!=b.constraints && a.plain.length>=b.plain.length*.85)return false
        // Relax digits for the first dense page, or a page whose words are
        // clearly unlike every recent scene. Similar templates remain strict.
        val firstDensePage = confirmation && minOf(a.plain.length,b.plain.length)>=200 &&
            (recent.isEmpty() || recent.none { aliases -> aliases.any {
                wordSimilarity(a,it)>=.65 || wordSimilarity(b,it)>=.65
            } })
        // Compare the question number before stripping it from the body. Equal
        // wording with a new question number must still be eligible for capture.
        if(!confirmation && a.labels.isNotEmpty() && b.labels.isNotEmpty() &&
            (a.labels-b.labels).isNotEmpty()) return false
        val sameNumbers = if(confirmation && minOf(a.plain.length,b.plain.length)>=200)
            a.numbers.sorted()==b.numbers.sorted() else a.numbers==b.numbers
        if(confirmation&&(!sameNumbers||a.operators!=b.operators)&&!firstDensePage)return false
        if(a.operators!=b.operators&&a.numbers==b.numbers&&!confirmation)return false
        if(a.plain==b.plain)return true
        if(a.plain.length<5||b.plain.length<5)return false
        val shared=a.grams.intersect(b.grams).size
        val similarity=2.0*shared/(a.grams.size+b.grams.size).coerceAtLeast(1)
        val containment=shared.toDouble()/minOf(a.grams.size,b.grams.size).coerceAtLeast(1)
        if(firstDensePage && (!sameNumbers||a.operators!=b.operators)) {
            // The words already distinguish this page from recent solves.
            // OCR gates a photo; it is not the submitted question.
            return similarity>=.70
        }
        val aNumbers=a.numbers.toSet();val bNumbers=b.numbers.toSet()
        val changedValues=(aNumbers-bNumbers).isNotEmpty()&&(bNumbers-aNumbers).isNotEmpty()
        if(changedValues&&!confirmation)return false // Same wording with new numerical conditions is a new question.
        if(!confirmation && (aNumbers-bNumbers).isNotEmpty() && a.plain.length>=b.plain.length*.9)return false
        val newLabels=a.labels.isNotEmpty()&&b.labels.isNotEmpty()&&(a.labels-b.labels).isNotEmpty()
        if(newLabels&&a.labels.intersect(b.labels).isNotEmpty()&&!confirmation)return false
        if(newLabels&&similarity<.91&&!confirmation)return false
        // A shared template is not sufficient proof of a duplicate. Only allow
        // partial-view matching when the observed view really is smaller.
        return if(confirmation) similarity>=.70 || containment>=.88&&similarity>=.42
        else similarity>=.90 || a.plain.length < b.plain.length*.85 &&
            shared.toDouble()/a.grams.size.coerceAtLeast(1)>=.94 && similarity>=.42
    }
    fun clear(){recent.clear();resetCandidate()}
    fun resetCandidate(){candidate=null;confirmations=0;lastGoodAt=0;missedFrames=0}
    fun holdingCandidate(nowMs:Long=System.nanoTime()/1_000_000)=antiShake&&candidate!=null&&nowMs-lastGoodAt<=2500
    fun sameView(first:String,second:String):Boolean {
        val a=text(first);val b=text(second)
        if(minOf(a.plain.length,b.plain.length)<5)return false
        if(a.labels.isNotEmpty() && b.labels.isNotEmpty() && a.labels!=b.labels)return false
        if(a.constraints!=b.constraints)return false
        if(maxOf(a.plain.length,b.plain.length)<200 && (a.numbers!=b.numbers||a.operators!=b.operators))return false
        return minOf(a.plain.length,b.plain.length)>=maxOf(a.plain.length,b.plain.length)*.6 && wordSimilarity(a,b)>=.65
    }
    fun known(raw:String):Boolean {
        val value=text(raw)
        return value.plain.length>=5&&recent.any {aliases->aliases.any {matches(value,it)}}
    }
    fun observe(raw:String,nowMs:Long=System.nanoTime()/1_000_000):Boolean {
        val value=text(raw)
        if(antiShake && candidate!=null && nowMs-lastGoodAt>2500)resetCandidate()
        val previous=candidate
        val partialDropout=antiShake && previous!=null && value.plain.length<previous.plain.length*.65 &&
            value.grams.isNotEmpty() && value.grams.intersect(previous.grams).size.toDouble()/value.grams.size>=.88
        if(value.plain.length<5 || partialDropout) {
            missedFrames++
            if(!antiShake || missedFrames>1 || nowMs-lastGoodAt>2500)resetCandidate()
            return false
        }
        if(recent.any {aliases->aliases.any {matches(value,it)}}){resetCandidate();return false}
        if(candidate?.let {matches(value,it,true)}==true)confirmations++ else {candidate=value;confirmations=1}
        lastGoodAt=nowMs;missedFrames=0
        val needed=when {sensitivity==0->4;recent.isNotEmpty()->2;sensitivity==2->2;else->3}
        return confirmations>=needed
    }
    fun remember(raw:String) {
        val value=text(raw);if(value.plain.length<5)return
        val group=recent.firstOrNull {aliases->aliases.any {matches(value,it)}}
        if(group!=null){if(group.none {it.plain==value.plain}&&group.size<4)group.add(value)}
        else {recent.addFirst(mutableListOf(value));while(recent.size>16)recent.removeLast()}
        if(candidate?.let {matches(it,value)}!=false)resetCandidate()
    }
}
