package kz.unistudy.app;

import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.function.Consumer;

/** Native XML-backed material, assignment and offline screens. */
public final class ContentScreens {
    private final MainActivity a;
    private int subjectId, week, kindIndex;
    private boolean assignments, drafts, offline;
    private String search="";
    private JSONArray items=new JSONArray();
    private LinearLayout materialResults;
    private EditText searchField;
    private Draft editor;
    private Answer answer;
    private static final List<String> KINDS=Arrays.asList("lecture","sro","lab","note","guide","exam");
    private static final class Draft {
        int id, subject; String title="", description="", week="", kind="lecture", section="resource", deadline="";
        boolean published, dirty; List<Integer> groups=new ArrayList<>();
        JSONArray files=new JSONArray(); final List<Uri> pending=new ArrayList<>();
    }
    private static final class Answer {
        JSONObject material, own; JSONArray rows=new JSONArray(); String text="", saved=""; boolean initialized;
        final List<Uri> pending=new ArrayList<>();
    }
    public ContentScreens(MainActivity activity){a=activity;}
    public void selectSubject(int id){if(!hasDraft())discardDraft();subjectId=id;week=0;kindIndex=0;search="";drafts=false;items=new JSONArray();}
    private void filterChanged(){if(materialResults!=null)renderItems(materialResults);}
    public boolean hasDraft(){return editor!=null&&(editor.dirty||!editor.pending.isEmpty())||answer!=null&&(!answer.text.equals(answer.saved)||!answer.pending.isEmpty());}
    public void discardDraft(){editor=null;answer=null;materialResults=null;searchField=null;}
    public void reset(){discardDraft();subjectId=0;week=0;kindIndex=0;search="";drafts=false;items=new JSONArray();}
    private static List<JSONObject> records(JSONArray array){return array==null?Collections.emptyList():Ui.list(array);}
    private void watch(EditText view,Consumer<String> update){view.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){} public void onTextChanged(CharSequence s,int start,int before,int count){update.accept(s.toString());}public void afterTextChanged(Editable e){}});}
    private void leave(Runnable next){if(a.busy())return;if(hasDraft())a.confirm("Сақталмаған өзгерістер","Өзгерістерді алып тастап шығасыз ба?",()->{discardDraft();next.run();});else{discardDraft();next.run();}}
    public void materials(boolean assignmentSection){
        assignments=assignmentSection;
        if(editor!=null){renderEditor();return;}
        if(answer!=null){renderAnswer();return;}
        List<JSONObject> subjects=records(a.subjects);
        if(subjects.isEmpty()){a.page(assignments?"Тапсырмалар":"Материалдар","Қолжетімді пәндер жоқ");return;}
        if(subjects.stream().noneMatch(s->s.optInt("id")==subjectId))subjectId=0;
        items=new JSONArray();offline=false;
        renderMaterials();
        a.run(a.api::allMaterials,result->{items=result.rows;offline=result.offline;filterChanged();});
    }
    public void focusSearch(){
        if(searchField==null || !searchField.isShown())return;
        searchField.requestFocus();
        android.view.inputmethod.InputMethodManager keyboard=(android.view.inputmethod.InputMethodManager)a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if(keyboard!=null)keyboard.showSoftInput(searchField,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
    }
    public void startNewMaterial(){
        if(a.staff() && !records(a.subjects).isEmpty())leave(()->edit(null));
    }
    private static List<String> kindLabels(){
        List<String> labels=new ArrayList<>();
        for(String kind:KINDS)labels.add(Ui.kind(kind));
        return labels;
    }
    private void renderMaterials(){
        LinearLayout p=a.page(assignments?"Тапсырмалар":"Материалдар","Оқу материалдарын іздеу");
        List<JSONObject> subjects=records(a.subjects);
        List<String> labels=new ArrayList<>();labels.add("Барлық пәндер");int selected=0;
        for(int i=0;i<subjects.size();i++){labels.add(Ui.s(subjects.get(i),"name"));if(subjects.get(i).optInt("id")==subjectId)selected=i+1;}
        Ui.spinner(p,"Пән",labels,selected,i->{subjectId=i==0?0:subjects.get(i-1).optInt("id");materials(assignments);});
        if(subjectId!=0){
            JSONObject subject=a.subject(subjectId);
            Ui.text(p,Ui.s(subject,"code")+" · "+Ui.s(subject,"teacher_name"));
            a.subjectLinks(p,subject);
        }
        List<String> weeks=new ArrayList<>(Arrays.asList("Барлық апталар","Жалпы материалдар"));
        for(int i=1;i<=15;i++)weeks.add(i+"-апта");
        Ui.spinner(p,"Оқу аптасы",weeks,week<0?1:week==0?0:week+1,i->{week=i==0?0:i==1?-1:i-1;filterChanged();});
        List<String> kinds=kindLabels();kinds.add(0,"Барлығы");
        Ui.spinner(p,"Түрі",kinds,kindIndex,i->{kindIndex=i;filterChanged();});
        if(a.staff()){CheckBox c=Ui.check(p,"Тек жарияланбаған жобалар",drafts);c.setOnCheckedChangeListener((v,b)->{drafts=b;filterChanged();});}
        EditText query=Ui.field(p,"Атау, сипаттама немесе файл атауы",search,false);searchField=query;
        LinearLayout results=Ui.card(p);materialResults=results;
        watch(query,value->{search=value;renderItems(results);});
        if(a.staff())Ui.button(p,assignments?"Тапсырма қосу":"Материал қосу",()->edit(null));
        Ui.secondary(p,"Жаңарту",()->materials(assignments));renderItems(results);
    }
    private void renderItems(LinearLayout p){
        p.removeAllViews();int count=0;
        if(offline)Ui.text(p,"Офлайн · соңғы сақталған материалдар");
        String query=android.icu.lang.UCharacter.foldCase(search.trim(),true);
        Set<Integer> permittedSubjects=new HashSet<>();
        for(JSONObject subject:records(a.subjects))permittedSubjects.add(subject.optInt("id"));
        for(JSONObject m:records(items)){
            String section=Ui.s(m,"section");if(section.isEmpty())section="lecture".equals(Ui.s(m,"kind"))?"resource":"assignment";
            if(assignments && !"assignment".equals(section)||kindIndex>0&&!KINDS.get(kindIndex-1).equals(Ui.s(m,"kind")))continue;
            if(!permittedSubjects.contains(m.optInt("subject_id")))continue;
            if(subjectId!=0 && m.optInt("subject_id")!=subjectId)continue;
            if(drafts==m.optBoolean("published"))continue;
            if(week<0 && !m.isNull("week") || week>0 && m.optInt("week")!=week)continue;
            if(!matches(m,query))continue;
            count++;LinearLayout card=Ui.card(p);
            String when=m.isNull("week")?"Жалпы материалдар":m.optInt("week")+"-апта";
            Ui.badge(card,Ui.kind(Ui.s(m,"kind"))+" · "+when+(m.optBoolean("published")?"":" · Жоба"));
            Ui.text(card,Ui.s(m,"subject_name"));
            Ui.title(card,Ui.s(m,"title"));Ui.text(card,Ui.s(m,"description"));
            if(!Ui.s(m,"deadline").isEmpty())Ui.text(card,"Мерзімі: "+Ui.s(m,"deadline"));
            for(JSONObject f:records(m.optJSONArray("files")))file(card,f,Ui.s(m,"title"),null);
            if(a.staff()){
                Ui.secondary(card,"Өңдеу",()->edit(m));
                Ui.secondary(card,"Материалды жою",()->a.confirm("Материалды жою",Ui.s(m,"title"),()->a.run(()->a.api.request("/materials/"+m.optInt("id"),"DELETE",null,true),r->materials(assignments))));
            }
            if("assignment".equals(section)&&m.optBoolean("published"))Ui.button(card,a.staff()?"Жауаптарды қарау":"Жұмысты тапсыру",()->showSubmission(m));
        }
        if(count==0)Ui.text(p,"Материал табылмады. Аптаны немесе сүзгіні өзгертіңіз.");
    }
    private static boolean matches(JSONObject material,String query){
        if(query.isEmpty())return true;
        for(String field:Arrays.asList("title","description"))
            if(android.icu.lang.UCharacter.foldCase(Ui.s(material,field),true).contains(query))return true;
        for(JSONObject file:records(material.optJSONArray("files")))
            if(android.icu.lang.UCharacter.foldCase(Ui.s(file,"name"),true).contains(query))return true;
        return false;
    }
    private void file(LinearLayout p,JSONObject f,String title,Runnable remove){
        String name=Ui.s(f,"name");int dot=name.lastIndexOf('.');
        String format=dot>=0 && dot+1<name.length()?name.substring(dot+1).toUpperCase(Locale.ROOT):Ui.s(f,"mime");
        Ui.secondary(p,name+" · "+format+" · "+Ui.size(f.optLong("size")),()->a.run(()->{String key=fileKey(f);for(Api.LocalFile cached:a.api.downloads())if(fileKey(cached.meta).equals(key))return cached;return a.api.download(f,title);},a::openFile));
        if(remove!=null)Ui.secondary(p,"Тіркемені жою",remove);
    }
    private String fileKey(JSONObject f){String key=Ui.s(f,"download_path");return key.isEmpty()?"/files/"+f.optInt("id")+"/download":key;}
    private void edit(JSONObject m){
        Draft d=new Draft();d.subject=m!=null?m.optInt("subject_id"):subjectId!=0?subjectId:records(a.subjects).get(0).optInt("id");d.week=week>0?String.valueOf(week):"";d.section=assignments?"assignment":"resource";d.kind=assignments?"lab":"lecture";
        JSONObject subject=a.subject(d.subject);d.groups=new ArrayList<>(Ui.intList(subject==null?new JSONArray():subject.optJSONArray("group_ids")));
        if(m!=null){d.id=m.optInt("id");d.title=Ui.s(m,"title");d.description=Ui.s(m,"description");d.week=m.isNull("week")?"":String.valueOf(m.optInt("week"));d.kind=Ui.s(m,"kind");d.section=Ui.s(m,"section");d.deadline=Ui.s(m,"deadline");if(d.deadline.length()>10)d.deadline=d.deadline.substring(0,10);d.published=m.optBoolean("published");d.groups=new ArrayList<>(Ui.intList(m.optJSONArray("group_ids")));d.files=m.optJSONArray("files");}
        editor=d;renderEditor();
    }
    private EditText draftField(LinearLayout p,String label,String value,boolean multi,Consumer<String> setter){EditText e=Ui.field(p,label,value,multi);watch(e,s->{setter.accept(s);if(editor!=null)editor.dirty=true;});return e;}
    private void renderEditor(){
        final Draft d=editor;if(d==null)return;
        LinearLayout p=a.page(d.id==0?"Жаңа материал":"Материалды өңдеу","Файлдарды қосып, топтарға жариялаңыз");
        Ui.secondary(p,"← Артқа",()->leave(()->materials(assignments)));
        if(d.id==0){
            List<JSONObject> subjects=records(a.subjects);List<String> names=new ArrayList<>();int selected=0;
            for(int i=0;i<subjects.size();i++){names.add(Ui.s(subjects.get(i),"name"));if(subjects.get(i).optInt("id")==d.subject)selected=i;}
            Ui.spinner(p,"Пән",names,selected,i->{JSONObject subject=subjects.get(i);d.subject=subject.optInt("id");d.groups=new ArrayList<>(Ui.intList(subject.optJSONArray("group_ids")));d.dirty=true;renderEditor();});
        }else Ui.text(p,Ui.s(a.subject(d.subject),"name"));
        draftField(p,"Атауы",d.title,false,s->d.title=s);draftField(p,"Сипаттамасы",d.description,true,s->d.description=s);
        draftField(p,"Апта (1–15, жалпы материал үшін бос қалдырыңыз)",d.week,false,s->d.week=s);
        Ui.spinner(p,"Түрі",kindLabels(),Math.max(0,KINDS.indexOf(d.kind)),i->{d.kind=KINDS.get(i);d.dirty=true;});
        Ui.spinner(p,"Бөлім",Arrays.asList("Материал","Тапсырма"),"assignment".equals(d.section)?1:0,i->{d.section=i==0?"resource":"assignment";d.dirty=true;});
        draftField(p,"Дедлайн YYYY-MM-DD (міндетті емес)",d.deadline,false,s->d.deadline=s);
        JSONArray available=new JSONArray();JSONObject subject=a.subject(d.subject);List<Integer> allowed=Ui.intList(subject==null?new JSONArray():subject.optJSONArray("group_ids"));
        for(JSONObject g:records(a.groups))if(allowed.contains(g.optInt("id")))available.put(g);
        for(CheckBox c:Ui.selectIds(p,"Қолжетімді топтар",available,d.groups))c.setOnCheckedChangeListener((v,b)->{Integer id=(Integer)c.getTag();if(b&&!d.groups.contains(id))d.groups.add(id);if(!b)d.groups.remove(id);d.dirty=true;});
        CheckBox published=Ui.check(p,"Студенттерге жариялау",d.published);published.setOnCheckedChangeListener((v,b)->{d.published=b;d.dirty=true;});
        for(JSONObject f:records(d.files))file(p,f,d.title,()->a.confirm("Тіркемені жою",Ui.s(f,"name"),()->a.run(()->a.api.request("/files/"+f.optInt("id"),"DELETE",null,true),r->{d.files=without(d.files,f.optInt("id"));renderEditor();})));
        pending(p,d.pending,()->renderEditor());
        Ui.secondary(p,"Файлдар қосу",()->{if(a.busy())return;a.pickFiles(uris->{if(editor!=d)return;for(Uri uri:uris)if(!d.pending.contains(uri))d.pending.add(uri);renderEditor();});});
        Ui.button(p,"Сақтау",()->save(d));
    }
    private JSONArray without(JSONArray files,int id){JSONArray result=new JSONArray();for(JSONObject f:records(files))if(f.optInt("id")!=id)result.put(f);return result;}
    private void pending(LinearLayout p,List<Uri> files,Runnable redraw){for(Uri uri:new ArrayList<>(files)){Ui.text(p,"Жаңа файл: "+displayName(uri));Ui.secondary(p,"Таңдаудан алып тастау",()->{if(a.busy())return;files.remove(uri);redraw.run();});}}
    private String displayName(Uri uri){try(android.database.Cursor c=a.getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0);}catch(Exception ignored){}return "Таңдалған файл";}
    private String signature(Draft d){return Ui.j("subject_id",d.subject,"title",d.title,"description",d.description,"week",d.week,"kind",d.kind,"section",d.section,"deadline",d.deadline,"published",d.published,"groups",Ui.ids(d.groups)).toString();}
    private void save(Draft d){
        if(a.busy())return;Integer w=null;try{if(!d.week.trim().isEmpty())w=Integer.valueOf(d.week.trim());}catch(Exception ignored){}
        if(d.title.trim().isEmpty()||(!d.week.trim().isEmpty()&&(w==null||w<1||w>15))){a.message("Атауын және 1–15 аралығындағы аптаны тексеріңіз");return;}
        if(!d.deadline.trim().isEmpty()&&!Rules.validDate(d.deadline.trim())){a.message("Дедлайн YYYY-MM-DD форматында болуы керек");return;}
        JSONObject body=Ui.j("subject_id",d.subject,"title",d.title.trim(),"description",d.description,"week",w,"kind",d.kind,"section",d.section,"published",d.published,"group_ids",Ui.ids(d.groups),"deadline",d.deadline.trim().isEmpty()?null:d.deadline.trim()+"T23:59:00+05:00");
        final String sent=signature(d);
        a.run(()->a.api.object(d.id==0?"/materials":"/materials/"+d.id,d.id==0?"POST":"PATCH",body),saved->{d.id=saved.optInt("id");d.files=saved.optJSONArray("files");d.dirty=!sent.equals(signature(d));renderEditor();uploadMaterial(d);});
    }
    private void uploadMaterial(Draft d){
        if(d.pending.isEmpty()){if(d.dirty){renderEditor();a.message("Жіберілген нұсқа сақталды. Жаңа өзгерістерді де сақтаңыз.");return;}editor=null;a.message("Материал сақталды");materials(assignments);return;}
        Uri uri=d.pending.get(0);a.run(()->a.api.upload("/materials/"+d.id+"/files",uri),f->{if(d.files==null)d.files=new JSONArray();d.files.put(f);d.pending.remove(uri);renderEditor();uploadMaterial(d);});
    }
    public void showSubmission(JSONObject m){Answer s=new Answer();s.material=m;answer=s;renderAnswer();loadAnswer(s);}
    private void loadAnswer(Answer s){a.run(()->a.api.array("/materials/"+s.material.optInt("id")+"/submissions"),rows->{s.rows=rows;s.own=null;for(JSONObject r:records(rows))if(r.optInt("student_id")==a.user.optInt("id"))s.own=r;if(!s.initialized){s.text=s.own==null?"":Ui.s(s.own,"text");s.saved=s.text;s.initialized=true;}renderAnswer();});}
    private void renderAnswer(){
        Answer s=answer;if(s==null)return;LinearLayout p=a.page(a.staff()?"Студенттердің жауаптары":"Менің жауабым",Ui.s(s.material,"title"));
        Ui.secondary(p,"← Тапсырмалар",()->leave(()->materials(true)));Ui.text(p,Ui.s(s.material,"description"));
        Ui.secondary(p,"Жаңарту",()->loadAnswer(s));
        if(a.staff()){
            if(s.rows.length()==0)Ui.text(p,"Әзірге жауап жоқ");
            for(JSONObject r:records(s.rows)){LinearLayout c=Ui.card(p);Ui.title(c,Ui.s(r,"student_name"));Ui.text(c,Ui.s(r,"updated_at"));Ui.text(c,Ui.s(r,"text"));for(JSONObject f:records(r.optJSONArray("files")))file(c,f,Ui.s(s.material,"title"),null);}
            Ui.text(p,"Бағалар «Журнал» бөлімінде қойылады.");return;
        }
        if(!s.initialized){Ui.text(p,"Жауап жүктелуде. Қате болса «Жаңарту» түймесін басыңыз.");return;}
        EditText text=Ui.field(p,"Жауап мәтіні",s.text,true);watch(text,value->s.text=value);
        if(s.own!=null){Ui.text(p,"Сақталған: "+Ui.s(s.own,"updated_at"));for(JSONObject f:records(s.own.optJSONArray("files")))file(p,f,Ui.s(s.material,"title"),()->a.confirm("Жауап файлын жою",Ui.s(f,"name"),()->a.run(()->a.api.request("/submission-files/"+f.optInt("id"),"DELETE",null,true),r->loadAnswer(s))));}
        pending(p,s.pending,()->renderAnswer());
        Ui.secondary(p,"Файлдар қосу",()->{if(a.busy())return;a.pickFiles(uris->{if(answer!=s)return;for(Uri uri:uris)if(!s.pending.contains(uri))s.pending.add(uri);renderAnswer();});});
        Ui.text(p,"PDF, Word, мәтін, фото, GIF немесе MP4. Әр файл: 100 МБ дейін.");
        Ui.button(p,"Жауапты жіберу / жаңарту",()->{if(a.busy())return;if(s.text.trim().isEmpty()&&s.pending.isEmpty()&&(s.own==null||records(s.own.optJSONArray("files")).isEmpty())){a.message("Мәтін немесе файл қосыңыз");return;}String value=s.text;a.run(()->a.api.object("/materials/"+s.material.optInt("id")+"/submission","PUT",Ui.j("text",value)),r->{s.own=r;s.saved=value;renderAnswer();uploadAnswer(s);});});
        Ui.text(p,"Баға «Журнал» бөлімінде көрсетіледі.");
    }
    private void uploadAnswer(Answer s){if(s.pending.isEmpty()){a.message("Жауап сақталды");loadAnswer(s);return;}Uri uri=s.pending.get(0);a.run(()->a.api.upload("/submissions/"+s.own.optInt("id")+"/files",uri),f->{s.pending.remove(uri);JSONArray files=s.own.optJSONArray("files");if(files!=null)files.put(f);renderAnswer();uploadAnswer(s);});}
    public void downloads(){LinearLayout p=a.page("Жүктелгендер","Жеке аккаунтқа сақталған офлайн файлдар");a.run(()->a.api.downloads(),files->{if(files.isEmpty())Ui.text(p,"Әзірге сақталған файл жоқ");for(Api.LocalFile f:files){LinearLayout c=Ui.card(p);Ui.title(c,Ui.s(f.meta,"name"));Ui.text(c,f.title);Ui.button(c,"Файлды ашу",()->a.openFile(f));Ui.secondary(c,"Телефоннан жою",()->a.confirm("Сақталған файлды жою",Ui.s(f.meta,"name"),()->a.run(()->{a.api.deleteDownload(f);return true;},r->downloads())));}});}
}



