import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public class VerifyBootstrap {
    public static void main(String[] args) throws Exception {
        String modPath = args[0];
        String priPath = args[1];
        int prefix = Integer.parseInt(args[2]);
        int eocdEnd = Integer.parseInt(args[3]);
        byte[] m = Files.readAllBytes(Paths.get(modPath));
        byte[] p = Files.readAllBytes(Paths.get(priPath));
        System.out.println("modified size = " + m.length + ", pristine size = " + p.length);
        System.out.println("size preserved  : " + (m.length == p.length));
        System.out.println("ELF magic       : " + (m[0] == 0x7f && m[1] == 'E' && m[2] == 'L' && m[3] == 'F'));
        System.out.println("e_type(PIE)     : " + le16(m, 0x10) + " (3 = ET_DYN)");
        System.out.println("e_machine       : " + le16(m, 0x12) + " (183 = AArch64)");
        boolean headOk = true;
        for (int i = 0; i < prefix; i++) { if (m[i] != p[i]) { headOk = false; System.out.println("  head differs at " + i); break; } }
        System.out.println("head[" + prefix + "] same   : " + headOk);
        int tailLen = p.length - eocdEnd;
        boolean tailOk = true;
        for (int i = 0; i < tailLen; i++) { if (m[eocdEnd + i] != p[eocdEnd + i]) { tailOk = false; System.out.println("  tail differs at " + (eocdEnd + i)); break; } }
        System.out.println("tail[" + tailLen + "] same    : " + tailOk);
        System.out.println("e_shoff  mod/pristine : " + le32(m, 0x28) + " / " + le32(p, 0x28));
        System.out.println("e_shnum  mod/pristine : " + le16(m, 0x3c) + " / " + le16(p, 0x3c));
        System.out.println("e_shentsize           : " + le16(m, 0x3a));
        System.out.println();
        System.out.println("--- simulating TermuxInstaller ZipInputStream pass ---");
        ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(m));
        ZipEntry e;
        int n = 0, chmodPrefix = 0;
        long uncompressed = 0;
        boolean symlinks = false;
        boolean sawQqbot = false;
        Set<String> qqbotNames = new TreeSet<String>();
        while ((e = z.getNextEntry()) != null) {
            n++;
            String name = e.getName();
            if (name.equals("SYMLINKS.txt")) symlinks = true;
            if (name.startsWith("bin/") || name.startsWith("libexec") || name.startsWith("lib/apt/apt-helper") || name.startsWith("lib/apt/methods")) chmodPrefix++;
            if (name.startsWith("opt/qqbot/") || name.startsWith("etc/profile.d/50-qqbot")) { sawQqbot = true; qqbotNames.add(name); }
            byte[] buf = new byte[65536];
            int r;
            while ((r = z.read(buf)) > 0) uncompressed += r;
        }
        z.close();
        System.out.println("entries read sequentially : " + n);
        System.out.println("total uncompressed bytes  : " + uncompressed);
        System.out.println("SYMLINKS.txt present      : " + symlinks + "  (TermuxInstaller throws if absent)");
        System.out.println("chmod-0700 prefix entries : " + chmodPrefix);
        System.out.println("qqbot entries found       : " + qqbotNames.size() + "  injected=" + sawQqbot);
        for (String s : qqbotNames) System.out.println("    " + s);
    }
    static int le32(byte[] b, int o) { return (b[o] & 0xff) | ((b[o+1] & 0xff) << 8) | ((b[o+2] & 0xff) << 16) | ((b[o+3] & 0xff) << 24); }
    static int le16(byte[] b, int o) { return (b[o] & 0xff) | ((b[o+1] & 0xff) << 8); }
}
