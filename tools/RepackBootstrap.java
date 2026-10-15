import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public class RepackBootstrap {

    static class Ent {
        String name;
        int method, flags, time, date, versionMadeBy, versionNeeded, internalAttrs;
        long crc, csize, usize, externalAttrs, lho;
        byte[] extra, comment;
        int fnl;
    }

    static long u32(byte[] b, int o) {
        return ((long) (b[o] & 0xFF)) | (((long) (b[o + 1] & 0xFF)) << 8)
             | (((long) (b[o + 2] & 0xFF)) << 16) | (((long) (b[o + 3] & 0xFF)) << 24);
    }

    static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    static void put16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >>> 8) & 0xFF);
    }

    static void put32(ByteArrayOutputStream o, long v) {
        o.write((int) (v & 0xFF));
        o.write((int) ((v >>> 8) & 0xFF));
        o.write((int) ((v >>> 16) & 0xFF));
        o.write((int) ((v >>> 24) & 0xFF));
    }

    static byte[] deflate(byte[] data) {
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION, true);
        d.setInput(data);
        d.finish();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        while (!d.finished()) {
            int n = d.deflate(buf);
            bo.write(buf, 0, n);
        }
        d.end();
        return bo.toByteArray();
    }

    static List<Ent> parse(byte[] all, int prefix, int sectionSize) throws Exception {
        int zipEnd = prefix + sectionSize;
        int eocd = -1;
        int lo = prefix;
        for (int i = zipEnd - 22; i >= lo; i--) {
            if (u32(all, i) == 0x06054B50L) { eocd = i; break; }
        }
        if (eocd < 0) throw new IOException("EOCD not found");
        int total = u16(all, eocd + 10);
        long cdOff = u32(all, eocd + 16);
        int cdAbs = (int) (prefix + cdOff);
        List<Ent> ents = new ArrayList<>();
        int p = cdAbs;
        for (int i = 0; i < total; i++) {
            if (u32(all, p) != 0x02014B50L) throw new IOException("bad CD sig at " + p);
            Ent e = new Ent();
            e.versionMadeBy = u16(all, p + 4);
            e.versionNeeded = u16(all, p + 6);
            e.flags = u16(all, p + 8);
            e.method = u16(all, p + 10);
            e.time = u16(all, p + 12);
            e.date = u16(all, p + 14);
            e.crc = u32(all, p + 16);
            e.csize = u32(all, p + 20);
            e.usize = u32(all, p + 24);
            e.fnl = u16(all, p + 28);
            int exl = u16(all, p + 30);
            int cml = u16(all, p + 32);
            e.internalAttrs = u16(all, p + 36);
            e.externalAttrs = u32(all, p + 38);
            e.lho = u32(all, p + 42);
            e.name = new String(all, p + 46, e.fnl, "UTF-8");
            e.extra = Arrays.copyOfRange(all, p + 46 + e.fnl, p + 46 + e.fnl + exl);
            e.comment = Arrays.copyOfRange(all, p + 46 + e.fnl + exl, p + 46 + e.fnl + exl + cml);
            ents.add(e);
            p += 46 + e.fnl + exl + cml;
        }
        return ents;
    }

    public static void main(String[] args) throws Exception {
        String cmd = args[0];
        String blob = args[1];
        int prefix = Integer.parseInt(args[2]);
        int sectionSize = Integer.parseInt(args[3]);

        if (cmd.equals("list")) {
            byte[] all = Files.readAllBytes(Paths.get(blob));
            List<Ent> ents = parse(all, prefix, sectionSize);
            for (Ent e : ents) {
                System.out.println(e.name + "|" + e.method + "|" + e.csize + "|" + e.usize + "|" + e.lho + "|" + e.flags + "|" + e.externalAttrs);
            }
            System.err.println("count=" + ents.size());
            return;
        }

        if (cmd.equals("cat")) {
            String name = args[4];
            String outFile = args[5];
            byte[] all = Files.readAllBytes(Paths.get(blob));
            List<Ent> ents = parse(all, prefix, sectionSize);
            for (Ent e : ents) {
                if (!e.name.equals(name)) continue;
                if (e.method != 0 && e.method != 8) throw new IOException("unsupported method " + e.method);
                int srcLho = prefix + (int) e.lho;
                int lfLen = u16(all, srcLho + 26);
                int leLen = u16(all, srcLho + 28);
                int dataOff = srcLho + 30 + lfLen + leLen;
                byte[] raw = Arrays.copyOfRange(all, dataOff, dataOff + (int) e.csize);
                byte[] data;
                if (e.method == 0) {
                    data = raw;
                } else {
                    boolean zlibWrapped = ((raw[0] & 0x0F) == 8)
                        && (((((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF)) % 31) == 0);
                    Inflater inf = new Inflater(!zlibWrapped);
                    inf.setInput(raw);
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    while (!inf.finished()) {
                        int n = inf.inflate(buf);
                        if (n == 0 && inf.needsInput()) break;
                        bo.write(buf, 0, n);
                    }
                    inf.end();
                    data = bo.toByteArray();
                }
                Files.write(Paths.get(outFile), data);
                System.out.println("wrote " + outFile + " " + data.length + " bytes");
                return;
            }
            throw new IOException("entry not found: " + name);
        }

        if (cmd.equals("repack")) {
            String out = args[4];
            Set<String> drops = new LinkedHashSet<>();
            List<String[]> adds = new ArrayList<>();
            for (int i = 5; i < args.length; i++) {
                if (args[i].equals("--drop")) { drops.add(args[++i]); }
                else if (args[i].equals("--add") || args[i].equals("--addx")) {
                    boolean exec = args[i].equals("--addx");
                    String s = args[++i];
                    int k = s.indexOf('=');
                    adds.add(new String[]{ s.substring(0, k), s.substring(k + 1), exec ? "x" : "-" });
                }
            }
            byte[] all = Files.readAllBytes(Paths.get(blob));
            List<Ent> ents = parse(all, prefix, sectionSize);
            ByteArrayOutputStream zip = new ByteArrayOutputStream(sectionSize);
            List<Ent> kept = new ArrayList<>();
            int droppedCount = 0;
            long droppedBytes = 0;
            for (Ent e : ents) {
                boolean dropped = false;
                for (String d : drops) { if (e.name.startsWith(d)) { dropped = true; break; } }
                if (!dropped) {
                    for (String[] a : adds) { if (a[0].equals(e.name)) { dropped = true; break; } }
                }
                if (dropped) { droppedCount++; droppedBytes += e.csize; continue; }
                int srcLho = prefix + (int) e.lho;
                int lfLen = u16(all, srcLho + 26);
                int leLen = u16(all, srcLho + 28);
                int localLen = 30 + lfLen + leLen + (int) e.csize;
                if ((e.flags & 8) != 0) localLen += 12;
                e.lho = zip.size();
                zip.write(all, srcLho, localLen);
                kept.add(e);
            }
            for (String[] a : adds) {
                String name = a[0];
                byte[] data = Files.readAllBytes(Paths.get(a[1]));
                byte[] comp = deflate(data);
                byte[] nameBytes = name.getBytes("UTF-8");
                Ent e = new Ent();
                e.name = name;
                e.method = 8;
                e.flags = 0;
                e.time = 0;
                e.date = 33;
                e.versionMadeBy = 0x031E;
                e.versionNeeded = 20;
                e.internalAttrs = 0;
                e.externalAttrs = a[2].equals("x") ? 0x81ED0000L : 0x81A40000L;
                CRC32 crc = new CRC32();
                crc.update(data);
                e.crc = crc.getValue();
                e.csize = comp.length;
                e.usize = data.length;
                e.fnl = nameBytes.length;
                e.extra = new byte[0];
                e.comment = new byte[0];
                e.lho = zip.size();
                ByteArrayOutputStream lh = new ByteArrayOutputStream();
                put32(lh, 0x04034B50L);
                put16(lh, 20);
                put16(lh, 0);
                put16(lh, 8);
                put16(lh, e.time);
                put16(lh, e.date);
                put32(lh, e.crc);
                put32(lh, e.csize);
                put32(lh, e.usize);
                put16(lh, e.fnl);
                put16(lh, 0);
                lh.write(nameBytes);
                zip.write(lh.toByteArray());
                zip.write(comp);
                kept.add(e);
            }
            long cdStart = zip.size();
            for (Ent e : kept) {
                byte[] nameBytes = e.name.getBytes("UTF-8");
                put32(zip, 0x02014B50L);
                put16(zip, e.versionMadeBy);
                put16(zip, e.versionNeeded == 0 ? 20 : e.versionNeeded);
                put16(zip, e.flags);
                put16(zip, e.method);
                put16(zip, e.time);
                put16(zip, e.date);
                put32(zip, e.crc);
                put32(zip, e.csize);
                put32(zip, e.usize);
                put16(zip, nameBytes.length);
                put16(zip, e.extra.length);
                put16(zip, e.comment.length);
                put16(zip, 0);
                put16(zip, e.internalAttrs);
                put32(zip, e.externalAttrs);
                put32(zip, e.lho);
                zip.write(nameBytes);
                zip.write(e.extra);
                zip.write(e.comment);
            }
            long cdSize = zip.size() - cdStart;
            put32(zip, 0x06054B50L);
            put16(zip, 0);
            put16(zip, 0);
            put16(zip, kept.size());
            put16(zip, kept.size());
            put32(zip, cdSize);
            put32(zip, cdStart);
            put16(zip, 0);
            byte[] newZip = zip.toByteArray();
            if (newZip.length > sectionSize) {
                throw new IOException("new zip too big: " + newZip.length + " > " + sectionSize);
            }
            byte[] outBytes = all.clone();
            System.arraycopy(newZip, 0, outBytes, prefix, newZip.length);
            for (int i = prefix + newZip.length; i < prefix + sectionSize; i++) outBytes[i] = 0;
            Files.write(Paths.get(out), outBytes);
            System.out.println("dropped=" + droppedCount + " droppedBytes=" + droppedBytes
                + " kept=" + kept.size() + " newZip=" + newZip.length
                + " sectionSize=" + sectionSize + " outSize=" + outBytes.length);
            return;
        }

        throw new IllegalArgumentException("unknown cmd " + cmd);
    }
}
