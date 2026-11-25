package kz.unistudy.app;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.LinearLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntConsumer;

/** Native XML-backed journal and timetable. Server remains authoritative for averages and scope. */
public final class AcademicScreens {
    private final MainActivity host;
    private int subjectId, groupId, assessmentId, version;
    private JSONObject book;
    private final Map<Integer,String> values = new LinkedHashMap<>();
    private final Map<Integer,String> original = new LinkedHashMap<>();
    private LocalDate selectedDay = LocalDate.parse(Ui.today());
    private boolean monthView = true;
    private LinearLayout loadingDetail;
    private boolean conflict;
    private String bulk = "";
    private static final List<String> KINDS = Arrays.asList("lecture", "sro", "lab");

    public AcademicScreens(MainActivity host) { this.host = host; }
    public void newStudent(int groupId, String groupName, Runnable saved) {
        if(!host.staff() || host.busy()) return;
        LinearLayout form=Ui.form(host);
        Ui.text(form,"Топ: "+groupName);
        EditText fullName=Ui.field(form,"Аты-жөні","",false);
        EditText username=Ui.field(form,"Логин","",false);
        EditText password=Ui.password(form,"Құпиясөз");
        EditText repeat=Ui.password(form,"Құпиясөзді қайталау");
        Ui.text(form,"Студент аккаунты бірден белсенді болады. Логин мен құпиясөзді студентке жеке беріңіз.");
        final AlertDialog[] dialog={null};
        dialog[0]=Ui.dialog(host,"Жаңа студент",form,"Қосу",()->{
            if(host.busy()) return;
            if(value(fullName).isEmpty() || value(fullName).length()>200){Ui.error(fullName,"Аты-жөні: 1–200 таңба");return;}
            if(value(username).length()<3 || value(username).length()>80){Ui.error(username,"Логин: 3–80 таңба");return;}
            String raw=password.getText().toString();
            if(raw.length()<8 || raw.length()>256){Ui.error(password,"Құпиясөз: 8–256 таңба");return;}
            if(!raw.equals(repeat.getText().toString())){Ui.error(repeat,"Құпиясөздер сәйкес емес");return;}
            JSONObject body=Ui.j("full_name",value(fullName),"username",value(username),"password",raw);
            host.run(()->host.api.object("/groups/"+groupId+"/new-student","POST",body),result->{
                password.setText("");repeat.setText("");dialog[0].dismiss();
                host.message("Студент қосылды");saved.run();
            });
        });
    }
    public boolean hasDraft() { return !values.equals(original); }
    public void discardDraft() { values.clear(); values.putAll(original); conflict=false; }
    public void reset() { subjectId=groupId=assessmentId=version=0; book=null; values.clear(); original.clear(); conflict=false; bulk=""; selectedDay=LocalDate.parse(Ui.today()); monthView=true; }
    private static LocalDate weekStart(LocalDate day) { return day.minusDays(day.getDayOfWeek().getValue()-1); }
    private static int integer(EditText e) { try { return Integer.parseInt(e.getText().toString().trim()); } catch(Exception ignored) { return 0; } }
    private static String value(EditText e) { return e.getText().toString().trim(); }
    private static void watch(EditText field, java.util.function.Consumer<String> changed) {
        field.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            public void onTextChanged(CharSequence s,int start,int before,int count) { changed.accept(s.toString()); }
            public void afterTextChanged(Editable e) {}
        });
    }
    private void transition(Runnable action) {
        if(host.busy()) return;
        if(hasDraft()) host.confirm("Сақталмаған бағалар", "Жалғастырсаңыз, енгізілген өзгерістер жойылады.", ()->{discardDraft(); action.run();});
        else action.run();
    }
    private List<JSONObject> groupsFor(JSONObject subject) {
        List<Integer> ids=Ui.intList(subject==null?new JSONArray():subject.optJSONArray("group_ids"));
        List<JSONObject> result=new ArrayList<>();
        for(JSONObject g:Ui.list(host.groups)) if(ids.contains(g.optInt("id")) && (host.staff() || g.optInt("id")==host.user.optInt("group_id"))) result.add(g);
        return result;
    }
    private static int index(List<JSONObject> rows,int id) { for(int i=0;i<rows.size();i++) if(rows.get(i).optInt("id")==id)return i; return 0; }
    private static int first(List<JSONObject> rows) {return rows.isEmpty()?0:rows.get(0).optInt("id");}
    private void choice(LinearLayout p,String label,List<JSONObject> rows,int id,IntConsumer change) {
        List<String> names=new ArrayList<>();
        for(JSONObject row:rows) names.add(row.optString("name",row.optString("full_name","#"+row.optInt("id"))));
        if(rows.isEmpty()){ Ui.text(p,label+": қолжетімді жазба жоқ"); return; }
        Ui.spinner(p,label,names,index(rows,id),i->{if(i>=0&&i<rows.size()) change.accept(rows.get(i).optInt("id"));});
    }
    private void clearBook() {book=null; assessmentId=version=0; values.clear(); original.clear(); conflict=false;}
    public void journal() {
        List<JSONObject> subjects=Ui.list(host.subjects);
        if(!subjects.isEmpty() && (subjectId==0 || host.subject(subjectId)==null)) {subjectId=first(subjects);clearBook();}
        List<JSONObject> groups=groupsFor(host.subject(subjectId));
        boolean found=false;for(JSONObject g:groups)if(g.optInt("id")==groupId)found=true;
        if(!found){groupId=first(groups);clearBook();}
        LinearLayout page=host.page("Бағалар журналы",host.staff()?"Бір топ · бір бағалау · ортақ сақтау":"Менің нәтижелерім");
        choice(page,"Пән",subjects,subjectId,id->{if(id!=subjectId)transition(()->{subjectId=id;groupId=0;clearBook();journal();});});
        choice(page,"Топ",groups,groupId,id->{if(id!=groupId)transition(()->{groupId=id;clearBook();journal();});});
        Ui.text(page,"0–100 орташаға кіреді. НП — қатыспады, орташаға кірмейді. Бос ұяшық — баға жоқ.");
        if(subjectId==0||groupId==0){Ui.text(page,"Пән мен оған тіркелген топ қажет.");return;}
        if(host.staff())Ui.button(page,"+ Бағалау",()->transition(this::createAssessment));
        Ui.secondary(page,"Жаңарту",()->transition(()->{clearBook();journal();}));
        if(book==null){
            Ui.text(page,"Журнал жүктелуде…");
            int sid=subjectId,gid=groupId;
            host.run(()->host.api.object("/gradebook?subject_id="+sid+"&group_id="+gid,"GET",null),data->{book=data;journal();});
            return;
        }
        List<JSONObject> assessments=Ui.list(book.optJSONArray("assessments"));
        if(!host.staff()) {
            int uid=host.user.optInt("id"); Ui.title(page,"Орташа балл: "+average(uid));
            for(JSONObject a:assessments){LinearLayout c=Ui.card(page);Ui.title(c,Ui.s(a,"title"));Ui.text(c,Ui.s(a,"date")+" · "+a.optInt("week")+"-апта · "+Ui.kind(Ui.s(a,"kind")));Ui.badge(c,grade(a.optInt("id"),uid).replace("NP","НП"));}
            if(assessments.isEmpty())Ui.text(page,"Бағалар әзірге жоқ.");
            return;
        }
        if(assessments.isEmpty()){Ui.text(page,"Журналды бастау үшін бағалау қосыңыз.");return;}
        if(assessmentId==0)chooseAssessment(assessments.get(0));
        List<String> labels=new ArrayList<>();int selected=0;
        for(int i=0;i<assessments.size();i++){JSONObject a=assessments.get(i);labels.add(a.optInt("week")+"-апта · "+Ui.s(a,"title")+" · "+Ui.s(a,"date"));if(a.optInt("id")==assessmentId)selected=i;}
        Ui.spinner(page,"Бағалау",labels,selected,i->{JSONObject a=assessments.get(i);if(a.optInt("id")!=assessmentId)transition(()->{chooseAssessment(a);journal();});});
        if(conflict){
            Ui.text(page,"Журнал басқа құрылғыда өзгертілді. Енгізілген бағалар сақталды. Сервердегі мәндерді жүктеп, салыстырыңыз.");
            Ui.button(page,"Сервердегі бағаларды салыстыру",this::reviewConflict);
        }
        EditText fill=Ui.field(page,"Барлығына: 0–100 / НП / бос",bulk,false);watch(fill,s->bulk=s);
        Ui.secondary(page,"Барлығына қолдану",()->{if(host.busy())return;if(!Rules.validGrade(value(fill))){Ui.error(fill,"0–100 немесе НП енгізіңіз");return;}String v=Rules.canonicalGrade(value(fill));for(Integer uid:values.keySet())values.put(uid,v==null?"":v);journal();});
        for(JSONObject student:Ui.list(book.optJSONArray("students"))){
            int uid=student.optInt("id"); LinearLayout card=Ui.card(page);
            Ui.title(card,Ui.s(student,"full_name"));Ui.text(card,"Сақталған орташа: "+average(uid));
            EditText field=Ui.field(card,"Баға: 0–100 / НП",values.getOrDefault(uid,""),false);
            watch(field,s->{values.put(uid,s);if(!Rules.validGrade(s))field.setError("0–100 немесе НП");});
            Ui.secondary(card,"НП — қатыспады",()->field.setText("NP"));
            Ui.secondary(card,"Бағаны тазалау",()->field.setText(""));
        }
        Ui.button(page,"Топ бағаларын сақтау",this::saveGrades);
        Ui.secondary(page,"Өзгерістерді тастау",()->transition(()->journal()));
        int deleting=assessmentId;
        Ui.secondary(page,"Бағалау бағанын жою",()->host.confirm("Бағалауды жою", "Осы бағандағы барлық бағалар жойылады.",()->{if(host.busy())return;host.run(()->host.api.object("/assessments/"+deleting,"DELETE",null),r->{clearBook();journal();});}));
    }
    private String grade(int aid,int uid) {for(JSONObject e:Ui.list(book.optJSONArray("entries")))if(e.optInt("assessment_id")==aid&&e.optInt("student_id")==uid)return Ui.s(e,"value");return "";}
    private String average(int uid) {for(JSONObject a:Ui.list(book.optJSONArray("averages")))if(a.optInt("student_id")==uid)return a.isNull("average")?"—":String.format(Locale.getDefault(),"%.1f",a.optDouble("average"));return "—";}
    private void chooseAssessment(JSONObject a) {assessmentId=a.optInt("id");version=a.optInt("version",1);values.clear();for(JSONObject s:Ui.list(book.optJSONArray("students")))values.put(s.optInt("id"),grade(assessmentId,s.optInt("id")));original.clear();original.putAll(values);conflict=false;bulk="";}
    private void saveGrades(){
        if(host.busy()||!host.staff())return;
        if(conflict){host.message("Алдымен сервердегі бағаларды салыстырыңыз.");return;}
        JSONArray entries=new JSONArray();Map<Integer,String> sent=new LinkedHashMap<>();
        for(Map.Entry<Integer,String> e:values.entrySet()){
            if(!Rules.validGrade(e.getValue())){host.message("Баға 0–100 немесе НП болуы керек.");return;}
            String canonical=Rules.canonicalGrade(e.getValue());sent.put(e.getKey(),e.getValue());entries.put(Ui.j("student_id",e.getKey(),"value",canonical==null?JSONObject.NULL:canonical));
        }
        int aid=assessmentId;JSONObject body=Ui.j("version",version,"entries",entries);
        host.run(()->{try{return host.api.object("/assessments/"+aid+"/grades","PUT",body);}catch(Api.Error e){if(e.status==409)return Ui.j("conflict",true);throw e;}},result->{
            if(result.optBoolean("conflict")){conflict=true;journal();return;}
            version=result.optInt("version",version+1);original.clear();original.putAll(sent);host.message("Топ бағалары сақталды");
            host.run(()->host.api.object("/gradebook?subject_id="+subjectId+"&group_id="+groupId,"GET",null),data->{book=data;journal();});
        });
    }
    private void reviewConflict(){
        if(host.busy())return;
        host.run(()->host.api.object("/gradebook?subject_id="+subjectId+"&group_id="+groupId,"GET",null),fresh->{
            JSONObject assessment=null;for(JSONObject a:Ui.list(fresh.optJSONArray("assessments")))if(a.optInt("id")==assessmentId)assessment=a;
            if(assessment==null){host.message("Бағалау жойылған. Енгізілген мәндер сақталды; оларды көшіріп, журналды жаңартыңыз.");return;}
            int nextVersion=assessment.optInt("version");LinearLayout form=Ui.form(host);
            for(JSONObject student:Ui.list(fresh.optJSONArray("students"))){int uid=student.optInt("id");String server="";for(JSONObject e:Ui.list(fresh.optJSONArray("entries")))if(e.optInt("assessment_id")==assessmentId&&e.optInt("student_id")==uid)server=Ui.s(e,"value");Ui.text(form,Ui.s(student,"full_name")+"\nСервер: "+server+" · Сіз енгізген: "+values.getOrDefault(uid,""));}
            final AlertDialog[] dialog={null};dialog[0]=Ui.dialog(host,"Бағаларды салыстыру",form,"Енгізілген бағалармен жалғастыру",()->{version=nextVersion;book=fresh;conflict=false;Map<Integer,String> retained=new LinkedHashMap<>();for(JSONObject s:Ui.list(fresh.optJSONArray("students"))){int uid=s.optInt("id");retained.put(uid,values.getOrDefault(uid,grade(assessmentId,uid)));}values.clear();values.putAll(retained);dialog[0].dismiss();journal();});
        });
    }
    private void createAssessment(){
        LinearLayout form=Ui.form(host);EditText title=Ui.field(form,"Бағалау атауы","",false);EditText date=dateField(form,"Күні",Ui.today());EditText week=Ui.field(form,"Оқу аптасы (1–15)","1",false);final String[] kind={"lab"};kindChoice(form,kind);
        final AlertDialog[] dialog={null};dialog[0]=Ui.dialog(host,"Жаңа бағалау",form,"Қосу",()->{
            if(host.busy())return;if(value(title).isEmpty()){Ui.error(title,"Атауын енгізіңіз");return;}if(!Rules.validDate(value(date))){Ui.error(date,"Күнді тексеріңіз");return;}if(integer(week)<1||integer(week)>15){Ui.error(week,"1–15");return;}
            JSONObject body=Ui.j("subject_id",subjectId,"group_id",groupId,"title",value(title),"date",value(date),"week",integer(week),"kind",kind[0]);
            host.run(()->host.api.object("/assessments","POST",body),r->{dialog[0].dismiss();clearBook();journal();});
        });
    }
    private void kindChoice(LinearLayout form,String[] kind){List<String> labels=new ArrayList<>();for(String k:KINDS)labels.add(Ui.kind(k));Ui.spinner(form,"Сабақ түрі",labels,Math.max(0,KINDS.indexOf(kind[0])),i->kind[0]=KINDS.get(i));}
    private EditText dateField(LinearLayout form,String label,String initial){EditText f=Ui.field(form,label+" (YYYY-MM-DD)",initial,false);Ui.secondary(form,"Күнді таңдау",()->{LocalDate day;try{day=LocalDate.parse(value(f));}catch(Exception e){day=LocalDate.parse(Ui.today());}new DatePickerDialog(host,(v,y,m,d)->f.setText(LocalDate.of(y,m+1,d).toString()),day.getYear(),day.getMonthValue()-1,day.getDayOfMonth()).show();});return f;}
    private EditText timeField(LinearLayout form,String label,String initial){EditText f=Ui.field(form,label,initial,false);Ui.secondary(form,label+" — таңдау",()->{LocalTime t;try{t=LocalTime.parse(value(f));}catch(Exception e){t=LocalTime.of(9,0);}new TimePickerDialog(host,(v,h,m)->f.setText(String.format(Locale.ROOT,"%02d:%02d",h,m)),t.getHour(),t.getMinute(),true).show();});return f;}
    private static final Locale KK = Locale.forLanguageTag("kk-KZ");
    private String dateLabel(LocalDate date, String pattern) {
        return date.format(java.time.format.DateTimeFormatter.ofPattern(pattern, KK));
    }
    public void schedule() {
        LinearLayout page = host.page("Күнтізбе", "");
        LinearLayout card = Ui.card(page);
        LinearLayout modes = Ui.row(card);
        if(monthView) {
            Ui.button(modes,"Айлық",()->{});
            Ui.secondary(modes,"Апталық",()->{monthView=false; schedule();});
        } else {
            Ui.secondary(modes,"Айлық",()->{monthView=true; schedule();});
            Ui.button(modes,"Апталық",()->{});
        }
        LocalDate start = monthView ? selectedDay.withDayOfMonth(1) : weekStart(selectedDay);
        LocalDate end = monthView ? start.plusMonths(1).minusDays(1) : start.plusDays(6);
        android.widget.TextView period = Ui.title(card, monthView ? dateLabel(start,"LLLL yyyy") : dateLabel(start,"d MMM") + " — " + dateLabel(end,"d MMM yyyy"));
        period.setCompoundDrawablesWithIntrinsicBounds(0,0,R.drawable.nav_calendar,0);
        period.setFocusable(true);
        period.setContentDescription(period.getText() + " · Күнді таңдау");
        period.setOnClickListener(view -> new DatePickerDialog(host,(picker,y,m,d)->{
            selectedDay=LocalDate.of(y,m+1,d);
            schedule();
        },selectedDay.getYear(),selectedDay.getMonthValue()-1,selectedDay.getDayOfMonth()).show());
        LinearLayout controls = Ui.row(card);
        Ui.secondary(controls,"‹",()->movePeriod(-1)).setContentDescription(monthView?"Алдыңғы ай":"Алдыңғы апта");
        Ui.secondary(controls,"Бүгін",()->{selectedDay=LocalDate.parse(Ui.today());schedule();});
        Ui.secondary(controls,"›",()->movePeriod(1)).setContentDescription(monthView?"Келесі ай":"Келесі апта");
        LinearLayout grid = Ui.form(host);
        card.addView(grid);
        LinearLayout detail = Ui.form(host);
        page.addView(detail);
        loadingDetail=detail;
        Ui.text(detail,"Кесте жүктелуде…");
        String path = "/schedule?from_date="+start+"&to_date="+end;
        host.run(()->host.api.array(path), result -> {
            loadingDetail=null;
            Map<String,List<JSONObject>> days = new LinkedHashMap<>();
            for(JSONObject lesson : Ui.list(result)) {
                days.computeIfAbsent(Ui.s(lesson,"date"), key -> new ArrayList<>()).add(lesson);
            }
            for(List<JSONObject> lessons : days.values())
                lessons.sort(java.util.Comparator.comparing(lesson -> Ui.s(lesson,"start_time")));
            drawCalendar(grid,detail,start,end,days);
        });
    }
    public void loadFailed() {
        if(loadingDetail==null || !loadingDetail.isAttachedToWindow()) return;
        loadingDetail.removeAllViews();
        Ui.text(loadingDetail,"Кестені жүктеу мүмкін болмады.");
        Ui.secondary(loadingDetail,"Қайталау",this::schedule);
        loadingDetail=null;
    }
    private void movePeriod(int step) {
        selectedDay = monthView ? selectedDay.plusMonths(step) : selectedDay.plusWeeks(step);
        schedule();
    }
    private void drawCalendar(LinearLayout grid, LinearLayout detail, LocalDate start, LocalDate end, Map<String,List<JSONObject>> days) {
        grid.removeAllViews();
        LinearLayout headers = Ui.row(grid);
        for(String name : Arrays.asList("Дс","Сс","Ср","Бс","Жм","Сб","Жс")) {
            android.widget.TextView label = Ui.text(headers,name);
            label.setGravity(android.view.Gravity.CENTER);
            label.setTextSize(12);
        }
        for(LocalDate week=weekStart(start); !week.isAfter(end); week=week.plusWeeks(1)) {
            LinearLayout row=Ui.row(grid);
            for(int i=0;i<7;i++) {
                LocalDate date=week.plusDays(i);
                android.widget.TextView cell = new android.widget.TextView(host);
                row.addView(cell,new LinearLayout.LayoutParams(0,Ui.dp(host,48),1));
                if(date.isBefore(start)||date.isAfter(end)) continue;
                boolean selected=date.equals(selectedDay);
                boolean marked=days.containsKey(date.toString());
                cell.setGravity(android.view.Gravity.CENTER);
                cell.setTextSize(14);
                cell.setText(date.getDayOfMonth() + (marked?"\n●":""));
                cell.setTextColor(host.getColor(selected?android.R.color.white:R.color.ink));
                if(selected) cell.setBackgroundResource(R.drawable.bg_primary);
                else if(marked) cell.setBackgroundResource(R.drawable.bg_pill);
                cell.setSelected(selected);
                cell.setFocusable(true);
                cell.setContentDescription(date + (marked?" · Сабақ бар":" · Сабақ жоқ"));
                cell.setOnClickListener(v->{selectedDay=date; drawCalendar(grid,detail,start,end,days);});
            }
        }
        detail.removeAllViews();
        Ui.title(detail,dateLabel(selectedDay,"d MMMM · EEEE"));
        if(host.admin()) Ui.secondary(detail,"+ Сабақ қосу",this::addLesson);
        List<JSONObject> lessons = days.get(selectedDay.toString());
        if(lessons==null) {Ui.text(detail,"Бұл күні сабақ жоқ.");return;}
        for(JSONObject lesson : lessons) lessonCard(detail,lesson);
    }
    public void addLesson() { openLesson(null); }
    private void lessonCard(LinearLayout parent, JSONObject lesson) {
        LinearLayout card=Ui.card(parent);
        String kind=Ui.s(lesson,"kind");
        android.widget.TextView badge=Ui.badge(card,Ui.kind(kind)+"  ·  "+Ui.s(lesson,"start_time")+" — "+Ui.s(lesson,"end_time"));
        badge.setTextColor(android.graphics.Color.parseColor("lab".equals(kind)?"#126153":"sro".equals(kind)?"#854213":"#4441A6"));
        Ui.title(card,Ui.s(lesson,"subject_name"));
        Ui.text(card,Ui.s(lesson,"teacher_name"));
        Ui.text(card,Ui.s(lesson,"group_name")+"  ·  Аудитория "+Ui.s(lesson,"room"));
        Ui.text(card,lesson.optInt("week")+"-апта  ·  "+Ui.sourceLabel(Ui.s(lesson,"source")));
        if(host.admin()) {
            LinearLayout actions=Ui.row(card);
            Ui.secondary(actions,"Өңдеу",()->openLesson(lesson));
            Ui.secondary(actions,"Жою",()->host.confirm("Сабақты жою",Ui.s(lesson,"subject_name")+" · "+selectedDay,
                    ()->host.run(()->host.api.object("/schedule/"+lesson.optInt("id"),"DELETE",null),ignored->schedule())));
        }
    }
    private void openLesson(JSONObject lesson){if(!host.admin()||host.busy())return;host.run(()->host.api.array("/admin/users"),users->lessonEditor(lesson,users));}
    private void lessonEditor(JSONObject lesson,JSONArray users){
        List<JSONObject> subjects=Ui.list(host.subjects);if(subjects.isEmpty()){host.message("Алдымен пән қосыңыз.");return;}
        LinearLayout form=Ui.form(host);int[] sid={lesson==null?first(subjects):lesson.optInt("subject_id")};int[] gid={lesson==null?0:lesson.optInt("group_id")};int[] tid={lesson==null?0:lesson.optInt("teacher_id")};
        LinearLayout scoped=Ui.form(host);
        Runnable scope=()->{scoped.removeAllViews();JSONObject s=host.subject(sid[0]);List<JSONObject> gs=groupsFor(s);List<JSONObject> ts=new ArrayList<>();List<Integer> ids=Ui.intList(s==null?new JSONArray():s.optJSONArray("teacher_ids"));for(JSONObject u:Ui.list(users))if(ids.contains(u.optInt("id"))&&"teacher".equals(Ui.s(u,"role"))&&u.optBoolean("active")&&u.optBoolean("approved"))ts.add(u);boolean gOk=false,tOk=false;for(JSONObject g:gs)if(g.optInt("id")==gid[0])gOk=true;for(JSONObject t:ts)if(t.optInt("id")==tid[0])tOk=true;if(!gOk)gid[0]=first(gs);if(!tOk)tid[0]=first(ts);choice(scoped,"Топ",gs,gid[0],id->gid[0]=id);choice(scoped,"Оқытушы",ts,tid[0],id->tid[0]=id);};
        choice(form,"Пән",subjects,sid[0],id->{sid[0]=id;gid[0]=tid[0]=0;scope.run();});form.addView(scoped);scope.run();
        EditText date=dateField(form,"Күні",lesson==null?selectedDay.toString():Ui.s(lesson,"date"));
        EditText start=timeField(form,"Басталуы",lesson==null?"09:00":Ui.s(lesson,"start_time"));EditText end=timeField(form,"Аяқталуы",lesson==null?"09:50":Ui.s(lesson,"end_time"));EditText room=Ui.field(form,"Аудитория / онлайн бөлме",lesson==null?"":Ui.s(lesson,"room"),false);EditText week=Ui.field(form,"Оқу аптасы (1–15)",lesson==null?"1":String.valueOf(lesson.optInt("week")),false);String[] kind={lesson==null?"lecture":Ui.s(lesson,"kind")};kindChoice(form,kind);
        EditText repeat=lesson==null?Ui.field(form,"Қатарынан неше апта? (1–15)","1",false):null;
        Ui.text(form,lesson==null?"Апта сайын осы күн мен уақытта. Соңғы оқу аптасы 15-тен аспайды.":"Өзгеріс тек осы күнгі сабаққа қолданылады.");
        Ui.text(form,"Оқытушы, топ немесе аудитория бос болмаса, сервер сақтауды тоқтатады.");
        final AlertDialog[] dialog={null};dialog[0]=Ui.dialog(host,lesson==null?"Жаңа сабақ":"Сабақты өңдеу",form,"Сақтау",()->{
            if(host.busy())return;
            if(sid[0]==0||gid[0]==0||tid[0]==0){host.message("Пәнге тіркелген топ пен оқытушы қажет.");return;}
            if(!Rules.validDate(value(date))){Ui.error(date,"Күнді тексеріңіз");return;}
            String clock="(?:[01][0-9]|2[0-3]):[0-5][0-9]";
            if(!value(start).matches(clock)){Ui.error(start,"HH:MM");return;}if(!value(end).matches(clock)||value(start).compareTo(value(end))>=0){Ui.error(end,"Аяқталуы басталуынан кейін болуы керек");return;}
            if(value(room).isEmpty()){Ui.error(room,"Аудиторияны енгізіңіз");return;}int w=integer(week),r=repeat==null?1:integer(repeat);if(w<1||w>15){Ui.error(week,"1–15");return;}if(r<1||r>15||w+r-1>15){Ui.error(repeat,"Соңғы апта 15-тен аспауы керек");return;}
            JSONObject body=Ui.j("subject_id",sid[0],"group_id",gid[0],"teacher_id",tid[0],"date",value(date),"start_time",value(start),"end_time",value(end),"room",value(room),"kind",kind[0],"week",w);
            if(lesson==null)body=Ui.j("subject_id",sid[0],"group_id",gid[0],"teacher_id",tid[0],"date",value(date),"start_time",value(start),"end_time",value(end),"room",value(room),"kind",kind[0],"week",w,"repeat_weeks",r);
            JSONObject payload=body;String path=lesson==null?"/schedule":"/schedule/"+lesson.optInt("id");String method=lesson==null?"POST":"PATCH";
            host.run(()->host.api.request(path,method,payload,true),result->{dialog[0].dismiss();selectedDay=LocalDate.parse(value(date));schedule();});
        });
    }
}
