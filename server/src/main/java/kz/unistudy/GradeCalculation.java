package kz.unistudy;

import static kz.unistudy.Store.fields;
import java.util.*;

/** Descriptive weekly means are not academic ratings. Unverified policy never yields a final. */
final class GradeCalculation {
  // Explicit legacy labels only; never guess an unknown academic assessment type.
  static String canonicalKind(Object value) {
    if(value==null)return "";
    String original=value.toString();
    return switch(original.strip().toLowerCase(Locale.ROOT)) {
      case "lecture", "дәріс", "лекция" -> "lecture";
      case "lab", "зертханалық" -> "lab";
      case "sro", "сро" -> "sro";
      default -> original;
    };
  }
  static final Properties MODEL = loadModel();
  static Properties loadModel() {
    var p=new Properties();
    try(var in=GradeCalculation.class.getResourceAsStream("/grade-policy.properties")) {p.load(new java.io.InputStreamReader(in,java.nio.charset.StandardCharsets.UTF_8));}
    catch(java.io.IOException e) {throw new IllegalStateException(e);}
    String external=System.getenv("UNISTUDY_GRADE_POLICY");
    if(external!=null&&!external.isBlank())try(var in=java.nio.file.Files.newBufferedReader(java.nio.file.Path.of(external))) {p.load(in);}catch(java.io.IOException e){throw new IllegalStateException(e);}
    for(var key:List.of("lecture","lab","sro","tk","rk","rating1","rating2","admission","exam")) {
      double n=Double.parseDouble(p.getProperty("weight."+key));if(!Double.isFinite(n)||n<0||n>1)throw new IllegalArgumentException("Invalid grade weight");
    }
    for(var keys:List.of(List.of("lecture","lab","sro"),List.of("tk","rk"),List.of("rating1","rating2"),List.of("admission","exam"))) {
      double sum=0;for(var k:keys)sum+=Double.parseDouble(p.getProperty("weight."+k));if(Math.abs(sum-1)>0.000001)throw new IllegalArgumentException("Grade weights must sum to 1");
    }
    int end1=Integer.parseInt(p.getProperty("period1.end")),end2=Integer.parseInt(p.getProperty("period2.end")),completed=Integer.parseInt(p.getProperty("completed.weeks"));
    if(end1<1||end1>=end2||end2>15||completed<0||completed>15)throw new IllegalArgumentException("Invalid grade periods");
    return p;
  }
  static double setting(String key) {return Double.parseDouble(MODEL.getProperty(key));}
  static Map<String,Object> policy() {
    return fields("status", "provisional", "explanation",
        "Апта мәні — сандық бағалардың орташа мәні; НП және бос мән есептелмейді, 0 есептеледі. ТК үлгісі: дәріс × 0.2 + зертхана × 0.5 + СРО × 0.3. Рейтинг үлгісі: (ТК + РК) / 2. Бұл алдын ала модель: ресми ереже мен дөңгелектеу толық расталмаған, қорытынды баға берілмейді. Аяқталмаған кезең рейтингі бос қалады.",
        "weekly_formula", "sum(numeric grades) / count(numeric grades)",
        "example", "0, 100, NP, null → 50; TK=90×0.2+92×0.5+86×0.3=89.8; RK=90 → R=89.9",
        "model",new TreeMap<>(MODEL),"missing_fields", missingPolicy());
  }
  static List<String> missingPolicy() {return List.of("current_official_regulation", "official_kind_weights", "official_rounding", "official_exam_admission");}
  static Double display(Double value) {return value==null?null:Math.round(value*100.0)/100.0;}
  static Map<String,Object> summary(Object student, Map<String,Object> components) {
    return summary(student,components,List.of(),List.of());
  }
  static Map<String,Object> summary(Object student, Map<String,Object> components,List<Map<String,Object>> assessments,List<Map<String,Object>> entries) {
    var out=fields("student_id",student,"tk1",null,"rk1",null,"r1",null,"tk2",null,"rk2",null,"r2",null,"exam",null,"final",null,"provisional_final",null,"component_version",0,"status","provisional");
    if(components!=null) {
      for(var key:List.of("rk1","rk2","exam"))out.put(key,components.get(key));
      out.put("component_version",components.get("version"));
    }
    var missing=new ArrayList<>(missingPolicy());
    Double[] ratings=new Double[2];
    for(int period=1;period<=2;period++) {
      int first=period==1?1:(int)setting("period1.end")+1,last=(int)setting("period"+period+".end");
      Double tk=current(student,assessments,entries,first,last,missing,"tk"+period);
      out.put("tk"+period,display(tk));
      boolean complete=setting("completed.weeks")>=last;
      out.put("period"+period+"_complete",complete);
      Object rk=out.get("rk"+period);
      if(!complete)missing.add("period"+period+"_incomplete");
      if(rk==null)missing.add("rk"+period);
      if(complete&&tk!=null&&rk instanceof Number n)ratings[period-1]=tk*setting("weight.tk")+n.doubleValue()*setting("weight.rk");
      out.put("r"+period,display(ratings[period-1]));
    }
    if(out.get("exam")==null)missing.add("exam");
    if(ratings[0]!=null&&ratings[1]!=null&&out.get("exam") instanceof Number exam) {
      double admission=ratings[0]*setting("weight.rating1")+ratings[1]*setting("weight.rating2");
      out.put("admission_candidate",display(admission));
      if(admission>=setting("threshold.admission")&&exam.doubleValue()>=setting("threshold.exam"))out.put("provisional_final",display(admission*setting("weight.admission")+exam.doubleValue()*setting("weight.exam")));
      else missing.add("admission_or_exam_threshold");
    }
    out.put("missing_fields",missing);
    return out;
  }
  static Double current(Object student,List<Map<String,Object>> assessments,List<Map<String,Object>> entries,int first,int last,List<String> missing,String prefix) {
    var byId=new HashMap<Object,Map<String,Object>>();for(var a:assessments)byId.put(a.get("id"),a);
    var sums=new HashMap<String,double[]>();
    for(var e:entries) {
      if(!Objects.equals(student,e.get("student_id")))continue;
      var a=byId.get(e.get("assessment_id"));if(a==null||!(a.get("week") instanceof Number w)||w.intValue()<first||w.intValue()>last)continue;
      Object value=e.get("value");if(value==null||"NP".equals(value))continue;
      var sum=sums.computeIfAbsent(canonicalKind(a.get("kind")),k->new double[2]);sum[0]+=Double.parseDouble(value.toString());sum[1]++;
    }
    double result=0;boolean complete=true;
    for(var kind:List.of("lecture","lab","sro")) {
      double weight=setting("weight."+kind);if(weight==0)continue;
      var sum=sums.get(kind);if(sum==null||sum[1]==0){complete=false;missing.add(prefix+"_"+kind);}else result+=weight*sum[0]/sum[1];
    }
    return complete?result:null;
  }
  /** Descriptive category period average; never an independent academic TK or rating. */
  static Double kindPeriodAverage(Object student, String kind, int period,
      List<Map<String,Object>> assessments, List<Map<String,Object>> entries) {
    int first=period==1?1:(int)setting("period1.end")+1;
    int last=(int)setting("period"+period+".end");
    var ids=new HashSet<Object>();
    for(var a:assessments)if(kind.equals(canonicalKind(a.get("kind"))) && a.get("week") instanceof Number w && w.intValue()>=first && w.intValue()<=last)ids.add(a.get("id"));
    double total=0;int count=0;
    for(var e:entries) {
      if(!Objects.equals(student,e.get("student_id")) || !ids.contains(e.get("assessment_id")))continue;
      Object value=e.get("value");
      if(value==null||"NP".equals(value))continue;
      total+=Double.parseDouble(value.toString());count++;
    }
    return count==0?null:total/count;
  }

  static List<Map<String,Object>> weekly(Object student, List<Map<String,Object>> assessments, List<Map<String,Object>> entries) {
    var values=new HashMap<Object,Map<String,Object>>();
    for(var e:entries)if(Objects.equals(student,e.get("student_id")))values.put(e.get("assessment_id"),e);
    var weeks=new ArrayList<Map<String,Object>>();
    for(int week=1;week<=15;week++) {
      var details=new ArrayList<Map<String,Object>>();double total=0;int count=0,np=0,missing=0,demo=0;
      for(var a:assessments) {
        if(!Objects.equals(week,a.get("week")))continue;
        var e=values.get(a.get("id"));Object value=e==null?null:e.get("value");
        boolean isDemo=e!=null&&((Number)e.getOrDefault("is_demo",0)).intValue()!=0;
        details.add(fields("assessment_id",a.get("id"),"title",a.get("title"),"kind",canonicalKind(a.get("kind")),"value",value,"is_demo",isDemo,"source",e==null?a.get("source"):e.get("source")));
        if(value==null)missing++;else if("NP".equals(value))np++;else {total+=Double.parseDouble(value.toString());count++;}
        if(isDemo)demo++;
      }
      weeks.add(fields("week",week,"average",count==0?null:total/count,"graded_count",count,"np_count",np,"missing_count",missing,"demo_count",demo,"assessments",details));
    }
    return weeks;
  }
}
