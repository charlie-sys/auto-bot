package com.autobot.reader

import com.autobot.reader.AppDialog as AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.widget.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal class NetworkCameraUi(private val activity:MainActivity,private val engine:ReaderEngine,private val reading:(Boolean)->Unit) {
    private val prefs=activity.getSharedPreferences("settings",Context.MODE_PRIVATE)
    private val client=OkHttpClient.Builder().connectTimeout(5,TimeUnit.SECONDS).callTimeout(10,TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    fun show(){
        val ownerId=AccountStore(activity).id()
        val ownerBase=prefs.getString("base_url","").orEmpty()
        fun sameOwner()=AccountStore(activity).id()==ownerId&&prefs.getString("base_url","").orEmpty()==ownerBase
        fun dp(n:Int)=(n*activity.resources.displayMetrics.density+.5f).toInt()
        fun background(color:Int)=android.graphics.drawable.GradientDrawable().apply{setColor(color);cornerRadius=dp(12).toFloat()}
        val panel=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL}
        fun label(value:String)=TextView(activity).apply{text=value;textSize=12f;setTextColor(AppPalette.muted);setLineSpacing(dp(3).toFloat(),1f);setPadding(0,dp(12),0,dp(8))}.also{panel.addView(it)}
        val info=label("正在获取本账号的专属推流地址…")
        label("Action 4 → DJI Mimo 自定义 RTMP → 本机 Docker 接流。请选择 1080p / 6 Mbps。App 显示约 3 帧/秒的识题预览，直播不是 4K 原始照片。推流地址含个人密钥，请勿分享。")
        var config:JSONObject?=null
        fun copy(value:String){if(!sameOwner()){Toast.makeText(activity,"账号或后端已变化，请重新打开页面",Toast.LENGTH_SHORT).show();return};(activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("相机接流",value));Toast.makeText(activity,"已复制，请粘贴到 DJI Mimo；不要分享密钥",Toast.LENGTH_SHORT).show()}
        fun button(title:String,action:()->Unit)=Button(activity).apply{text=title;textSize=13f;isAllCaps=false;stateListAnimator=null;setTextColor(AppPalette.primaryStrong);background=background(AppPalette.soft);setOnClickListener{action()}}.also{panel.addView(it,LinearLayout.LayoutParams(-1,dp(46)).apply{topMargin=dp(8)})}
        val copyAll=button("复制完整 RTMP 推流地址"){config?.optString("publish_url")?.let{copy(it)}}
        val copyServer=button("复制 RTMP 服务器地址"){config?.optString("server_url")?.let{copy(it)}}
        val copyKey=button("复制推流密钥"){config?.optString("stream_key")?.let{copy(it)}}
        listOf(copyAll,copyServer,copyKey).forEach{it.isEnabled=false}
        label("取景旋转（只处理收到的画面，不控制相机镜头）")
        val rotation=Spinner(activity).apply{adapter=ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,listOf("0°","90°","180°","270°"));setSelection(prefs.getInt("network_rotation",0)/90)}
        panel.addView(rotation)
        label("识题区域百分比：左、上、宽、高。默认 0、0、100、100 为整幅画面；预览与识题使用相同区域。数字缩放可用主界面＋－调整。")
        fun number(title:String,key:String,default:Int):EditText {
            label(title)
            return EditText(activity).apply{inputType=InputType.TYPE_CLASS_NUMBER;setSingleLine();textSize=14f;setTextColor(AppPalette.ink);background=background(AppPalette.background);setPadding(dp(14),0,dp(14),0);setText(prefs.getInt(key,default).toString())}.also{panel.addView(it,LinearLayout.LayoutParams(-1,dp(48)))}
        }
        val left=number("左 (%)","network_left",0);val top=number("上 (%)","network_top",0)
        val width=number("宽 (%)","network_width",100);val height=number("高 (%)","network_height",100)
        label("首次部署请在电脑运行 setup-camera.cmd。手机应能访问当前后端；建议使用局域网直连。断流后自动重连，过期画面不会重复取题。固定焦距相机不提供自动对焦控制。")
        fun load(rotate:Boolean=false){
            info.text="正在获取接流配置…"
            Thread({
                val result=runCatching{
                    val account=AccountStore(activity)
                    val url=AccountApi.server(prefs.getString("base_url","").orEmpty())+"/v1/network-camera/"+if(rotate)"rotate" else "config"
                    val request=Request.Builder().url(url).header("Authorization","Bearer "+account.token()).post(ByteArray(0).toRequestBody()).build()
                    client.newCall(request).execute().use{response->
                        val text=response.body?.string().orEmpty();val value=runCatching{JSONObject(text)}.getOrNull()
                        if(!response.isSuccessful)throw ApiException(response.code,value?.optString("detail")?:"接流配置请求失败")
                        value?:error("接流配置无效")
                    }
                }
                activity.runOnUiThread{if(!activity.isFinishing&&!activity.isDestroyed&&sameOwner()){
                    result.onSuccess{value->config=value;info.text="接流服务：${value.optString("server_url")}\n密钥已就绪；通过上方复制按钮填写 DJI Mimo。";listOf(copyAll,copyServer,copyKey).forEach{it.isEnabled=true}}
                        .onFailure{info.text="${it.message}\n可先配置取景，部署接流后再打开此页面。"}
                }}
            },"camera-config").apply{isDaemon=true}.start()
        }
        button("重新获取接流地址"){load()}
        button("重置个人推流密钥"){
            AlertDialog.Builder(activity).setTitle("重置推流密钥？").setMessage("请先停止 DJI Mimo 直播。旧地址将不能重新推流，需要把新地址重新填入 DJI Mimo。")
                .setNegativeButton("取消",null).setPositiveButton("重置"){_,_->engine.stopAll();load(true)}.show()
        }
        val dialog=AlertDialog.Builder(activity).setTitle("网络摄像头 · Action 4")
            .setView(panel)
            .setNegativeButton("取消",null).setNeutralButton("使用手机镜头"){_,_->
                engine.stopAll();prefs.edit().putString("camera_source","phone").apply();engine.sourceChanged();activity.refreshSourceBackground()
                Toast.makeText(activity,"已切换手机镜头，点击开始识题",Toast.LENGTH_SHORT).show()
            }.setPositiveButton("使用网络相机",null).create()
        dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            val l=left.text.toString().toIntOrNull()?:-1;val t=top.text.toString().toIntOrNull()?:-1
            val w=width.text.toString().toIntOrNull()?:-1;val h=height.text.toString().toIntOrNull()?:-1
            if(l !in 0..95||t !in 0..95||w !in 5..100||h !in 5..100||l+w>100||t+h>100){Toast.makeText(activity,"区域必须在画面内，宽高至少为 5%",Toast.LENGTH_LONG).show();return@setOnClickListener}
            engine.stopAll();prefs.edit().putString("camera_source","network").putInt("network_rotation",rotation.selectedItemPosition*90)
                .putInt("network_left",l).putInt("network_top",t).putInt("network_width",w).putInt("network_height",h).apply()
            engine.sourceChanged();activity.refreshSourceBackground();dialog.dismiss();Toast.makeText(activity,"网络相机已选择，请先开播，再点开始识题",Toast.LENGTH_LONG).show()
        }}
        reading(true);dialog.setOnDismissListener{reading(false)};dialog.show();load()
    }
}
