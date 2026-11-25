package kz.unistudy.app;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import org.json.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.function.IntConsumer;

/** Small XML-inflating view helpers. All reusable UI structure lives in res/layout. */
public final class Ui {
    private Ui() {}
    public static JSONObject j(Object... kv){JSONObject j=new JSONObject();try{for(int i=0;i<kv.length;i+=2){Object v=kv[i+1];j.put((String)kv[i],v==null?JSONObject.NULL:v instanceof Collection?new JSONArray((Collection<?>)v):v);}return j;}catch(JSONException e){throw new IllegalArgumentException(e);}}
    public static String s(JSONObject j,String key){return j==null||j.isNull(key)?"":j.optString(key,"");}
    public static List<JSONObject> list(JSONArray a){List<JSONObject> out=new ArrayList<>();if(a!=null)for(int i=0;i<a.length();i++){JSONObject j=a.optJSONObject(i);if(j!=null)out.add(j);}return out;}
    public static JSONArray ids(Collection<Integer> values){return new JSONArray(values);}
    public static List<Integer> intList(JSONArray a){List<Integer> ids=new ArrayList<>();if(a!=null)for(int i=0;i<a.length();i++)ids.add(a.optInt(i));return ids;}
    public static int dp(Context c,int value){return Math.round(c.getResources().getDisplayMetrics().density*value);}
    private static <T extends View> T inflate(LinearLayout parent,int layout){T v=(T)LayoutInflater.from(parent.getContext()).inflate(layout,parent,false);parent.addView(v);return v;}
    private static void fitRow(LinearLayout parent,View child){if(parent.getOrientation()==LinearLayout.HORIZONTAL){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1);p.setMargins(dp(parent.getContext(),3),dp(parent.getContext(),4),dp(parent.getContext(),3),dp(parent.getContext(),4));child.setLayoutParams(p);}}
    public static TextView text(LinearLayout parent,String value){TextView t=inflate(parent,R.layout.ui_text);t.setText(value);fitRow(parent,t);return t;}
    public static TextView title(LinearLayout parent,String value){TextView t=text(parent,value);t.setTextSize(19);t.setTypeface(null,Typeface.BOLD);t.setTextColor(parent.getContext().getColor(R.color.ink));return t;}
    public static TextView badge(LinearLayout parent,String value){TextView t=text(parent,value);t.setTextSize(11);t.setTextColor(parent.getContext().getColor(R.color.indigo));t.setBackgroundResource(R.drawable.bg_pill);t.setPadding(dp(parent.getContext(),10),dp(parent.getContext(),6),dp(parent.getContext(),10),dp(parent.getContext(),6));if(parent.getOrientation()==LinearLayout.VERTICAL)t.setLayoutParams(new LinearLayout.LayoutParams(-2,-2));return t;}
    public static LinearLayout card(LinearLayout parent){return inflate(parent,R.layout.ui_card);}
    public static LinearLayout row(LinearLayout parent){return inflate(parent,R.layout.ui_row);}
    public static void gap(LinearLayout parent,int size){TextView gap=inflate(parent,R.layout.ui_text);gap.setText("");gap.setLayoutParams(new LinearLayout.LayoutParams(1,dp(parent.getContext(),size)));}
    public static Button button(LinearLayout parent,String label,Runnable action){Button b=inflate(parent,R.layout.ui_button);b.setText(label);fitRow(parent,b);b.setOnClickListener(v->{Context c=parent.getContext();if(!(c instanceof MainActivity)||!((MainActivity)c).busy())action.run();});return b;}
    public static Button secondary(LinearLayout parent,String label,Runnable action){Button b=button(parent,label,action);b.setBackgroundResource(R.drawable.bg_secondary);b.setTextColor(parent.getContext().getColor(R.color.indigo));return b;}
    public static EditText field(LinearLayout parent,String label,String initial,boolean multiline){LinearLayout box=inflate(parent,R.layout.ui_field);fitRow(parent,box);((TextView)box.findViewById(R.id.field_label)).setText(label);EditText input=box.findViewById(R.id.field_input);input.setTag(label);input.setHint(label);input.setText(initial==null?"":initial);input.setSingleLine(!multiline);input.setInputType(InputType.TYPE_CLASS_TEXT | (multiline?InputType.TYPE_TEXT_FLAG_MULTI_LINE:0));if(multiline){input.setMinLines(3);input.setGravity(android.view.Gravity.TOP);}return input;}
    public static EditText password(LinearLayout parent,String label){EditText input=field(parent,label,"",false);input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);input.setTransformationMethod(PasswordTransformationMethod.getInstance());return input;}
    public static CheckBox check(LinearLayout parent,String label,boolean checked){CheckBox box=inflate(parent,R.layout.ui_check);box.setText(label);box.setChecked(checked);fitRow(parent,box);return box;}
    public static Spinner spinner(LinearLayout parent,String label,List<String> labels,int selected,IntConsumer change){LinearLayout box=inflate(parent,R.layout.ui_spinner);fitRow(parent,box);((TextView)box.findViewById(R.id.spinner_label)).setText(label);Spinner spinner=box.findViewById(R.id.spinner_input);spinner.setContentDescription(label);ArrayAdapter<String> adapter=new ArrayAdapter<>(parent.getContext(),R.layout.spinner_item,new ArrayList<>(labels));adapter.setDropDownViewResource(R.layout.spinner_item);spinner.setAdapter(adapter);int initial=Math.max(0,Math.min(selected,labels.size()-1));spinner.setSelection(initial,false);int[] last={initial};spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int position,long id){if(position!=last[0]){last[0]=position;if(change!=null)change.accept(position);}}});return spinner;}
    public static List<CheckBox> selectIds(LinearLayout parent,String label,JSONArray records,List<Integer> selected){title(parent,label);List<CheckBox> boxes=new ArrayList<>();for(JSONObject record:list(records)){String name=s(record,"name");if(name.isEmpty())name=s(record,"full_name");CheckBox b=check(parent,name,selected.contains(record.optInt("id")));b.setTag(record.optInt("id"));boxes.add(b);}return boxes;}
    public static JSONArray checkedIds(List<CheckBox> boxes){JSONArray result=new JSONArray();for(CheckBox b:boxes)if(b.isChecked())result.put((Integer)b.getTag());return result;}
    public static LinearLayout form(Context context){return (LinearLayout)LayoutInflater.from(context).inflate(R.layout.ui_form,null,false);}
    public static ScrollView scroll(LinearLayout form){ScrollView scroll=(ScrollView)LayoutInflater.from(form.getContext()).inflate(R.layout.ui_scroll,null,false);scroll.addView(form);return scroll;}
    public static AlertDialog dialog(MainActivity a,String title,LinearLayout form,String positive,Runnable action){AlertDialog d=new AlertDialog.Builder(a).setTitle(title).setView(scroll(form)).setPositiveButton(positive,null).setNegativeButton("Бас тарту",null).create();d.setOnShowListener(v->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{if(!a.busy())action.run();}));d.show();return d;}
    public static String kind(String kind){switch(kind){case "note":return "Конспект";case "guide":return "Әдістемелік материал";case "exam":return "Емтиханға дайындық";case "lecture":return "Дәріс";case "sro":return "СРО";case "lab":return "Зертхана";default:return kind;}}
    public static String sourceLabel(String source){switch(source){case "platonus":return "Platonus-тен көшірілген";case "planned":return "Оқу нұсқасы · ресми кесте емес";default:return "Әкімші енгізген";}}
    public static String size(long bytes){return bytes<1048576?bytes/1024+" КБ":String.format(Locale.getDefault(),"%.1f МБ",bytes/1048576d);}
    public static String today(){return LocalDate.now(ZoneId.of("Asia/Qyzylorda")).toString();}
    public static void error(EditText field,String error){field.setError(error);field.requestFocus();}
    public static void insets(View root){root.setOnApplyWindowInsetsListener((v,i)->{if(Build.VERSION.SDK_INT>=30){android.graphics.Insets b=i.getInsets(android.view.WindowInsets.Type.systemBars());v.setPadding(b.left,b.top,b.right,b.bottom);}else v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom());return i;});root.requestApplyInsets();}
}
