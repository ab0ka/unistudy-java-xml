package kz.unistudy;
import static kz.unistudy.Store.fields;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class GradeCalculationTest {
 @Test void provisionalWeightsExcludeAbsenceAndMissingAndKeepIncompletePeriodOpen() {
  var assessments=List.of(fields("id",1,"kind","lecture","week",1),fields("id",2,"kind","Зертханалық","week",2),fields("id",3,"kind","sro","week",7),fields("id",4,"kind","lab","week",2),fields("id",5,"kind","lecture","week",8),fields("id",6,"kind","lab","week",10),fields("id",7,"kind","sro","week",11));
  var entries=List.of(fields("student_id",1,"assessment_id",1,"value","90"),fields("student_id",1,"assessment_id",2,"value","92"),fields("student_id",1,"assessment_id",3,"value","86"),fields("student_id",1,"assessment_id",4,"value","NP"),fields("student_id",1,"assessment_id",5,"value","100"),fields("student_id",1,"assessment_id",6,"value","100"),fields("student_id",1,"assessment_id",7,"value","100"));
  var result=GradeCalculation.summary(1,fields("rk1",90,"rk2",100,"exam",100,"version",3),assessments,entries);
  assertEquals(89.8,result.get("tk1"));assertEquals(89.9,result.get("r1"));assertEquals(100.0,result.get("tk2"));assertNull(result.get("r2"));assertNull(result.get("final"));assertNull(result.get("provisional_final"));assertEquals(false,result.get("period2_complete"));
 }
 @Test void missingKindIsNotSilentlyZeroOrReweighted() {
  var result=GradeCalculation.summary(1,fields("rk1",100,"version",1),List.of(fields("id",1,"kind","lecture","week",1)),List.of(fields("student_id",1,"assessment_id",1,"value","0")));
  assertNull(result.get("tk1"));assertNull(result.get("r1"));assertTrue(((List<?>)result.get("missing_fields")).contains("tk1_lab"));
 }
}
