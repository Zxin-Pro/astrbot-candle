import java.io.*;
import java.nio.file.*;
import java.util.*;

public class ArTool {
    public static void main(String[] args) throws Exception {
        byte[] b = Files.readAllBytes(Paths.get(args[0]));
        String magic = new String(b, 0, 8, "US-ASCII");
        if (!magic.equals("!<arch>\n")) { System.out.println("not an ar archive: " + magic); return; }
        int p = 8;
        String want = args.length > 1 ? args[1] : null;
        String outDir = args.length > 2 ? args[2] : null;
        while (p + 60 <= b.length) {
            String name = new String(b, p, 16, "US-ASCII").trim();
            String sizeStr = new String(b, p + 48, 10, "US-ASCII").trim();
            if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
            int size;
            try { size = Integer.parseInt(sizeStr); } catch (Exception ex) { break; }
            System.out.println("  member: " + name + "  size=" + size);
            if (want != null && name.equals(want) && outDir != null) {
                Path o = Paths.get(outDir, name);
                Files.write(o, Arrays.copyOfRange(b, p + 60, p + 60 + size));
                System.out.println("    -> extracted to " + o);
            }
            p += 60 + size;
            if (p % 2 != 0) p++;
        }
    }
}
