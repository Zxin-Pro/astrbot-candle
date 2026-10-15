import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public class VerifyBootstrap2 {
    public static void main(String[] args) throws Exception {
        String modPath = args[0];
        String priPath = args[1];
        int prefix = Integer.parseInt(args[2]);
        int blobSize = Integer.parseInt(args[3]);
        int eocdEnd = Integer.parseInt(args[4]);
        byte[] m = Files.readAllBytes(Paths.get(modPath));
        byte[] p = Files.readAllBytes(Paths.get(priPath));
        System.out.println("file size preserved   : " + (m.length == p.length) + "  (" + m.length + ")");
        System.out.println("ELF magic             : " + (m[0] == 0x7f && m[1] == 'E' && m[2] == 'L' && m[3] == 'F'));
        System.out.println("e_type / e_machine    : " + le16(m, 0x10) + " / " + le16(m, 0x12) + "  (want 3 / 183)");
        System.out.println("e_shoff mod=pristine  : " + (le32(m,0x28) == le32(p,0x28)) + "  (" + le32(m,0x28) + ")");
        System.out.println("e_shnum mod=pristine  : " + (le16(m,0x3c) == le16(p,0x3c)) + "  (" + le16(m,0x3c) + ")");
        boolean headOk = true;
        for (int i = 0; i < prefix; i++) if (m[i] != p[i]) { headOk = false; break; }
        System.out.println("ELF head[0," + prefix + ") identical : " + headOk);
        boolean tailOk = true;
        int tailLen = p.length - eocdEnd;
        for (int i = 0; i < tailLen; i++) if (m[eocdEnd + i] != p[eocdEnd + i]) { tailOk = false; break; }
        System.out.println("ELF tail[" + eocdEnd + ",end) identical : " + tailOk + "  (" + tailLen + " bytes)");
        System.out.println("PK at offset " + prefix + " : " + (m[prefix]==0x50 && m[prefix+1]==0x4b && m[prefix+2]==0x03 && m[prefix+3]==0x04));
        System.out.println();
        System.out.println("--- simulating TermuxInstaller getZip() + ZipInputStream ---");
        byte[] zipBytes = new byte[blobSize];
        System.arraycopy(m, prefix, zipBytes, 0, blobSize);
        System.out.println("getZip() returns " + zipBytes.length + " bytes starting with PK : " + (zipBytes[0]==0x50 && zipBytes[1]==0x4b));
        ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zipBytes));
        ZipEntry e;
        int n = 0, chmodPrefix = 0, dirs = 0;
        long uncompressed = 0;
        boolean symlinks = false;
        Set<String> qqbot = new TreeSet<String>();
        while ((e = z.getNextEntry()) != null) {
            n++;
            String name = e.getName();
            if (e.isDirectory()) dirs++;
            if (name.equals("SYMLINKS.txt")) symlinks = true;
            if (name.startsWith("bin/") || name.startsWith("libexec") || name.startsWith("lib/apt/apt-helper") || name.startsWith("lib/apt/methods")) chmodPrefix++;
            if (name.startsWith("opt/qqbot/") || name.startsWith("etc/profile.d/50-qqbot")) qqbot.add(name);
            byte[] buf = new byte[65536];
            int r;
            while ((r = z.read(buf)) > 0) uncompressed += r;
        }
        z.close();
        System.out.println("entries extracted     : " + n + "  (dirs=" + dirs + ")");
        System.out.println("total uncompressed    : " + uncompressed);
        System.out.println("SYMLINKS.txt present  : " + symlinks);
        System.out.println("chmod-0700 entries    : " + chmodPrefix);
        System.out.println("injected qqbot entries: " + qqbot.size());
        for (String s : qqbot) System.out.println("    " + s);
    }
    static int le32(byte[] b, int o) { return (b[o] & 0xff) | ((b[o+1] & 0xff) << 8) | ((b[o+2] & 0xff) << 16) | ((b[o+3] & 0xff) << 24); }
    static int le16(byte[] b, int o) { return (b[o] & 0xff) | ((b[o+1] & 0xff) << 8); }
}
