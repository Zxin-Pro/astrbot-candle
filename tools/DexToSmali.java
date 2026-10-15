import com.android.tools.smali.baksmali.Baksmali;
import com.android.tools.smali.baksmali.BaksmaliOptions;
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.MultiDexContainer;
import java.io.File;

public class DexToSmali {
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void main(String[] args) throws Exception {
        File dex = new File(args[0]);
        File out = new File(args[1]);
        out.mkdirs();
        int api = Integer.parseInt(args[2]);
        MultiDexContainer c = DexFileFactory.loadDexContainer(dex, Opcodes.forApi(api));
        for (Object nameObj : c.getDexEntryNames()) {
            String name = (String) nameObj;
            MultiDexContainer.DexEntry e = c.getEntry(name);
            BaksmaliOptions o = new BaksmaliOptions();
            o.apiLevel = api;
            boolean ok = Baksmali.disassembleDexFile((DexFile) e.getDexFile(), out, 1, o);
            System.out.println(name + " disassembled=" + ok + " -> " + out.getAbsolutePath());
        }
    }
}
