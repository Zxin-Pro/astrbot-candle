import java.io.*;
import java.util.zip.*;
public class Zx {
  public static void main(String[] a) throws Exception {
    ZipFile z = new ZipFile(a[0]);
    ZipEntry e = z.getEntry(a[1]);
    if (e == null) { System.out.println("NOT FOUND " + a[1]); return; }
    InputStream in = z.getInputStream(e);
    OutputStream out = new FileOutputStream(a[2]);
    byte[] b = new byte[1 << 16]; int n;
    while ((n = in.read(b)) > 0) out.write(b, 0, n);
    out.close(); in.close(); z.close();
    System.out.println("extracted " + new File(a[2]).length());
  }
}