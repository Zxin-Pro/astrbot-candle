import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

public class ZipProbe {
    public static void main(String[] args) throws Exception {
        byte[] all = Files.readAllBytes(Paths.get(args[0]));
        int prefix = Integer.parseInt(args[1]);
        int size = Integer.parseInt(args[2]);
        int n = 0;
        long total = 0;
        boolean symlinks = false;
        java.util.List<String> hits = new java.util.ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(all, prefix, size))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                n++;
                if (e.getName().equals("SYMLINKS.txt")) symlinks = true;
                byte[] buf = new byte[8192];
                int r; long c = 0;
                while ((r = zis.read(buf)) != -1) c += r;
                total += c;
                if (n <= 3 || e.getName().startsWith("etc/profile.d/") || e.getName().startsWith("opt/qqbot")) {
                    hits.add(e.getName() + " (" + c + " bytes)");
                }
            }
        }
        System.out.println("entries=" + n + " totalBytes=" + total + " SYMLINKS.txt=" + symlinks);
        for (String s : hits) System.out.println("  " + s);
    }
}
