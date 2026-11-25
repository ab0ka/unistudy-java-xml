package kz.unistudy.app;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

/** Domain rules shared by Java screens and local JVM tests. */
public final class Rules {
    private Rules() {}
    public static boolean validGrade(String value) {
        if(value==null || value.trim().isEmpty())return true;
        String v=value.trim();
        return v.equalsIgnoreCase("NP") || v.equalsIgnoreCase("НП") || (v.matches("[0-9]{1,3}") && Integer.parseInt(v)<=100);
    }
    public static String canonicalGrade(String value) {
        if(!validGrade(value))throw new IllegalArgumentException("Баға 0–100 немесе НП болуы керек");
        if(value==null || value.trim().isEmpty())return null;
        String v=value.trim();return v.equalsIgnoreCase("NP") || v.equalsIgnoreCase("НП")?"NP":String.valueOf(Integer.parseInt(v));
    }
    public static Double average(List<String> values) {
        double sum=0;int count=0;
        for(String v:values){String n=canonicalGrade(v);if(n!=null&&!n.equals("NP")){sum+=Integer.parseInt(n);count++;}}
        return count==0?null:sum/count;
    }
    public static boolean visibleAssignmentsChanged(List<Integer> original, List<Integer> available, List<Integer> selected) {
        java.util.Set<Integer> visible=new java.util.HashSet<>(original);
        visible.retainAll(available);
        return !visible.equals(new java.util.HashSet<>(selected));
    }
    public static boolean validDate(String value){try{return LocalDate.parse(value).toString().equals(value);}catch(Exception e){return false;}}
    public static String teacherHeading(String fullName) {
        String name = fullName == null ? "" : fullName.trim();
        String[] parts = name.split("\\s+", 2);
        return parts.length < 2 ? name : parts[1];
    }
    public static boolean validEndpoint(String value) {
        try {
            URI uri = new URI(value);
            return ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getPort() != 0 && uri.getPort() <= 65535
                    && uri.getQuery() == null && uri.getFragment() == null;
        } catch (Exception error) {
            return false;
        }
    }
    public static boolean validExternalLink(String value) {
        try {
            URI uri = new URI(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getPort() != 0 && uri.getPort() <= 65535;
        } catch (Exception error) {
            return false;
        }
    }
}
