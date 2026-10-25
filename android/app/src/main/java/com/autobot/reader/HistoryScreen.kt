package com.autobot.reader

import android.app.Activity
import com.autobot.reader.AppDialog as AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.*
import android.text.TextWatcher
import android.text.Editable
import java.util.concurrent.Executors

/** Reads and filters off the main thread; only ten light summary cards are inflated. */
internal class HistoryScreen(private val activity:Activity) {
    private val worker=Executors.newSingleThreadExecutor()
    private val handler=Handler(Looper.getMainLooper())
    private var closed=false
    private var generation=0
    private var page=0
    private var query=""
    private var favoritesOnly=false
    private var resetScroll=true
    private var snapshot=HistorySnapshot(emptyList(),0)
    private var repo:HistoryRepository?=null
    private var redraw:()->Unit={}
    private fun dp(n:Int)=(n*activity.resources.displayMetrics.density+.5f).toInt()
    private fun column()=LinearLayout(activity).apply{orientation=1}
    private fun row()=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL}
    private fun label(value:String,size:Float=14f,color:Int=AppPalette.ink)=TextView(activity).apply{text=value;textSize=size;setTextColor(color)}
    private fun shape(color:Int)=GradientDrawable().apply{setColor(color);cornerRadius=dp(16).toFloat()}
    private fun button(value:String,click:()->Unit)=Button(activity).apply{text=value;textSize=12f;isAllCaps=false;stateListAnimator=null;setTextColor(AppPalette.primaryStrong);background=shape(AppPalette.soft);setOnClickListener{click()}}
    fun close(){closed=true;generation++;handler.removeCallbacksAndMessages(null);worker.shutdownNow()}
    private fun valid(r:HistoryRepository)=!closed&&!activity.isDestroyed&&repo===r&&r.isCurrent()
    private fun change(action:(HistoryRepository)->HistorySnapshot){
        val r=repo?:return
        worker.execute{
            val result=runCatching{action(r)}
            handler.post{if(valid(r)){result.onSuccess{snapshot=it;redraw()}.onFailure{Toast.makeText(activity,it.message?:"记录加载失败",Toast.LENGTH_LONG).show()}}}
        }
    }
    fun reload()=change{it.load()}
    fun show(parent:LinearLayout,repository:HistoryRepository,details:(HistoryEntry,(Boolean)->Unit)->Unit){
        generation++;repo=repository;parent.removeAllViews()
        parent.addView(label("识题记录",25f).apply{typeface=Typeface.DEFAULT_BOLD;setPadding(dp(20),dp(22),dp(20),dp(6))})
        val count=label("正在加载记录…",12f,AppPalette.muted).apply{setPadding(dp(20),0,dp(20),dp(14))};parent.addView(count)
        val searchRow=row().apply{setPadding(dp(16),0,dp(16),dp(12))}
        val search=EditText(activity).apply{hint="搜索题目或答案";setSingleLine();textSize=14f;background=shape(Color.WHITE);setPadding(dp(14),dp(8),dp(14),dp(8));setText(query)}
        searchRow.addView(search,LinearLayout.LayoutParams(0,dp(48),1f))
        searchRow.addView(button("清空"){
            AlertDialog.Builder(activity).setTitle("清空普通历史？").setMessage("普通识题记录将被清空，收藏题目和它们的题图会保留。")
                .setNegativeButton("取消",null).setPositiveButton("清空"){_,_->change{it.clearHistory()}}.show()
        },LinearLayout.LayoutParams(dp(64),dp(44)).apply{leftMargin=dp(8)})
        parent.addView(searchRow)
        val filters=row().apply{setPadding(dp(16),0,dp(16),dp(12))}
        val all=button("全部"){favoritesOnly=false;page=0;resetScroll=true;redraw()}
        val stars=button("我的收藏"){favoritesOnly=true;page=0;resetScroll=true;redraw()}
        filters.addView(all,LinearLayout.LayoutParams(0,dp(42),1f));filters.addView(stars,LinearLayout.LayoutParams(0,dp(42),1f).apply{leftMargin=dp(8)})
        parent.addView(filters)
        val content=column().apply{setPadding(dp(16),0,dp(16),dp(12))}
        val scroll=ScrollView(activity).apply{isVerticalScrollBarEnabled=false;addView(content)}
        parent.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val pager=row().apply{setPadding(dp(16),dp(8),dp(16),dp(10))}
        val previous=button("上一页"){page--;resetScroll=true;redraw()};val next=button("下一页"){page++;resetScroll=true;redraw()}
        val position=label("",12f,AppPalette.muted).apply{gravity=Gravity.CENTER}
        pager.addView(previous,LinearLayout.LayoutParams(dp(84),dp(44)));pager.addView(position,LinearLayout.LayoutParams(0,-2,1f));pager.addView(next,LinearLayout.LayoutParams(dp(84),dp(44)))
        parent.addView(pager)
        redraw={
            val ticket=++generation;val source=snapshot;val keyword=query.lowercase();val only=favoritesOnly;val requested=page
            worker.execute{
                val result=HistoryPaging.page(source.entries.filter{(!only||it.favorite)&&(keyword.isBlank()||it.searchText.contains(keyword))},requested)
                handler.post{
                    if(valid(repository)&&ticket==generation){
                        page=result.index;count.text="${source.entries.size} 道题目 · 收藏 ${source.favorites} 道 · 每页 10 道"
                        all.background=shape(if(only)Color.WHITE else AppPalette.soft);stars.background=shape(if(only)AppPalette.soft else Color.WHITE)
                        stars.text="我的收藏 (${source.favorites})"
                        position.text="${page+1} / ${result.pages} 页 · 共 ${result.total} 题";previous.isEnabled=page>0;next.isEnabled=page+1<result.pages
                        val oldY=scroll.scrollY
                        val anchor=(0 until content.childCount).map{content.getChildAt(it)}.firstOrNull{it.bottom>oldY}
                        val anchorId=anchor?.tag
                        val anchorOffset=(anchor?.top?:0)-oldY
                        val moveToTop=resetScroll||result.index!=requested
                        resetScroll=false
                        content.removeAllViews()
                        result.items.forEach{entry->
                            val card=column().apply{tag=entry.id;background=shape(Color.WHITE);setPadding(dp(16),dp(12),dp(16),dp(16))}
                            val meta=row()
                            meta.addView(label(java.text.SimpleDateFormat("MM-dd HH:mm",java.util.Locale.getDefault()).format(java.util.Date(entry.time)),11f,AppPalette.muted),LinearLayout.LayoutParams(0,-2,1f))
                            val star=button(if(entry.favorite)"★ 已收藏" else "☆ 收藏"){
                                change{it.favorite(entry.id,!entry.favorite)}
                            }
                            meta.addView(star,LinearLayout.LayoutParams(dp(88),dp(40)));card.addView(meta)
                            card.addView(label(entry.brief,15f).apply{maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(0,dp(6),0,dp(10))})
                            card.addView(label("参考答案  ${entry.answer}",13f,AppPalette.primaryStrong).apply{maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END})
                            card.addView(label("查看题目与解析  ›",12f,AppPalette.muted).apply{setPadding(0,dp(12),0,0)})
                            card.setOnClickListener{details(entry){selected->change{it.favorite(entry.id,selected)}}}
                            content.addView(card,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(12)})
                        }
                        if(result.total==0)content.addView(label(if(query.isNotBlank())"没有找到相关题目" else if(only)"还没有收藏题目" else "还没有识题记录",16f,AppPalette.muted).apply{gravity=Gravity.CENTER;setPadding(dp(12),dp(50),dp(12),dp(50))})
                        scroll.post{
                            if(valid(repository)&&ticket==generation){
                                val retained=(0 until content.childCount).map{content.getChildAt(it)}.firstOrNull{it.tag==anchorId&&anchorId!=null}
                                scroll.scrollTo(0,if(moveToTop)0 else retained?.let{it.top-anchorOffset}?:oldY)
                            }
                        }
                    }
                }
            }
        }
        search.addTextChangedListener(object:TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun afterTextChanged(s:Editable?){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){query=s.toString().trim();page=0;resetScroll=true;generation++;handler.removeCallbacks(searchTask);handler.postDelayed(searchTask,180)}
        })
        previous.isEnabled=false;next.isEnabled=false
        reload()
    }
    private val searchTask=Runnable{if(!closed)redraw()}
}
