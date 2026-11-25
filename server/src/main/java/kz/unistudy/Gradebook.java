package kz.unistudy;

import static kz.unistudy.Input.*;
import static kz.unistudy.Store.*;

import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@Transactional
class Gradebook {
  final Store s;

  Gradebook(Store s) {
    this.s = s;
  }

  static Map<String,Object> assessmentJson(Map<String,Object> row) {
    var out=new LinkedHashMap<>(row);
    out.put("original_kind",row.get("kind"));
    out.put("kind",GradeCalculation.canonicalKind(row.get("kind")));
    return out;
  }

  void scope(Map<String, Object> u, Object subject, Object group) {
    var row = s.subject(u, subject);
    if (!s.list(row, "group_ids").contains(group)
        || role(u, "student") && !Objects.equals(group, u.get("group_id")))
      throw ApiError.missing();
    s.get("groups", group);
  }

  @GetMapping("/gradebook")
  Object view(@RequestParam int subject_id, @RequestParam int group_id,
      @RequestParam(required=false) String kind, @RequestParam(required=false) Integer week,
      @RequestParam(defaultValue="real") String mode) {
    choice(mode,"real","demo","unified");
    mode="unified";
    final String selectedKind = kind==null || kind.isBlank() ? null : choice(kind,"lecture","lab","sro");
    if(week!=null) num(week,1,15);
    var u = s.user();
    scope(u, id(subject_id), id(group_id));
    var assessments =
        s.rows(
            "SELECT * FROM assessments WHERE subject_id=? AND group_id=? AND archived=0 ORDER BY date,id",
            subject_id,
            group_id);
    assessments = assessments.stream().map(Gradebook::assessmentJson).toList();
    var students =
        role(u, "student")
            ? List.of(u)
            : s.rows(
                "SELECT * FROM users WHERE role='student' AND group_id=? AND active=1 AND"
                    + " approved=1 ORDER BY full_name,id",
                group_id);
    var query =
        "SELECT g.assessment_id,g.student_id,g.value,g.is_demo,g.source FROM grade_entries g JOIN assessments a ON"
            + " a.id=g.assessment_id JOIN users u ON g.student_id=u.id WHERE a.subject_id=? AND"
            + " a.group_id=? AND u.group_id=a.group_id AND u.active=1 AND u.approved=1 AND"
            + " u.role='student' AND a.archived=0";
    var order = " ORDER BY g.assessment_id,g.student_id";
    var entries =
        role(u, "student")
            ? s.rows(query + " AND u.id=?" + order, subject_id, group_id, u.get("id"))
            : s.rows(query + order, subject_id, group_id);
    var calculationAssessments = assessments;
    var calculationEntries = entries;
    assessments = assessments.stream().filter(a -> (selectedKind==null || selectedKind.equals(a.get("kind"))) && (week==null || week.equals(a.get("week")))).toList();
    var assessmentIds = assessments.stream().map(a -> a.get("id")).toList();
    entries = entries.stream().filter(e -> assessmentIds.contains(e.get("assessment_id"))).toList();
    var stats = new HashMap<Object, int[]>();
    for (var e : entries) {
      var st = stats.computeIfAbsent(e.get("student_id"), k -> new int[3]);
      var value = (String) e.get("value");
      if ("NP".equals(value)) st[2]++;
      else {
        st[0] += Integer.parseInt(value);
        st[1]++;
      }
    }
    var averages = new ArrayList<Map<String, Object>>();
    for (var student : students) {
      var id = student.get("id");
      var st = stats.getOrDefault(id, new int[3]);
      averages.add(
          fields(
              "student_id",
              id,
              "average",
              st[1] == 0 ? null : (double) st[0] / st[1],
              "graded_count",
              st[1],
              "np_count",
              st[2]));
    }
    var weekly = new ArrayList<Map<String,Object>>();
    var summaries = new ArrayList<Map<String,Object>>();
    var kindWeekly = new ArrayList<Map<String,Object>>();
    for(var student:students) {
      var sid=student.get("id");
      weekly.add(fields("student_id",sid,"weeks",GradeCalculation.weekly(sid,assessments,entries)));
      for(var category:List.of("lecture","sro","lab")) {
        var categoryAssessments=assessments.stream().filter(a -> category.equals(a.get("kind"))).toList();
        kindWeekly.add(fields("student_id",sid,"kind",category,
            "weeks",GradeCalculation.weekly(sid,categoryAssessments,entries),
            "period1_average",GradeCalculation.kindPeriodAverage(sid,category,1,calculationAssessments,calculationEntries),
            "period2_average",GradeCalculation.kindPeriodAverage(sid,category,2,calculationAssessments,calculationEntries)));
      }
      summaries.add(GradeCalculation.summary(sid,s.find("SELECT * FROM grade_components WHERE subject_id=? AND group_id=? AND student_id=?",subject_id,group_id,sid),calculationAssessments,calculationEntries));
    }
    return fields(
        "mode",mode,"weekly",weekly,"kind_weekly",kindWeekly,"summaries",summaries,"policy",GradeCalculation.policy(),
        "assessments",
        assessments,
        "students",
        students.stream().map(s::userJson).toList(),
        "entries",
        entries,
        "averages",
        averages);
  }

  List<Map<String,Object>> weeklyAssessments(int subject,int group,String kind,int week) {
    return s.rows("SELECT a.*,EXISTS(SELECT 1 FROM grade_entries g WHERE g.assessment_id=a.id) AS has_entries FROM assessments a WHERE a.subject_id=? AND a.group_id=? AND a.week=? AND a.archived=0 ORDER BY has_entries DESC,a.id",
        subject,group,week).stream().map(Gradebook::assessmentJson).filter(a -> kind.equals(a.get("kind"))).toList();
  }

  /** SQLite IMMEDIATE transactions serialize selection plus creation; source_key also guards uniqueness. */
  @PostMapping("/gradebook/weekly")
  Object weeklyAssessment(@RequestBody Map<String,Object> b) {
    var u=s.staff();
    keys(b,"subject_id group_id kind week mode");
    int subject=id(b.get("subject_id")),group=id(b.get("group_id")),week=num(b.get("week"),1,15);
    String kind=choice(b.get("kind"),"lecture","lab","sro");
    if(b.get("mode")!=null)choice(b.get("mode"),"real","demo","unified");
    String mode="unified";
    scope(u,subject,group);
    var matches=weeklyAssessments(subject,group,kind,week);
    boolean created=matches.isEmpty();
    if(created) {
      Map<String,Object> material=null,schedule=null;
      for(var candidate:s.rows("SELECT * FROM materials WHERE subject_id=? AND week=? ORDER BY published DESC,id",subject,week)) {
        var groups=s.list(candidate,"group_ids");
        if(kind.equals(GradeCalculation.canonicalKind(candidate.get("kind"))) && (groups.isEmpty()||groups.contains(group))) {material=candidate;break;}
      }
      for(var candidate:s.rows("SELECT * FROM schedule WHERE subject_id=? AND group_id=? AND week=? ORDER BY date,id",subject,group,week)) {
        if(kind.equals(GradeCalculation.canonicalKind(candidate.get("kind")))) {schedule=candidate;break;}
      }
      String label=switch(kind) {case "lecture" -> "Дәріс";case "lab" -> "Зертхана";default -> "СРО";};
      String key="weekly:v57:"+subject+":"+group+":"+kind+":"+week+":"+mode;
      s.insert("assessments",fields("subject_id",subject,"group_id",group,
          "title",s.get("subjects",subject).get("name")+" · "+label+" · "+week+"-апта",
          "date",schedule==null?java.time.LocalDate.now(java.time.ZoneId.of("Asia/Qyzylorda")).toString():schedule.get("date"),
          "week",week,"kind",kind,"version",1,"is_demo",0,"source","weekly","source_key",key,
          "material_id",material==null?null:material.get("id"),"schedule_id",schedule==null?null:schedule.get("id")));
      matches=weeklyAssessments(subject,group,kind,week);
    }
    var assessment=matches.get(0);
    var entries=s.rows("SELECT g.assessment_id,g.student_id,g.value,g.is_demo,g.source FROM grade_entries g JOIN users u ON u.id=g.student_id WHERE g.assessment_id=? AND u.group_id=? AND u.role='student' AND u.active=1 AND u.approved=1 ORDER BY g.student_id",
        assessment.get("id"),group);
    return fields("assessment",assessment,"entries",entries,"created",created,"matching_assessments",matches,
        "selection","oldest_graded_then_oldest","mode",mode);
  }

  @PostMapping("/assessments")
  Object create(@RequestBody Map<String, Object> b) {
    var u = s.staff();
    keys(b, "material_id schedule_id subject_id group_id title date week kind");
    int group = id(b.get("group_id"));
    if (b.get("material_id") != null && b.get("schedule_id") != null) throw ApiError.invalid();
    Map<String,Object> values;
    if (b.get("material_id") != null) {
      var material=s.material(u,id(b.get("material_id")));
      if (!Set.of("lecture","lab","sro").contains(material.get("kind"))) throw ApiError.invalid();
      scope(u, material.get("subject_id"), group);
      var groups=s.list(material,"group_ids");
      if(!groups.isEmpty()&&!groups.contains(group))throw ApiError.missing();
      values=fields("material_id",material.get("id"),"subject_id",material.get("subject_id"),"group_id",group,
          "title",material.get("title"),"date",date(b.get("date")),"week",material.get("week")!=null?material.get("week"):(b.get("week")==null?null:num(b.get("week"),1,15)),"kind",material.get("kind"),"version",1);
    } else {
      var schedule=s.get("schedule",id(b.get("schedule_id")));
      scope(u,schedule.get("subject_id"),group);
      if(!Objects.equals(group,schedule.get("group_id")) && schedule.get("shared_key")!=null && "lecture".equals(schedule.get("kind"))) {
        schedule=s.find("SELECT * FROM schedule WHERE shared_key=? AND subject_id=? AND group_id=? AND kind='lecture' ORDER BY id LIMIT 1",
            schedule.get("shared_key"),schedule.get("subject_id"),group);
        if(schedule==null)throw ApiError.missing();
      }
      if(!Objects.equals(group,schedule.get("group_id")) || !"lecture".equals(schedule.get("kind")))throw ApiError.invalid();
      var subject=s.get("subjects",schedule.get("subject_id"));
      values=fields("schedule_id",schedule.get("id"),"subject_id",schedule.get("subject_id"),"group_id",group,
          "title",subject.get("name")+" / Дәріс","date",schedule.get("date"),"week",schedule.get("week"),"kind","lecture","version",1);
    }
    if(b.get("subject_id")!=null&&!Objects.equals(id(b.get("subject_id")),values.get("subject_id")))throw ApiError.invalid();
    return assessmentJson(s.get("assessments",s.insert("assessments",values)));
  }

  @PutMapping("/assessments/{id}/grades")
  Object save(@PathVariable int id, @RequestBody Map<String, Object> b) {
    var u = s.staff();
    var a = s.get("assessments", id);
    scope(u, a.get("subject_id"), a.get("group_id"));
    if(bool(a.get("archived")))throw new ApiError(409,"Журнал жаңартылды. Қайта ашыңыз.");
    keys(b, "version entries");
    var version = id(b.get("version"));
    if (!(b.get("entries") instanceof List<?> entries) || entries.size() > 1000)
      throw ApiError.invalid();
    var edits = new LinkedHashMap<Integer, String>();
    var roster =
        new HashSet<>(
            s.db.queryForList(
                "SELECT id FROM users WHERE group_id=? AND role='student' AND approved=1 AND"
                    + " active=1",
                Integer.class,
                a.get("group_id")));
    for (var entry : entries) {
      if (!(entry instanceof Map<?, ?> e) || !e.keySet().equals(Set.of("student_id", "value")))
        throw ApiError.invalid();
      var student = id(e.get("student_id"));
      Object value = e.get("value");
      if (!roster.contains(student)
          || edits.containsKey(student)
          || value != null
              && (!(value instanceof String str) || !str.matches("NP|100|[1-9]?[0-9]")))
        throw ApiError.invalid();
      edits.put(student, (String) value);
    }
    if (s.db.update(
            "UPDATE assessments SET version=version+1 WHERE id=? AND version=?", id, version)
        != 1) throw new ApiError(409,"Журнал өзгерген. Оны жаңартып, қайта сақтаңыз.");
    edits.forEach(
        (student, value) -> {
          var old=s.find("SELECT value FROM grade_entries WHERE assessment_id=? AND student_id=?",id,student);
          if(Objects.equals(old==null?null:old.get("value"),value))return;
          audit(u,a,student,"grade",old==null?null:old.get("value"),value);
          if (value == null)
            s.db.update(
                "DELETE FROM grade_entries WHERE assessment_id=? AND student_id=?", id, student);
          else
            s.db.update(
                "INSERT INTO grade_entries(assessment_id,student_id,value,is_demo,source,generated_origin) VALUES(?,?,?,0,'manual',0) ON"
                    + " CONFLICT(assessment_id,student_id) DO UPDATE SET value=excluded.value,source='manual',is_demo=0,generated_origin=0",
                id,
                student,
                value);
        });
    return assessmentJson(s.get("assessments", id));
  }

  void audit(Map<String,Object> actor, Map<String,Object> assessment, Object student, String component, Object before, Object after) {
    if(Objects.equals(before,after))return;
    s.insert("grade_audit",fields("assessment_id",assessment.get("id"),"subject_id",assessment.get("subject_id"),
        "group_id",assessment.get("group_id"),"student_id",student,"component",component,
        "old_value",before,"new_value",after,"changed_by",actor.get("id"),"changed_at",java.time.Instant.now().toString()));
  }

  @PutMapping("/gradebook/components")
  Object components(@RequestBody Map<String,Object> b) {
    var u=s.staff();
    keys(b,"subject_id group_id student_id version rk1 rk2 exam");
    int subject=id(b.get("subject_id")),group=id(b.get("group_id")),student=id(b.get("student_id"));
    scope(u,subject,group);
    var person=s.get("users",student);
    if(!Objects.equals(group,person.get("group_id")) || !role(person,"student") || !bool(person.get("active")) || !bool(person.get("approved")))throw ApiError.missing();
    int version=num(b.get("version"),0,Integer.MAX_VALUE);
    var edits=new LinkedHashMap<String,Object>();
    for(var key:List.of("rk1","rk2","exam"))if(b.containsKey(key))edits.put(key,b.get(key)==null?null:num(b.get(key),0,100));
    if(edits.isEmpty())throw ApiError.invalid();
    s.db.update("INSERT OR IGNORE INTO grade_components(subject_id,group_id,student_id) VALUES(?,?,?)",subject,group,student);
    var old=s.find("SELECT * FROM grade_components WHERE subject_id=? AND group_id=? AND student_id=?",subject,group,student);
    if(s.db.update("UPDATE grade_components SET version=version+1 WHERE subject_id=? AND group_id=? AND student_id=? AND version=?",subject,group,student,version)!=1)
      throw new ApiError(409,"Журнал өзгерген. Оны жаңартып, қайта сақтаңыз.");
    for(var edit:edits.entrySet()) {
      s.db.update("UPDATE grade_components SET "+edit.getKey()+"=? WHERE subject_id=? AND group_id=? AND student_id=?",edit.getValue(),subject,group,student);
      audit(u,fields("subject_id",subject,"group_id",group),student,edit.getKey(),old.get(edit.getKey()),edit.getValue());
    }
    return GradeCalculation.summary(student,s.find("SELECT * FROM grade_components WHERE subject_id=? AND group_id=? AND student_id=?",subject,group,student));
  }

  @GetMapping("/gradebook/audit")
  Object history(@RequestParam int subject_id,@RequestParam int group_id) {
    var u=s.user();scope(u,id(subject_id),id(group_id));
    var sql="SELECT * FROM grade_audit WHERE subject_id=? AND group_id=?";
    return role(u,"student") ? s.rows(sql+" AND student_id=? ORDER BY id DESC LIMIT 500",subject_id,group_id,u.get("id"))
        : s.rows(sql+" ORDER BY id DESC LIMIT 500",subject_id,group_id);
  }

  @DeleteMapping("/assessments/{id}")
  Object remove(@PathVariable int id) {
    var u = s.staff();
    var a = s.get("assessments", id);
    scope(u, a.get("subject_id"), a.get("group_id"));
    if(bool(a.get("archived")))throw new ApiError(409,"Журнал жаңартылды. Қайта ашыңыз.");
    for(var entry:s.rows("SELECT * FROM grade_entries WHERE assessment_id=?",id))
      audit(u,a,entry.get("student_id"),"assessment_deleted",entry.get("value"),null);
    s.db.update("DELETE FROM assessments WHERE id=?", id);
    return OK;
  }
}
