package com.autobot.reader

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.*

/** Shared modal surface; action semantics match Android dialogs, including validation overrides. */
internal class AppDialog private constructor(context:Context,config:Builder):Dialog(context) {
    private val buttons=mutableMapOf<Int,Button>()
    private fun dp(n:Int)=(n*context.resources.displayMetrics.density+.5f).toInt()
    private fun shape(color:Int,radius:Int)=GradientDrawable().apply{setColor(color);cornerRadius=dp(radius).toFloat()}
    private fun text(value:CharSequence,size:Float,color:Int)=TextView(context).apply{text=value;textSize=size;setTextColor(color);setLineSpacing(dp(5).toFloat(),1f)}
    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root=LinearLayout(context).apply{orientation=1;background=shape(Color.WHITE,24);setPadding(dp(24),dp(22),dp(24),dp(20))}
        val header=LinearLayout(context).apply{gravity=Gravity.CENTER_VERTICAL}
        val heading=LinearLayout(context).apply{orientation=1}
        heading.addView(text("识题助手",11f,AppPalette.primaryStrong).apply{letterSpacing=.12f})
        heading.addView(text(config.heading,20f,AppPalette.ink).apply{typeface=Typeface.DEFAULT_BOLD;setPadding(0,dp(7),0,dp(2))})
        header.addView(heading,LinearLayout.LayoutParams(0,-2,1f))
        header.addView(Button(context).apply{
            text="×";textSize=23f;contentDescription="关闭弹框";stateListAnimator=null;setTextColor(AppPalette.muted);background=shape(AppPalette.background,20);setPadding(0,0,0,0);setOnClickListener{cancel()}
        },LinearLayout.LayoutParams(dp(40),dp(40)).apply{leftMargin=dp(12)})
        root.addView(header)
        val content=LinearLayout(context).apply{orientation=1;setPadding(0,dp(18),0,dp(8))}
        config.message?.let{message->
            val lines=message.toString().lines()
            val metric=Regex("^([^：:\\n]{1,14})[：:]\\s*(.{1,40})$")
            val stats=lines.takeWhile{metric.matches(it)}
            if(stats.size>=2){
                stats.chunked(2).forEach{pair->
                    val row=LinearLayout(context)
                    pair.forEachIndexed{index,line->
                        val match=metric.matchEntire(line)!!
                        row.addView(LinearLayout(context).apply{
                            orientation=1;background=shape(AppPalette.background,14);setPadding(dp(14),dp(13),dp(14),dp(13))
                            addView(text(match.groupValues[1],11f,AppPalette.muted))
                            addView(text(match.groupValues[2],19f,AppPalette.primaryStrong).apply{typeface=Typeface.DEFAULT_BOLD;setPadding(0,dp(5),0,0)})
                        },LinearLayout.LayoutParams(0,-1,1f).apply{if(index==1)leftMargin=dp(8)})
                    }
                    content.addView(row,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})
                }
                val remainder=lines.drop(stats.size).joinToString("\n").trim()
                if(remainder.isNotBlank())content.addView(text(remainder,12f,AppPalette.muted).apply{setPadding(0,dp(8),0,0);setTextIsSelectable(true)})
            }else content.addView(text(message,14f,AppPalette.muted).apply{setTextIsSelectable(true)})
        }
        config.content?.let{view->(view.parent as? ViewGroup)?.removeView(view);content.addView(view)}
        root.addView(CappedScrollView(context).apply{isVerticalScrollBarEnabled=false;addView(content)},LinearLayout.LayoutParams(-1,-2))
        val actions=LinearLayout(context).apply{orientation=1;setPadding(0,dp(12),0,0)}
        fun addButton(which:Int,primary:Boolean):Button? {
            val spec=config.actions[which]?:return null
            val accent=if(primary&&config.destructive)Color.rgb(203,64,77)else AppPalette.primaryStrong
            return Button(context).apply{
                text=spec.first;isAllCaps=false;textSize=14f;typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);stateListAnimator=null
                setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),intArrayOf(AppPalette.muted,if(primary)Color.WHITE else AppPalette.primaryStrong)))
                background=shape(if(primary)accent else AppPalette.soft,13)
                setOnClickListener{spec.second?.invoke(this@AppDialog,which);dismiss()}
            }.also{buttons[which]=it}
        }
        addButton(BUTTON_NEUTRAL,false)?.let{actions.addView(it,LinearLayout.LayoutParams(-1,dp(48)).apply{bottomMargin=dp(10)})}
        val row=LinearLayout(context)
        val negative=addButton(BUTTON_NEGATIVE,false)
        val positive=addButton(BUTTON_POSITIVE,true)
        negative?.let{row.addView(it,LinearLayout.LayoutParams(0,dp(48),1f).apply{if(positive!=null)rightMargin=dp(10)})}
        positive?.let{row.addView(it,LinearLayout.LayoutParams(0,dp(48),1f))}
        actions.addView(row);root.addView(actions)
        setContentView(root)
        window?.apply{setBackgroundDrawableResource(android.R.color.transparent);addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);setDimAmount(.32f);setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)}
        setCanceledOnTouchOutside(true)
    }
    override fun show(){super.show();window?.setLayout(minOf(context.resources.displayMetrics.widthPixels-dp(32),dp(560)),ViewGroup.LayoutParams.WRAP_CONTENT)}
    fun getButton(which:Int):Button=buttons.getValue(which)
    class Builder(private val context:Context){
        internal var heading:CharSequence=""
        internal var message:CharSequence?=null
        internal var content:View?=null
        internal var destructive=false
        internal val actions=linkedMapOf<Int,Pair<CharSequence,((DialogInterface,Int)->Unit)?>>()
        fun setTitle(value:CharSequence)=apply{heading=value;destructive=listOf("清空","移除","退出","删除","重置推流").any{value.contains(it)}}
        fun setMessage(value:CharSequence)=apply{message=value}
        fun setView(value:View)=apply{content=value}
        fun setNegativeButton(value:CharSequence,listener:((DialogInterface,Int)->Unit)?)=apply{actions[BUTTON_NEGATIVE]=value to listener}
        fun setPositiveButton(value:CharSequence,listener:((DialogInterface,Int)->Unit)?)=apply{actions[BUTTON_POSITIVE]=value to listener}
        fun setNeutralButton(value:CharSequence,listener:((DialogInterface,Int)->Unit)?)=apply{actions[BUTTON_NEUTRAL]=value to listener}
        fun create()=AppDialog(context,this)
        fun show()=create().also{it.show()}
    }
    companion object {
        const val BUTTON_POSITIVE=DialogInterface.BUTTON_POSITIVE
        const val BUTTON_NEGATIVE=DialogInterface.BUTTON_NEGATIVE
        const val BUTTON_NEUTRAL=DialogInterface.BUTTON_NEUTRAL
    }
}

private class CappedScrollView(context:Context):ScrollView(context){
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
        val cap=(resources.displayMetrics.heightPixels*.55f).toInt()
        val available=if(MeasureSpec.getMode(heightMeasureSpec)==MeasureSpec.UNSPECIFIED)cap else minOf(cap,MeasureSpec.getSize(heightMeasureSpec))
        super.onMeasure(widthMeasureSpec,MeasureSpec.makeMeasureSpec(available,MeasureSpec.AT_MOST))
    }
}
