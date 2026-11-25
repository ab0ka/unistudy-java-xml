package kz.unistudy;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;

/** One-time, transaction-bound consolidation. Immutable JSON retains the complete pre-merge journal. */
final class GradeMerge57 {
  static int integer(Object value) {return ((Number)value).intValue();}
  static List<Map<String,Object>> rows(Connection c,String sql,Object...args) throws SQLException {
    try(var p=c.prepareStatement(sql)) {
      for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);
      try(var rs=p.executeQuery()) {
        var out=new ArrayList<Map<String,Object>>();var meta=rs.getMetaData();
        while(rs.next()) {var row=new LinkedHashMap<String,Object>();for(int i=1;i<=meta.getColumnCount();i++)row.put(meta.getColumnName(i),rs.getObject(i));out.add(row);}
        return out;
      }
    }
  }
  static void update(Connection c,String sql,Object...args) throws SQLException {
    try(var p=c.prepareStatement(sql)) {for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);p.executeUpdate();}
  }
  static void migrate(Connection c) throws Exception {
    if(!rows(c,"SELECT version FROM schema_migrations WHERE version=57").isEmpty())return;
    var assessments=rows(c,"SELECT * FROM assessments ORDER BY id");
    var entries=rows(c,"SELECT * FROM grade_entries ORDER BY assessment_id,student_id");
    var audit=rows(c,"SELECT * FROM grade_audit ORDER BY id");
    String snapshot=new ObjectMapper().writeValueAsString(Store.fields("assessments",assessments,"grade_entries",entries,"grade_audit",audit));
    update(c,"INSERT INTO grade_merge_archive(version,created_at,data) VALUES(57,?,?)",Instant.now().toString(),snapshot);
    var byAssessment=new HashMap<Integer,List<Map<String,Object>>>();
    for(var e:entries) {
      e.put("generated_origin",integer(e.get("is_demo"))!=0?1:0);
      byAssessment.computeIfAbsent(integer(e.get("assessment_id")),k->new ArrayList<>()).add(e);
    }
    var latestAudit=new HashMap<String,Integer>();
    for(var a:audit)if(a.get("assessment_id")!=null&&"grade".equals(a.get("component")))latestAudit.put(a.get("assessment_id")+":"+a.get("student_id"),integer(a.get("id")));
    var buckets=new LinkedHashMap<String,List<Map<String,Object>>>();
    for(var a:assessments) {
      a.put("generated_origin",integer(a.get("is_demo"))!=0?1:0);
      String kind=GradeCalculation.canonicalKind(a.get("kind"));
      if(!(a.get("week") instanceof Number w)||w.intValue()<1||w.intValue()>15||!Set.of("lecture","lab","sro").contains(kind))continue;
      String key=a.get("subject_id")+":"+a.get("group_id")+":"+kind+":"+w.intValue();
      buckets.computeIfAbsent(key,k->new ArrayList<>()).add(a);
    }
    update(c,"UPDATE assessments SET generated_origin=CASE WHEN is_demo<>0 THEN 1 ELSE 0 END");
    update(c,"UPDATE grade_entries SET generated_origin=CASE WHEN is_demo<>0 THEN 1 ELSE 0 END");
    for(var bucket:buckets.values()) {
      var genuine=new HashSet<Integer>();
      for(var a:bucket) {
        int id=integer(a.get("id"));
        if(integer(a.get("generated_origin"))==0 || byAssessment.getOrDefault(id,List.of()).stream().anyMatch(e->integer(e.get("generated_origin"))==0))genuine.add(id);
      }
      var ranked=new ArrayList<>(bucket);
      ranked.sort(Comparator.<Map<String,Object>>comparingInt(a->genuine.contains(integer(a.get("id")))?0:1)
          .thenComparingInt(a->byAssessment.getOrDefault(integer(a.get("id")),List.of()).isEmpty()?1:0).thenComparingInt(a->integer(a.get("id"))));
      var target=ranked.get(0);int targetId=integer(target.get("id"));
      var genuineStudents=new HashSet<Integer>();
      var candidates=new HashMap<Integer,List<Map<String,Object>>>();
      for(var a:bucket)for(var e:byAssessment.getOrDefault(integer(a.get("id")),List.of())) {
        int student=integer(e.get("student_id"));
        if(integer(e.get("generated_origin"))==0)genuineStudents.add(student);
        else candidates.computeIfAbsent(student,k->new ArrayList<>()).add(e);
      }
      boolean changed=false;
      for(var entry:candidates.entrySet()) {
        int student=entry.getKey();
        // Genuine grades, including 0 and NP, always take priority; every competing row is retained in the archive.
        if(genuineStudents.contains(student))continue;
        var choices=entry.getValue();
        choices.sort(Comparator.<Map<String,Object>>comparingInt(e->"manual".equals(e.get("source"))?0:1)
            .thenComparing((a,b)->Integer.compare(latestAudit.getOrDefault(b.get("assessment_id")+":"+student,0),latestAudit.getOrDefault(a.get("assessment_id")+":"+student,0)))
            .thenComparingInt(e->integer(e.get("assessment_id"))));
        var chosen=choices.get(0);
        if(integer(chosen.get("assessment_id"))==targetId)continue;
        update(c,"INSERT INTO grade_entries(assessment_id,student_id,value,is_demo,source,generated_origin) VALUES(?,?,?,0,?,1) ON CONFLICT(assessment_id,student_id) DO UPDATE SET value=excluded.value,source=excluded.source,generated_origin=1,is_demo=0",
            targetId,student,chosen.get("value"),chosen.get("source"));
        changed=true;
      }
      // A retained genuine assessment can also contain generated cells; remove only those duplicates.
      // Their exact pre-merge rows remain in grade_merge_archive.
      for(var a:bucket) {
        int id=integer(a.get("id"));
        if(id!=targetId&&!genuine.contains(id))continue;
        for(var e:byAssessment.getOrDefault(id,List.of()))if(integer(e.get("generated_origin"))!=0 && (id!=targetId||genuineStudents.contains(integer(e.get("student_id")))))
          update(c,"DELETE FROM grade_entries WHERE assessment_id=? AND student_id=? AND generated_origin=1",id,e.get("student_id"));
      }
      for(var a:bucket) {
        int id=integer(a.get("id"));
        if(id!=targetId&&!genuine.contains(id))update(c,"UPDATE assessments SET archived=1,merged_into=?,version=version+1 WHERE id=?",targetId,id);
      }
      if(changed)update(c,"UPDATE assessments SET version=version+1 WHERE id=?",targetId);
    }
    update(c,"UPDATE assessments SET is_demo=0");
    update(c,"UPDATE grade_entries SET is_demo=0");
    update(c,"INSERT INTO schema_migrations(version) VALUES(57)");
  }
}
