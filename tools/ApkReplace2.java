import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Streaming variant of ApkReplace: repl payloads are written straight from disk
 * (never held in a byte[]), and untouched entries are piped through a buffer.
 * Same CLI as ApkReplace so build-apk.ps1 can swap the class name.
 */
public class ApkReplace2 {
    public static void main(String[] args) throws Exception {
        String in = args[0], out = args[1];
        Map<String, Path> repl = new LinkedHashMap<>();
        for (int i = 2; i < args.length; i++) {
            String s = args[i];
            int k = s.indexOf('=');
            repl.put(s.substring(0, k), Paths.get(s.substring(k + 1)));
        }
        Set<String> seen = new HashSet<>();
        int copied = 0;
        byte[] buf = new byte[1 << 20];
        try (ZipFile zf = new ZipFile(in);
             ZipOutputStream zo = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out), 1 << 20))) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                boolean replaced = repl.containsKey(name);
                if (replaced) {
                    seen.add(name);
                    Path p = repl.get(name);
                    long size = Files.size(p);
                    ZipEntry ne = new ZipEntry(name);
                    ne.setTime(e.getTime());
                    ne.setMethod(ZipEntry.DEFLATED);
                    zo.putNextEntry(ne);
                    try (InputStream fi = new BufferedInputStream(Files.newInputStream(p), 1 << 20)) {
                        int n;
                        while ((n = fi.read(buf)) > 0) zo.write(buf, 0, n);
                    }
                    zo.closeEntry();
                    System.out.println("replaced: " + name + " -> " + size + " bytes (streamed)");
                } else if (e.isDirectory()) {
                    ZipEntry ne = new ZipEntry(name);
                    ne.setTime(e.getTime());
                    ne.setMethod(ZipEntry.STORED);
                    ne.setSize(0);
                    ne.setCompressedSize(0);
                    ne.setCrc(0);
                    zo.putNextEntry(ne);
                    zo.closeEntry();
                } else if (e.getMethod() == ZipEntry.STORED) {
                    // STORED entries need size + crc up front, so buffer this one entry then replay.
                    ByteArrayOutputStream spill = new ByteArrayOutputStream();
                    CRC32 crc = new CRC32();
                    int n;
                    try (InputStream ei = zf.getInputStream(e)) {
                        while ((n = ei.read(buf)) > 0) { spill.write(buf, 0, n); crc.update(buf, 0, n); }
                    }
                    byte[] data = spill.toByteArray();
                    ZipEntry ne = new ZipEntry(name);
                    ne.setTime(e.getTime());
                    ne.setMethod(ZipEntry.STORED);
                    ne.setSize(data.length);
                    ne.setCompressedSize(data.length);
                    ne.setCrc(crc.getValue());
                    zo.putNextEntry(ne);
                    zo.write(data);
                    zo.closeEntry();
                } else {
                    ZipEntry ne = new ZipEntry(name);
                    ne.setTime(e.getTime());
                    ne.setMethod(e.getMethod());
                    zo.putNextEntry(ne);
                    try (InputStream ei = zf.getInputStream(e)) {
                        int n;
                        while ((n = ei.read(buf)) > 0) zo.write(buf, 0, n);
                    }
                    zo.closeEntry();
                }
                copied++;
            }
            for (Map.Entry<String, Path> m : repl.entrySet()) {
                if (seen.contains(m.getKey())) continue;
                Path p = m.getValue();
                long size = Files.size(p);
                ZipEntry ne = new ZipEntry(m.getKey());
                ne.setMethod(ZipEntry.DEFLATED);
                zo.putNextEntry(ne);
                try (InputStream fi = new BufferedInputStream(Files.newInputStream(p), 1 << 20)) {
                    int n;
                    while ((n = fi.read(buf)) > 0) zo.write(buf, 0, n);
                }
                zo.closeEntry();
                System.out.println("added: " + m.getKey() + " -> " + size + " bytes (streamed)");
            }
        }
        System.out.println("copiedEntries=" + copied + " outSize=" + Files.size(Paths.get(out)));
    }
}
