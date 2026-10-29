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
                    putFile(zo, name, p, e.getTime(), buf);
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
                putFile(zo, m.getKey(), m.getValue(), 0L, buf);
            }
        }
        System.out.println("copiedEntries=" + copied + " outSize=" + Files.size(Paths.get(out)));
    }

    // 已经是压缩格式的载荷再 deflate 只会变大（实测 bundle +170,002 B，0.029%），
    // 而且只有 STORED 条目才能让 AssetManager.openFd() 直接返回 fd —— 设备上就能
    // 省掉一次 583 MB 的 inflate。>=64 MB 的条目一律 STORED，不再逐个判断扩展名。
    private static boolean shouldStore(String name, long size) {
        return name.endsWith(".tar.xz") || name.endsWith(".zip") || size >= (64L << 20);
    }

    // 流式写一条（永不整条进 byte[]）：STORED 必须在 putNextEntry 之前给出
    // size / compressedSize / crc，所以 STORED 走两遍 —— 第一遍只算 CRC，第二遍写入。
    private static void putFile(ZipOutputStream zo, String name, Path p, long time, byte[] buf) throws IOException {
        long size = Files.size(p);
        boolean store = shouldStore(name, size);
        ZipEntry ne = new ZipEntry(name);
        if (time > 0) ne.setTime(time);
        if (store) {
            CRC32 c = new CRC32();
            try (InputStream fi = new BufferedInputStream(Files.newInputStream(p), 1 << 20)) {
                int n;
                while ((n = fi.read(buf)) > 0) c.update(buf, 0, n);
            }
            ne.setMethod(ZipEntry.STORED);
            ne.setSize(size);
            ne.setCompressedSize(size);
            ne.setCrc(c.getValue());
        } else {
            ne.setMethod(ZipEntry.DEFLATED);
        }
        zo.putNextEntry(ne);
        long written = 0;
        try (InputStream fi = new BufferedInputStream(Files.newInputStream(p), 1 << 20)) {
            int n;
            while ((n = fi.read(buf)) > 0) { zo.write(buf, 0, n); written += n; }
        }
        // 两遍之间文件被改写会让 CRC/size 对不上，这里先拦一道给出可读的错误。
        if (written != size) throw new IOException("payload changed while packing: " + name);
        zo.closeEntry();
        System.out.println((store ? "stored: " : "deflated: ") + name + " -> " + size + " bytes");
    }

}
