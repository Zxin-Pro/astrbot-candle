import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public class ApkReplace {
    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        return bo.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        String in = args[0], out = args[1];
        Map<String, byte[]> repl = new LinkedHashMap<>();
        for (int i = 2; i < args.length; i++) {
            String s = args[i];
            int k = s.indexOf('=');
            repl.put(s.substring(0, k), Files.readAllBytes(Paths.get(s.substring(k + 1))));
        }
        Set<String> seen = new HashSet<>();
        int copied = 0;
        try (ZipFile zf = new ZipFile(in);
             ZipOutputStream zo = new ZipOutputStream(new FileOutputStream(out))) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                byte[] data;
                boolean replaced = repl.containsKey(name);
                if (replaced) { data = repl.get(name); seen.add(name); }
                else { data = readAll(zf.getInputStream(e)); }
                ZipEntry ne = new ZipEntry(name);
                ne.setTime(e.getTime());
                int method = e.getMethod();
                if (e.isDirectory()) method = ZipEntry.STORED;
                ne.setMethod(method);
                if (method == ZipEntry.STORED) {
                    ne.setSize(data.length);
                    ne.setCompressedSize(data.length);
                    CRC32 c = new CRC32();
                    c.update(data);
                    ne.setCrc(c.getValue());
                }
                zo.putNextEntry(ne);
                zo.write(data);
                zo.closeEntry();
                copied++;
                if (replaced) System.out.println("replaced: " + name + " -> " + data.length + " bytes");
            }
            for (Map.Entry<String, byte[]> m : repl.entrySet()) {
                if (!seen.contains(m.getKey())) {
                    ZipEntry ne = new ZipEntry(m.getKey());
                    zo.putNextEntry(ne);
                    zo.write(m.getValue());
                    zo.closeEntry();
                    System.out.println("added: " + m.getKey() + " -> " + m.getValue().length + " bytes");
                }
            }
        }
        System.out.println("copiedEntries=" + copied + " outSize=" + Files.size(Paths.get(out)));
    }
}
