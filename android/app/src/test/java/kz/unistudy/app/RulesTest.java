package kz.unistudy.app;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;
public class RulesTest {
 @Test public void averagesIncludeZeroAndExcludeAbsences(){assertEquals(50d,Rules.average(Arrays.asList("0","100","NP","НП","")),0.001);assertNull(Rules.average(Arrays.asList("NP","")));}
 @Test public void gradesAreBoundedIntegers(){for(String s:Arrays.asList("-1","101","1.5","NaN","1e2"))assertFalse(s,Rules.validGrade(s));assertEquals("NP",Rules.canonicalGrade("нп"));assertEquals("0",Rules.canonicalGrade("000"));assertNull(Rules.canonicalGrade("  "));}
 @Test public void endpointsRejectCredentialsAndMalformedUrls(){assertTrue(Rules.validEndpoint("http://10.0.2.2:8000"));assertTrue(Rules.validEndpoint("https://study.example.com"));for(String s:Arrays.asList("file:///etc","https://user:secret@host","https://host?token=a","https://host#x","host"))assertFalse(s,Rules.validEndpoint(s));}
 @Test public void datesRejectImpossibleDays(){assertTrue(Rules.validDate("2028-02-29"));assertFalse(Rules.validDate("2026-02-29"));assertFalse(Rules.validDate("2026-2-1"));}
 @Test public void teacherCabinetOmitsSurname(){assertEquals("Айдана Демоқызы",Rules.teacherHeading("Үлгі Айдана Демоқызы"));assertEquals("Айдана Демоқызы",Rules.teacherHeading("  Үлгі  Айдана Демоқызы  "));assertEquals("Айдана",Rules.teacherHeading("Айдана"));}
 @Test public void externalLinksCannotLaunchLocalOrScriptSchemes(){assertTrue(Rules.validExternalLink("https://drive.google.com/drive/folders/example"));for(String s:Arrays.asList("javascript:alert(1)","file:///private/data","intent://host","https://user:secret@host","http://host","https://host:70000"))assertFalse(s,Rules.validExternalLink(s));}
 @Test public void unavailableTeacherAssignmentsSurviveUnchangedSelection(){
  assertFalse(Rules.visibleAssignmentsChanged(Arrays.asList(1,2),Arrays.asList(1,3),Arrays.asList(1)));
  assertFalse(Rules.visibleAssignmentsChanged(Arrays.asList(2),Arrays.asList(1,3),Arrays.asList()));
  assertTrue(Rules.visibleAssignmentsChanged(Arrays.asList(1,2),Arrays.asList(1,3),Arrays.asList(1,3)));
  assertTrue(Rules.visibleAssignmentsChanged(Arrays.asList(1,2),Arrays.asList(1,3),Arrays.asList()));
 }
}
