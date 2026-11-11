import com.tideclock.messmachine.engine.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Measures the key of every built-in loop with the app's own KeyFinder and writes the sure ones into
 * library.json as "mkey" (for example "A# minor"). Run it again after tools/make_loops.py adds loops:
 *
 *   mkdir -p build/enginetest
 *   javac -d build/enginetest $(find app/src/main/java -name "*.java") tools/LoopKeys.java
 *   java -cp build/enginetest LoopKeys app/src/main/assets/sounds/          (add "dry" to only print)
 *
 * The loops cut from one song are also measured together, because a song has one key: a loop gets the song's
 * key when that match is sure and the loop on its own does not disagree. Unclear ones get no key.
 * Only the "mkey" entries change; the rest of the file is left byte for byte as it was.
 */
public class LoopKeys {
  public static void main(String[] a) throws Exception {
    String base = a[0];
    boolean dry = a.length > 1 && a[1].equals("dry");
    Library lib = new Library(p -> new FileInputStream(p), base);
    Map<String, List<Library.Sound>> songs = new LinkedHashMap<>();
    Map<String, KeyFinder.Tone> tones = new HashMap<>();
    for (Library.Sound s : lib.sounds) {
      if (!s.cab.equals("loops")) continue;
      Sample smp = lib.load(s.key);
      tones.put(s.key, KeyFinder.measure(smp.data, smp.rate));
      songs.computeIfAbsent(s.group.isEmpty() ? s.key : s.group, k -> new ArrayList<>()).add(s);
    }
    Map<String, String> keys = new LinkedHashMap<>();
    int n = 0;
    System.out.printf("%-48s %-10s %5s %5s %5s  %s%n", "loop", "key", "match", "ahead", "tonal", "verdict");
    for (List<Library.Sound> items : songs.values()) {
      double[] song = new double[12];
      for (Library.Sound s : items) for (int i = 0; i < 12; i++) song[i] += tones.get(s.key).chroma[i];
      double sum = 0;
      for (double v : song) sum += v;
      if (sum > 0) for (int i = 0; i < 12; i++) song[i] /= sum;
      KeyFinder.Key sk = KeyFinder.ranked(song).get(0);
      for (Library.Sound s : items) {
        n++;
        KeyFinder.Tone t = tones.get(s.key);
        List<KeyFinder.Key> own = KeyFinder.ranked(t.chroma);
        boolean agrees = false;
        for (int i = 0; i < 3; i++) if (own.get(i).notes() == sk.notes()) agrees = true;
        boolean songSure = sk.match >= KeyFinder.MATCH && sk.ahead >= KeyFinder.AHEAD;
        boolean sure = songSure && agrees && t.tonal >= KeyFinder.TONAL && own.get(0).match >= .55;
        String why = sure ? "written" : t.tonal < KeyFinder.TONAL ? "drums or noise" : !songSure ? "unclear" : "loop and song disagree";
        System.out.printf("%-48s %-10s %5.2f %5.2f %5.2f  %s%n", s.key, sk.name(), sk.match, sk.ahead, t.tonal, why);
        if (sure) keys.put(s.key, sk.name());
      }
    }
    System.out.println(keys.size() + " of " + n + " loops get a key");
    if (dry) return;
    Path file = Paths.get(base, "library.json");
    String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).replaceAll(",\"mkey\":\"[^\"]*\"", "");
    StringBuilder b = new StringBuilder(text);
    for (Map.Entry<String, String> e : keys.entrySet()) {
      int at = b.indexOf("\"k\":\"" + e.getKey() + "\"");
      if (at < 0) throw new IllegalStateException("not in library.json: " + e.getKey());
      int end = b.indexOf("}", at);
      b.insert(end, ",\"mkey\":\"" + e.getValue() + "\"");
    }
    Files.write(file, b.toString().getBytes(StandardCharsets.UTF_8));
  }
}
