package kz.unistudy.app;

import android.animation.ValueAnimator;
import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.text.*;
import org.json.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Java activity hosting XML layouts and role-scoped screen controllers. */
public class MainActivity extends Activity {
    private static Api sharedApi;
    public Api api;
    public JSONObject user;
    public JSONArray subjects=new JSONArray(), groups=new JSONArray();
    public final AcademicScreens academic=new AcademicScreens(this);
    public final ContentScreens content=new ContentScreens(this);
    private final ExecutorService workers=Executors.newFixedThreadPool(3);
    private FrameLayout container;
    private View progress, bottom;
    private String route="home";
    private int pageEpoch, pending, detailSubjectId;
    private boolean destroyed;
    private Consumer<List<Uri>> picker;
    private static final String[] ROUTES={"home","materials","assignments","schedule","profile"};
    private static final int[] NAV={R.id.nav_home,R.id.nav_materials,R.id.nav_tasks,R.id.nav_grades,R.id.nav_menu};

    @Override public void onCreate(Bundle state){
        super.onCreate(state);setContentView(R.layout.activity_main);
        container=findViewById(R.id.content);progress=findViewById(R.id.progress);bottom=findViewById(R.id.bottom_nav);
        Ui.insets(findViewById(R.id.root));if(sharedApi==null)sharedApi=new Api(this);api=sharedApi;user=api.user();subjects=api.cachedSubjects();
        for(int i=0;i<NAV.length;i++){final String destination=ROUTES[i];Button b=findViewById(NAV[i]);b.setOnClickListener(v->{if(!busy())navigate(destination);});}
        if(user==null)showLogin();else{navigate("home");refresh();}
        if(Build.VERSION.SDK_INT>=37&&checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK")!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{"android.permission.ACCESS_LOCAL_NETWORK"},701);
    }
    public boolean staff(){return user!=null&&!"student".equals(Ui.s(user,"role"));}
    public boolean admin(){return "admin".equals(Ui.s(user,"role"));}
    public JSONObject subject(int id){for(JSONObject s:Ui.list(subjects))if(s.optInt("id")==id)return s;return new JSONObject();}
    public boolean busy(){return pending>0;}
    public void message(String value){Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
    public void confirm(String title,String body,Runnable yes){new AlertDialog.Builder(this).setTitle(title).setMessage(body).setNegativeButton("Бас тарту",null).setPositiveButton("Иә",(d,w)->yes.run()).show();}
    public LinearLayout page(String title,String subtitle){pageEpoch++;container.removeAllViews();View v=getLayoutInflater().inflate(R.layout.page,container,false);container.addView(v);fadeIn(v);((TextView)v.findViewById(R.id.page_title)).setText(title);TextView caption=v.findViewById(R.id.page_subtitle);caption.setText(subtitle);caption.setVisibility(subtitle.isEmpty()?View.GONE:View.VISIBLE);v.findViewById(R.id.overflow).setOnClickListener(anchor -> overflow(anchor));return v.findViewById(R.id.page_body);}
    private void fadeIn(View view) {
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        view.setAlpha(0.86f);
        view.animate().alpha(1f).setDuration(160).start();
    }
    public <T> void run(Callable<T> work,Consumer<T> success){execute(work,success,false);}
    private <T> void execute(Callable<T> work,Consumer<T> success,boolean sessionChange){
        final long generation=api.generation();final int view=pageEpoch;pending++;progress.setVisibility(View.VISIBLE);
        workers.execute(()->{T value=null;Exception failure=null;try{if(generation!=api.generation())throw new CancellationException();value=work.call();}catch(Exception e){failure=e;}final T result=value;final Exception error=failure;
            runOnUiThread(()->{pending=Math.max(0,pending-1);if(destroyed)return;progress.setVisibility(pending==0?View.INVISIBLE:View.VISIBLE);
                if(user!=null&&api.user()==null&&(!sessionChange||error!=null)){clearLocal();showLogin();message("Сессия аяқталды. Қайта кіріңіз.");return;}
                if(view!=pageEpoch||(!sessionChange&&generation!=api.generation()))return;
                if(error!=null){if(!(error instanceof CancellationException)){message(Api.userMessage(error)); if(route.equals("schedule") && user!=null)academic.loadFailed();}return;}success.accept(result);
            });
        });
    }
    private void clearLocal(){detailSubjectId=0;user=null;subjects=new JSONArray();groups=new JSONArray();academic.reset();content.reset();}
    public void refresh(){run(()->{JSONObject me=api.me();return new Object[]{me,api.subjects(),api.array("/groups")};},values->{user=(JSONObject)values[0];subjects=(JSONArray)values[1];groups=(JSONArray)values[2];render();});}
    public void navigate(String next){
        if(user==null){showLogin();return;}
        if(!next.equals(route)&&content.hasDraft()){confirm("Сақталмаған өзгерістер","Өзгерістерді алып тастап, басқа бетке өтесіз бе?",()->{content.discardDraft();navigate(next);});return;}
        if(!next.equals(route))content.discardDraft();route=next;render();
    }
    private void render(){
        int active=Arrays.asList(ROUTES).indexOf(route);
        if(active<0)active=NAV.length-1;
        boolean navVisible=bottom.getVisibility()==View.VISIBLE;
        for(int i=0;i<NAV.length;i++){
            View button=findViewById(NAV[i]);
            boolean animate=navVisible&&i==active&&!button.isSelected();
            button.setSelected(i==active);
            if(animate)fadeIn(button);
        }
        bottom.setVisibility(View.VISIBLE);
        switch(route){case "materials":content.materials(false);break;case "assignments":content.materials(true);break;case "journal":academic.journal();break;case "schedule":academic.schedule();break;case "downloads":content.downloads();break;case "subjects":subjectsPage();break;case "subject":subjectDetail();break;case "groups":groupsPage();break;case "profile":profile();break;case "admin":adminPage();break;case "menu":profile();break;default:home();}
    }
    private LinearLayout auth(String title){pageEpoch++;bottom.setVisibility(View.GONE);container.removeAllViews();View v=getLayoutInflater().inflate(R.layout.auth,container,false);container.addView(v);fadeIn(v);v.findViewById(R.id.server_button).setOnClickListener(w->{if(!busy())server();});LinearLayout body=v.findViewById(R.id.auth_body);Ui.title(body,title);return body;}
    public void showLogin(){
        LinearLayout p=auth("Қош келдіңіз");EditText login=Ui.field(p,"Логин","",false),pass=Ui.password(p,"Құпиясөз");
        Ui.button(p,"Кіру",()->{String name=login.getText().toString().trim(),password=pass.getText().toString();if(name.isEmpty()){Ui.error(login,"Логинді енгізіңіз");return;}if(password.isEmpty()){Ui.error(pass,"Құпиясөзді енгізіңіз");return;}
            execute(()->api.login(name,password),me->{clearLocal();user=me;route="home";render();refresh();},true);});
        Ui.secondary(p,"Тіркелу",this::register);Ui.secondary(p,"Құпиясөзді қалпына келтіру",this::resetPassword);
    }
    private boolean newPassword(EditText password,EditText repeat){String s=password.getText().toString();if(s.length()<8){Ui.error(password,"Кемінде 8 таңба");return false;}if(!s.equals(repeat.getText().toString())){Ui.error(repeat,"Құпиясөздер сәйкес емес");return false;}return true;}
    private void register(){
        LinearLayout p=auth("Жаңа аккаунт");EditText name=Ui.field(p,"Аты-жөні","",false),login=Ui.field(p,"Логин","",false),pass=Ui.password(p,"Құпиясөз"),repeat=Ui.password(p,"Құпиясөзді қайталау");
        Spinner role=Ui.spinner(p,"Рөл",Arrays.asList("Студент","Оқытушы"),0,null);LinearLayout groupArea=Ui.form(this);p.addView(groupArea);final JSONArray[] available={new JSONArray()};final Spinner[] selected={null};
        run(()->api.publicGroups(),rows->{available[0]=rows;List<String> labels=new ArrayList<>();for(JSONObject g:Ui.list(rows))labels.add(Ui.s(g,"name"));selected[0]=Ui.spinner(groupArea,"Топ",labels,0,null);});
        Ui.text(p,"Тіркелгеннен кейін әкімші аккаунтты растайды.");Ui.button(p,"Аккаунт ашу",()->{if(!newPassword(pass,repeat))return;boolean student=role.getSelectedItemPosition()==0;Object gid=student&&selected[0]!=null&&selected[0].getSelectedItemPosition()>=0?available[0].optJSONObject(selected[0].getSelectedItemPosition()).optInt("id"):null;JSONObject body=Ui.j("full_name",name.getText().toString(),"username",login.getText().toString(),"password",pass.getText().toString(),"role",student?"student":"teacher","group_id",gid);run(()->api.request("/auth/register","POST",body,false),v->{message("Аккаунт құрылды. Әкімші растауын күтіңіз.");showLogin();});});Ui.secondary(p,"Кіруге оралу",this::showLogin);
    }
    private void resetPassword(){LinearLayout p=auth("Құпиясөзді қалпына келтіру");EditText login=Ui.field(p,"Логин","",false),code=Ui.field(p,"Әкімші берген код","",false),pass=Ui.password(p,"Жаңа құпиясөз"),repeat=Ui.password(p,"Қайта енгізіңіз");Ui.button(p,"Қалпына келтіру",()->{if(!newPassword(pass,repeat))return;JSONObject b=Ui.j("username",login.getText().toString(),"code",code.getText().toString(),"new_password",pass.getText().toString());run(()->api.request("/auth/reset","POST",b,false),v->{message("Құпиясөз өзгерді");showLogin();});});Ui.secondary(p,"Кіруге оралу",this::showLogin);}
    private void passwordPage() {
        LinearLayout p=page("Құпиясөзді өзгерту","Кемінде 8 таңба");
        EditText old=Ui.password(p,"Ағымдағы құпиясөз");
        EditText password=Ui.password(p,"Жаңа құпиясөз");
        EditText repeat=Ui.password(p,"Қайта енгізіңіз");
        Ui.button(p,"Құпиясөзді сақтау",()->{
            if(old.length()==0){Ui.error(old,"Ағымдағы құпиясөзді енгізіңіз");return;}
            if(!newPassword(password,repeat))return;
            JSONObject body=Ui.j("current_password",old.getText().toString(),"new_password",password.getText().toString());
            execute(()->{api.object("/auth/password","POST",body);api.clearSession();return true;},v->{
                clearLocal();showLogin();message("Құпиясөз өзгерді. Қайта кіріңіз.");
            },true);
        });
        Ui.secondary(p,"Артқа",this::profile);
    }
    private String checkedEndpoint(EditText input) {
        String endpoint=input.getText().toString().trim().replaceAll("/+$","");
        if(Rules.validEndpoint(endpoint))return endpoint;
        Ui.error(input,"Дұрыс http:// немесе https:// адресін енгізіңіз");
        return null;
    }
    private void server() {
        LinearLayout form=Ui.form(this);
        EditText url=Ui.field(form,"Сервер адресі",api.endpoint(),false);
        url.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        Ui.text(form,"Эмуляторда http://10.0.2.2:8000 адресін қолданыңыз. Телефон мен компьютерді бір Wi-Fi желісіне қосып, компьютердің жергілікті IP адресін және сервер портын енгізіңіз.");
        TextView status=Ui.text(form,"");
        status.setVisibility(View.GONE);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        watch(url,()->status.setVisibility(View.GONE));
        Ui.secondary(form,"Қосылымды тексеру",()->{
            String endpoint=checkedEndpoint(url);
            if(endpoint==null)return;
            status.setVisibility(View.VISIBLE);
            status.setText("Қосылым тексерілуде…");
            run(()->{
                try{api.testConnection(endpoint);return "UniStudy серверіне қосылдық.";}
                catch(Exception error){return Api.userMessage(error);}
            },result->{
                if(endpoint.equals(url.getText().toString().trim().replaceAll("/+$",""))){
                    status.setVisibility(View.VISIBLE);
                    status.setText(result);
                }
            });
        });
        AlertDialog[] dialog={null};
        dialog[0]=Ui.dialog(this,"Сервер",form,"Сақтау",()->{
            String endpoint=checkedEndpoint(url);
            if(endpoint==null)return;
            if(endpoint.equals(api.endpoint())){dialog[0].dismiss();return;}
            Runnable apply=()->{api.configure(endpoint);clearLocal();dialog[0].dismiss();showLogin();};
            if(user!=null)confirm("Серверді өзгерту","Аккаунттан шығып, жаңа серверге қосыласыз ба?",apply);
            else apply.run();
        });
    }
    private String roleName(){return admin()?"Әкімші":staff()?"Оқытушы":"Студент";}
    private void home(){
        boolean teacher="teacher".equals(Ui.s(user,"role"));
        String fullName=Ui.s(user,"full_name");
        LinearLayout p=page("Басты бет",teacher?"":Ui.s(user,"group_name"));
        View hero=getLayoutInflater().inflate(R.layout.hero,p,false);
        p.addView(hero);
        ((TextView)hero.findViewById(R.id.hero_title)).setText(teacher?Rules.teacherHeading(fullName):fullName);
        hero.findViewById(R.id.hero_action).setOnClickListener(v->{if(!busy())navigate("schedule");});
        if(admin()) {
            LinearLayout manage=Ui.card(p);
            manage.setBackgroundResource(R.drawable.bg_admin_card);
            Ui.badge(manage,"ӘКІМШІ");
            Ui.title(manage,"Оқу кеңістігін басқару");
            Ui.button(manage,"Оқытушылар мен студенттер",()->navigate("admin"));
            Ui.secondary(manage,"Топтар мен студенттер",()->navigate("groups"));
            Ui.secondary(manage,"Пәндер мен оқытушылар",()->navigate("subjects"));
            Ui.secondary(manage,"Кестені басқару",()->navigate("schedule"));
        }
        LinearLayout row=Ui.row(p);Ui.secondary(row,"Материалдар",()->navigate("materials"));Ui.secondary(row,"Тапсырмалар",()->navigate("assignments"));Ui.secondary(p,"Бағалар журналы",()->navigate("journal"));Ui.title(p,"Менің пәндерім · "+subjects.length());
        for(JSONObject s:Ui.list(subjects)){LinearLayout c=Ui.card(p);subjectBadge(c,s);Ui.title(c,Ui.s(s,"name"));Ui.text(c,Ui.s(s,"teacher_name"));Ui.secondary(c,"Материалдарға өту",()->{content.selectSubject(s.optInt("id"));navigate("materials");});}
        Ui.secondary(p,"Деректерді жаңарту",this::refresh);
    }
    private void subjectBadge(LinearLayout card, JSONObject subject) {
        TextView badge = Ui.badge(card, Ui.s(subject,"code"));
        android.graphics.drawable.Drawable icon = getDrawable(R.drawable.nav_book).mutate();
        icon.setTint(getColor(R.color.indigo));
        int size = Ui.dp(this, 16);
        icon.setBounds(0,0,size,size);
        badge.setCompoundDrawables(icon,null,null,null);
        badge.setCompoundDrawablePadding(Ui.dp(this,6));
    }
    private void overflow(View anchor) {
        if(busy()) return;
        PopupMenu popup = new PopupMenu(this, anchor);
        Map<String,Runnable> actions = new LinkedHashMap<>();
        actions.put("Жаңарту", this::refresh);
        if(route.equals("materials") || route.equals("assignments")) {
            actions.put("Іздеу және сүзгілер", content::focusSearch);
            if(staff()) actions.put("Материал қосу", content::startNewMaterial);
        }
        if(route.equals("schedule") && admin()) actions.put("Сабақ қосу", academic::addLesson);
        if(!route.equals("journal")) actions.put("Бағалар журналы", () -> navigate("journal"));
        if(!route.equals("schedule")) actions.put("Сабақ кестесі", () -> navigate("schedule"));
        if(admin()&&!route.equals("admin")) actions.put("Әкімші панелі", () -> navigate("admin"));
        if(route.equals("profile")) actions.put("Аккаунттан шығу", this::logout);
        for(String label : actions.keySet()) popup.getMenu().add(label);
        popup.setOnMenuItemClickListener(item -> { actions.get(item.getTitle().toString()).run(); return true; });
        popup.show();
    }
    private void profile() {
        LinearLayout p = page("Жеке кабинет", "");
        LinearLayout identity = Ui.card(p);
        identity.setBackgroundResource(R.drawable.bg_hero);
        Ui.badge(identity, roleName());
        Ui.title(identity, Ui.s(user,"full_name")).setTextColor(android.graphics.Color.WHITE);
        String group = Ui.s(user,"group_name");
        Ui.text(identity, Ui.s(user,"username") + (group.isEmpty() ? "" : "  ·  " + group)).setTextColor(android.graphics.Color.WHITE);
        LinearLayout account = Ui.card(p);
        Ui.title(account, "Аккаунт");
        Ui.secondary(account, "Логинді өзгерту", this::editProfile);
        Ui.secondary(account, "Профиль деректерін өңдеу", this::editProfile);
        Ui.secondary(account, "Құпиясөзді өзгерту", this::passwordPage);
        LinearLayout study = Ui.card(p);
        Ui.title(study, "Оқу");
        Ui.secondary(study, "Менің пәндерім", () -> navigate("subjects"));
        Ui.secondary(study, "Бағалар журналы", () -> navigate("journal"));
        Ui.secondary(study, "Жүктелген файлдар", () -> navigate("downloads"));
        Ui.secondary(study, "Сабақ кестесі", () -> navigate("schedule"));
        if(staff()) {
            LinearLayout manage = Ui.card(p);
            Ui.title(manage, "Басқару");
            Ui.secondary(manage, "Топтар мен студенттер", () -> navigate("groups"));
            if(admin()) {
                Ui.secondary(manage, "Оқытушылар мен студенттер", () -> navigate("admin"));
                Ui.secondary(manage, "Пәндер мен оқытушылар", () -> navigate("subjects"));
                Ui.secondary(manage, "Кестені басқару", () -> navigate("schedule"));
            }
        }
        Ui.secondary(p, "Сервер баптауы", this::server);
        Ui.secondary(p, "Аккаунттан шығу", this::logout);
    }
    private void editProfile() {
        LinearLayout form = Ui.form(this);
        EditText name = Ui.field(form, "Аты-жөні", Ui.s(user,"full_name"), false);
        EditText login = Ui.field(form, "Логин", Ui.s(user,"username"), false);
        EditText pass = Ui.password(form, "Ағымдағы құпиясөз");
        AlertDialog[] dialog = {null};
        dialog[0] = Ui.dialog(this, "Профиль деректері", form, "Сақтау", () -> {
            JSONObject body = Ui.j("username",login.getText().toString().trim(),"full_name",name.getText().toString().trim(),"current_password",pass.getText().toString());
            run(() -> { api.object("/auth/profile","PATCH",body); return api.me(); }, me -> {
                user = me; dialog[0].dismiss(); profile();
            });
        });
    }
    private void logout(){confirm("Аккаунттан шығу",academic.hasDraft()||content.hasDraft()?"Сақталмаған өзгерістер жойылады. Шығасыз ба?":"Аккаунттан шығасыз ба?",()->execute(()->{try{api.logout();}catch(Exception offline){/* Api clears this session even if the server is unavailable. */}return true;},v->{clearLocal();showLogin();},true));}
    private void subjectsPage(){LinearLayout p=page("Пәндер","Толық атауы, коды және оқытушысы");if(admin())Ui.button(p,"Пән қосу",()->editSubject(null));EditText search=Ui.field(p,"Пәнді іздеу","",false);LinearLayout list=Ui.form(this);p.addView(list);Runnable draw=()->{list.removeAllViews();String q=search.getText().toString().toLowerCase(Locale.ROOT);for(JSONObject s:Ui.list(subjects)){if(!(Ui.s(s,"name")+Ui.s(s,"code")+Ui.s(s,"teacher_name")).toLowerCase(Locale.ROOT).contains(q))continue;LinearLayout c=Ui.card(list);subjectBadge(c,s);Ui.title(c,Ui.s(s,"name"));Ui.text(c,Ui.s(s,"teacher_name"));Ui.text(c,Ui.s(s,"description"));subjectLinks(c,s);Ui.secondary(c,"Пәнді ашу",()->openSubject(s));if(staff())Ui.secondary(c,"Өңдеу",()->editSubject(s));}};watch(search,draw);draw.run();}
    private void openSubject(JSONObject subject) {
        detailSubjectId=subject.optInt("id");
        navigate("subject");
    }
    private void subjectDetail() {
        JSONObject subject=subject(detailSubjectId);
        if(!subject.has("id")){navigate("subjects");return;}
        LinearLayout p=page(Ui.s(subject,"name"),Ui.s(subject,"code")+" · "+Ui.s(subject,"teacher_name"));
        String description=Ui.s(subject,"description");
        if(!description.isEmpty())Ui.text(p,description);
        subjectLinks(p,subject);
        Ui.button(p,"Материалдар",()->{content.selectSubject(detailSubjectId);navigate("materials");});
        Ui.secondary(p,"Тапсырмалар",()->{content.selectSubject(detailSubjectId);navigate("assignments");});
        Ui.secondary(p,"Пәндерге оралу",()->navigate("subjects"));
    }
    public void subjectLinks(LinearLayout parent, JSONObject subject) {
        JSONArray links=subject.optJSONArray("external_links");
        if(links==null)return;
        boolean titled=false;
        for(JSONObject link:Ui.list(links)){
            String title=Ui.s(link,"title").trim();
            String url=Ui.s(link,"url").trim();
            if(title.isEmpty()||!Rules.validExternalLink(url))continue;
            if(!titled){
                Ui.title(parent,"Google Drive материалдары");
                titled=true;
            }
            Ui.secondary(parent,title,()->{
                try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE));}
                catch(ActivityNotFoundException|SecurityException error){message("Сілтемені ашу үшін Google Drive немесе браузер қажет.");}
            });
        }
    }
    private void editSubject(JSONObject original){if(admin())run(()->api.array("/admin/users"),users->subjectForm(original,users));else subjectForm(original,new JSONArray());}
    private void subjectForm(JSONObject old,JSONArray users) {
        LinearLayout p=Ui.form(this);
        EditText name=Ui.field(p,"Пәннің толық атауы",Ui.s(old,"name"),true),code=Ui.field(p,"Код",Ui.s(old,"code"),false),teacher=Ui.field(p,"Оқытушының аты-жөні",Ui.s(old,"teacher_name"),true),desc=Ui.field(p,"Сипаттама",Ui.s(old,"description"),true);
        List<CheckBox> groupBoxes=Ui.selectIds(p,"Қолжетімді топтар",groups,old==null?Collections.emptyList():Ui.intList(old.optJSONArray("group_ids")));
        JSONArray teachers=new JSONArray();
        List<Integer> available=new ArrayList<>();
        for(JSONObject u:Ui.list(users))if("teacher".equals(Ui.s(u,"role"))&&u.optBoolean("active")&&u.optBoolean("approved")){
            teachers.put(u);available.add(u.optInt("id"));
        }
        List<Integer> original=old==null?Collections.emptyList():Ui.intList(old.optJSONArray("teacher_ids"));
        List<Integer> unavailable=new ArrayList<>(original);unavailable.removeAll(available);
        List<CheckBox> teacherBoxes=admin()?Ui.selectIds(p,"Оқытушы аккаунттары",teachers,original):Collections.emptyList();
        if(admin()&&!unavailable.isEmpty())Ui.text(p,"Пәнге белсенді емес оқытушы тағайындалған. Оқытушылар таңдауын өзгертпесеңіз, бұрынғы байланыстар сақталады.");
        AlertDialog[] d={null};
        d[0]=Ui.dialog(this,old==null?"Пән қосу":"Пәнді өңдеу",p,"Сақтау",()->{
            JSONObject body=Ui.j("name",name.getText().toString(),"code",code.getText().toString(),"teacher_name",teacher.getText().toString(),"description",desc.getText().toString(),"group_ids",Ui.checkedIds(groupBoxes));
            JSONArray selected=Ui.checkedIds(teacherBoxes);
            boolean replaceTeachers=admin()&&(old==null||Rules.visibleAssignmentsChanged(original,available,Ui.intList(selected)));
            if(replaceTeachers)try{body.put("teacher_ids",selected);}catch(JSONException ignored){}
            Runnable save=()->run(()->{api.object(old==null?"/subjects":"/subjects/"+old.optInt("id"),old==null?"POST":"PATCH",body);return api.subjects();},rows->{subjects=rows;d[0].dismiss();subjectsPage();});
            if(replaceTeachers&&!unavailable.isEmpty())confirm("Оқытушыларды ауыстыру","Таңдалған оқытушылар сақталады. Белсенді емес оқытушылардың осы пәнмен байланысы жойылады. Жалғастырасыз ба?",save);
            else save.run();
        });
    }
    private void groupsPage(){if(!staff()){navigate("home");return;}LinearLayout p=page("Топтар","Топтар мен ішкі топтардың студенттері");Ui.button(p,"Жаңа топ қосу",()->{LinearLayout f=Ui.form(this);EditText name=Ui.field(f,"Топ атауы","",false);AlertDialog[] d={null};d[0]=Ui.dialog(this,"Топ қосу",f,"Қосу",()->{JSONObject b=Ui.j("name",name.getText().toString());run(()->{api.object("/groups","POST",b);return api.array("/groups");},g->{groups=g;d[0].dismiss();groupsPage();});});});List<JSONObject> rows=Ui.list(groups);if(rows.isEmpty()){Ui.text(p,"Топтар жоқ");return;}List<String> names=new ArrayList<>();for(JSONObject g:rows)names.add(Ui.s(g,"name"));LinearLayout list=Ui.form(this);final int[] selected={0};Ui.spinner(p,"Топ",names,0,i->{selected[0]=i;loadStudents(list,rows.get(i).optInt("id"));});p.addView(list);Ui.secondary(p,"Жаңа студент қосу",()->{JSONObject g=rows.get(selected[0]);int gid=g.optInt("id");academic.newStudent(gid,Ui.s(g,"name"),()->loadStudents(list,gid));});if(admin())Ui.secondary(p,"Студентті топқа қосу / ауыстыру",()->run(()->api.array("/admin/users"),users->{List<JSONObject> students=new ArrayList<>();List<String> labels=new ArrayList<>();for(JSONObject u:Ui.list(users))if("student".equals(Ui.s(u,"role"))){students.add(u);labels.add(Ui.s(u,"full_name")+" · "+Ui.s(u,"group_name"));}LinearLayout f=Ui.form(this);Spinner s=Ui.spinner(f,"Студент",labels,0,null);AlertDialog[] d={null};d[0]=Ui.dialog(this,"Студентті ауыстыру",f,"Сақтау",()->{if(students.isEmpty())return;int gid=rows.get(selected[0]).optInt("id");JSONObject b=Ui.j("user_id",students.get(s.getSelectedItemPosition()).optInt("id"));run(()->api.object("/groups/"+gid+"/students","POST",b),v->{d[0].dismiss();loadStudents(list,gid);});});}));loadStudents(list,rows.get(0).optInt("id"));}
    private int rosterRequest;
    private void loadStudents(LinearLayout list,int id){int request=++rosterRequest;list.removeAllViews();Ui.text(list,"Жүктелуде…");run(()->api.array("/groups/"+id+"/students"),rows->{if(request!=rosterRequest)return;list.removeAllViews();Ui.title(list,rows.length()+" студент");for(JSONObject u:Ui.list(rows)){LinearLayout c=Ui.card(list);Ui.title(c,Ui.s(u,"full_name"));Ui.text(c,Ui.s(u,"username"));}});}
    private void adminPage() {
        if(!admin()){navigate("home");return;}
        LinearLayout p=page("Пайдаланушылар","Оқытушылар, студенттер және тіркелу өтінімдері");
        LinearLayout actions=Ui.card(p);
        actions.setBackgroundResource(R.drawable.bg_admin_card);
        Ui.badge(actions,"ӘКІМШІ · БАСҚАРУ");
        Ui.title(actions,"Оқу кеңістігін басқару");
        Ui.button(actions,"Оқытушы қосу",()->createUser("teacher"));
        Ui.secondary(actions,"Студент аккаунтын қосу",()->createUser("student"));
        Ui.secondary(actions,"Топтар мен студенттер",()->navigate("groups"));
        Ui.secondary(actions,"Пәндер мен оқытушылар",()->navigate("subjects"));
        Ui.secondary(actions,"Кестені басқару",()->navigate("schedule"));
        run(()->api.array("/admin/users"),users->{
            int teachers=0,students=0,waiting=0;
            for(JSONObject u:Ui.list(users)){
                if("teacher".equals(Ui.s(u,"role")))teachers++;
                if("student".equals(Ui.s(u,"role")))students++;
                if(!u.optBoolean("approved"))waiting++;
            }
            Ui.badge(p,teachers+" оқытушы · "+students+" студент · "+waiting+" өтінім");
            EditText search=Ui.field(p,"Аты немесе логині бойынша іздеу","",false);
            LinearLayout list=Ui.form(this);
            int[] selected={0};
            Runnable draw=()->{
                list.removeAllViews();
                String q=search.getText().toString().trim().toLowerCase(Locale.ROOT);
                int found=0;
                for(JSONObject u:Ui.list(users)){
                    String role=Ui.s(u,"role");
                    if(selected[0]==1&&!role.equals("teacher") || selected[0]==2&&!role.equals("student") || selected[0]==3&&u.optBoolean("approved"))continue;
                    if(!(Ui.s(u,"full_name")+" "+Ui.s(u,"username")+" "+Ui.s(u,"group_name")).toLowerCase(Locale.ROOT).contains(q))continue;
                    found++;
                    LinearLayout c=Ui.card(list);
                    Ui.badge(c,role.equals("teacher")?"Оқытушы":role.equals("student")?"Студент":"Әкімші");
                    Ui.title(c,Ui.s(u,"full_name"));
                    Ui.text(c,Ui.s(u,"username")+(Ui.s(u,"group_name").isEmpty()?"":" · "+Ui.s(u,"group_name")));
                    Ui.badge(c,!u.optBoolean("approved")?"Растауды күтуде":u.optBoolean("active")?"Белсенді":"Бұғатталған");
                    if(!u.optBoolean("approved"))Ui.button(c,"Тіркелуді растау",()->run(()->api.object("/admin/users/"+u.optInt("id")+"/approve","POST",Ui.j()),v->adminPage()));
                    Ui.secondary(c,"Аккаунтты өңдеу",()->editUser(u));
                    Ui.secondary(c,"Қалпына келтіру коды",()->confirm("Қалпына келтіру коды","Осы пайдаланушы үшін жаңа код жасалсын ба?",()->run(()->api.object("/admin/users/"+u.optInt("id")+"/reset","POST",Ui.j()),v->{
                        LinearLayout f=Ui.form(this);Ui.text(f,"Кодты тек аккаунт иесіне беріңіз.");
                        TextView text=Ui.text(f,Ui.s(v,"code"));text.setTextIsSelectable(true);
                        new AlertDialog.Builder(this).setTitle("Қалпына келтіру коды").setView(Ui.scroll(f)).setPositiveButton("Жабу",null).show();
                    })));
                }
                if(found==0)Ui.text(list,"Пайдаланушылар табылмады. Іздеуді немесе сүзгіні өзгертіңіз.");
            };
            Ui.spinner(p,"Пайдаланушылар сүзгісі",Arrays.asList("Барлығы","Оқытушылар","Студенттер","Растауды күтуде"),0,i->{selected[0]=i;draw.run();});
            p.addView(list);watch(search,draw);draw.run();
        });
    }
    private void createUser(String role) {
        if(!admin())return;
        LinearLayout f=Ui.form(this);
        Ui.text(f,"Аккаунт бірден белсенді болады. Логин мен құпиясөзді аккаунт иесіне беріңіз.");
        EditText name=Ui.field(f,"Аты-жөні","",false);
        EditText login=Ui.field(f,"Логин","",false);
        login.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        EditText password=Ui.password(f,"Құпиясөз");
        EditText repeat=Ui.password(f,"Құпиясөзді қайталау");
        List<JSONObject> gs=Ui.list(groups);
        List<String> labels=new ArrayList<>();labels.add("Топсыз");
        for(JSONObject g:gs)labels.add(Ui.s(g,"name"));
        Spinner group=role.equals("student")?Ui.spinner(f,"Топ",labels,0,null):null;
        AlertDialog[] dialog={null};
        dialog[0]=Ui.dialog(this,role.equals("teacher")?"Оқытушы қосу":"Студент аккаунтын қосу",f,"Аккаунт ашу",()->{
            if(name.getText().toString().trim().isEmpty()||name.getText().toString().trim().length()>200){Ui.error(name,"Аты-жөні: 1–200 таңба");return;}
            int loginLength=login.getText().toString().trim().length();
            if(loginLength<3||loginLength>80){Ui.error(login,"Логин: 3–80 таңба");return;}
            if(password.length()>256){Ui.error(password,"Құпиясөз: 8–256 таңба");return;}
            if(!newPassword(password,repeat))return;
            int index=group==null?0:group.getSelectedItemPosition();
            JSONObject body=Ui.j("full_name",name.getText().toString().trim(),"username",login.getText().toString().trim(),"password",password.getText().toString(),"role",role,"group_id",index>0?gs.get(index-1).optInt("id"):null);
            run(()->api.object("/admin/users","POST",body),created->{
                password.setText("");repeat.setText("");dialog[0].dismiss();adminPage();
                message("Аккаунт құрылды"+(role.equals("teacher")?". Пәндер мен оқытушылар бөлімінде пән тағайындаңыз.":"."));
            });
        });
    }
    private void editUser(JSONObject u){LinearLayout f=Ui.form(this);List<String> roles=Arrays.asList("student","teacher","admin");Spinner role=Ui.spinner(f,"Рөл",roles,Math.max(0,roles.indexOf(Ui.s(u,"role"))),null);CheckBox active=Ui.check(f,"Белсенді",u.optBoolean("active"));List<JSONObject> gs=Ui.list(groups);List<String> labels=new ArrayList<>();labels.add("Топсыз");int initial=0;for(int i=0;i<gs.size();i++){labels.add(Ui.s(gs.get(i),"name"));if(gs.get(i).optInt("id")==u.optInt("group_id"))initial=i+1;}Spinner group=Ui.spinner(f,"Топ",labels,initial,null);AlertDialog[] d={null};d[0]=Ui.dialog(this,"Аккаунт",f,"Сақтау",()->{String r=roles.get(role.getSelectedItemPosition());int g=group.getSelectedItemPosition();JSONObject b=Ui.j("role",r,"active",active.isChecked(),"group_id",r.equals("student")&&g>0?gs.get(g-1).optInt("id"):null);run(()->api.object("/admin/users/"+u.optInt("id"),"PATCH",b),v->{d[0].dismiss();adminPage();});});}
    private void watch(EditText input,Runnable update){input.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){update.run();}public void afterTextChanged(Editable e){}});}
    public void pickFiles(Consumer<List<Uri>> callback){picker=callback;Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivityForResult(intent,702);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==702){Consumer<List<Uri>> callback=picker;picker=null;if(result==RESULT_OK&&data!=null&&callback!=null){List<Uri> uris=new ArrayList<>();if(data.getClipData()!=null)for(int i=0;i<data.getClipData().getItemCount();i++)uris.add(data.getClipData().getItemAt(i).getUri());else if(data.getData()!=null)uris.add(data.getData());callback.accept(uris);}}}
    public void openFile(Api.LocalFile file){startActivity(new Intent(this,FileViewerActivity.class).putExtra("path",file.file.getAbsolutePath()).putExtra("name",Ui.s(file.meta,"name")).putExtra("mime",Ui.s(file.meta,"mime")).putExtra("title",file.title));}
    @Override public void onBackPressed(){if(busy())return;if(user==null)showLogin();else if(route.equals("subject"))navigate("subjects");else if(!route.equals("home"))navigate("home");else if(academic.hasDraft()||content.hasDraft())confirm("Шығу","Сақталмаған өзгерістер жойылады. Қосымшаны жабасыз ба?",this::finish);else super.onBackPressed();}
    @Override protected void onDestroy(){destroyed=true;workers.shutdown();super.onDestroy();}
}
