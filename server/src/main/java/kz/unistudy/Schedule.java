package kz.unistudy;

import static kz.unistudy.Input.*;
import static kz.unistudy.Store.*;

import java.time.LocalDate;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@Transactional
class Schedule {
  final Store s;

  Schedule(Store s) {
    this.s = s;
  }

  static final String SELECT =
      "SELECT"
          + " l.id,l.subject_id,l.teacher_id,l.group_id,l.date,l.start_time,l.end_time,l.room,l.kind,l.week,l.source,l.shared_key,sub.name"
          + " AS subject_name,t.full_name AS teacher_name,g.name AS group_name FROM schedule l JOIN"
          + " subjects sub ON sub.id=l.subject_id JOIN users t ON t.id=l.teacher_id JOIN groups g"
          + " ON g.id=l.group_id";
  static final String KEYS =
      "subject_id teacher_id group_id group_ids date start_time end_time room kind week";

  @GetMapping("/schedule")
  Object schedule(@RequestParam String from_date, @RequestParam String to_date) {
    var u = s.user();
    var start = date(from_date);
    var end = date(to_date);
    if (start.compareTo(end) > 0) throw ApiError.invalid();
    var query = SELECT + " WHERE l.date BETWEEN ? AND ?";
    var order = " ORDER BY l.date,l.start_time,l.id";
    List<Map<String,Object>> rows;
    if (role(u, "teacher")) rows=s.rows(query+" AND l.teacher_id=?"+order,start,end,u.get("id"));
    else if(role(u,"student")) rows=s.rows(query+" AND l.group_id=?"+order,start,end,u.get("group_id"));
    else rows=s.rows(query+order,start,end);
    return aggregate(rows);
  }

  List<Map<String,Object>> aggregate(List<Map<String,Object>> rows) {
    var out=new LinkedHashMap<String,Map<String,Object>>();
    for(var row:rows){
      String key=row.get("shared_key")==null?"row:"+row.get("id"):"event:"+row.get("shared_key");
      var existing=out.get(key);
      if(existing==null){
        existing=new LinkedHashMap<>(row);
        existing.put("group_ids",new ArrayList<>(List.of(row.get("group_id"))));
        existing.put("group_names",new ArrayList<>(List.of(row.get("group_name"))));
        out.put(key,existing);
      }else{
        @SuppressWarnings("unchecked") var ids=(List<Object>)existing.get("group_ids");ids.add(row.get("group_id"));
        @SuppressWarnings("unchecked") var names=(List<Object>)existing.get("group_names");names.add(row.get("group_name"));
        existing.put("group_name",String.join(" · ",names.stream().map(String::valueOf).toList()));
      }
    }
    return new ArrayList<>(out.values());
  }

  Map<String, Object> values(Map<String, Object> b) {
    var subject = s.get("subjects", id(b.get("subject_id")));
    var teacher = s.get("users", id(b.get("teacher_id")));
    int group = id(b.get("group_id"));
    s.get("groups", group);
    if (!role(teacher, "teacher")
        || !yes(teacher.get("active"))
        || !yes(teacher.get("approved"))
        || !s.list(subject, "group_ids").contains(group)
        || !s.list(subject, "teacher_ids").contains(teacher.get("id"))) throw ApiError.invalid();
    var start = clock(b.get("start_time"));
    var end = clock(b.get("end_time"));
    if (start.compareTo(end) >= 0) throw ApiError.invalid();
    var room = str(b, "room", 1, 120, true);
    return fields(
        "subject_id",
        subject.get("id"),
        "teacher_id",
        teacher.get("id"),
        "group_id",
        group,
        "date",
        date(b.get("date")),
        "start_time",
        start,
        "end_time",
        end,
        "room",
        room,
        "room_key",
        fold(room).replaceAll("(?U)\\s+", " ").strip(),
        "kind",
        str(b, "kind", 1, 30, true),
        "week",
        num(b.get("week"), 1, 15),
        "source",
        "manual");
  }

  List<Integer> groups(Map<String,Object> b, Map<String,Object> old) {
    List<Integer> groups;
    if(b.containsKey("group_ids"))groups=ids(b.get("group_ids"));
    else if(old!=null && old.get("shared_key")!=null)
      groups=s.db.queryForList("SELECT group_id FROM schedule WHERE shared_key=? ORDER BY id",Integer.class,old.get("shared_key"));
    else groups=List.of(id(b.get("group_id")));
    if(groups.isEmpty()||groups.size()>1&&!"lecture".equals(b.get("kind")))throw ApiError.invalid();
    return groups;
  }

  void conflict(Map<String,Object> v,List<Integer> except) {
    var clashes=s.rows("SELECT id FROM schedule WHERE date=? AND start_time<? AND end_time>? AND (teacher_id=? OR group_id=? OR room_key=?)",
      v.get("date"),v.get("end_time"),v.get("start_time"),v.get("teacher_id"),v.get("group_id"),v.get("room_key"));
    if(clashes.stream().anyMatch(r->!except.contains(id(r.get("id")))))
      throw new ApiError(409,v.get("date")+": оқытушы, топ немесе аудитория бос емес.");
  }

  @PostMapping("/schedule")
  Object create(@RequestBody Map<String,Object> b) {
    s.admin();keys(b,KEYS+" repeat_weeks");
    var groups=groups(b,null);
    int count=num(b.getOrDefault("repeat_weeks",1),1,15),week=num(b.get("week"),1,15);
    if(week+count-1>15)throw ApiError.invalid();
    var start=LocalDate.parse(date(b.get("date")));
    var output=new ArrayList<Map<String,Object>>();
    for(int i=0;i<count;i++){
      var members=new ArrayList<Map<String,Object>>();
      String shared=groups.size()>1?UUID.randomUUID().toString():null;
      for(int group:groups){
        var body=new LinkedHashMap<>(b);body.put("group_id",group);body.put("date",start.plusWeeks(i).toString());body.put("week",week+i);
        var v=values(body);v.put("shared_key",shared);conflict(v,List.of());members.add(v);
      }
      for(var v:members){int inserted=s.insert("schedule",v);output.add(s.find(SELECT+" WHERE l.id=?",inserted));}
    }
    return aggregate(output);
  }

  @PatchMapping("/schedule/{id}")
  Object edit(@PathVariable int id,@RequestBody Map<String,Object> b) {
    s.admin();keys(b,KEYS);var old=s.get("schedule",id);
    var oldRows=old.get("shared_key")==null?List.of(old):s.rows("SELECT * FROM schedule WHERE shared_key=? ORDER BY id",old.get("shared_key"));
    var except=oldRows.stream().map(r->id(r.get("id"))).toList();
    var groups=groups(b,old);String shared=groups.size()>1?(old.get("shared_key")==null?UUID.randomUUID().toString():(String)old.get("shared_key")):null;
    var replacements=new ArrayList<Map<String,Object>>();
    for(int group:groups){var body=new LinkedHashMap<>(b);body.put("group_id",group);var v=values(body);v.put("shared_key",shared);conflict(v,except);replacements.add(v);}
    var result=new ArrayList<Map<String,Object>>();
    for(var v:replacements){
      var existing=oldRows.stream().filter(r->Objects.equals(r.get("group_id"),v.get("group_id"))).findFirst();
      int rowId;if(existing.isPresent()){rowId=id(existing.get().get("id"));s.update("schedule",rowId,v);}else rowId=s.insert("schedule",v);
      result.add(s.find(SELECT+" WHERE l.id=?",rowId));
    }
    for(var row:oldRows)if(!groups.contains(id(row.get("group_id"))))s.db.update("DELETE FROM schedule WHERE id=?",row.get("id"));
    return aggregate(result).get(0);
  }

  @DeleteMapping("/schedule/{id}")
  Object remove(@PathVariable int id) {
    s.admin();var old=s.get("schedule",id);
    if(old.get("shared_key")==null)s.db.update("DELETE FROM schedule WHERE id=?",id);
    else s.db.update("DELETE FROM schedule WHERE shared_key=?",old.get("shared_key"));
    return OK;
  }
}
