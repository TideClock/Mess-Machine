import com.tideclock.messmachine.engine.WebEngine;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import org.teavm.jso.typedarrays.Float32Array;
import org.teavm.jso.typedarrays.Int8Array;

/** Renders the demo mess on the JVM through WebEngine, so the JavaScript build can be checked against it sample for sample. */
public class Golden {
    public static void main(String[] a) throws Exception {
        Path sounds = Paths.get(a[0]), out = Paths.get(a[1]);
        WebEngine.init(48000);
        String demo = WebEngine.demoMess();
        for (String name : new String[]{"basement-kick", "wet-clap", "pot-lid-hat", "bw-snare", "tom-of-doom", "bubble-pop", "spring-doink", "tape-wobble-chord"}) {
            Path f = sounds.resolve(name + ".flac");
            if (Files.exists(f)) WebEngine.addFile("samples/" + name + ".wav", new Int8Array(Files.readAllBytes(f)), 0, 0);
        }
        System.out.println("missing " + WebEngine.setMess(demo));
        WebEngine.play(0);
        int blocks = 48000 * 3 / 128;
        ByteBuffer bb = ByteBuffer.allocate(blocks * 128 * 8).order(ByteOrder.LITTLE_ENDIAN);
        Float32Array l = new Float32Array(128), r = new Float32Array(128);
        for (int b = 0; b < blocks; b++) { WebEngine.render(l, r); for (int i = 0; i < 128; i++) { bb.putFloat(l.a[i]); bb.putFloat(r.a[i]); } }
        Files.write(out, bb.array());
        System.out.println("errors " + WebEngine.errors() + " " + WebEngine.lastError());
    }
}
