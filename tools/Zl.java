import java.io.*;
import java.util.*;
import java.util.zip.*;
public class Zl {
  public static void main(String[] a) throws Exception {
    ZipFile z = new ZipFile(a[0]);
    int n = 0;
    List<String> hits = new ArrayList<String>();
    Enumeration<? extends ZipEntry> en = z.entries();
    while (en.hasMoreElements()) {
      ZipEntry e = en.nextElement();
      n++;
      String s = e.getName();
      if (s.startsWith("assets/") || s.indexOf("qqbot") >= 0) hits.add(s + "  " + e.getSize() + "  " + e.getMethod());
    }
    System.out.println(a[0] + "  entries=" + n);
    for (String h : hits) System.out.println("   " + h);
    z.close();
  }
}