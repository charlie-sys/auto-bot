package com.autobot.reader

/** Detail-off never reveals a numeric/free-text answer. Multiple-choice labels remain audible. */
internal object AnswerSpeech {
    fun text(answer:String,full:String,includeDetail:Boolean): String {
        val clean=MarkdownContent.speech(answer).trim()
        val label=Regex("^([A-H](?:[、,，/和及与\\s]*[A-H]){0,7})(?=$|[.．、:：\\s])").find(clean)?.groupValues?.get(1)
        if(!includeDetail)return if(label!=null) "答案是：$label" else "答案已生成，请查看屏幕"
        if(label!=null && clean.trim().trimEnd('.','．','、',':','：')==label) {
            val content=Regex("(?m)^\\s*"+label+"[.．、:：]\\s*(.+)$").find(full)?.groupValues?.get(1)
            if(!content.isNullOrBlank())return "答案是：$label，${MarkdownContent.speech(content)}"
        }
        return "答案是：$clean"
    }
}
