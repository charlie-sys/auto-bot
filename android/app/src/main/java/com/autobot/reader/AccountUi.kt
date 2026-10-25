package com.autobot.reader

import com.autobot.reader.AppDialog as AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import org.json.JSONObject
import java.util.concurrent.Executors

/** Account screens use the same native palette as the recognition screen. */
class AccountUi(
    private val activity: ComponentActivity,
    private val onAuthenticated: () -> Unit,
    private val onSignedOut: () -> Unit,
    private val onReading: (Boolean) -> Unit,
) {
    private val account = AccountStore(activity)
    private val prefs = activity.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()
    private var generation = 0
    private var activeDialog: android.app.Dialog? = null
    private val teal = AppPalette.primaryStrong
    private val ink = AppPalette.ink
    private val muted = AppPalette.muted
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density + .5f).toInt()
    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private fun text(value: String, size: Float = 14f, color: Int = ink) = TextView(activity).apply { text=value; textSize=size; setTextColor(color); setLineSpacing(dp(3).toFloat(),1f) }
    private fun field(label: String, hint: String, value: String = "", secret: Boolean = false, uri: Boolean = false): Pair<LinearLayout, EditText> {
        val edit = EditText(activity).apply {
            this.hint=hint; setText(value); textSize=14f; setSingleLine()
            inputType=InputType.TYPE_CLASS_TEXT or (if(secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else if(uri) InputType.TYPE_TEXT_VARIATION_URI else InputType.TYPE_TEXT_VARIATION_NORMAL)
            if(secret) importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO
            setPadding(dp(10),dp(10),dp(10),dp(10))
            setTextColor(AppPalette.ink);setHintTextColor(AppPalette.muted)
            background=GradientDrawable().apply{setColor(Color.WHITE);cornerRadius=dp(12).toFloat();setStroke(dp(1),AppPalette.line)}
        }
        return column().apply { addView(text(label,12f,muted)); addView(edit,LinearLayout.LayoutParams(-1,dp(54)).apply{topMargin=dp(7)}); setPadding(0,dp(12),0,0) } to edit
    }
    private fun button(value: String, filled: Boolean = false, click: () -> Unit) = Button(activity).apply {
        text=value; isAllCaps=false; textSize=14f; setTextColor(if(filled) Color.WHITE else teal)
        stateListAnimator=null;elevation=0f
        background=GradientDrawable().apply { setColor(if(filled) teal else AppPalette.soft);cornerRadius=dp(12).toFloat() }
        setOnClickListener { click() }
    }
    private fun addButton(parent: LinearLayout, value: String, filled: Boolean = false, click: () -> Unit): Button {
        val control=button(value,filled,click)
        parent.addView(control,LinearLayout.LayoutParams(-1,dp(48)).apply { topMargin=dp(12) });return control
    }
    private fun base() = prefs.getString("base_url","").orEmpty()
    private fun toast(message: String) = Toast.makeText(activity,message,Toast.LENGTH_LONG).show()
    private fun <T> work(action: () -> T, complete: (Result<T>) -> Unit) {
        val ticket=generation
        executor.execute {
            val result=runCatching(action)
            activity.runOnUiThread { if(!activity.isDestroyed && ticket==generation) complete(result) }
        }
    }

    fun start() {
        if(account.token().isBlank()) { showLogin();return }
        activity.setContentView(text("正在验证登录…",16f,muted).apply { gravity=Gravity.CENTER })
        work({ AccountApi.request(base(),"/v1/me",token=account.token()) }) { result ->
            result.onSuccess { value ->
                account.updateUser(value.getJSONObject("user"))
                if(account.user()?.optBoolean("must_change_password")==true || account.user()?.optInt("must_change_password")==1) showPassword(true)
                else finishLogin()
            }.onFailure { error ->
                if(error is ApiException && error.status==401) account.clear()
                showLogin();toast(error.message ?: "登录验证失败，请检查服务地址")
            }
        }
    }

    fun showLogin(register: Boolean = false) {
        generation++
        val page=column().apply { setPadding(dp(26),dp(36),dp(26),dp(24));setBackgroundColor(AppPalette.background) }
        page.addView(text("识题助手",29f,teal).apply { typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL) })
        page.addView(text(if(register) "创建账号 · 审核通过后即可使用" else "登录账号，继续识题与语音讲解",14f,muted).apply { setPadding(0,dp(10),0,dp(24)) })
        val (urlBox,url)=field("识题后端服务地址","电脑局域网或临时 HTTPS 地址",base(),uri=true)
        val (nameBox,name)=field(if(register) "用户名" else "用户名 / 邮箱",if(register) "3—32 位字母、数字、下划线" else "用户名或已绑定邮箱")
        val (emailBox,email)=field("验证邮箱","用于验证码登录和密码找回")
        val (codeBox,code)=field("邮箱验证码","6 位验证码，10 分钟内有效")
        val (passBox,password)=field("密码","至少 10 位，包含字母和数字",secret=true)
        page.addView(urlBox);page.addView(nameBox)
        addButton(page,"测试服务地址 / 局域网连接") {
            val address=runCatching {AccountApi.server(url.text.toString())}.getOrElse {toast(it.message?:"地址无效");return@addButton}
            work({AccountApi.request(address,"/health")}) {result->result.onSuccess {toast(if(it.optBoolean("accounts_ready"))"连接成功，可登录；同 Wi-Fi 使用电脑局域网 IP 可省去穿透转发" else "当前地址不是兼容的账号后端")}.onFailure {toast("无法连接：${it.message}。请确认同一 Wi-Fi、电脑服务和 8080 防火墙放行。")}}
        }
        if(register){page.addView(emailBox);page.addView(codeBox)}
        page.addView(passBox)
        val error=text("",13f,Color.rgb(168,62,66));page.addView(error)
        if(register)addCodeButton(page,url,email,"register",error)
        lateinit var submit: Button
        submit=addButton(page,if(register) "注册并提交审核" else "登录",true) {
            val address=runCatching { AccountApi.server(url.text.toString()) }.getOrElse { url.error=it.message;return@addButton }
            val username=name.text.toString().trim();val secret=password.text.toString()
            if(username.isBlank() || secret.isBlank()) { error.text="请填写用户名和密码";return@addButton }
            submit.isEnabled=false;error.text="正在连接…"
            work({
                val body=JSONObject().put("username",username).put("password",secret)
                if(register)body.put("email",email.text.toString()).put("code",code.text.toString())
                val value=AccountApi.request(address,"/v1/auth/"+if(register) "register" else "login", "POST", body)
                value
            }) { result ->
                submit.isEnabled=true
                result.onSuccess { value ->
                    if (!register) {
                        runCatching { account.save(value,address) }.onFailure { error.text="无法安全保存登录，请重试";return@onSuccess }
                    }
                    prefs.edit().putString("base_url",address).remove("token").apply();password.text.clear()
                    if(register) {showLogin();toast(value.optString("message","请等待管理员审核"))}
                    else if(account.user()?.optInt("must_change_password")==1 || account.user()?.optBoolean("must_change_password")==true) showPassword(true)
                    else {error.text="";finishLogin()}
                }.onFailure { error.text=it.message ?: "请求失败" }
            }
        }
        addButton(page,if(register) "已有账号，返回登录" else "没有账号？申请注册") {showLogin(!register)}
        if(!register){
            addButton(page,"邮箱验证码登录") {saveLoginAddress(url);showEmailAccess(false)}
            addButton(page,"忘记密码") {saveLoginAddress(url);showEmailAccess(true)}
        }
        page.addView(text("服务地址是本项目后端地址。模型 URL 与 API Key 在登录后的个人设置中填写。跨网连接请使用 HTTPS。",12f,muted).apply {setPadding(0,dp(20),0,0)})
        activity.setContentView(ScrollView(activity).apply {isFillViewport=true;addView(page)})
    }

    private fun saveLoginAddress(url: EditText) {
        runCatching {AccountApi.server(url.text.toString())}.onSuccess {prefs.edit().putString("base_url",it).apply()}
    }

    fun showConnectionSwitch() {
        val content=column()
        content.addView(text("同一 Wi-Fi 下可填写 http://电脑局域网IP:8080，省去内网穿透转发。电脑需运行 Docker 并允许手机访问 8080。切换地址后需重新登录；同一后端账号的本机历史保留。",13f,muted))
        val (box,address)=field("后端地址","http://192.168.x.x:8080 或 HTTPS 地址",base(),uri=true)
        content.addView(box);val status=text("",12f,muted);content.addView(status)
        addButton(content,"测试连接（不发送账号令牌）") {
            val target=runCatching {AccountApi.server(address.text.toString())}.getOrElse {status.text=it.message;return@addButton}
            work({AccountApi.request(target,"/health")}) {result->result.onSuccess {status.text=if(it.optBoolean("accounts_ready"))"连接成功，版本 "+it.optString("version") else "当前地址不是兼容后端"}.onFailure {status.text="连接失败：${it.message}"}}
        }
        addButton(content,"切换地址并重新登录",true) {
            val target=runCatching {AccountApi.server(address.text.toString())}.getOrElse {status.text=it.message;return@addButton}
            signOut();prefs.edit().putString("base_url",target).apply();showLogin()
        }
        dialog("局域网直连 / 更换后端",content)
    }

    private fun addCodeButton(parent: LinearLayout,url: EditText,email: EditText,purpose: String,status: TextView) {
        var retryAt=0L
        lateinit var send: Button
        send=addButton(parent,"发送邮箱验证码") {
            if(System.currentTimeMillis()<retryAt){toast("请等待 60 秒后再发送");return@addButton}
            val address=runCatching {AccountApi.server(url.text.toString())}.getOrElse {status.text=it.message;return@addButton}
            send.isEnabled=false
            val token=if(purpose=="bind")account.token() else ""
            work({AccountApi.request(address,"/v1/auth/email-code","POST",JSONObject().put("email",email.text.toString()).put("purpose",purpose),token)}) {result->
                result.onSuccess {retryAt=System.currentTimeMillis()+60000;send.text="60 秒后可重新发送";status.text=it.optString("message");send.postDelayed({send.isEnabled=true;send.text="重新发送验证码"},60000)}.onFailure {send.isEnabled=true;status.text=it.message}
            }
        }
    }

    private fun showEmailAccess(reset: Boolean) {
        generation++
        val page=column().apply {setPadding(dp(26),dp(36),dp(26),dp(24))}
        page.addView(text(if(reset) "邮箱找回密码" else "邮箱验证码登录",26f,teal))
        page.addView(text("旧账号请先用密码登录，在账号设置绑定邮箱。新注册账号仍需管理员审核。",13f,muted))
        val (urlBox,url)=field("识题后端服务地址","电脑局域网或 HTTPS 地址",base(),uri=true)
        val (mailBox,email)=field("邮箱","已验证邮箱")
        val (codeBox,code)=field("验证码","6 位验证码")
        val (passBox,password)=field("新密码","至少 10 位，包含字母和数字",secret=true)
        val (repeatBox,repeat)=field("确认新密码","再次输入新密码",secret=true)
        page.addView(urlBox);page.addView(mailBox);page.addView(codeBox)
        val status=text("",13f,muted);page.addView(status)
        addCodeButton(page,url,email,if(reset) "reset" else "login",status)
        if(reset){page.addView(passBox);page.addView(repeatBox)}
        lateinit var submit: Button
        submit=addButton(page,if(reset) "重置密码" else "登录",true) {
            if(reset&&password.text.toString()!=repeat.text.toString()){status.text="两次密码不一致";return@addButton}
            val address=runCatching {AccountApi.server(url.text.toString())}.getOrElse {status.text=it.message;return@addButton}
            val body=JSONObject().put("email",email.text.toString()).put("code",code.text.toString())
            if(reset)body.put("password",password.text.toString())
            submit.isEnabled=false
            work({AccountApi.request(address,"/v1/auth/email-"+if(reset) "reset" else "login","POST",body)}) {result->
                submit.isEnabled=true
                result.onSuccess {value->
                    prefs.edit().putString("base_url",address).apply()
                    if(reset){password.text.clear();repeat.text.clear();showLogin();toast(value.optString("message"))}
                    else {
                        runCatching {account.save(value,address)}.onFailure {status.text="登录保存失败，请重试";return@onSuccess}
                        if(account.user()?.optInt("must_change_password")==1)showPassword(true) else finishLogin()
                    }
                }.onFailure {status.text=it.message}
            }
        }
        addButton(page,"返回密码登录") {saveLoginAddress(url);showLogin()}
        activity.setContentView(ScrollView(activity).apply {isFillViewport=true;addView(page)})
    }

    private fun finishLogin() {
        work({account.migrateLegacyHistory()}) { result ->
            result.onFailure {toast("旧历史迁移未完成，将保留原始数据")}
            onAuthenticated()
        }
    }

    private fun dialog(title: String, content: LinearLayout): AlertDialog {
        content.setPadding(0,0,0,dp(6))
        val dialog=AlertDialog.Builder(activity).setTitle(title).setView(content).setNegativeButton("关闭",null).create()
        activeDialog?.dismiss();activeDialog=dialog
        dialog.setOnDismissListener {if(activeDialog===dialog)activeDialog=null;onReading(false)};onReading(true);dialog.show();return dialog
    }

    fun showAccount() {
        val sheet=SettingsSheet(activity,"账号、模型与订阅","管理个人账号、订阅额度与专属模型服务")
        var content=sheet.card("个人资料")
        val user=account.user() ?: return showLogin()
        content.addView(text(user.optString("username") + if(account.role()=="superadmin") " · 超级管理员" else " · 普通用户",17f,teal))
        if(user.optString("email").isNullOrBlank() || user.optString("email")=="null") {
            addButton(content,"绑定邮箱（登录 / 找回密码）") {showBindEmail()}
        } else content.addView(text("验证邮箱："+user.optString("email"),12f,muted))
        content=sheet.card("订阅与额度","查看套餐、续期和使用情况")
        addButton(content,"额度与付费订阅 / 我的订单",true) {
            activity.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(base().trimEnd('/')+"/admin#subscription")))
            toast("在网页登录后可管理套餐与订单，网页登录与 App 独立")
        }
        addButton(content,"查看订阅余额") {
            work({AccountApi.request(base(),"/v1/me/subscription",token=account.token())}) {result->result.onSuccess {value->
                val balances=value.getJSONObject("balances")
                AlertDialog.Builder(activity).setTitle("订阅额度").setMessage("调用次数：${balances.optLong("requests")}\n题目数量：${balances.optLong("questions")}\nToken：${balances.optLong("tokens")}\n\n"+value.optString("rules")).setPositiveButton("知道了",null).show()
            }.onFailure {toast(it.message?:"查询失败")}}
        }
        content=sheet.card("个人模型配置","连接支持图片输入的 OpenAI 兼容接口")
        val (baseBox,modelUrl)=field("OpenAI 兼容 Base URL","https://服务商域名/接口路径",uri=true)
        val (modelBox,model)=field("支持图片输入的模型名","例如 qwen3.8-flash")
        val (keyBox,key)=field("个人 API Key","加载配置中…",secret=true)
        content.addView(baseBox);content.addView(modelBox);content.addView(keyBox)
        content.addView(text("密钥在后端加密保存，只属于当前账号；不会回传完整值。已配置时留空可保留原密钥。",12f,muted))
        val status=text("正在加载个人配置…",12f,muted);content.addView(status)
        lateinit var save: Button
        save=addButton(content,"保存个人模型配置",true) {
            save.isEnabled=false;status.text="正在保存…"
            val body=JSONObject().put("base_url",modelUrl.text.toString().trim()).put("model",model.text.toString().trim())
            if(key.text.isNotBlank())body.put("api_key",key.text.toString().trim())
            work({AccountApi.request(base(),"/v1/me/model","PUT",body,account.token())}) { result ->
                save.isEnabled=true;result.onSuccess {key.text.clear();key.hint="已配置，留空保留原密钥";status.text="个人模型配置已保存";toast("个人配置已保存")}.onFailure {handleError(it,status)}
            }
        }
        save.isEnabled=false
        val test=addButton(content,"测试已保存配置（计一次调用）") {
            status.text="正在测试，请稍候…"
            work({AccountApi.request(base(),"/v1/me/model/test","POST",token=account.token())}) {result->result.onSuccess {status.text=it.optString("message")}.onFailure {handleError(it,status)}}
        }
        test.isEnabled=false
        addButton(content,"移除个人模型配置") {
            AlertDialog.Builder(activity).setTitle("移除个人模型配置？").setMessage("将删除当前账号保存的模型 URL、模型名和密钥。之后需要重新配置才能解题。").setNegativeButton("取消",null).setPositiveButton("移除") {_,_->
                work({AccountApi.request(base(),"/v1/me/model","DELETE",token=account.token())}) {result->result.onSuccess {modelUrl.text.clear();model.text.clear();key.text.clear();key.hint="填写自己的 API Key";status.text="个人模型配置已移除"}.onFailure {handleError(it,status)}}
            }.show()
        }
        content=sheet.card("使用与安全")
        addButton(content,"查看我的使用情况") {
            work({AccountApi.request(base(),"/v1/me/usage?days=7",token=account.token())}) {result->result.onSuccess {
                val summary=it.getJSONObject("summary")
                AlertDialog.Builder(activity).setTitle("我的使用情况 · 最近7天").setMessage("今日调用：${it.optInt("today")} / ${it.optInt("daily_quota")}\n总调用：${summary.optInt("requests")}\n成功：${summary.optInt("success")}\n失败：${summary.optInt("failed")}\n解答题数：${summary.optInt("questions")}\n输入 token：${summary.optLong("input_tokens")}\n输出 token：${summary.optLong("output_tokens")}\n\nToken 以接口报告值为准，实际计费以服务商为准。").setPositiveButton("知道了",null).show()
            }.onFailure {handleError(it,status)}}
        }
        if(account.role()=="superadmin")addButton(content,"打开超级管理员后台") {
            runCatching {activity.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(base().trimEnd('/')+"/admin")))}.onFailure {toast("无法打开浏览器，请访问服务地址下的 /admin")}
        }
        lateinit var window: android.app.Dialog
        addButton(content,"修改登录密码") {window.dismiss();showPassword(false)}
        addButton(content,"退出登录 / 切换账号") {
            AlertDialog.Builder(activity).setTitle("退出当前账号？").setMessage("将停止本次识题与播报；该账号的本机历史会保留。").setNegativeButton("取消",null).setPositiveButton("退出") {_,_->window.dismiss();signOut()}.show()
        }
        window=sheet.dialog
        activeDialog?.dismiss();activeDialog=window
        window.setOnDismissListener {if(activeDialog===window)activeDialog=null;onReading(false)}
        onReading(true);sheet.show()
        work({AccountApi.request(base(),"/v1/me/model",token=account.token())}) {result->
            result.onSuccess {modelUrl.setText(it.optString("base_url"));model.setText(it.optString("model"));key.hint=if(it.optBoolean("has_key")) "已配置，留空保留原密钥" else "填写自己的 API Key";status.text="只支持公共 HTTPS 模型接口；识题需视觉模型。";save.isEnabled=true;test.isEnabled=true}.onFailure {handleError(it,status)}
        }
    }

    private fun showBindEmail() {
        if(!account.user()?.optString("email").isNullOrBlank() && account.user()?.optString("email")!="null")return
        val content=column()
        val (_,url)=field("服务地址","",base())
        val (mailBox,email)=field("邮箱","要绑定的邮箱")
        val (codeBox,code)=field("验证码","6 位验证码")
        content.addView(mailBox);content.addView(codeBox)
        val status=text("",12f,muted);content.addView(status)
        addCodeButton(content,url,email,"bind",status)
        addButton(content,"验证并绑定",true) {
            work({AccountApi.request(base(),"/v1/me/email","POST",JSONObject().put("email",email.text.toString()).put("code",code.text.toString()),account.token());AccountApi.request(base(),"/v1/me",token=account.token())}) {result->
                result.onSuccess {account.updateUser(it.getJSONObject("user"));status.text="邮箱已绑定，可用于验证码登录和找回密码"}.onFailure {status.text=it.message}
            }
        }
        dialog("绑定邮箱",content)
    }

    private fun handleError(error: Throwable, status: TextView) {
        status.text=error.message ?: "请求失败"
        if(error is ApiException && error.status==401) signOut(error.message)
        else if(error is ApiException && error.status==428) showPassword(true)
    }

    fun showPassword(forced: Boolean) {
        if(forced)activeDialog?.dismiss()
        val content=column()
        content.addView(text(if(forced) "首次使用或重置密码后，请先设置新密码。" else "修改密码会撤销其他设备的登录。",13f,muted))
        val (oldBox,old)=field("当前密码","当前登录密码",secret=true)
        val (newBox,next)=field("新密码","至少 10 位，包含字母和数字",secret=true)
        val (repeatBox,repeat)=field("确认新密码","再次输入新密码",secret=true)
        content.addView(oldBox);content.addView(newBox);content.addView(repeatBox)
        val error=text("",12f,Color.rgb(168,62,66));content.addView(error)
        var popup: AlertDialog?=null
        lateinit var save: Button
        save=addButton(content,"修改密码并继续",true) {
            if(next.text.toString()!=repeat.text.toString()){error.text="两次输入的新密码不一致";return@addButton}
            save.isEnabled=false
            val body=JSONObject().put("old_password",old.text.toString()).put("new_password",next.text.toString())
            work({AccountApi.request(base(),"/v1/auth/password","POST",body,account.token())}) {result->
                save.isEnabled=true;result.onSuccess {
                    runCatching {account.save(it)}.onFailure {error.text="密码已修改，但安全保存登录失败，请重新登录";return@onSuccess}
                    old.text.clear();next.text.clear();repeat.text.clear();popup?.dismiss();toast("密码已修改");if(forced)finishLogin()
                }.onFailure {error.text=it.message ?: "修改失败"}
            }
        }
        if(forced) {
            content.setPadding(dp(26),dp(50),dp(26),dp(24));content.addView(text("账号：${account.user()?.optString("username")}",12f,muted),0)
            addButton(content,"退出登录") {signOut()}
            activity.setContentView(ScrollView(activity).apply {isFillViewport=true;addView(content)})
        } else popup=dialog("修改密码",content)
    }

    fun signOut(message: String? = null) {
        val token=account.token();val address=base()
        generation++;activeDialog?.dismiss();account.clear();onSignedOut();showLogin()
        if(message!=null)toast(message)
        if(token.isNotBlank())executor.execute {runCatching {AccountApi.request(address,"/v1/auth/logout","POST",token=token)}}
    }
    fun close() {generation++;executor.shutdownNow()}
}
