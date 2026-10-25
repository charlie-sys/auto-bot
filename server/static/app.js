"use strict";
const root = document.querySelector('#app');
let current = null, csrfToken = '', page = 'overview', days = 7, userOffset = 0, usageOffset = 0, auditOffset = 0, query = '', statusFilter = '', usageUser = '';
const statuses = {active:'已启用',pending:'待审核',disabled:'已停用',rejected:'未通过',success:'成功',failed:'失败',processing:'处理中'};
const fmt = n => Number(n || 0).toLocaleString('zh-CN');
const date = n => n ? new Date(n*1000).toLocaleString('zh-CN',{hour12:false}) : '—';
function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key,value] of Object.entries(props)) {
    if (key === 'class') node.className = value;
    else if (key.startsWith('on')) node.addEventListener(key.slice(2).toLowerCase(), value);
    else if (key === 'text') node.textContent = value;
    else node[key] = value;
  }
  children.flat(Infinity).forEach(child => { if (child != null) node.append(child instanceof Node ? child : document.createTextNode(String(child))); });
  return node;
}
function toast(text) { const node=document.querySelector('#toast'); node.textContent=text; node.hidden=false; clearTimeout(toast.timer); toast.timer=setTimeout(()=>node.hidden=true,4500); }
async function api(path, method='GET', body) {
  const response = await fetch(path,{method,credentials:'same-origin',headers:{'Content-Type':'application/json','X-CSRF-Token':csrfToken},body:body===undefined?undefined:JSON.stringify(body)});
  const value = await response.json().catch(()=>({detail:'服务响应异常'}));
  if (!response.ok) {
    if (response.status===401 && current) { current=null; authScreen(); }
    if (response.status===428 && current) passwordPage(true);
    throw new Error(typeof value.detail==='string'?value.detail:'请求失败，请稍后重试');
  }
  return value;
}
function button(text, action, cls='') { return el('button',{type:'button',class:cls,onClick:()=>Promise.resolve().then(action).catch(error=>toast(error.message))},text); }
function field(title, input) { return el('div',{class:'field'},el('label',{},title,input)); }
function input(type, placeholder='', value='') { return el('input',{type,placeholder,value,ariaLabel:placeholder,required:true}); }
function modal(title, children, actions=[]) {
  const dialog=document.querySelector('#modal'); dialog.close();
  document.querySelector('#modal-content').replaceChildren(el('h2',{},title),...children,el('div',{class:'button-row'},button('关闭',()=>dialog.close()),...actions));
  dialog.showModal(); return dialog;
}
function authScreen(register=false) {
  const name=input('text',register?'3—32 位字母、数字、下划线':'用户名或已绑定邮箱'); name.autocomplete='username'; name.maxLength=254;
  const password=input('password','至少 10 位，包含字母和数字'); password.autocomplete=register?'new-password':'current-password'; password.maxLength=128;
  const error=el('div',{class:'error-text',role:'alert'});
  const email=input('email','用于登录与密码找回'), code=input('text','6 位邮箱验证码');code.maxLength=6;code.autocomplete='one-time-code';
  const send=codeButton(email,register?'register':'login',error);
  const submit=el('button',{type:'submit',class:'primary'},register?'注册并提交审核':'登录');
  const form=el('form',{onSubmit:async event=>{
    event.preventDefault(); submit.disabled=true; error.textContent='';
    try {
      const value=await api('/v1/auth/'+(register?'register':'login'),'POST',{username:name.value,password:password.value,...(register?{email:email.value,code:code.value}:{})});
      password.value='';
      if (register) { authScreen(false); toast(value.message); }
      else { current=value.user; csrfToken=value.csrf_token; if(current.must_change_password) passwordPage(true); else { page=location.hash==='#subscription'?'subscription':current.role==='superadmin'?'overview':'myUsage'; shell(); } }
    } catch (failure) { error.textContent=failure.message; } finally { submit.disabled=false; }
  }},el('p',{class:'eyebrow'},'YOUR READER WORKSPACE'),el('h2',{},register?'创建账号':'欢迎回来'),el('p',{class:'hint'},register?'验证邮箱后提交申请，由管理员审核启用。':'登录后管理个人模型、识题额度与订阅。'),field(register?'用户名':'用户名 / 邮箱',name),...(register?[field('邮箱地址',email),field('邮箱验证码',el('div',{class:'code-field'},code,send))]:[]),field('密码',password),error,submit,button(register?'已有账号，返回登录':'还没有账号？申请注册',()=>authScreen(!register),'link'),...(!register?[el('div',{class:'auth-links'},button('验证码登录',()=>emailScreen('login'),'link'),button('忘记密码',()=>emailScreen('reset'),'link'))]:[]));
  root.replaceChildren(el('div',{class:'auth'},el('section',{class:'auth-intro'},el('div',{class:'brand-icon'},'识'),el('p',{class:'eyebrow'},'QUESTION READER'),el('h1',{},'让每一次识题，\n都有清晰的答案。'),el('p',{},'个人模型独立配置，识题用量清楚可见。为用户与管理员准备的工作空间。')),el('section',{class:'auth-card'},form)));
}
async function logout() { await api('/v1/auth/logout','POST'); current=null; csrfToken=''; authScreen(); }
function svgNode(tag,attrs,text){const node=document.createElementNS('http://www.w3.org/2000/svg',tag);for(const[k,v]of Object.entries(attrs))node.setAttribute(k,v);if(text!==undefined)node.textContent=text;return node;}
function icon(name) {
  const paths={overview:'M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z',users:'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2 M16 3a4 4 0 0 1 0 8 M22 21v-2a4 4 0 0 0-3-3.87 M13 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0',usage:'M5 3h14v18H5z M8 8h8 M8 12h8 M8 16h5',audit:'M12 8v5l3 2 M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0',plans:'M12 3l9 9-9 9-9-9z M8 12h8',orders:'M5 3h14v18l-3-2-4 2-4-2-3 2z M8 8h8 M8 12h8',services:'M4 6h16v12H4z M4 6l8 7 8-7',myUsage:'M4 20V10 M10 20V4 M16 20v-7 M22 20H2',subscription:'M3 7h18v13H3z M3 11h18 M7 16h4',model:'M8 3v3 M16 3v3 M5 6h14v14H5z M8 11h1 M15 11h1 M9 16h6',password:'M5 10h14v11H5z M8 10V6a4 4 0 0 1 8 0v4 M12 14v3',scan:'M8 3H3v5 M16 3h5v5 M21 16v5h-5 M8 21H3v-5 M8 10h8 M8 14h5'};
  const svg=svgNode('svg',{viewBox:'0 0 24 24',fill:'none',stroke:'currentColor','stroke-width':1.7,'stroke-linecap':'round','stroke-linejoin':'round','aria-hidden':'true'});
  svg.append(svgNode('path',{d:paths[name]||paths.overview}));return svg;
}
function shell() {
  if (!current) return authScreen();
  const items=current.role==='superadmin'?[['overview','总览看板'],['users','用户管理'],['usage','调用明细'],['audit','操作日志']]:[];
  if(current.role==='superadmin')items.push(['plans','套餐管理'],['orders','订阅订单'],['services','邮件与支付']);
  items.push(['myUsage','我的用量'],['subscription','额度与订阅'],['model','个人模型'],['password','账号安全']);
  const nav=el('nav',{class:'nav',ariaLabel:'工作空间导航'},el('p',{class:'nav-label'},current.role==='superadmin'?'管理工作空间':'个人工作空间'),items.map(([id,title])=>{
    const control=button([el('span',{class:'nav-icon'},icon(id)),el('span',{},title)],()=>{page=id;shell();},page===id?'selected':'');
    if(page===id)control.setAttribute('aria-current','page');return control;
  }));
  const side=el('aside',{class:'sidebar'},el('div',{class:'brand'},el('span',{class:'brand-icon'},icon('scan')),el('div',{},'识题助手',el('small',{},'READER WORKSPACE'))),nav,el('div',{class:'sidebar-footer'},el('div',{class:'side-account'},el('span',{class:'avatar'},current.username.slice(0,1).toUpperCase()),el('div',{},el('strong',{},current.username),el('small',{},current.role==='superadmin'?'超级管理员':'普通用户'))),button('退出登录',logout)));
  const main=el('main',{id:'content'});
  const top=el('div',{class:'topbar'},el('div',{class:'breadcrumb'},'工作空间',el('span',{},'/'),el('strong',{},items.find(item=>item[0]===page)?.[1]||'控制台')),el('div',{class:'profile'},el('span',{class:'connection-dot'}),'已登录',el('span',{class:'avatar'},current.username.slice(0,1).toUpperCase()),el('strong',{},current.username)));
  root.replaceChildren(el('div',{class:'shell'},side,el('div',{class:'workspace'},top,main)));
  renderPage().catch(error=>{ if(current) main.replaceChildren(el('div',{class:'panel error-text'},error.message,button('重试',shell))); });
}
function heading(title, subtitle, period=false) {
  const tools=el('div',{class:'tools'});
  if(period) { const select=el('select',{},...[7,30,90].map(n=>el('option',{value:n,selected:n===days},`最近 ${n} 天`))); select.onchange=()=>{days=Number(select.value);shell();};tools.append(select); }
  tools.append(button('刷新',shell),button('退出',logout,'mobile-logout'));
  return el('header',{class:'page-header'},el('div',{},el('h1',{},title),el('p',{},subtitle)),tools);
}
function metric(label,value,note) { return el('div',{class:'metric'},el('span',{class:'label'},label),el('strong',{},value),el('small',{},note)); }
function metrics(value, users) {
  const data=value.summary, completed=data.success+data.failed;
  const cards=[metric('调用次数',fmt(data.requests),'含连通测试与失败调用'),metric('调用成功率',completed?(100*data.success/completed).toFixed(1)+'%':'—','成功 '+fmt(data.success)+' / 失败 '+fmt(data.failed)),metric('解答题目',fmt(data.questions),'以已返回的完整题目计数'),metric('Token 用量',fmt(data.input_tokens+data.output_tokens),'输入 '+fmt(data.input_tokens)+' / 输出 '+fmt(data.output_tokens))];
  if(users) cards.push(metric('注册用户',fmt(users.total),'已启用 '+fmt(users.active)),metric('待审核账号',fmt(users.pending),'在用户管理中处理注册申请'));
  else cards.push(metric('今日调用',fmt(value.today),'每日额度 '+fmt(value.daily_quota)),metric('平均耗时',(data.avg_duration_ms/1000).toFixed(1)+' s','模型请求与结果处理'));
  if(users&&value.payments)cards.push(metric('订阅实收',money(value.payments.revenue_fen),'已支付 '+fmt(value.payments.paid)+' 笔'),metric('订阅订单',fmt(value.payments.orders),'本周期创建的订单'));
  return el('div',{class:'grid'},cards);
}
function overviewAside(value,admin) {
  const data=value.summary;
  const tasks=admin?[['待审核账号',fmt(value.users.pending),'users'],['订阅订单',fmt(value.payments?.orders),'orders'],['服务设置','邮件与支付','services']]:[['今日调用',fmt(value.today),'myUsage'],['基础日额度',fmt(value.daily_quota),'subscription'],['个人模型','查看配置','model']];
  const status=el('section',{class:'activity-card'},el('p',{class:'eyebrow'},'WORKSPACE'),el('h2',{},admin?'管理概览':'我的工作空间'),el('p',{},current.username+' · '+(admin?'超级管理员':'个人账号')),
    el('div',{class:'activity-number'},fmt(data.processing)),el('span',{},'当前统计范围内的处理中调用'));
  const shortcuts=panel(admin?'待办与快捷入口':'快捷入口',...tasks.map(([title,number,id])=>button([el('span',{},title,el('small',{},number)),el('span',{class:'shortcut-arrow'},'›')],()=>{page=id;shell();},'shortcut')));
  const total=Math.max(1,data.requests);
  const health=panel('调用状态',...[['成功',data.success,'success'],['失败',data.failed,'failed'],['处理中',data.processing,'processing']].map(([name,count,state])=>el('div',{class:'state-summary'},el('div',{},tag(state),el('strong',{},fmt(count))),el('progress',{max:total,value:count,'aria-label':name+'调用数量'}))),el('p',{class:'hint'},'按所选时间范围统计，不代表模型服务的实时可用率。'));
  return el('aside',{class:'overview-aside'},status,shortcuts,health);
}
function table(headers, rows) {
  return el('div',{class:'table-wrap'},el('table',{},el('thead',{},el('tr',{},headers.map(value=>el('th',{},value)))),el('tbody',{},rows.length?rows:el('tr',{},el('td',{colSpan:headers.length,class:'empty'},'暂无记录')))));
}
function tag(value) { return el('span',{class:'tag '+value},statuses[value]||value); }
function panel(title,...children) { return el('section',{class:'panel'},el('h2',{},title),children); }
function pager(total, offset, change) { return el('div',{class:'pager'},`共 ${fmt(total)} 条`,el('div',{class:'tools'},el('button',{disabled:offset===0,onClick:()=>change(Math.max(0,offset-30))},'上一页'),el('button',{disabled:offset+30>=total,onClick:()=>change(offset+30)},'下一页'))); }
function chart(value) {
  const map=new Map(value.daily.map(item=>[item.day,item])); const maximum=Math.max(1,...value.daily.map(item=>item.requests));
  const chartDays=Math.min(value.days,14), rows=[];
  const svg=svgNode('svg',{viewBox:'0 0 660 255',class:'bar-chart',role:'img','aria-label':'每日调用次数柱状图'});
  for(let tick=0;tick<4;tick++){const y=25+tick*60;svg.append(svgNode('line',{x1:38,y1:y,x2:645,y2:y,class:'chart-guide'}),svgNode('text',{x:29,y:y+4,'text-anchor':'end',class:'chart-axis'},fmt(Math.round(maximum*(3-tick)/3))));}
  for(let i=chartDays-1;i>=0;i--) {
    const key=new Date(Date.now()+8*3600000-i*86400000).toISOString().slice(0,10), number=map.get(key)?.requests||0;
    const cell=600/chartDays,x=40+(chartDays-1-i)*cell+cell*.3,height=number/maximum*180;
    const rect=svgNode('rect',{x,y:205-height,width:Math.min(28,cell*.4),height,rx:4,class:'chart-bar',tabindex:'0'});rect.append(svgNode('title',{},`${key}：${fmt(number)} 次`));
    svg.append(rect,svgNode('text',{x:x+Math.min(28,cell*.4)/2,y:230,'text-anchor':'middle',class:'chart-axis'},key.slice(5)));
    rows.push(`${key}：${number} 次`);
  }
  return panel('每日调用趋势',svg,el('details',{class:'hint'},el('summary',{},'查看每日数据'),el('p',{},rows.join('；'))),el('p',{class:'hint'},'按北京时间统计；长周期展示最近 14 天趋势。'));
}
async function renderPage() {
  const main=document.querySelector('#content'); main.replaceChildren(el('div',{class:'loading'},'正在加载…'));
  if(page==='overview'||page==='myUsage') {
    const admin=page==='overview', value=await api((admin?'/v1/admin/overview':'/v1/me/usage')+'?days='+days);
    const content=[heading(admin?'总览看板':'我的用量',admin?'账号、调用与订阅数据，一目了然。':'查看当前账号的调用、解答题数与模型用量。',true)];
    let rank;
    if(admin) {
      const rows=value.ranking.map(row=>el('tr',{},el('td',{},row.username),el('td',{},fmt(row.requests)),el('td',{},fmt(row.questions)),el('td',{},fmt(row.tokens))));
      rank=panel('用户调用排行',table(['用户','调用','题数','Token'],rows));
    } else rank=panel('用量说明',el('p',{class:'hint'},'Token 以模型接口报告的数据为准。接口未报告的用量按 0 展示，不表示免费；实际费用请以模型服务商账单为准。每日调用额度包含测试、失败和处理中请求。'));
    content.push(el('div',{class:'overview-layout'},el('div',{class:'overview-main'},metrics(value,value.users),chart(value),rank),overviewAside(value,admin))); main.replaceChildren(...content); return;
  }
  if(page==='users') {
    const value=await api('/v1/admin/users?offset='+userOffset+'&q='+encodeURIComponent(query)+'&status='+encodeURIComponent(statusFilter));
    const search=input('search','搜索用户名',query); search.required=false;
    const filter=el('select',{},el('option',{value:''},'全部状态'),...['pending','active','disabled','rejected'].map(id=>el('option',{value:id,selected:id===statusFilter},statuses[id])));
    const run=()=>{query=search.value;statusFilter=filter.value;userOffset=0;shell();};search.onkeydown=event=>{if(event.key==='Enter')run();};filter.onchange=run;
    const rows=value.items.map(user=>{
      const actions=[];
      if(user.id!==current.id) {
        actions.push(button(user.status==='active'?'停用':user.status==='pending'?'通过审核':'启用',async()=>{await confirmChange(user,user.status==='active'?'disabled':'active');}));
        if(user.status==='pending')actions.push(button('不通过',()=>confirmChange(user,'rejected'),'danger'));
      }
      actions.push(button('管理',()=>editUser(user)));
      return el('tr',{},el('td',{},user.username,el('small',{},'ID '+user.id)),el('td',{},user.role==='superadmin'?'超级管理员':'普通用户'),el('td',{},tag(user.status)),el('td',{},fmt(user.daily_quota)+' 次/日'),el('td',{},fmt(user.requests)+' 次',el('small',{},fmt(user.questions)+' 题 · '+fmt(user.tokens)+' token')),el('td',{},date(user.last_login)),el('td',{},actions));
    });
    main.replaceChildren(heading('用户管理','审核注册申请、管理账号权限与每日调用额度。'),el('section',{class:'panel'},el('div',{class:'filters'},search,filter,button('搜索',run)),table(['账号','角色','状态','调用额度','累计使用','最近登录','操作'],rows),pager(value.total,userOffset,n=>{userOffset=n;shell();})));return;
  }
  if(page==='usage') {
    const value=await api('/v1/admin/usage?offset='+usageOffset+(usageUser?'&user_id='+encodeURIComponent(usageUser):''));
    const filter=input('number','用户 ID，留空查看全部',usageUser);filter.required=false;filter.min='1';
    const rows=value.items.map(row=>el('tr',{},el('td',{},date(row.created_at)),el('td',{},row.username),el('td',{},tag(row.status)),el('td',{},row.model),el('td',{},fmt(row.question_count)),el('td',{},fmt(row.input_tokens)+' / '+fmt(row.output_tokens)),el('td',{},row.billing_unit?fmt(row.billing_charged)+' '+unitNames[row.billing_unit]+(row.billing_settled?'':'（结算中）'):'基础额度',row.billing_unit?el('small',{},'实际 '+fmt(row.billing_actual)+' · 免扣 '+fmt(Math.max(0,row.billing_actual-row.billing_charged))):null),el('td',{},(row.duration_ms/1000).toFixed(1)+' s'),el('td',{},row.error_code||'—')));
    main.replaceChildren(heading('调用明细','只记录调用元数据，不保存题目正文、图片或完整密钥。'),el('section',{class:'panel'},el('div',{class:'filters'},filter,button('筛选',()=>{usageUser=filter.value;usageOffset=0;shell();})),table(['时间','用户','状态','模型','题数','输入 / 输出 token','订阅扣减','耗时','错误码'],rows),pager(value.total,usageOffset,n=>{usageOffset=n;shell();})),el('p',{class:'hint'},'Token 以接口报告值为准，实际计费请以服务商账单为准。'));return;
  }
  if(page==='audit') {
    const value=await api('/v1/admin/audit?offset='+auditOffset);
    const names={register_pending:'提交注册申请',login:'登录',logout:'退出登录',change_password:'修改密码',update_model:'修改个人模型配置',delete_model:'删除个人模型配置',reset_password:'重置用户密码'};
    const rows=value.items.map(row=>el('tr',{},el('td',{},date(row.created_at)),el('td',{},row.actor||'注册申请'),el('td',{},row.target||'—'),el('td',{},row.action.startsWith('update_user:')?'修改账号：'+row.action.slice(12):(names[row.action]||row.action))));
    main.replaceChildren(heading('操作日志','记录账号与配置管理操作，敏感值不会进入日志。'),el('section',{class:'panel'},table(['时间','操作人','目标用户','操作'],rows),pager(value.total,auditOffset,n=>{auditOffset=n;shell();})));return;
  }
  if(page==='model') return modelPage(main);
  if(page==='subscription') return subscriptionPage(main);
  if(page==='plans') return plansPage(main);
  if(page==='orders') return ordersPage(main,true);
  if(page==='services') return servicesPage(main);
  if(page==='password') return passwordPage(false,main);
}
function confirmChange(user,status) {
  const dialog=modal('确认'+statuses[status]+'？',[el('p',{},`账号：${user.username}`),el('p',{class:'hint'},status==='active'?'通过后用户可以登录，并使用自己的模型配置。':'此操作将撤销该账号已有登录状态。')],[button('确认',async()=>{await api('/v1/admin/users/'+user.id,'PATCH',{status});dialog.close();toast('账号状态已更新');shell();},'primary')]);
}
function editUser(user) {
  const quota=input('number','每日次数',user.daily_quota);quota.min='0';quota.max='100000';
  const role=el('select',{disabled:user.id===current.id},el('option',{value:'user',selected:user.role==='user'},'普通用户'),el('option',{value:'superadmin',selected:user.role==='superadmin'},'超级管理员'));
  const children=[field('每日基础调用额度（0 表示仅使用订阅额度）',quota),field('账号角色',role),el('p',{class:'hint'},'要禁止所有调用请停用账号。角色变更会撤销该账号的已有登录。超级管理员可以管理所有用户，请谨慎授权。')];
  const actions=[button('保存',async()=>{const data={daily_quota:Number(quota.value)};if(role.value!==user.role)data.role=role.value;await api('/v1/admin/users/'+user.id,'PATCH',data);dialog.close();toast('用户设置已保存');shell();},'primary')];
  if(user.id!==current.id)actions.unshift(button('重置密码',async()=>{
    if(!window.confirm(`确定为 ${user.username} 重置密码？已有登录将失效。`))return;
    const value=await api('/v1/admin/users/'+user.id+'/reset-password','POST');
    modal('临时密码',[el('p',{},value.message),el('div',{class:'secret'},value.temporary_password)],[button('复制',async()=>{await navigator.clipboard.writeText(value.temporary_password);toast('已复制临时密码');})]);
  },'danger'));
  const dialog=modal('管理 '+user.username,children,actions);
}
async function modelPage(main) {
  const value=await api('/v1/me/model');
  const base=input('url','https://服务商域名/兼容接口路径',value.base_url||'');
  const model=input('text','例如 qwen3.8-flash',value.model||'');
  const key=input('password',value.has_key?'已配置，留空保留原密钥':'填写自己的 API Key');key.required=!value.has_key;key.autocomplete='off';key.maxLength=4096;
  const message=el('p',{class:'status-line'}), submit=el('button',{type:'submit',class:'primary'},'保存个人配置');
  const form=el('form',{onSubmit:async event=>{event.preventDefault();submit.disabled=true;try{await api('/v1/me/model','PUT',{base_url:base.value,model:model.value,api_key:key.value||null});key.value='';key.placeholder='已配置，留空保留原密钥';key.required=false;message.textContent='个人模型配置已保存';toast('配置已保存');}catch(error){message.textContent=error.message;}finally{submit.disabled=false;}}},field('OpenAI 兼容接口 Base URL',base),field('支持图片输入的模型名称',model),field('个人 API Key',key),el('div',{class:'notice'},'配置只属于当前账号。密钥加密保存，不会回传完整值。请填写公共 HTTPS 接口地址；服务会追加 /chat/completions。'),el('div',{class:'button-row'},submit,button('测试已保存配置',async()=>{message.textContent='正在测试…';const result=await api('/v1/me/model/test','POST');message.textContent=result.message;toast(result.message);}),button('移除配置',async()=>{if(window.confirm('确定删除当前账号的模型配置和密钥？')){await api('/v1/me/model','DELETE');toast('配置已移除');shell();}},'danger')),message,el('p',{class:'hint'},'连通测试会发送一条短文本请求并计入用量。识题要求模型支持视觉输入和 JSON 输出。'));
  main.replaceChildren(heading('个人模型','使用你自己的模型服务、模型名称与 API Key。'),el('section',{class:'panel narrow'},form));
}
function passwordPage(forced=false, target) {
  const old=input('password','当前密码'), next=input('password','至少 10 位，包含字母和数字'), repeat=input('password','再次输入新密码');old.autocomplete='current-password';next.autocomplete=repeat.autocomplete='new-password';
  const message=el('p',{class:'error-text',role:'alert'}), submit=el('button',{type:'submit',class:'primary'},'修改密码');
  const form=el('form',{onSubmit:async event=>{event.preventDefault();if(next.value!==repeat.value){message.textContent='两次输入的新密码不一致';return;}submit.disabled=true;try{const value=await api('/v1/auth/password','POST',{old_password:old.value,new_password:next.value});old.value=next.value=repeat.value='';current=value.user;csrfToken=value.csrf_token;toast('密码已修改，其他登录状态已撤销');page=current.role==='superadmin'?'overview':'myUsage';shell();}catch(error){message.textContent=error.message;}finally{submit.disabled=false;}}},field('当前密码',old),field('新密码',next),field('确认新密码',repeat),message,submit);
  if(forced)root.replaceChildren(el('div',{class:'auth'},el('section',{class:'auth-intro'},el('div',{class:'brand-icon'},'识'),el('h1',{},'先设置你的新密码。'),el('p',{},'当前使用初始密码或管理员重置的临时密码。修改后即可继续使用。')),el('section',{class:'auth-card'},el('h2',{},'修改初始密码'),el('p',{class:'hint'},'当前账号：'+current.username),form,button('退出登录',logout,'link'))));
  else target.replaceChildren(heading('账号安全','修改密码会撤销其他设备的登录状态。'),emailBindingPanel(),el('section',{class:'panel narrow'},el('h2',{},'修改密码'),form));
}
function codeButton(email,purpose,message) {
  const control=button('获取验证码',async()=>{
    control.disabled=true;
    try {const value=await api('/v1/auth/email-code','POST',{email:email.value,purpose});message.textContent=value.message;let seconds=60;control.textContent=seconds+' 秒后重发';const timer=setInterval(()=>{seconds--;control.textContent=seconds?seconds+' 秒后重发':'获取验证码';if(!seconds){clearInterval(timer);control.disabled=false;}},1000);}
    catch(error){control.disabled=false;message.textContent=error.message;}
  });return control;
}
function emailScreen(mode) {
  const reset=mode==='reset',email=input('email','你的已验证邮箱'),code=input('text','6 位验证码'),password=input('password','至少 10 位，包含字母和数字'),repeat=input('password','再次输入新密码');
  code.maxLength=6;code.autocomplete='one-time-code';password.autocomplete=repeat.autocomplete='new-password';
  const message=el('p',{class:'error-text',role:'alert'}),submit=el('button',{type:'submit',class:'primary'},reset?'重置密码':'验证码登录');
  const form=el('form',{onSubmit:async event=>{event.preventDefault();if(reset&&password.value!==repeat.value){message.textContent='两次输入的新密码不一致';return;}submit.disabled=true;try{const value=await api('/v1/auth/email-'+(reset?'reset':'login'),'POST',{email:email.value,code:code.value,...(reset?{password:password.value}:{})});if(reset){authScreen();toast(value.message);}else{current=value.user;csrfToken=value.csrf_token;page=current.role==='superadmin'?'overview':'myUsage';if(current.must_change_password)passwordPage(true);else shell();}}catch(error){message.textContent=error.message;}finally{submit.disabled=false;}}},el('p',{class:'eyebrow'},'ACCOUNT ACCESS'),el('h2',{},reset?'找回密码':'邮箱验证码登录'),el('p',{class:'hint'},'验证码 10 分钟内有效。尚未绑定邮箱的旧账号，请先使用用户名和密码登录，在账号安全中绑定。'),field('邮箱',email),field('验证码',el('div',{class:'code-field'},code,codeButton(email,mode,message))),...(reset?[field('新密码',password),field('确认新密码',repeat)]:[]),message,submit,button('返回密码登录',()=>authScreen(),'link'));
  root.replaceChildren(el('div',{class:'auth'},el('section',{class:'auth-intro'},el('div',{class:'brand-icon'},'识'),el('p',{class:'eyebrow'},'QUESTION READER'),el('h1',{},'安全连接，\n轻松找回答案。'),el('p',{},'邮箱验证保护你的账号。识题、用量与订阅，都在同一个工作空间。')),el('section',{class:'auth-card'},form)));
}
function emailBindingPanel() {
  if(current.email)return panel('验证邮箱',el('p',{},current.email),el('p',{class:'hint'},'该邮箱可用于验证码登录和密码找回。更换邮箱请联系管理员核验身份。'));
  const email=input('email','填写要绑定的邮箱'),code=input('text','6 位验证码'),message=el('p',{class:'status-line'});code.maxLength=6;
  return panel('绑定邮箱',el('p',{class:'hint'},'绑定后可使用验证码登录与密码找回。'),field('邮箱',email),field('验证码',el('div',{class:'code-field'},code,codeButton(email,'bind',message))),button('验证并绑定',async()=>{const value=await api('/v1/me/email','POST',{email:email.value,code:code.value});current=(await api('/v1/me')).user;toast(value.message);shell();},'primary'),message);
}
const unitNames={requests:'调用次数',questions:'题目数量',tokens:'Token'};
const money=n=>'¥'+(Number(n)/100).toFixed(2);
const paymentNames={alipay:'支付宝',wechat:'微信支付'};
const orderNames={pending:'待付款 / 待核对',paid:'已支付'};
let orderOffset=0;
function checkField(title,checked=false) {const control=el('input',{type:'checkbox',checked});return [el('label',{class:'check-field'},control,title),control];}
async function subscriptionPage(main) {
  const [value,plans]=await Promise.all([api('/v1/me/subscription'),api('/v1/plans')]);
  const cards=Object.entries(value.balances).map(([unit,balance])=>metric(unitNames[unit]+'余额',fmt(balance),'有效期内的剩余额度'));
  const planCards=plans.items.map(plan=>{
    const provider=el('select',{},...plans.providers.map(id=>el('option',{value:id},paymentNames[id]))),[check,accepted]=checkField('已阅读并同意下方额度规则');
    const buy=button(plans.providers.length?'创建订单并付款':'支付尚未开通',async()=>{
      if(!accepted.checked){toast('请先阅读并同意额度规则');return;}
      buy.disabled=true;try{const order=await api('/v1/me/orders','POST',{plan_id:plan.id,provider:provider.value,accept_rules:true});showOrder(order.id);}finally{buy.disabled=false;}
    },'primary');buy.disabled=!plans.providers.length;
    return el('section',{class:'panel plan-card'},el('span',{class:'tag'},unitNames[plan.unit]),el('h2',{},plan.name),el('strong',{class:'plan-price'},money(plan.price_fen)),el('p',{},fmt(plan.credits)+' '+unitNames[plan.unit]),el('p',{class:'hint'},'付款到账起 '+plan.duration_days+' 天有效 · 主动续期'),provider,check,buy);
  });
  const grantRows=value.grants.map(row=>el('tr',{},el('td',{},row.plan_name),el('td',{},unitNames[row.unit]),el('td',{},fmt(row.remaining)+' / '+fmt(row.total)),el('td',{},date(row.expires_at)),el('td',{},row.expires_at*1000>Date.now()?'有效':'已到期')));
  main.replaceChildren(heading('额度与订阅','按需购买，主动续期。你的个人模型费用由模型服务商另行计费。'),el('div',{class:'grid'},cards),...(!plans.providers.length?[el('div',{class:'notice'},'当前尚未开通收款渠道。管理员配置商户参数并启用后，才可以创建付款订单。')]:[]),el('div',{class:'plan-grid'},planCards.length?planCards:el('div',{class:'panel empty'},'暂无上架套餐，请联系管理员。')),panel('额度规则',el('p',{class:'hint'},value.rules),el('p',{class:'hint'},'每日基础调用额度：'+fmt(value.daily_quota)+' 次；同画面多题只占一次基础调用。')),panel('我的套餐',table(['套餐','单位','剩余 / 总额','到期时间','状态'],grantRows)),button('查看我的订单',()=>ordersPage(main,false)));
}
async function plansPage(main) {
  const value=await api('/v1/admin/plans');
  const rows=value.items.map(plan=>el('tr',{},el('td',{},plan.name),el('td',{},money(plan.price_fen)),el('td',{},fmt(plan.credits)+' '+unitNames[plan.unit]),el('td',{},plan.duration_days+' 天'),el('td',{},plan.enabled?'已上架':'未上架'),el('td',{},button('编辑套餐',()=>editPlan(plan)))));
  main.replaceChildren(heading('套餐管理','配置三种计费方式。价格、额度与有效期在下单时固定。'),el('div',{class:'button-row'},button('＋ 新建套餐',()=>editPlan(),'primary')),panel('订阅套餐',table(['名称','价格','额度','有效期','状态','操作'],rows)),el('div',{class:'notice'},'每日基础额度优先。若希望用户从首次调用就使用套餐，请在用户管理中将该用户的基础额度设为 0。停用账号仍会禁止调用。'));
}
function editPlan(plan) {
  const name=input('text','例如：月度题目包',plan?.name||''),price=input('number','单位：元',plan?(plan.price_fen/100).toFixed(2):''),credits=input('number','额度',plan?.credits||''),duration=input('number','有效天数',plan?.duration_days||30);
  price.min='.01';price.max='100000';price.step='.01';credits.min=1;credits.max=1000000000;duration.min=1;duration.max=3660;
  const unit=el('select',{},...Object.entries(unitNames).map(([id,title])=>el('option',{value:id,selected:id===(plan?.unit||'requests')},title))),[check,enabled]=checkField('上架套餐',!!plan?.enabled);
  const error=el('p',{class:'error-text'});
  const form=el('form',{onSubmit:async event=>{event.preventDefault();const submit=event.submitter;submit.disabled=true;try{await api('/v1/admin/plans'+(plan?'/'+plan.id:''),plan?'PUT':'POST',{name:name.value,price_fen:Math.round(Number(price.value)*100),credits:Number(credits.value),duration_days:Number(duration.value),unit:unit.value,enabled:enabled.checked});dialog.close();toast('套餐已保存');shell();}catch(e){error.textContent=e.message;}finally{submit.disabled=false;}}},field('套餐名称',name),field('价格（人民币元）',price),field('计费单位',unit),field('套餐额度',credits),field('有效天数（从支付到账起）',duration),check,error,el('button',{type:'submit',class:'primary'},'保存套餐'));
  const dialog=modal(plan?'编辑套餐':'新建套餐',[form,el('p',{class:'hint'},'重复购买各自独立计时。上架前请检查金额与额度；现有订单不会被修改。')]);
}
async function ordersPage(main,admin=false) {
  const value=await api((admin?'/v1/admin':'/v1/me')+'/orders?offset='+orderOffset);
  const rows=value.items.map(row=>el('tr',{},el('td',{},row.id,el('small',{},date(row.created_at))),...(admin?[el('td',{},row.username)]:[]),el('td',{},row.plan_name,el('small',{},fmt(row.credits)+' '+unitNames[row.unit]+' · '+row.duration_days+' 天')),el('td',{},money(row.price_fen)),el('td',{},paymentNames[row.provider]),el('td',{},el('span',{class:'tag '+(row.status==='paid'?'success':'pending')},orderNames[row.status]||row.status)),el('td',{},row.paid_at?date(row.paid_at):'—'),...(!admin?[el('td',{},row.status==='pending'?button('付款 / 核对',()=>showOrder(row.id)): '额度已入账')]:[])));
  main.replaceChildren(heading(admin?'订阅订单':'我的订单','只有经过服务端签名验证的支付结果才会增加额度。'),panel('订单记录',table(['订单',...(admin?['用户']:[]),'套餐','金额','渠道','状态','支付时间',...(!admin?['操作']:[])],rows),pager(value.total,orderOffset,n=>{orderOffset=n;ordersPage(main,admin).catch(e=>toast(e.message));})),...(!admin?[button('返回套餐',()=>subscriptionPage(main))]:[]));
}
function showOrder(id) {
  const status=el('p',{class:'status-line'},'请用对应支付 App 扫码。若二维码过期，请先核对订单，确认未付款后再新建。');
  modal('订单支付',[el('p',{class:'hint'},id),el('img',{class:'payment-qr',src:'/v1/me/orders/'+encodeURIComponent(id)+'/qr',alt:'支付二维码；若未显示或已过期，请核对订单状态'}),status],[button('我已付款，核对状态',async()=>{const value=await api('/v1/me/orders/'+encodeURIComponent(id)+'/refresh','POST');status.textContent=value.message;toast(value.message);},'primary')]);
}
async function servicesPage(main) {
  const [smtp,payment]=await Promise.all([api('/v1/admin/smtp'),api('/v1/admin/payment')]);
  const [smtpSwitch,smtpEnabled]=checkField('启用邮箱验证码',!!smtp.enabled),host=input('text','例如 smtp.qq.com',smtp.host||''),port=input('number','465 / 587',smtp.port||465),sender=input('email','发件邮箱',smtp.sender||''),username=input('text','SMTP 登录账号',smtp.username||''),password=input('password',smtp.has_password?'已配置，留空保留':'邮箱授权码 / SMTP 密码'),security=el('select',{},...['ssl','starttls'].map(id=>el('option',{value:id,selected:id===(smtp.security||'ssl')},id==='ssl'?'SSL / TLS':'STARTTLS')));
  [host,port,sender,username,password].forEach(item=>item.required=false);password.autocomplete='off';
  const mailStatus=el('p',{class:'status-line'}),testEmail=input('email','接收测试邮件的邮箱');
  const mailForm=el('form',{onSubmit:async e=>{e.preventDefault();const value=await api('/v1/admin/smtp','PUT',{enabled:smtpEnabled.checked,host:host.value,port:Number(port.value),security:security.value,sender:sender.value,username:username.value,password:password.value||null}).catch(error=>{mailStatus.textContent=error.message;});if(value){password.value='';mailStatus.textContent=value.message;toast(value.message);}}},smtpSwitch,el('div',{class:'form-grid'},field('SMTP 主机',host),field('端口',port),field('加密方式',security),field('发件邮箱',sender)),field('SMTP 用户名',username),field('授权码（加密保存）',password),el('button',{type:'submit',class:'primary'},'保存邮件配置'),mailStatus,field('发送测试邮件',el('div',{class:'code-field'},testEmail,button('发送测试',async()=>{const value=await api('/v1/admin/smtp/test','POST',{email:testEmail.value});toast(value.message);}))),el('p',{class:'hint'},'先保存配置再发送测试。未启用时，注册、验证码登录与找回密码不可用；已有账号仍可使用密码登录。'));
  const publicUrl=input('url','https://你的公网域名',payment.public_base_url||'');publicUrl.required=false;
  const channels={};
  const channelForms=['alipay','wechat'].map(provider=>{
    const s=payment[provider]||{},[enabledBox,enabled]=checkField('启用'+paymentNames[provider],!!s.enabled),fields={};channels[provider]={enabled,fields};
    const labels={app_id:'APPID',merchant_id:provider==='alipay'?'支付宝收款账号 PID（seller_id）':'微信商户号',private_key:'商户 RSA 私钥（PEM）',public_key:provider==='alipay'?'支付宝公钥（PEM）':'微信支付公钥（PEM）',serial_no:'商户 API 证书序列号',public_key_id:'微信支付公钥 ID',api_v3_key:'APIv3 密钥（32 字节）'};
    return el('section',{class:'channel'},el('h3',{},paymentNames[provider]+' · 扫码付款'),enabledBox,...Object.entries(labels).filter(([id])=>provider==='wechat'||!['serial_no','public_key_id','api_v3_key'].includes(id)).map(([id,label])=>{
      const secret=['private_key','api_v3_key'].includes(id);
      const control=id.endsWith('key')&&id!=='api_v3_key'?el('textarea',{rows:4,placeholder:secret&&s['has_'+id]?'已配置，留空保留':'PEM 格式，包含 BEGIN / END',value:secret?'':s[id]||''}):input(secret?'password':'text',secret&&s['has_'+id]?'已配置，留空保留':label,secret?'':s[id]||'');
      control.required=false;control.autocomplete='off';fields[id]=control;return field(label,control);
    }));
  });
  const payStatus=el('p',{class:'status-line'});
  const payForm=el('form',{onSubmit:async e=>{e.preventDefault();const body={public_base_url:publicUrl.value};for(const [id,{enabled,fields}]of Object.entries(channels)){body[id]={enabled:enabled.checked};for(const [key,control]of Object.entries(fields))body[id][key]=control.value;}
    try{const value=await api('/v1/admin/payment','PUT',body);for(const {fields}of Object.values(channels))for(const key of ['private_key','api_v3_key'])if(fields[key])fields[key].value='';payStatus.textContent=value.message;toast(value.message);}catch(error){payStatus.textContent=error.message;}
  }},field('公网 HTTPS 根地址（用于支付回调）',publicUrl),el('div',{class:'notice'},'没有商户账号时请保持关闭。临时穿透地址变化后需更新此处，旧订单回调可能失效，可从我的订单主动核对。当前支持用户扫码主动续期，无自动扣款。退款需到商户平台处理并联系运营核对额度。'),...channelForms,el('button',{type:'submit',class:'primary'},'保存支付配置'),payStatus);
  main.replaceChildren(heading('邮件与支付','配置服务后才启用真实发送和收款，密钥不会完整回显。'),el('div',{class:'split settings-split'},panel('邮件服务',mailForm),panel('支付商户',payForm)));
}
(async()=>{try{const value=await api('/v1/me');current=value.user;csrfToken=value.csrf_token;if(current.must_change_password)passwordPage(true);else{page=location.hash==='#subscription'?'subscription':current.role==='superadmin'?'overview':'myUsage';shell();}}catch(error){authScreen();}})();
