package com.autobot.reader

import android.app.Activity
import android.app.Dialog
import android.graphics.Typeface
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

/** Shared, spacious settings surface with a fixed navigation and action area. */
internal class SettingsSheet(private val activity:Activity,val title:String,val subtitle:String) {
    val dialog=Dialog(activity,android.R.style.Theme_Material_Light_NoActionBar)
    val body=LinearLayout(activity).apply{orientation=1;setPadding(dp(16),dp(8),dp(16),dp(24))}
    private val root=LinearLayout(activity).apply{orientation=1;fitsSystemWindows=true;setBackgroundColor(AppPalette.background)}
    fun dp(n:Int)=(n*activity.resources.displayMetrics.density+.5f).toInt()
    fun label(value:String,size:Float=14f,color:Int=AppPalette.ink)=TextView(activity).apply{text=value;textSize=size;setTextColor(color);setLineSpacing(dp(3).toFloat(),1f)}
    init {
        val header=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),dp(12),dp(16),dp(12))}
        header.addView(button("‹ 返回"){dialog.dismiss()},LinearLayout.LayoutParams(dp(76),dp(44)))
        header.addView(label(title,19f).apply{gravity=Gravity.CENTER;typeface=Typeface.DEFAULT_BOLD},LinearLayout.LayoutParams(0,-2,1f))
        header.addView(View(activity),LinearLayout.LayoutParams(dp(76),1))
        root.addView(header)
        body.addView(label(subtitle,13f,AppPalette.muted).apply{setPadding(dp(4),dp(4),dp(4),dp(18))})
        root.addView(ScrollView(activity).apply{isVerticalScrollBarEnabled=false;addView(body)},LinearLayout.LayoutParams(-1,0,1f))
        dialog.setContentView(root)
    }
    fun card(title:String,description:String=""):LinearLayout {
        val card=LinearLayout(activity).apply{orientation=1;setPadding(dp(18),dp(18),dp(18),dp(18));background=GradientDrawable().apply{setColor(Color.WHITE);cornerRadius=dp(20).toFloat()}}
        card.addView(label(title,16f).apply{typeface=Typeface.DEFAULT_BOLD;setPadding(0,0,0,dp(8))})
        if(description.isNotBlank())card.addView(label(description,12f,AppPalette.muted).apply{setPadding(0,0,0,dp(10))})
        body.addView(card,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(14)})
        return card
    }
    fun button(value:String,primary:Boolean=false,action:()->Unit)=Button(activity).apply{
        text=value;isAllCaps=false;textSize=14f;stateListAnimator=null
        setTextColor(if(primary)Color.WHITE else AppPalette.primaryStrong)
        background=GradientDrawable().apply{setColor(if(primary)AppPalette.primaryStrong else AppPalette.soft);cornerRadius=dp(12).toFloat()}
        setOnClickListener{action()}
    }
    fun footer(value:String,action:()->Unit){
        root.addView(button(value,true,action),LinearLayout.LayoutParams(-1,dp(52)).apply{setMargins(dp(20),dp(10),dp(20),dp(16))})
    }
    fun show(){dialog.show();dialog.window?.apply{setLayout(-1,-1);statusBarColor=AppPalette.background;navigationBarColor=AppPalette.background;decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)}}
}
