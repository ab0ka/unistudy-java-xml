'use strict';
const root = document.getElementById('app');
let session = null, controller = new AbortController(), epoch = 0, refreshPromise = null;
let subjects = [], groups = [], currentRoute = 'home', currentData;
const urls = new Set();
try { session = JSON.parse(sessionStorage.getItem('unistudy.session')); } catch (_) { /* Private browsing may disable storage. */ }
const kinds = {lecture:'Дәріс',lab:'Зертханалық',sro:'СӨЖ',note:'Конспект',guide:'Нұсқаулық',exam:'Емтихан'};
const roles = {student:'Студент',teacher:'Оқытушы',admin:'Әкімші'};
const kazakhMonths=['қаңтар','ақпан','наурыз','сәуір','мамыр','маусым','шілде','тамыз','қыркүйек','қазан','қараша','желтоқсан'];
const kazakhWeekdays=['Жексенбі','Дүйсенбі','Сейсенбі','Сәрсенбі','Бейсенбі','Жұма','Сенбі'];
const staff = () => session && session.user.role !== 'student';
function el(tag, cls, text) { const e = document.createElement(tag); if (cls) e.className = cls; if (text !== undefined) e.textContent = text; return e; }
function add(parent, ...children) { children.filter(Boolean).forEach(c => parent.append(c)); return parent; }
function button(text, fn, cls = '') { const b = el('button', cls, text); b.type = 'button'; b.addEventListener('click', fn); return b; }
function note(parent) { const n = el('div','notice'); n.setAttribute('role','status'); n.setAttribute('aria-live','polite'); parent.append(n); return n; }
function message(n, text, error = false) { n.className = 'notice' + (error ? ' error' : ''); n.textContent = text; }
function field(parent, text, name, type = 'text', value = '', required = true) { const l=el('label','',text), i=el(type==='textarea'?'textarea':'input'); i.name=name; if(type!=='textarea') i.type=type; i.value=value; i.required=required; if(type==='password') {i.maxLength=256;i.autocomplete=name==='current_password'?'current-password':'new-password';} if(type==='password'){
  const wrap=el('span','password-control'),toggle=button('',()=>{const start=i.selectionStart,end=i.selectionEnd,direction=i.selectionDirection;i.type=i.type==='password'?'text':'password';toggle.setAttribute('aria-pressed',String(i.type==='text'));toggle.setAttribute('aria-label',i.type==='text'?'Құпиясөзді жасыру':'Құпиясөзді көрсету');i.focus({preventScroll:true});i.setSelectionRange(start,end,direction);},'password-eye');
  toggle.append(icon('eye'));toggle.setAttribute('aria-label','Құпиясөзді көрсету');toggle.setAttribute('aria-pressed','false');toggle.addEventListener('pointerdown',e=>e.preventDefault());add(l,add(wrap,i,toggle));
}else l.append(i);parent.append(l);return i; }
function select(parent, label, options, value='') { const l=el('label','',label), s=el('select'); options.forEach(([v,t])=>{ const o=el('option','',t);o.value=v;s.append(o); });s.value=value;add(parent,add(l,s));return s; }
function submit(form, label, action) { const status=note(form), b=el('button','',label);b.type='submit';form.append(b);form.addEventListener('submit',async e=>{e.preventDefault();if(b.disabled)return;b.disabled=true;b.setAttribute('aria-busy','true');b.textContent='Орындалуда…';message(status,'');try{await action(status);}catch(err){if(err.name!=='AbortError')message(status,err.message,true);}finally{b.disabled=false;b.removeAttribute('aria-busy');b.textContent=label;}});return status; }
function saveSession(s) {session=s;try{s?sessionStorage.setItem('unistudy.session',JSON.stringify(s)):sessionStorage.removeItem('unistudy.session');}catch(_){} }
function cancelView() {controller.abort();controller=new AbortController();epoch++;urls.forEach(URL.revokeObjectURL);urls.clear();}
async function api(path, options={}) {
  const signal=options.signal || controller.signal, generation=epoch;
  const headers = new Headers(options.headers || {});
  if(session && !options.public)headers.set('Authorization','Bearer '+session.access_token);
  if(options.body && !(options.body instanceof FormData)){headers.set('Content-Type','application/json');options={...options,body:JSON.stringify(options.body)};}
  let response;
  try{response=await fetch('/api'+path,{...options,headers,signal,cache:'no-store'});}catch(e){if(e.name==='AbortError')throw e;throw new Error('Серверге қосылу мүмкін болмады. Интернетті тексеріп, қайта көріңіз.');}
  if(response.status===401 && session && !options.public && !options.retried){
    if(!refreshPromise){const previous=session;refreshPromise=(async()=>{const r=await fetch('/api/auth/refresh',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({refresh_token:previous.refresh_token}),cache:'no-store'});if(!r.ok)throw new Error('Сессия аяқталды. Қайта кіріңіз.');const s=await r.json();if(session===previous){saveSession(s);return s;}await fetch('/api/auth/logout',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({refresh_token:s.refresh_token}),cache:'no-store'});throw new DOMException('Aborted','AbortError');})().finally(()=>{refreshPromise=null;});}
    try{await refreshPromise;}catch(e){if(generation===epoch){saveSession(null);auth(false,e.message);}throw e;}
    if(signal.aborted || generation!==epoch)throw new DOMException('Aborted','AbortError');
    // The body is already serialized by the first attempt.
    const retry={...options,retried:true,signal}; if(typeof retry.body==='string')retry.body=JSON.parse(retry.body);
    return api(path,retry);
  }
  if(response.status===401 && session && options.retried){saveSession(null);auth(false,'Сессия аяқталды. Қайта кіріңіз.');throw new DOMException('Aborted','AbortError');}
  if(!response.ok){let data;try{data=await response.json();}catch(_){}throw new Error(data?.detail || 'Сұрау орындалмады ('+response.status+'). Қайта көріңіз.');}
  if(signal.aborted || generation!==epoch)throw new DOMException('Aborted','AbortError');
  let result;
  if(options.blob&&response.body){const reader=response.body.getReader(),chunks=[];let received=0;const total=Number(response.headers.get('content-length'));while(true){const {done,value}=await reader.read();if(done)break;chunks.push(value);received+=value.length;options.onProgress?.(received,total);}result=new Blob(chunks,{type:response.headers.get('content-type')||'application/octet-stream'});}else result=await (options.blob?response.blob():response.status===204?null:response.json());
  if(signal.aborted || generation!==epoch)throw new DOMException('Aborted','AbortError');
  return result;
}
function brand(){return add(el('div','brand'),el('span','brandmark','U'),el('span','','UniStudy'));}
function empty(parent,text){parent.append(el('div','empty',text));}
function heading(parent,title,subtitle){add(parent,add(el('header','pagehead'),el('h1','',title),subtitle?el('p','muted',subtitle):null));}
function dateKey(d){return d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0')+'-'+String(d.getDate()).padStart(2,'0');}
function prettyDate(s){if(!s)return '';const d=new Date(s.length===10?s+'T12:00:00':s);return isNaN(d)?s:d.getDate()+' '+kazakhMonths[d.getMonth()]+' '+d.getFullYear();}
function monthTitle(d){const name=kazakhMonths[d.getMonth()];return name[0].toUpperCase()+name.slice(1)+' '+d.getFullYear();}
function todayTitle(){const d=new Date();return kazakhWeekdays[d.getDay()]+', '+d.getDate()+' '+kazakhMonths[d.getMonth()];}
async function auth(register=false, notice='') {
  cancelView();root.replaceChildren();const outer=el('div','auth'), card=el('section','card authcard');add(root,add(outer,card));
  add(card,brand(),add(el('div','authintro'),el('div','eyebrow','Білімге бір қадам жақын'),el('h1','',register?'Жаңа аккаунт':'Қош келдіңіз'),el('p','muted',register?'Тіркелу өтінімін жіберіңіз.':'Оқуыңызға қажеттінің бәрі бір жерде.')));
  add(card,add(el('div','tabs'),button('Кіру',()=>auth(false),register?'':'active'),button('Тіркелу',()=>auth(true),register?'active':'')));
  if(notice)message(note(card),notice);
  const form=el('form');card.append(form);
  const full=register?field(form,'Аты-жөні','full_name'):null;if(full)full.maxLength=200;
  const username=field(form,'Логин','username');username.autocomplete='username';username.minLength=3;username.maxLength=80;username.autocapitalize='none';username.spellcheck=false;
  const pass=field(form,'Құпиясөз','password','password');pass.autocomplete=register?'new-password':'current-password';pass.minLength=register?8:1;
  let role,group;
  if(register){role=select(form,'Рөл',[['student','Студент'],['teacher','Оқытушы']],'student');group=select(form,'Топ',[['','Топ таңдалмаған']]);role.onchange=()=>{group.parentElement.hidden=role.value!=='student';};try{const rows=await api('/groups',{public:true});rows.forEach(g=>{const o=el('option','',g.name);o.value=g.id;group.append(o);});}catch(e){if(e.name==='AbortError')return;message(note(form),e.message,true);}}
  submit(form,register?'Өтінім жіберу':'Жүйеге кіру',async status=>{
    if(register){const result=await api('/auth/register',{method:'POST',public:true,body:{username:username.value,password:pass.value,full_name:full.value,role:role.value,group_id:role.value==='student'&&group.value?Number(group.value):null}});auth(false,result.message);}
    else{const s=await api('/auth/login',{method:'POST',public:true,body:{username:username.value,password:pass.value}});saveSession(s);await route('home');}
  });
  add(card,el('p','footer',register?'Өтінімді әкімші мақұлдағаннан кейін кіре аласыз.':'Студент пен оқытушыға арналған жеке кабинет'));
}
const icons={eye:'M2 12s3-7 10-7 10 7 10 7-3 7-10 7S2 12 2 12M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0',home:'M3 10 12 3l9 7M5 9v12h5v-7h4v7h5V9',materials:'M4 4h7l2 2h7v15H4zM8 11h8M8 15h6',tasks:'M8 4H5v17h14V4h-3M9 2h6v4H9zM8 11l2 2 5-5M8 17h7',schedule:'M4 5h16v16H4zM8 2v6M16 2v6M4 10h16',profile:'M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0M4 21v-2a8 8 0 0 1 16 0v2'};
function icon(name){const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');svg.setAttribute('viewBox','0 0 24 24');svg.setAttribute('aria-hidden','true');const p=document.createElementNS(svg.namespaceURI,'path');p.setAttribute('d',icons[name]);svg.append(p);return svg;}
function overflowMenu(){
  const menu=el('details','overflow-menu'), toggle=el('summary','overflow-toggle');
  toggle.setAttribute('aria-label','Мәзір');toggle.title='Мәзір';toggle.append(el('span','','⋮'));
  const panel=el('div','overflow-panel');panel.setAttribute('aria-label','Қосымша навигация');
  function item(label,action,cls=''){panel.append(button(label,()=>{menu.open=false;action();},cls));}
  item('Жаңарту',()=>route(currentRoute,currentData));
  if(['materials','tasks'].includes(currentRoute))item('Іздеу және сүзгілер',()=>document.querySelector('input[name=q]')?.focus());
  if(staff())item('Материал қосу',()=>route('editor'));
  item('Менің пәндерім',()=>route('subjects'));
  item('Материалдар',()=>route('materials'));
  item('Тапсырмалар',()=>route('tasks'));
  item(staff()?'Бағалар журналы':'Менің бағаларым',()=>route('grades'));
  item('Сабақ кестесі',()=>route('schedule'));
  if(staff())item('Топтар мен студенттер',()=>route('groups'));
  if(isAdmin())item('Басқару · аккаунттар',()=>route('admin'));
  item('Жеке кабинет',()=>route('profile'));
  item('Аккаунттан шығу',logout,'menu-signout');
  add(menu,toggle,panel);
  document.addEventListener('click',e=>{if(!menu.contains(e.target))menu.open=false;},{signal:controller.signal});
  document.addEventListener('keydown',e=>{if(e.key==='Escape'&&menu.open){menu.open=false;toggle.focus();}},{signal:controller.signal});
  return menu;
}
function shell(name){
  root.replaceChildren();const top=el('header','topbar'),avatar=button((session.user.full_name||'U').slice(0,1),()=>route('profile'),'avatar');
  avatar.setAttribute('aria-label','Жеке кабинетті ашу');
  add(top,brand(),el('span','muted topdate',todayTitle()),add(el('div','top-actions'),avatar,overflowMenu()));
  const main=el('main');main.id='content';main.tabIndex=-1;const nav=el('nav','nav');nav.setAttribute('aria-label','Негізгі навигация');
  [['home','Басты бет'],['materials','Материал'],['tasks','Тапсырма'],['schedule','Күнтізбе'],['profile','Кабинет']].forEach(([key,label])=>{const b=button('',()=>route(key),key===name?'active':'');add(b,icon(key),el('span','',label));if(key===name)b.setAttribute('aria-current','page');nav.append(b);});
  add(root,top,main,nav);return main;
}
async function subjectsPage(main){
  heading(main,'Менің пәндерім','Пән материалдары, тапсырмалар және оқытушылар.');
  if(isAdmin())main.append(button('+ Пән қосу',()=>route('subjectEditor'),'create-material'));
  const grid=el('div','grid');
  subjects.forEach(s=>{const card=add(el('article','card stack'),el('span','pill',s.code||'Пән'),el('h2','',s.name),el('p','muted',s.teacher_name),add(el('div','row'),button('Материалдар',()=>route('materials',s.id),'secondary'),button('Тапсырмалар',()=>route('tasks',s.id),'ghost')));externalLinks(card,s.external_links||[]);grid.append(card);});
  if(isAdmin())Array.from(grid.children).forEach((card,i)=>card.append(button('Пәнді және оқытушыларды өңдеу',()=>route('subjectEditor',subjects[i]),'ghost')));
  main.append(grid);if(!subjects.length)empty(main,'Пәндер әлі тағайындалмаған.');
}
async function route(name, data) {
  if(!session)return auth();cancelView();currentRoute=name;currentData=data;const main=shell(name);window.scrollTo(0,0);const generation=epoch;
  const loading=el('div','loading','Жүктелуде…');loading.setAttribute('role','status');main.append(loading);
  try{[subjects,groups]=await Promise.all([api('/subjects'),api('/groups')]);if(generation!==epoch)return;main.replaceChildren();await ({home,materials:()=>materials(main,false,data),tasks:()=>materials(main,true,data),subjects:subjectsPage,editor:()=>materialEditor(main,data),schedule,profile,groups:groupPage,grades:gradesPage,detail:()=>detail(main,data),admin:adminPage,userEditor:()=>userEditor(main,data),subjectEditor:()=>subjectEditor(main,data),lessonEditor:()=>lessonEditor(main,data)}[name]||home)(main);}
  catch(e){if(e.name==='AbortError')return;loading.remove();message(note(main),e.message,true);main.append(button('Қайта жүктеу',()=>route(name,data),'secondary'));}
}
async function home(main){
  if(isAdmin())main.append(adminShortcuts());
  const u=session.user;add(main,add(el('section','hero'),el('div','eyebrow',roles[u.role]+(u.group_name?' · '+u.group_name:'')),el('h1','',u.role==='teacher'?u.full_name.trim().replace(/^\S+\s+/, ''):u.full_name),el('p','','Бүгінгі сабақтарыңыз бен оқу материалдарыңызға өтіңіз.'),button('Материалдарды ашу →',()=>route('materials'))));
  const quick=el('div','grid');add(quick,add(el('section','card stack'),el('div','eyebrow','Сіздің пәндеріңіз'),el('div','metric',String(subjects.length)),el('p','muted','Қолжетімді оқу пәні')),add(el('section','card stack'),el('h2','',staff()?'Бағалар журналы':'Менің бағаларым'),el('p','muted',staff()?'Бағалау мен студенттердің нәтижелері.':'Өз бағаларыңыз бен орташа нәтижеңіз.'),button('Журналды ашу →',()=>route('grades'),'secondary')));main.append(quick);
  if(staff())main.append(add(el('section','card row spread'),add(el('div','stack'),el('h2','','Топтар мен студенттер'),el('p','muted','Топ құру және жаңа студент тіркеу.')),button('Топтарды ашу',()=>route('groups'),'secondary')));
  add(main,el('h2','','Бүгінгі сабақтар'));const today=dateKey(new Date()), rows=await api('/schedule?from_date='+today+'&to_date='+today);lessons(main,rows);
  const box=el('section','stack');add(box,el('h2','','Менің пәндерім'));subjects.forEach(s=>box.append(add(el('article','card stack'),el('h3','',s.name),el('p','muted',s.teacher_name),button('Материалдар →',()=>route('materials',s.id),'ghost'))));if(!subjects.length)empty(box,'Пәндер әлі тағайындалмаған.');main.append(box);
}
function externalLinks(parent, list){const links=el('div','linklist');list.forEach(l=>{try{const u=new URL(l.url);if(u.protocol!=='https:')return;const a=el('a','',l.title+' ↗');a.href=u.href;a.target='_blank';a.rel='noopener noreferrer';links.append(a);}catch(_){}});if(links.childNodes.length)parent.append(links);}
async function materials(main, task, selectedSubject){
  main.classList.add('library-screen');
  heading(main,task?'Тапсырмалар':'Материалдар');
  if(staff())main.append(button(task?'+ Тапсырма қосу':'+ Материал қосу',()=>route('editor',{subject_id:selectedSubject,section:task?'assignment':'resource'}),'create-material'));
  const filters=el('form','filters library-filters');const query=field(filters,'Іздеу','q','search','',false);query.placeholder='Тақырып немесе файл атауы';const subject=select(filters,'Пән',[['','Барлық пән'],...subjects.map(s=>[s.id,s.name])]);const week=select(filters,'Апта',[['','Барлық апта'],['general','Жалпы материалдар'],...Array.from({length:15},(_,i)=>[i+1,(i+1)+'-апта'])]);const kind=select(filters,'Түрі',[['','Барлық түр'],...Object.entries(kinds).filter(([key])=>task?['lab','sro'].includes(key):!['lab','sro'].includes(key))]);const go=el('button','','Іздеу');go.type='submit';const searchRow=el('div','library-search-row'),subjectRow=el('div','library-subject-row'),refineRow=el('div','library-refine-row');add(searchRow,query.parentElement,go);add(subjectRow,subject.parentElement);add(refineRow,week.parentElement,kind.parentElement);add(filters,searchRow,subjectRow,refineRow);main.append(filters);
  if(selectedSubject)subject.value=String(selectedSubject);const status=note(main), results=el('div','grid'), drives=el('section','card stack');add(main,results,drives);let serial=0;
  async function search(){const ticket=++serial;go.disabled=true;message(status,'Жүктелуде…');try{const p=new URLSearchParams();if(query.value.trim())p.set('q',query.value.trim());if(subject.value)p.set('subject_id',subject.value);if(week.value)p.set('week',week.value);if(kind.value)p.set('kind',kind.value);p.set('section',task?'assignment':'resource');const rows=await api('/materials?'+p);if(ticket!==serial)return;results.replaceChildren();message(status,rows.length+' материал табылды');rows.forEach(m=>{const card=el('article','card material'),files=m.files||[],extension=(files[0]?.name?.split('.').pop()||'TXT').toUpperCase(),symbol=({PDF:'PDF',DOC:'W',DOCX:'W',PPT:'P',PPTX:'P',TXT:'≡',PNG:'▧',JPG:'▧',JPEG:'▧'})[extension]||extension;
      const top=add(el('div','row spread'),add(el('div','row'),el('span','file-symbol',symbol),kindBadge(m.kind)),el('span','meta',m.week?m.week+'-апта':'Жалпы'));
      if(staff()){const menu=el('details','overflow-menu'),summary=el('summary','overflow-toggle','⋮');summary.setAttribute('aria-label',m.title+' әрекеттері');const panel=el('div','overflow-panel');add(panel,button('Өңдеу',()=>route('editor',m),'ghost'),deletePanel('Жою','«'+m.title+'» материалы жойылады.',async()=>{await api('/materials/'+m.id,{method:'DELETE'});await search();}));add(menu,summary,panel);top.append(menu);}
      const actions=el('div','row library-actions'),download=button('Жүктеу',async()=>{download.disabled=true;try{for(const f of files){const blob=await api('/files/'+f.id+'/download',{blob:true}),url=URL.createObjectURL(blob);urls.add(url);const link=el('a');link.href=url;link.download=f.name;link.click();}}catch(e){message(note(card),e.message,true);}finally{download.disabled=false;}},'secondary');download.disabled=!files.length;
      add(actions,button('Ашу',()=>route('detail',m)),download);
      add(card,top,!m.published?el('span','pill draft','Жоба'):null,el('h3','',m.title),el('p','muted',m.subject_name),task&&m.description?el('p','pre',visibleDescription(m.description)):null,el('p','meta',extension+' · '+fileSize(files.reduce((sum,f)=>sum+(f.size||0),0))+' · '+files.length+' тіркеме'+(m.deadline?' · '+prettyDeadline(m.deadline):'')),actions);if(m.student_status)card.append(statusBadge(m.student_status));results.append(card);});if(!rows.length)empty(results,'Осы сүзгімен материал табылмады. Басқа пәнді немесе аптаны таңдаңыз.');drives.replaceChildren();if(!task){add(drives,el('h2','','Пән сілтемелері · Drive'));subjects.filter(s=>!subject.value||String(s.id)===subject.value).forEach(s=>{if(s.external_links?.length){drives.append(el('h3','',s.name));externalLinks(drives,s.external_links);}});}drives.hidden=task||drives.childNodes.length<2;}catch(e){if(e.name!=='AbortError')message(status,e.message,true);}finally{if(ticket===serial)go.disabled=false;}}
  filters.addEventListener('submit',e=>{e.preventDefault();search();});subject.onchange=search;week.onchange=search;kind.onchange=search;await search();
}
function fileSize(size){if(!size)return '0 КБ';return size>=1048576?(size/1048576).toFixed(1)+' МБ':Math.max(1,Math.round(size/1024))+' КБ';}
function fileButtons(parent,files,submission=false,onDelete=null){files.forEach(f=>{
  const row=el('div','file-row stack'),extension=(f.name.split('.').pop()||'FILE').toUpperCase();
  add(row,add(el('div','row spread'),el('strong','file-name',f.name),el('span','meta',extension+' · '+fileSize(f.size))));const actions=el('div','row'),status=note(row);
  async function download(preview){const buttons=[...actions.querySelectorAll('button')];buttons.forEach(b=>b.disabled=true);message(status,'Файл жүктелуде…');let tab;
    try{if(preview){tab=window.open('about:blank','_blank');if(tab)tab.opener=null;}
      const blob=await api('/'+(submission?'submission-files':'files')+'/'+f.id+'/download',{blob:true,onProgress:(received,total)=>message(status,'Жүктелуде: '+fileSize(received)+(total?' / '+fileSize(total):''))}),url=URL.createObjectURL(blob);urls.add(url);
      if(preview&&tab)tab.location.href=url;
      const link=el('a','',preview?'Алдын ала қарауды ашу ↗':'Файлды қайта сақтау');link.href=url;link.target='_blank';link.rel='noopener';if(!preview)link.download=f.name;
      status.replaceChildren(el('span','','Файл дайын. '),link);if(!preview)link.click();
    }catch(e){if(tab)tab.close();if(e.name!=='AbortError')message(status,e.message,true);}finally{buttons.forEach(b=>b.disabled=false);}
  }
  if(/^(PDF|PNG|JPG|JPEG|GIF|WEBP|TXT)$/.test(extension))actions.append(button('Алдын ала қарау',()=>download(true),'secondary'));
  actions.append(button('Жүктеп алу ↓',()=>download(false),'ghost'));row.append(actions);parent.append(row);if(onDelete)parent.append(deletePanel('Файлды жою',f.name+' файлы жойылады.',()=>onDelete(f)));
});}

async function detail(main,m){
  const refreshed=await api('/materials?subject_id='+m.subject_id+'&section='+m.section);m=refreshed.find(row=>row.id===m.id);if(!m)throw new Error('Материал енді қолжетімді емес.');
  const receivedAt=performance.now();
  main.append(button('← '+(m.section==='assignment'?'Тапсырмалар':'Материалдар'),()=>route(m.section==='assignment'?'tasks':'materials'),'ghost back'));heading(main,m.title,m.subject_name);const card=el('section','card stack');if(m.student_status)card.append(statusBadge(m.student_status));add(card,add(el('div','row'),kindBadge(m.kind),el('span','meta',m.week?m.week+'-апта':'Жалпы')),m.deadline?el('p','meta','Тапсыру мерзімі: '+prettyDeadline(m.deadline)):null,el('p','pre',visibleDescription(m.description)||'Сипаттама қосылмаған.'));fileButtons(card,m.files);main.append(card);if(staff())main.append(button('Материалды өңдеу / файл қосу',()=>route('editor',m),'secondary'));if(staff())main.append(deletePanel('Материалды жою','«'+m.title+'» материалы, оған тіркелген файлдар және осы тапсырмаға жіберілген студент жұмыстары жойылады.',async()=>{await api('/materials/'+m.id,{method:'DELETE'});await route(m.section==='assignment'?'tasks':'materials',m.subject_id);}));if(m.section!=='assignment')return;
  const rows=await api('/materials/'+m.id+'/submissions');
  if(staff()){add(main,el('h2','','Жіберілген жұмыстар · '+rows.length));if(!rows.length)empty(main,'Бұл тапсырмаға жұмыс әлі жіберілмеген.');rows.forEach(s=>{const box=add(el('article','card stack'),el('h3','',s.student_name),el('p','meta','Жаңартылған: '+prettyDate(s.updated_at)),el('p','pre',s.text||'Мәтінсіз жұмыс'));fileButtons(box,s.files,true);main.append(box);});return;}
  let own=rows[0];
  const closed=()=>m.submission_closed||!!(m.deadline&&Date.parse(m.deadline)<=Date.parse(m.server_now)+performance.now()-receivedAt);
  if(closed()){const archive=add(el('section','card stack'),el('h2','','Менің жауабым'),el('p','notice','Тапсыру мерзімі аяқталды. Жауапты өзгерту және файл қосу жабық.'));if(own){archive.append(el('p','pre',own.text||'Мәтінсіз жұмыс'));fileButtons(archive,own.files,true);}else empty(archive,'Жұмыс жіберілмеген.');main.append(archive);return;}
  async function removeOwnFile(file){await api('/submission-files/'+file.id,{method:'DELETE'});own.files=own.files.filter(f=>f.id!==file.id);existing.replaceChildren();fileButtons(existing,own.files,true,removeOwnFile);}const form=el('form','card');add(form,el('h2','','Менің жауабым'),el('p','muted','Мәтінді сақтап, бірнеше файл қосуға болады. Бұрынғы файлдар сақталады.'));const text=field(form,'Жауап мәтіні','text','textarea',own?.text||'',false);text.maxLength=20000;const files=field(form,'Файлдар қосу','files','file','',false);files.multiple=true;add(form,el('p','muted','Әр файл: 200 МБ дейін.'));const existing=el('div','stack');fileButtons(existing,own?.files||[],true,removeOwnFile);form.append(existing);submit(form,own?'Жауапты жаңарту':'Жұмысты жіберу',async status=>{if(closed())throw new Error('Тапсыру мерзімі аяқталды. Бетті жаңартыңыз.');if(!text.value.trim()&&!files.files.length&&!own?.files.length)throw new Error('Жауап мәтінін немесе файлды қосыңыз.');const selected=[...files.files];if(selected.some(f=>f.size>200*1024*1024))throw new Error('Әр файл көлемі 200 МБ-тан аспауы керек.');const saved=await api('/materials/'+m.id+'/submission',{method:'PUT',body:{text:text.value}});let completed=0;try{for(const f of selected){message(status,'Мәтін сақталды. Файл жүктелуде: '+f.name);const data=new FormData();data.append('file',f);await api('/submissions/'+saved.id+'/files',{method:'POST',body:data});completed++;}}catch(e){files.value='';const fresh=await api('/materials/'+m.id+'/submissions');own=fresh[0];existing.replaceChildren();fileButtons(existing,fresh[0]?.files||[],true,removeOwnFile);throw new Error('Мәтін және '+completed+' файл сақталды. Қалған файлдарды қайта таңдаңыз. '+e.message);}files.value='';const fresh=await api('/materials/'+m.id+'/submissions');own=fresh[0];existing.replaceChildren();fileButtons(existing,fresh[0]?.files||[],true,removeOwnFile);message(status,'Жұмысыңыз сақталды. Оқытушы оны көре алады.');});main.append(form);
  const expiryTimer=setInterval(()=>{if(closed()){clearInterval(expiryTimer);form.querySelectorAll('input,textarea,button').forEach(c=>c.disabled=true);existing.replaceChildren();fileButtons(existing,own?.files||[],true);message(note(form),'Тапсыру мерзімі аяқталды. Жауап тек көруге қолжетімді.');}},1000);controller.signal.addEventListener('abort',()=>clearInterval(expiryTimer),{once:true});
}
function lessons(parent,rows){if(!rows.length){empty(parent,'Бұл күнге сабақ жоспарланбаған.');return;}const box=el('section','card stack');rows.forEach(r=>{const content=add(el('div','stack'),el('h3','',r.subject_name),el('p','muted',(kinds[r.kind]||r.kind)+' · '+r.room+' · '+(r.group_names?.join(' · ')||r.group_name)),el('p','meta',r.teacher_name));if(isAdmin())content.append(button('Сабақты өңдеу',()=>route('lessonEditor',r),'ghost'));box.append(add(el('article','lesson'),add(el('div','stack'),el('time','',r.start_time),el('span','meta',r.end_time)),content));});parent.append(box);}
async function schedule(main){
  if(isAdmin())main.append(button('+ Сабақ жоспарлау',()=>route('lessonEditor'),'create-material'));
  heading(main,'Сабақ кестесі','Күнді таңдап, сабақтарды қараңыз.');let chosen=typeof currentData==='string'&&/^\d{4}-\d{2}-\d{2}$/.test(currentData)?currentData:dateKey(new Date());let month=new Date(chosen+'T12:00:00');month.setDate(1);const card=el('section','card stack'),title=el('h2'),grid=el('div','calendar'),list=el('section','stack'),status=note(main);const prev=button('←',()=>change(-1),'ghost'),next=button('→',()=>change(1),'ghost');prev.setAttribute('aria-label','Алдыңғы ай');next.setAttribute('aria-label','Келесі ай');add(card,add(el('div','row spread'),prev,title,next),grid);add(main,card,list);let serial=0;
  async function change(delta=0){month.setMonth(month.getMonth()+delta);if(delta)chosen=dateKey(month);currentData=chosen;const ticket=++serial;message(status,'Жүктелуде…');try{const end=new Date(month.getFullYear(),month.getMonth()+1,0);const rows=await api('/schedule?from_date='+dateKey(month)+'&to_date='+dateKey(end));if(ticket!==serial)return;title.textContent=monthTitle(month);function draw(){grid.replaceChildren();['Дс','Сс','Ср','Бс','Жм','Сб','Жс'].forEach(d=>grid.append(el('span','weekday',d)));for(let i=0;i<(month.getDay()+6)%7;i++)grid.append(el('span'));for(let n=1;n<=end.getDate();n++){const key=dateKey(new Date(month.getFullYear(),month.getMonth(),n));const b=button(String(n),()=>{chosen=key;currentData=key;draw();},[chosen===key?'active':'',key===dateKey(new Date())?'today':'',rows.some(r=>r.date===key)?'haslesson':''].join(' '));b.setAttribute('aria-label',prettyDate(key));b.setAttribute('aria-pressed',String(chosen===key));grid.append(b);}list.replaceChildren(el('h2','',prettyDate(chosen)));lessons(list,rows.filter(r=>r.date===chosen));}draw();message(status,'');}catch(e){if(e.name!=='AbortError')message(status,e.message,true);}}
  await change();
}
async function logout(){const old=session;cancelView();saveSession(null);auth(false);if(old)try{await fetch('/api/auth/logout',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({refresh_token:old.refresh_token}),cache:'no-store'});}catch(_){} }
async function profile(main){const u=session.user;heading(main,'Жеке кабинет');add(main,add(el('section','hero'),el('div','eyebrow',roles[u.role]),el('h2','',u.full_name),el('p','',u.username+(u.group_name?' · '+u.group_name:''))));
  if(isAdmin())main.append(adminShortcuts());
  main.append(button('▤  Менің пәндерім →',()=>route('subjects'),'profile-row'));main.append(button('▦  Сабақ кестесі →',()=>route('schedule'),'profile-row'));if(staff())main.append(button('Топтар мен студенттер →',()=>route('groups'),'profile-row'));main.append(button(staff()?'Бағалар журналы →':'Менің бағаларым →',()=>route('grades'),'profile-row'));
  const form=el('form','card');add(form,el('h2','','Профильді өңдеу'));const full=field(form,'Аты-жөні','full_name','text',u.full_name),username=field(form,'Логин','username','text',u.username),pw=field(form,'Ағымдағы құпиясөз','current_password','password');full.maxLength=200;username.minLength=3;username.maxLength=80;username.autocapitalize='none';submit(form,'Өзгерістерді сақтау',async n=>{const user=await api('/auth/profile',{method:'PATCH',body:{full_name:full.value,username:username.value,current_password:pw.value}});saveSession({...session,user});pw.value='';message(n,'Профиль сақталды.');});const edit=el('details','card profile-edit');edit.append(el('summary','','✎  Профильді өңдеу / Логинді өзгерту'));edit.append(form);main.append(edit);
  const change=el('details','card');change.append(el('summary','','♧  Құпиясөзді өзгерту'));const pf=el('form');const current=field(pf,'Ағымдағы құпиясөз','current_password','password'),fresh=field(pf,'Жаңа құпиясөз (кемінде 8 таңба)','new_password','password');fresh.minLength=8;submit(pf,'Құпиясөзді жаңарту',async()=>{await api('/auth/password',{method:'POST',body:{current_password:current.value,new_password:fresh.value}});saveSession(null);auth(false,'Құпиясөз өзгерді. Жаңа құпиясөзбен қайта кіріңіз.');});change.append(pf);main.append(change);profileUtilities(main);main.append(button('Аккаунттан шығу',logout,'danger'));
}
async function groupPage(main){heading(main,'Топтар мен студенттер','Жаңа студент дайын аккаунтпен жүйеге кіре алады.');if(!staff())return;const create=el('form','card');add(create,el('h2','','Жаңа топ'));const name=field(create,'Топ атауы','name');name.maxLength=120;submit(create,'Топ құру',async n=>{await api('/groups',{method:'POST',body:{name:name.value}});await route('groups');});main.append(create);if(!groups.length)empty(main,'Әзірге топ жоқ. Алғашқы топты құрыңыз.');
  main.append(subjectGroupForm());for(const g of groups){const box=el('details','card');box.append(el('summary','',g.name+' · '+g.student_count+' студент'));const content=el('div','stack');box.append(content);let loaded=false;box.addEventListener('toggle',async()=>{if(!box.open||loaded)return;loaded=true;const status=note(content);try{const roster=await api('/groups/'+g.id+'/students');const list=el('div','stack');function student(s){list.append(add(el('div','divider'),el('h3','',s.full_name),el('p','muted',s.username)));}roster.forEach(student);if(!roster.length)empty(list,'Бұл топта студент жоқ.');content.append(list);const form=el('form','divider');add(form,el('h3','','Студентке аккаунт ашу'));const full=field(form,'Аты-жөні','full_name'),username=field(form,'Логин','username'),pw=field(form,'Құпиясөз (кемінде 8 таңба)','password','password');full.maxLength=200;username.minLength=3;username.maxLength=80;username.autocapitalize='none';pw.minLength=8;submit(form,'Студентті тіркеу',async n=>{const s=await api('/groups/'+g.id+'/new-student',{method:'POST',body:{full_name:full.value,username:username.value,password:pw.value}});list.querySelector('.empty')?.remove();student(s);form.reset();g.student_count++;box.firstChild.textContent=g.name+' · '+g.student_count+' студент';message(n,'Студент тіркелді. Логин: '+s.username+'. Өзіңіз енгізген құпиясөзді студентке беріңіз.');});content.append(form);}catch(e){loaded=false;if(e.name!=='AbortError')message(status,e.message,true);}});main.append(box);}
}
function subjectGroupForm(){
  const box=el('details','card');box.append(el('summary','','Топты пәнге байланыстыру'));
  const form=el('form'),subject=select(form,'Пән',subjects.map(s=>[s.id,s.name]),subjects[0]?.id),choices=el('div','stack');
  add(form,el('p','muted','Белгіленген топтар осы пәннің жарияланған материалдарын көреді. Бұрынғы байланыстар сақталады.'),choices);let inputs=[];
  function draw(){choices.replaceChildren();inputs=[];const s=subjects.find(s=>String(s.id)===subject.value);groups.forEach(g=>{const l=el('label','checkrow'),i=el('input');i.type='checkbox';i.checked=!!s?.group_ids.includes(g.id);add(l,i,el('span','',g.name));choices.append(l);inputs.push([g.id,i]);});}
  subject.onchange=draw;draw();
  submit(form,'Байланысты сақтау',async status=>{const s=subjects.find(s=>String(s.id)===subject.value);if(!s)throw new Error('Алдымен пән таңдалуы керек.');const visible=new Set(groups.map(g=>g.id));const ids=[...s.group_ids.filter(id=>!visible.has(id)),...inputs.filter(([,i])=>i.checked).map(([id])=>id)];const updated=await api('/subjects/'+s.id,{method:'PATCH',body:{group_ids:ids}});subjects=subjects.map(x=>x.id===s.id?updated:x);message(status,'Топтар пәнге байланыстырылды.');});box.append(form);return box;
}
async function studentGradesPage(main){
  heading(main,'Менің бағаларым');
  const filters=el('div','filters student-grade-filters'),jump=select(filters,'Пәнге өту',[['','Барлық пән'],...subjects.map(s=>[s.id,s.name])]);main.append(filters);
  const intro=el('p','meta','0 — қойылған баға. НП — қатыспады, орташаға кірмейді. — белгісі баға әлі қойылмағанын білдіреді. Ұяшықты басып, жұмыстарды көріңіз.');main.append(intro);
  const status=note(main),body=el('div','stack student-subjects');main.append(body);let ticket=0;
  const value=v=>v===null||v===undefined||v===''?'—':typeof v==='number'?Number(v.toFixed(2)).toString():String(v).replace('NP','НП');
  async function load(){const serial=++ticket;body.replaceChildren();message(status,'Жүктелуде…');if(!subjects.length){message(status,'Пәндер әлі тағайындалмаған.');return;}const cards=subjects.map(s=>{const card=add(el('section','card stack subject-grade-card'),el('span','subject-code',s.code||'Пән'),el('h2','',s.name),el('p','loading','Бағалар жүктелуде…'));card.id='student-subject-'+s.id;body.append(card);return {s,card};});
    await Promise.all(cards.map(async({s,card})=>{try{const groupId=session.user.group_id;if(!groupId)throw new Error('Аккаунтқа топ тағайындалмаған.');const data=await api('/gradebook?'+new URLSearchParams({subject_id:s.id,group_id:groupId}));if(serial!==ticket)return;card.querySelector('.loading')?.remove();
      const wrap=el('div','journal-scroll student-journal-scroll');wrap.tabIndex=0;wrap.setAttribute('aria-label',s.name+' бағалары, көлденең айналдыруға болады');const table=el('table','journal student-journal'),head=el('thead'),header=el('tr');['Сабақ түрі',...Array.from({length:15},(_,i)=>String(i+1)),'1–7 орташа','8–15 орташа'].forEach(t=>{const th=el('th','',t);th.scope='col';header.append(th);});head.append(header);table.append(head);const tbody=el('tbody'),detail=el('div','student-week-detail stack');detail.hidden=true;
      const own=data.students.find(st=>st.id===session.user.id)||data.students[0];
      ['lecture','sro','lab'].forEach(kind=>{const category=(data.kind_weekly||[]).find(r=>r.student_id===own?.id&&r.kind===kind),row=el('tr');const name=({lecture:'Дәріс',sro:'СРО',lab:'Зертханалық жұмыс'})[kind],label=el('th','',name);label.scope='row';row.append(label);for(let week=1;week<=15;week++){const w=category?.weeks?.find(w=>Number(w.week)===week),text=w?.average!=null?value(w.average):w?.np_count?'НП':'—',cell=el('td'),b=button(text,()=>{detail.hidden=false;detail.replaceChildren(add(el('div','row spread'),el('h3','',name+' · '+week+'-апта'),button('Жабу',()=>detail.hidden=true,'ghost')));if(!w?.assessments?.length)empty(detail,'Осы аптада бағалау жоқ.');else w.assessments.forEach(a=>detail.append(add(el('div','student-assessment-line'),el('span','',a.title),el('strong','',value(a.value)))));detail.scrollIntoView({behavior:'smooth',block:'nearest'});},'week-cell');b.setAttribute('aria-label',name+', '+week+'-апта: '+text);if(w?.assessments?.length)b.append(el('small','',w.assessments.length+' жұмыс'));cell.append(b);row.append(cell);}add(row,el('td','summary-cell',value(category?.period1_average)),el('td','summary-cell',value(category?.period2_average)));tbody.append(row);});table.append(tbody);wrap.append(table);add(card,wrap,detail);
      const summary=data.summaries?.find(r=>r.student_id===own?.id)||{},metrics=el('div','student-grade-metrics');[['tk1','ТК1'],['rk1','РК1'],['r1','Р1'],['tk2','ТК2'],['rk2','РК2'],['r2','Р2'],['exam','Емтихан'],['final','Қорытынды']].forEach(([key,title])=>metrics.append(add(el('div','grade-metric'),el('span','meta',title),el('strong','',value(summary[key])))));card.append(metrics);
      const explanation=el('details','grade-explanation');explanation.append(el('summary','','Есеп'));add(explanation,el('p','meta',data.policy?.explanation||'Есептеу ережелері толық расталмаған.'),el('p','meta','1–7 және 8–15 орташа — сабақ түрінің сандық бағаларының орташа мәні. Бұл бағандар ТК немесе РК емес.'),el('p','meta',data.policy?.example||''));card.append(explanation);
    }catch(e){if(serial!==ticket||e.name==='AbortError')return;card.querySelector('.loading')?.remove();message(note(card),e.message,true);card.append(button('Қайта жүктеу',load,'secondary'));}}));if(serial===ticket)message(status,'');
  }
  jump.onchange=()=>{if(jump.value)document.getElementById('student-subject-'+jump.value)?.scrollIntoView({behavior:'smooth',block:'start'});};await load();
}
async function gradesPage(main){
  if(!staff())return studentGradesPage(main);
  main.classList.add('gradebook-screen');heading(main,'Бағалар журналы');
  const scope=el('div','journal-scope'),subject=select(scope,'Пән',subjects.map(s=>[s.id,s.name]),subjects[0]?.id),group=select(scope,'Топ / ішкі топ',[]);main.append(scope);
  const kindBar=el('div','kind-segments');kindBar.setAttribute('role','group');kindBar.setAttribute('aria-label','Сабақ түрі');main.append(kindBar);
  const status=note(main),body=el('div','stack gradebook-body');status.classList.add('grade-status');main.append(body);let ticket=0,selectedKind='lecture',selectedWeek=1,book=null,drawTable=()=>{},updateEditor=()=>{},activeAssessment=null;const drafts=new Map();
  const display=v=>v===null||v===undefined||v===''?'—':typeof v==='number'?String(Number(v.toFixed(2))):String(v).replace('NP','НП');
  const kindLabels={lecture:'Дәріс',sro:'СРО',lab:'Зертханалық жұмыс'};
  function groupOptions(){group.replaceChildren();const s=subjects.find(s=>String(s.id)===subject.value);groups.filter(g=>s?.group_ids.includes(g.id)).forEach(g=>{const o=el('option','',g.name);o.value=g.id;group.append(o);});}
  function setKind(k){selectedKind=k;kindBar.querySelectorAll('button').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.kind===k)));drawTable();updateEditor(false);}
  Object.entries(kindLabels).forEach(([k,label])=>{const b=button(label,()=>setKind(k),'kind-segment');b.dataset.kind=k;b.setAttribute('aria-pressed',String(k===selectedKind));kindBar.append(b);});
  async function load(reopen=false){const restoreAssessment=reopen?activeAssessment:null,serial=++ticket;body.replaceChildren();activeAssessment=null;if(!subject.value||!group.value){empty(body,'Бұл пәнге топ тағайындалмаған.');return;}message(status,'Журнал жүктелуде…');try{
    const data=await api('/gradebook?'+new URLSearchParams({subject_id:subject.value,group_id:group.value}));if(serial!==ticket)return;book=data;message(status,'');
    body.append(el('p','roster-count',data.students.length+' студент'));
    const wrap=el('div','journal-scroll overview-scroll');wrap.tabIndex=0;wrap.setAttribute('aria-label','1–15 апта бағалары. Көлденең айналдыруға болады.');const table=el('table','journal'),head=el('thead'),header=el('tr');
    ['№','Студенттің аты-жөні'].forEach(t=>{const th=el('th','',t);th.scope='col';header.append(th);});
    for(let n=1;n<=15;n++){const th=el('th','week-heading');th.scope='col';th.dataset.week=n;const pick=button(String(n),()=>selectWeek(n,true),'week-heading-button');pick.setAttribute('aria-label',n+'-аптаны өзгерту');th.append(pick);header.append(th);}
    ['ТК1','РК1','Р1','ТК2','РК2','Р2','Емтихан','Қорытынды'].forEach(t=>{const th=el('th','',t);th.scope='col';header.append(th);});head.append(header);const tbody=el('tbody');add(table,head,tbody);wrap.append(table);body.append(wrap);
    const editor=el('section','weekly-workspace'),toolbar=el('div','week-toolbar'),title=el('h2'),draftStatus=el('span','draft-status'),weekPicker=el('div','week-picker'),action=button('',()=>openAssessment(true),'weekly-open'),editorBody=el('div','weekly-editor-content');weekPicker.setAttribute('role','group');weekPicker.setAttribute('aria-label','Баға енгізу аптасы');
    for(let n=1;n<=15;n++){const b=button(String(n),()=>selectWeek(n,false),'week-choice');b.dataset.week=n;b.setAttribute('aria-label',n+'-апта');weekPicker.append(b);}
    add(toolbar,add(el('div','row'),title,draftStatus),action);add(editor,weekPicker,toolbar,editorBody);body.append(editor);
    function hasGrades(a){return !!a&&data.entries.some(e=>e.assessment_id===a.id&&e.value!==null&&e.value!==undefined&&e.value!=='');}
    function updateDraftStatus(){draftStatus.textContent=matching().some(a=>drafts.has(a.id))?'Сақталмаған':'';}
    function matching(){return data.assessments.filter(a=>a.kind===selectedKind&&Number(a.week)===selectedWeek).sort((a,b)=>Number(data.entries.some(e=>e.assessment_id===b.id))-Number(data.entries.some(e=>e.assessment_id===a.id))||a.id-b.id);}
    function selectWeek(n,open){selectedWeek=n;drawTable();updateEditor(open);const cell=header.querySelector('[data-week="'+n+'"]'),bounds=wrap.getBoundingClientRect(),r=cell.getBoundingClientRect(),sticky=header.children[1].getBoundingClientRect().width;const shift=r.left<bounds.left+sticky?r.left-bounds.left-sticky:r.right>bounds.right?r.right-bounds.right:0;if(shift)wrap.scrollBy({left:shift,behavior:matchMedia('(prefers-reduced-motion: reduce)').matches?'auto':'smooth'});if(open)editor.scrollIntoView({behavior:matchMedia('(prefers-reduced-motion: reduce)').matches?'auto':'smooth',block:'start'});}
    drawTable=()=>{tbody.replaceChildren();header.querySelectorAll('[data-week]').forEach(th=>{th.classList.toggle('selected-week',Number(th.dataset.week)===selectedWeek);th.querySelector('button').setAttribute('aria-pressed',String(Number(th.dataset.week)===selectedWeek));});
      data.students.forEach((student,index)=>{const row=el('tr');add(row,el('td','',index+1),el('th','student-name',student.full_name));const category=data.kind_weekly?.find(r=>r.student_id===student.id&&r.kind===selectedKind);const weeks=category?.weeks||data.weekly?.find(r=>r.student_id===student.id)?.weeks||[];
        for(let n=1;n<=15;n++){const w=weeks.find(w=>Number(w.week)===n),cell=el('td',n===selectedWeek?'selected-week':''),value=w?.average!=null?display(w.average):w?.np_count?'НП':'—',b=button(value,()=>selectWeek(n,true),'week-cell');cell.dataset.week=n;b.setAttribute('aria-label',student.full_name+', '+n+'-апта: '+value);if(w?.assessments?.length>1)b.append(el('small','',w.assessments.length+' жұмыс'));cell.append(b);row.append(cell);}
        const summary=data.summaries?.find(r=>r.student_id===student.id)||{};['tk1','rk1','r1','tk2','rk2','r2','exam','final'].forEach(key=>row.append(el('td','summary-cell',display(summary[key]))));tbody.append(row);
      });
    };
    updateEditor=(open=false)=>{const list=matching();title.textContent=selectedWeek+'-апта · '+kindLabels[selectedKind];action.textContent=hasGrades(list[0])?'Өзгерту':'Баға қою';weekPicker.querySelectorAll('button').forEach(b=>b.setAttribute('aria-pressed',String(Number(b.dataset.week)===selectedWeek)));const picked=weekPicker.querySelector('[aria-pressed="true"]'),pr=weekPicker.getBoundingClientRect(),br=picked.getBoundingClientRect();weekPicker.scrollLeft+=br.left<pr.left?br.left-pr.left:br.right>pr.right?br.right-pr.right:0;editorBody.replaceChildren();activeAssessment=null;updateDraftStatus();if(open&&list.length)renderAssessment(list.find(a=>a.id===restoreAssessment)||list[0]);};
    async function openAssessment(create){const list=matching();if(list.length){renderAssessment(list.find(a=>a.id===activeAssessment)||list[0]);return;}if(!create)return;action.disabled=true;try{const result=await api('/gradebook/weekly',{method:'POST',body:{subject_id:Number(subject.value),group_id:Number(group.value),kind:selectedKind,week:selectedWeek}});if(serial!==ticket)return;const a=result.assessment;data.assessments.push(a);data.entries.push(...(result.entries||[]).map(e=>({...e,assessment_id:a.id})));action.textContent=hasGrades(a)?'Өзгерту':'Баға қою';renderAssessment(a);drawTable();}catch(e){if(e.name!=='AbortError')message(note(editorBody),e.message,true);}finally{action.disabled=false;}}
    function renderAssessment(a){activeAssessment=a.id;action.textContent=hasGrades(a)?'Өзгерту':'Баға қою';editorBody.replaceChildren();const list=matching();if(list.length>1){const choice=select(editorBody,'Осы аптадағы жұмыс',list.map(x=>[x.id,x.title]),a.id);choice.onchange=()=>renderAssessment(list.find(x=>String(x.id)===choice.value));}
      const f=el('form','assessment-editor'),inputs=[],preview=el('div','grade-preview');preview.hidden=true;add(f,el('h3','assessment-title',a.title));const bulk=el('div','grade-bulk'),all=field(bulk,'Бүкіл топқа','bulk','text','',false);all.maxLength=3;
      const apply=button('Қолдану',()=>{const v=all.value.trim().toUpperCase();if(!/^(?:100|[1-9]?[0-9]|NP|НП)?$/.test(v)){message(note(bulk),'0–100, НП немесе бос мән енгізіңіз.',true);return;}inputs.forEach(x=>x.input.value=v);remember();},'secondary');add(bulk,apply);f.append(bulk);
      const save=button('Сақтау',async()=>{save.disabled=true;try{await api('/assessments/'+a.id+'/grades',{method:'PUT',body:{version:a.version,entries:inputs.filter(x=>x.input.value!==x.before).map(x=>({student_id:x.id,value:canonicalGrade(x.input.value)}))}});drafts.delete(a.id);await load(true);message(status,'Бағалар сақталды.');}catch(e){message(note(f),e.message,true);}finally{save.disabled=false;}});save.hidden=true;
      function remember(){if(inputs.some(x=>x.input.value!==x.before))drafts.set(a.id,Object.fromEntries(inputs.map(x=>[x.id,x.input.value])));else drafts.delete(a.id);updateDraftStatus();preview.hidden=true;save.hidden=true;inputs.forEach(x=>x.input.classList.toggle('changed',x.input.value!==x.before));}
      data.students.forEach(s=>{const entry=data.entries.find(e=>e.assessment_id===a.id&&e.student_id===s.id),before=entry?.value==null?'':String(entry.value).replace('NP','НП'),input=field(f,s.full_name,'grade_'+s.id,'text',drafts.get(a.id)?.[s.id]??before,false);input.maxLength=3;input.pattern='NP|np|НП|нп|100|[1-9]?[0-9]';input.placeholder='0–100 / НП';input.oninput=remember;input.classList.toggle('changed',input.value!==before);inputs.push({id:s.id,name:s.full_name,input,before});});
      const check=el('button','secondary','Өзгерістерді қарау');check.type='submit';f.addEventListener('submit',e=>{e.preventDefault();preview.replaceChildren();const changed=inputs.filter(x=>x.input.value!==x.before);changed.forEach(x=>preview.append(el('p','',x.name+': '+display(x.before)+' → '+display(x.input.value))));if(!changed.length)preview.append(el('p','','Өзгеріс жоқ.'));preview.hidden=false;save.hidden=!changed.length;});const cancel=button('Болдырмау',()=>{drafts.delete(a.id);updateEditor(false);},'ghost');add(f,el('p','meta','НП орташаға кірмейді. Бос мән бағаны өшіреді.'),preview,add(el('div','grade-edit-actions'),check,save,cancel));f.append(deletePanel('Бағалауды жою','Осы жұмыстың барлық бағасы жойылады.',async()=>{await api('/assessments/'+a.id,{method:'DELETE'});drafts.delete(a.id);await load();}));editorBody.append(f);editorBody.classList.remove('editor-reveal');void editorBody.offsetWidth;editorBody.classList.add('editor-reveal');
    }
    drawTable();updateEditor(reopen);
    const policy=el('details','grade-explanation');policy.append(el('summary','','Есеп'));add(policy,el('p','meta',data.policy?.explanation||'Есептеу ережелері толық расталмаған.'),el('p','meta',data.policy?.example||''));body.append(policy);
    {
      const components=el('details','card');components.append(el('summary','','Бақылау және емтихан бағалары'));data.students.forEach(s=>{const summary=data.summaries?.find(r=>r.student_id===s.id)||{},f=el('form','component-form');f.append(el('h3','',s.full_name));const values={};['rk1','rk2','exam'].forEach(key=>{values[key]=field(f,({tk1:'ТК1',rk1:'РК1',tk2:'ТК2',rk2:'РК2',exam:'Емтихан'})[key],key,'number',summary[key]??'',false);values[key].min=0;values[key].max=100;values[key].step='any';});let pending=null;const preview=el('div','grade-preview');preview.hidden=true;f.append(preview);Object.values(values).forEach(i=>i.addEventListener('input',()=>{pending=null;preview.hidden=true;}));submit(f,'Алдын ала көру / Сақтау',async()=>{const snapshot=JSON.stringify(Object.fromEntries(Object.entries(values).map(([k,i])=>[k,i.value])));if(pending!==snapshot){preview.replaceChildren(el('h3','','Сақталатын мәндер'));Object.entries(values).forEach(([k,i])=>preview.append(el('p','',({rk1:'РК1',rk2:'РК2',exam:'Емтихан'})[k]+': '+display(summary[k])+' → '+display(i.value))));preview.append(el('p','meta','Сақтау үшін батырманы қайта басыңыз.'));preview.hidden=false;pending=snapshot;return;}await api('/gradebook/components',{method:'PUT',body:{subject_id:Number(subject.value),group_id:Number(group.value),student_id:s.id,version:summary.component_version||0,...Object.fromEntries(Object.entries(values).map(([k,i])=>[k,i.value===''?null:Number(i.value)]))}});await load();});components.append(f);});body.append(components);
    }
  }catch(e){if(e.name!=='AbortError')message(status,e.message,true);}}
  subject.onchange=()=>{groupOptions();load();};group.onchange=()=>load();groupOptions();await load();
}
function deadlineLocal(value){if(!value)return '';const parts=new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Qyzylorda',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'}).formatToParts(new Date(value));const get=k=>parts.find(p=>p.type===k).value;return get('year')+'-'+get('month')+'-'+get('day')+'T'+get('hour')+':'+get('minute')+':'+get('second');}
function prettyDeadline(value){const local=deadlineLocal(value);return prettyDate(local.slice(0,10))+' · '+local.slice(11,19)+' (UTC+05:00)';}
function kindBadge(kind){const symbol=el('span','badge-symbol',({lecture:'▤',lab:'⚗',sro:'✎',note:'▧',guide:'↗',exam:'✓'})[kind]||'▤');symbol.setAttribute('aria-hidden','true');return add(el('span','pill kind-'+kind),symbol,el('span','',kinds[kind]||kind));}
function statusBadge(status){return el('span','pill status-'+status,({submitted:'✓ Тапсырылды',graded:'✓ Бағаланды',overdue:'◷ Мерзімі өтті',not_submitted:'○ Тапсырылмаған'})[status]||status);}
function visibleDescription(value){return (value||'').split('\n').filter(line=>!/^\s*(?:Provenance|Импортировано из|Imported from)\s*:/i.test(line)).join('\n').trim();}
function profileUtilities(main){
  const contacts=add(el('section','card stack'),el('h2','','Біздің контактілер')),phone=el('a','contact-phone','+77018601864');phone.href='tel:+77018601864';contacts.append(phone);main.append(contacts);
  const section=add(el('section','card stack'),el('h2','','Біздің мекенжай'),el('p','','Астана қаласы, Пушкина көшесі, 11.'),el('p','meta','Тұрақты мекенжай мен GPS орны бөлек көрсетіледі. Орналасқан жеріңіз тек сұраған кезде анықталады.')),canvas=el('div','campus-map'),status=note(section);canvas.setAttribute('aria-label','Пушкина көшесі 11 интерактивті картасы');section.append(canvas);main.append(section);
  if(!window.L){message(status,'Карта модулі жүктелмеді. Бетті қайта жүктеңіз.',true);return;}
  const campus=[51.159896,71.465239],map=L.map(canvas).setView(campus,17),markerIcon=L.divIcon({className:'campus-marker',html:'<span>U</span>',iconSize:[36,42],iconAnchor:[18,42]});
  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'}).on('tileerror',()=>message(status,'Карта қабаттары жүктелмеді. Интернетті тексеріңіз; мекенжай белгісі сақталады.',true)).addTo(map);
  L.marker(campus,{icon:markerIcon}).addTo(map).bindPopup('Біздің мекенжай: Астана, Пушкина көшесі, 11');let gps=null,accuracyCircle=null;
  const locate=button('◎ Менің орналасқан жерім',()=>{if(!window.isSecureContext){message(status,'GPS үшін HTTPS немесе localhost қажет. Бұл HTTP мекенжайын HTTPS арқылы ашыңыз.',true);return;}if(!navigator.geolocation){message(status,'Браузер GPS қолдамайды.',true);return;}locate.disabled=true;message(status,'GPS орны анықталуда…');const generation=epoch;navigator.geolocation.getCurrentPosition(p=>{if(generation!==epoch)return;locate.disabled=false;const point=[p.coords.latitude,p.coords.longitude];if(gps)map.removeLayer(gps);if(accuracyCircle)map.removeLayer(accuracyCircle);gps=L.circleMarker(point,{radius:9,color:'#243B6B',fillColor:'#14B8A6',fillOpacity:1}).addTo(map).bindPopup('Менің орналасқан жерім');accuracyCircle=L.circle(point,{radius:p.coords.accuracy,color:'#14B8A6',weight:1,fillOpacity:.1}).addTo(map);map.setView(point,16);gps.openPopup();message(status,'GPS орны бөлек белгіленді · дәлдігі '+Math.round(p.coords.accuracy)+' м');},e=>{if(generation!==epoch)return;locate.disabled=false;message(status,({1:'GPS рұқсаты берілмеді. Браузер баптауларынан рұқсат беріңіз.',2:'GPS орны қолжетімсіз. Құрылғы геолокациясын қосыңыз.',3:'GPS күту уақыты аяқталды. Қайта көріңіз.'})[e.code]||'GPS қатесі.',true);},{enableHighAccuracy:true,timeout:15000,maximumAge:0});},'secondary');
  add(section,add(el('div','row'),button('⌖ Мекенжайға оралу',()=>map.setView(campus,17)),locate));if(!navigator.onLine)message(status,'Интернет жоқ. Карта қабаттары желі қосылғанда қолжетімді болады.',true);controller.signal.addEventListener('abort',()=>map.remove(),{once:true});requestAnimationFrame(()=>map.invalidateSize());
}
if(session) {api('/auth/me').then(user=>{saveSession({...session,user});route('home');}).catch(e=>{if(e.name!=='AbortError'){saveSession(null);auth(false,e.message);}});} else auth();

async function materialEditor(main, initial={}){
  if(!staff()){heading(main,'Қолжетімділік жоқ');return;}
  let savedId=initial.id||null;
  heading(main,savedId?'Материалды өңдеу':'Жаңа материал','Дәріс, тапсырма немесе қосымша файлдарды пәнге қосыңыз.');
  if(!subjects.length){empty(main,'Алдымен сізге пән тағайындалуы керек.');return;}
  const form=el('form','card editor-form'), subject=select(form,'Пән',subjects.map(s=>[s.id,s.name]),initial.subject_id||subjects[0].id);
  if(savedId)subject.disabled=true;
  const title=field(form,'Материал атауы','title','text',initial.title||'');title.maxLength=400;title.placeholder='Мысалы: 8-дәріс. Transformer архитектурасы';
  const options=el('div','editor-options');form.append(options);
  const section=select(options,'Бөлім',[['resource','Материалдар'],['assignment','Тапсырмалар']],initial.section||'resource');
  const kind=select(options,'Түрі',Object.entries(kinds),initial.kind||(initial.section==='assignment'?'lab':'lecture'));
  const week=select(options,'Апта',[['','Жалпы материал'],...Array.from({length:15},(_,i)=>[i+1,(i+1)+'-апта'])],initial.week||'');
  const deadline=field(form,'Тапсыру мерзімі · Asia/Qyzylorda (UTC+05:00)','deadline','datetime-local',deadlineLocal(initial.deadline),false);deadline.step='1';
  function sectionChanged(){const previous=kind.value;kind.replaceChildren();Object.entries(kinds).filter(([key])=>section.value==='assignment'?['lab','sro'].includes(key):!['lab','sro'].includes(key)).forEach(([key,label])=>{const option=el('option','',label);option.value=key;kind.append(option);});if([...kind.options].some(o=>o.value===previous))kind.value=previous;deadline.parentElement.hidden=section.value!=='assignment';}section.onchange=sectionChanged;sectionChanged();
  const description=field(form,'Сипаттама / тапсырма шарты','description','textarea',initial.description||'',false);description.maxLength=20000;
  const audience=el('fieldset','audience');audience.append(el('legend','','Кімге қолжетімді'));const groupChoices=el('div','stack');audience.append(groupChoices);form.append(audience);let checks=[];
  function drawGroups(){groupChoices.replaceChildren();checks=[];const sub=subjects.find(s=>String(s.id)===subject.value);const allowed=groups.filter(g=>sub.group_ids.includes(g.id));allowed.forEach(g=>{const label=el('label','checkrow'),c=el('input');c.type='checkbox';c.checked=!initial.group_ids?.length||initial.group_ids.includes(g.id);add(label,c,el('span','',g.name));groupChoices.append(label);checks.push([g.id,c]);});if(!allowed.length)empty(groupChoices,'Пәнге топ әлі байланыстырылмаған.');}
  subject.onchange=drawGroups;drawGroups();
  const upload=el('div','upload-zone');add(upload,el('span','upload-symbol','↑'),el('h3','','Файлдарды қосу'),el('p','muted','PDF, Word, презентация, мәтін, фото және басқа файлдар. Әр файл — 200 МБ дейін.'));
  const files=field(upload,'Құрылғыдан таңдау','material_files','file','',false);files.multiple=true;
  const selected=el('div','selected-files');files.addEventListener('change',()=>{selected.replaceChildren();[...files.files].forEach(f=>selected.append(el('div','selected-file',f.name+' · '+Math.max(1,Math.round(f.size/1024))+' КБ')));});upload.append(selected);form.append(upload);
  if(initial.files?.length){const existing=add(el('section','stack'),el('h3','','Қосылған файлдар'));fileButtons(existing,initial.files,false,removeFile);async function removeFile(file){await api('/files/'+file.id,{method:'DELETE'});initial.files=initial.files.filter(f=>f.id!==file.id);existing.replaceChildren(el('h3','','Қосылған файлдар'));fileButtons(existing,initial.files,false,removeFile);}form.append(existing);}
  const label=el('label','checkrow'),published=el('input');published.type='checkbox';published.checked=initial.published!==false;add(label,published,el('span','','Студенттерге жариялау'));form.append(label);
  submit(form,'Сақтау және файлдарды жүктеу',async status=>{
    const selection=[...files.files];if(selection.some(f=>f.size>200*1024*1024))throw new Error('Әр файл көлемі 200 МБ-тан аспауы керек.');
    const selectedGroups=checks.filter(([,c])=>c.checked).map(([id])=>id);if(checks.length&&!selectedGroups.length)throw new Error('Кемінде бір топ таңдаңыз.');
    const values={subject_id:Number(subject.value),title:title.value,description:description.value,kind:kind.value,section:section.value,week:week.value?Number(week.value):null,group_ids:selectedGroups,deadline:section.value==='assignment'&&deadline.value?deadline.value+'+05:00':null};
    const controls=[...form.querySelectorAll('input,select,textarea')];controls.forEach(c=>c.disabled=true);
    let completed=0;
    try{
      if(!savedId){const created=await api('/materials',{method:'POST',body:{...values,published:false}});savedId=created.id;}
      else await api('/materials/'+savedId,{method:'PATCH',body:values});
      for(const file of selection){message(status,'Файл жүктелуде: '+file.name);const data=new FormData();data.append('file',file);await api('/materials/'+savedId+'/files',{method:'POST',body:data});completed++;}
      const result=await api('/materials/'+savedId,{method:'PATCH',body:{published:published.checked}});
      files.value='';selected.replaceChildren();result.subject_name=subjects.find(s=>s.id===result.subject_id)?.name;
      await route('detail',result);
    }catch(e){
      files.value='';selected.replaceChildren();
      throw new Error((savedId?'Материал сақталды. '+completed+' файл жүктелді. Қалған файлдарды қайта таңдаңыз. ':'')+e.message);
    }finally{controls.forEach(c=>c.disabled=false);if(savedId)subject.disabled=true;}
  });
  form.append(button('Артқа',()=>route(initial.section==='assignment'?'tasks':'materials',Number(subject.value)),'ghost'));main.append(form);
}

function canonicalGrade(value){const v=value.trim().toUpperCase();return v==='НП'?'NP':v||null;}

function deletePanel(title,description,action){
  const panel=el('details','delete-panel');panel.append(el('summary','',title));
  const content=add(el('div','stack'),el('p','muted',description)),label=el('label','checkrow'),confirm=el('input');confirm.type='checkbox';add(label,confirm,el('span','','Жоюды растаймын'));content.append(label);
  const status=note(content),remove=button('Біржола жою',async()=>{remove.disabled=true;confirm.disabled=true;message(status,'');try{await action();}catch(e){if(e.name!=='AbortError')message(status,e.message,true);}finally{confirm.disabled=false;remove.disabled=!confirm.checked;}},'danger');remove.disabled=true;confirm.onchange=()=>{remove.disabled=!confirm.checked;};content.append(remove);panel.append(content);return panel;
}

function isAdmin(){return session?.user.role==='admin';}
function adminShortcuts(){
  return add(el('section','card admin-accent stack'),el('div','eyebrow','Әкімші кеңістігі'),el('h2','','Оқуды ұйымдастыру'),el('p','muted','Аккаунттар, пәндер және сабақ кестесі.'),add(el('div','row'),button('Аккаунттар',()=>route('admin')),button('Пәндер',()=>route('subjects'),'secondary'),button('Топтар',()=>route('groups'),'secondary'),button('+ Сабақ',()=>route('lessonEditor'),'ghost')));
}
function adminGuard(main){if(isAdmin())return true;heading(main,'Қолжетімділік жоқ','Бұл бөлім әкімшіге арналған.');return false;}
async function adminPage(main){
  if(!adminGuard(main))return;
  heading(main,'Аккаунттарды басқару','Оқытушылар, студенттер және тіркелу өтінімдері.');
  const rows=await api('/admin/users');
  const metrics=el('div','admin-metrics');
  ['teacher','student','admin'].forEach(role=>metrics.append(add(el('section','card stack'),el('div','metric',String(rows.filter(u=>u.role===role).length)),el('span','muted',roles[role]))));main.append(metrics);
  main.append(add(el('div','row'),button('+ Оқытушы',()=>route('userEditor',{role:'teacher'})),button('+ Студент',()=>route('userEditor',{role:'student'}),'secondary'),button('Топтар',()=>route('groups'),'ghost')));
  const filters=el('div','filters'),q=field(filters,'Іздеу','user_query','search','',false),role=select(filters,'Рөл',[['','Барлығы'],...Object.entries(roles)]),group=select(filters,'Топ',[['','Барлық топ'],...groups.map(g=>[g.id,g.name])]);q.placeholder='Аты-жөні немесе логин';main.append(filters);
  const list=el('div','grid');main.append(list);
  function draw(){
    list.replaceChildren();const query=q.value.trim().toLocaleLowerCase();
    const visible=rows.filter(u=>(!role.value||u.role===role.value)&&(!group.value||String(u.group_id)===group.value)&&(!query||(u.full_name+' '+u.username).toLocaleLowerCase().includes(query)));
    visible.forEach(u=>{const card=add(el('article','card stack'),add(el('div','row'),el('span','pill',roles[u.role]),!u.approved?el('span','pill draft','Мақұлдауды күтеді'):null,!u.active?el('span','pill draft','Өшірілген'):null),el('h2','',u.full_name),el('p','muted',u.username+(u.group_name?' · '+u.group_name:'')),button('Аккаунтты өңдеу',()=>route('userEditor',u),'secondary'));list.append(card);});
    if(!visible.length)empty(list,'Сүзгіге сәйкес аккаунт табылмады.');
  }q.oninput=draw;role.onchange=draw;group.onchange=draw;draw();
}
async function userEditor(main,initial={role:'teacher'}){
  if(!adminGuard(main))return;
  const editing=!!initial.id;
  heading(main,editing?'Аккаунтты басқару':'Жаңа аккаунт',editing?initial.full_name:'Аккаунт бірден белсенді болады. Құпиясөзді иесіне беріңіз.');
  const form=el('form','card editor-form');let full,username,password,confirm;
  if(!editing){full=field(form,'Аты-жөні','full_name');full.maxLength=200;username=field(form,'Логин','username');username.minLength=3;username.maxLength=80;username.autocapitalize='none';username.spellcheck=false;password=field(form,'Құпиясөз (кемінде 8 таңба)','password','password');password.minLength=8;confirm=field(form,'Құпиясөзді қайталау','confirm_password','password');confirm.minLength=8;}
  else form.append(el('p','muted','Логин: '+initial.username));
  const role=select(form,'Рөл',editing?Object.entries(roles):[['teacher','Оқытушы'],['student','Студент']],initial.role);
  const group=select(form,'Топ',[['','Топ таңдалмаған'],...groups.map(g=>[g.id,g.name])],initial.group_id||'');
  function roleChanged(){group.parentElement.hidden=role.value!=='student';}role.onchange=roleChanged;roleChanged();
  let active;if(editing){const label=el('label','checkrow');active=el('input');active.type='checkbox';active.checked=initial.active;add(label,active,el('span','','Аккаунт белсенді'));form.append(label);if(initial.id===session.user.id){role.disabled=true;active.disabled=true;}}
  submit(form,editing?'Өзгерістерді сақтау':'Аккаунт ашу',async status=>{
    if(!editing&&password.value!==confirm.value)throw new Error('Құпиясөздер сәйкес емес.');
    const values={role:role.value,group_id:role.value==='student'&&group.value?Number(group.value):null};
    if(editing){values.active=active.checked;await api('/admin/users/'+initial.id,{method:'PATCH',body:values});await route('admin');}
    else{await api('/admin/users',{method:'POST',body:{...values,full_name:full.value,username:username.value,password:password.value}});password.value='';confirm.value='';await route('admin');}
  });main.append(form);
  if(editing&&!initial.approved){const approve=el('form','card');add(approve,el('h2','','Тіркелу өтінімі'),el('p','muted','Мақұлданған аккаунт жүйеге кіре алады.'));submit(approve,'Өтінімді мақұлдау',async()=>{await api('/admin/users/'+initial.id+'/approve',{method:'POST',body:{}});await route('admin');});main.append(approve);}
  if(editing&&initial.active&&initial.approved){
    const details=el('details','card');details.append(el('summary','','Құпиясөзді қалпына келтіру'));
    const reset=el('form');reset.append(el('p','muted','Жаңа құпиясөзді аккаунт иесіне беріңіз. Ол барлық құрылғыда қайта кіреді.'));
    const fresh=field(reset,'Жаңа құпиясөз (кемінде 8 таңба)','new_password','password'),again=field(reset,'Құпиясөзді қайталау','repeat_password','password');fresh.minLength=8;again.minLength=8;
    submit(reset,'Жаңа құпиясөз орнату',async status=>{
      if(fresh.value!==again.value)throw new Error('Құпиясөздер сәйкес емес.');
      const result=await api('/admin/users/'+initial.id+'/reset',{method:'POST',body:{}});
      await api('/auth/reset',{method:'POST',public:true,body:{username:initial.username,code:result.code,new_password:fresh.value}});
      fresh.value='';again.value='';
      if(initial.id===session.user.id){saveSession(null);auth(false,'Құпиясөз өзгерді. Қайта кіріңіз.');}
      else message(status,'Құпиясөз өзгертілді. Аккаунт иесі жаңа құпиясөзбен кіре алады.');
    });details.append(reset);main.append(details);
  }
  main.append(button('← Аккаунттар',()=>route('admin'),'ghost back'));
}
function choices(parent,label,rows,selected){
  const box=el('fieldset','audience stack');box.append(el('legend','',label));const result=[];
  rows.forEach(([id,name])=>{const c=el('input');c.type='checkbox';c.checked=selected.includes(id);box.append(add(el('label','checkrow'),c,el('span','',name)));result.push([id,c]);});if(!rows.length)empty(box,'Тізім әзірге бос.');parent.append(box);return()=>result.filter(([,c])=>c.checked).map(([id])=>id);
}
async function subjectEditor(main,initial={}){
  if(!adminGuard(main))return;
  heading(main,initial.id?'Пәнді өңдеу':'Жаңа пән','Оқытушылар мен топтарды пәнге тағайындаңыз.');
  const teachers=(await api('/admin/users')).filter(u=>u.role==='teacher'&&u.active&&u.approved);
  const form=el('form','card editor-form'),name=field(form,'Пәннің толық атауы','name','text',initial.name||''),code=field(form,'Пән коды','code','text',initial.code||''),teacherName=field(form,'Оқытушының көрсетілетін аты-жөні','teacher_name','text',initial.teacher_name||''),description=field(form,'Сипаттама','description','textarea',initial.description||'',false);
  name.maxLength=300;code.maxLength=80;teacherName.maxLength=200;description.maxLength=20000;
  const teacherIds=choices(form,'Оқытушылар',teachers.map(t=>[t.id,t.full_name]),initial.teacher_ids||[]),groupIds=choices(form,'Топтар',groups.map(g=>[g.id,g.name]),initial.group_ids||[]);
  const selectedTeachersAtOpen=JSON.stringify(teacherIds());
  const unavailable=(initial.teacher_ids||[]).filter(id=>!teachers.some(t=>t.id===id));
  if(unavailable.length)form.append(el('p','notice','Бұл пәнде тізімде көрсетілмеген '+unavailable.length+' оқытушы байланысы бар. Оқытушылар таңдауын өзгертпесеңіз, олар сақталады.'));
  const reassignment=el('label','checkrow'),acceptReassignment=el('input');acceptReassignment.type='checkbox';add(reassignment,acceptReassignment,el('span','','Оқытушылардың бұрынғы байланысын таңдалған тізіммен ауыстыруды растаймын.'));reassignment.hidden=true;form.append(reassignment);
  let lastTeacherSelection=selectedTeachersAtOpen;
  form.addEventListener('change',()=>{const selection=JSON.stringify(teacherIds());if(selection!==lastTeacherSelection){acceptReassignment.checked=false;lastTeacherSelection=selection;}reassignment.hidden=!initial.id||selection===selectedTeachersAtOpen;});
  if(!teachers.length)form.append(button('+ Оқытушы қосу',()=>route('userEditor',{role:'teacher'}),'ghost'));
  form.append(el('p','muted','Таңдалған оқытушылар пәнді жүргізеді. Студенттерге материалдар өз тобы арқылы ашылады.'));
  submit(form,'Пәнді сақтау',async()=>{const changed=JSON.stringify(teacherIds())!==selectedTeachersAtOpen;if(initial.id&&changed&&!acceptReassignment.checked)throw new Error('Оқытушылар байланысын ауыстыруды растаңыз.');const body={name:name.value,code:code.value,teacher_name:teacherName.value,description:description.value,group_ids:groupIds()};if(!initial.id||changed)body.teacher_ids=teacherIds();await api('/subjects'+(initial.id?'/'+initial.id:''),{method:initial.id?'PATCH':'POST',body});await route('subjects');});main.append(form);main.append(button('← Пәндер',()=>route('subjects'),'ghost back'));
}
async function lessonEditor(main,initial={}){
  if(!adminGuard(main))return;
  heading(main,initial.id?'Сабақты өңдеу':'Сабақ жоспарлау','Кесте оқытушыға және таңдалған топ студенттеріне көрінеді.');
  const teachers=(await api('/admin/users')).filter(u=>u.role==='teacher'&&u.active&&u.approved);
  if(!subjects.length){empty(main,'Алдымен пән құрыңыз және оған оқытушы мен топты тағайындаңыз.');main.append(button('+ Пән қосу',()=>route('subjectEditor')));return;}
  const form=el('form','card editor-form'),subject=select(form,'Пән',subjects.map(s=>[s.id,s.name]),initial.subject_id||subjects[0].id),teacher=select(form,'Оқытушы',[]),group=select(form,'Топ',[]);
  const hint=note(form);function fill(){const s=subjects.find(s=>String(s.id)===subject.value);teacher.replaceChildren();group.replaceChildren();teachers.filter(t=>s?.teacher_ids.includes(t.id)).forEach(t=>{const o=el('option','',t.full_name);o.value=t.id;teacher.append(o);});groups.filter(g=>s?.group_ids.includes(g.id)).forEach(g=>{const o=el('option','',g.name);o.value=g.id;group.append(o);});message(hint,!teacher.value||!group.value?'Бұл пәнге оқытушы мен топ тағайындаңыз.':'');}subject.onchange=fill;fill();if(initial.teacher_id)teacher.value=initial.teacher_id;if(initial.group_id)group.value=initial.group_id;
  const options=el('div','editor-options');form.append(options);const date=field(options,'Күні','date','date',initial.date||dateKey(new Date())),start=field(options,'Басталуы','start_time','time',initial.start_time||'09:00'),end=field(options,'Аяқталуы','end_time','time',initial.end_time||'09:50');
  const room=field(form,'Аудитория / онлайн','room','text',initial.room||''),kind=select(form,'Сабақ түрі',[['lecture','Дәріс'],['lab','Зертханалық жұмыс'],['sro','СРО'],['exam','Емтихан'],...(initial.kind&&!['lecture','lab','sro','exam'].includes(initial.kind)?[[initial.kind,initial.kind]]:[])],initial.kind||'lecture'),week=field(form,'Апта (1–15)','week','number',initial.week||1);room.maxLength=120;week.min=1;week.max=15;week.step=1;
  const cohort=el('div','stack');form.append(cohort);let selectedGroups=()=>[];function fillCohort(preserve=false){cohort.replaceChildren();const assigned=subjects.find(s=>String(s.id)===subject.value)?.group_ids||[],selected=preserve?(initial.group_ids||[initial.group_id]).filter(Boolean):[Number(group.value)];selectedGroups=choices(cohort,'Ортақ дәріс топтары',groups.filter(g=>assigned.includes(g.id)).map(g=>[g.id,g.name]),selected);cohort.append(el('p','meta','Бірнеше топқа ортақ дәріс оқытушы кестесінде бір сабақ болып көрсетіледі.'));toggleCohort();}function toggleCohort(){cohort.hidden=kind.value!=='lecture';group.parentElement.hidden=kind.value==='lecture';}kind.onchange=toggleCohort;subject.onchange=()=>{fill();fillCohort();};fillCohort(true);
  let repeat;if(!initial.id){repeat=field(form,'Апта сайын қайталау (сабақ саны)','repeat_weeks','number',1);repeat.min=1;repeat.max=15;repeat.step=1;form.append(el('p','muted','1 — тек осы күн. Қайталанатын сабақтар 15-аптадан аспауы керек.'));}
  submit(form,'Кестені сақтау',async()=>{if(!teacher.value||!group.value)throw new Error('Пәнге оқытушы мен топты тағайындаңыз.');if(start.value>=end.value)throw new Error('Аяқталу уақыты басталу уақытынан кейін болуы керек.');if(repeat&&Number(week.value)+Number(repeat.value)-1>15)throw new Error('Қайталану 15-аптадан асып кетті.');const body={subject_id:Number(subject.value),teacher_id:Number(teacher.value),group_id:Number(group.value),date:date.value,start_time:start.value,end_time:end.value,room:room.value,kind:kind.value,week:Number(week.value)};if(kind.value==='lecture'){const ids=selectedGroups();if(!ids.length)throw new Error('Дәріске кемінде бір топ таңдаңыз.');body.group_ids=ids;body.group_id=ids[0];}if(repeat)body.repeat_weeks=Number(repeat.value);await api('/schedule'+(initial.id?'/'+initial.id:''),{method:initial.id?'PATCH':'POST',body});await route('schedule',date.value);});main.append(form);
  if(initial.id){const details=el('details','card');details.append(el('summary','','Осы сабақты жою'));const remove=el('form');remove.append(el('p','muted',prettyDate(initial.date)+' · '+initial.start_time+' · '+initial.subject_name+'. Тек осы сабақ жойылады.'));submit(remove,'Сабақты жою',async()=>{await api('/schedule/'+initial.id,{method:'DELETE'});await route('schedule',initial.date);});remove.querySelector('button').className='danger';details.append(remove);main.append(details);}
  main.append(button('← Күнтізбе',()=>route('schedule',date.value),'ghost back'));
}
