import com.kingzcheung.xime.rime.RimeEngine;
import java.io.File;
import java.util.Arrays;

/**
 * Drag-to-edit part of the first sentence (首选上拖选改局部) on a given schema: the snapshot maps each character to
 * its keys, a replacement code for the dragged characters lists candidates in an isolated session, and applying one
 * keeps the rest of the sentence and the input. Arguments: LIB USER SCHEMA INPUT START END CODE REPLACEMENT
 * [EXPECTED-SENTENCE]. CODE and REPLACEMENT may be split into words with | (JUB|TUAB and 进|田: two picks, as on
 * the keyboard). USER is a disposable deployed user directory listing SCHEMA. Nine keys: INPUT and CODE are
 * digits typed through the nine-key path.
 */
public final class SentenceEditProbe {
  public static void main(String[] args) throws Exception {
    System.load(new File(args[0], "librime_jni.so").getAbsolutePath());
    new File(args[1], "logs").mkdirs();
    String schema = args[2], input = args[3];
    String[] codes = args[6].split("\\|"), texts = args[7].split("\\|");
    String code = String.join("", codes), replacement = String.join("", texts);
    int start = Integer.parseInt(args[4]), end = Integer.parseInt(args[5]);
    boolean digits = input.chars().allMatch(c -> c >= '2' && c <= '9');
    RimeEngine e = new RimeEngine();
    e.nativeInitialize(args[1], args[1]);
    try {
      e.nativeStartMaintenance(true);
      long until = System.currentTimeMillis() + 180000;
      while (e.nativeIsMaintaining()) {
        if (System.currentTimeMillis() > until) throw new Exception("deployment timeout");
        Thread.sleep(100);
      }
      if (!e.nativeCreateSession()) throw new Exception("session unavailable");
      if (!e.nativeSwitchSchema(schema)) throw new Exception("schema unavailable: " + schema);
      e.nativeSetOption("ascii_mode", false);
      if (digits) e.nativeT9ClearComposition(1);
      for (char c : input.toCharArray()) {
        if (!e.nativeProcessKey(c, 0)) throw new Exception("unhandled key " + c);
        if (digits) e.nativeT9FlushRimeInput();
      }
      String[] snapshot = e.nativeSentenceEditSnapshot();
      System.out.println("snapshot " + Arrays.toString(snapshot));
      require(snapshot.length >= 5, "no sentence snapshot");
      String sentence = snapshot[1];
      int[] lengths = new int[codes.length * 2];
      int[] choices = new int[codes.length];
      String rest = code;
      for (int i = 0; i < codes.length; ++i) {
        String[] words = e.nativeSentenceEditCandidates(rest, new int[0], -1, -1);
        System.out.println("candidates for " + rest + " " + Arrays.toString(words));
        String wanted = codes[i].length() + "\t" + texts[i];
        require(Arrays.stream(words).anyMatch(wanted::equals), "word " + texts[i] + " not listed for " + rest);
        lengths[2 * i] = codes[i].length();
        lengths[2 * i + 1] = texts[i].length();
        rest = rest.substring(codes[i].length());
      }
      require(e.nativeApplySentenceEdit(snapshot[0], sentence, start, end, code, replacement, lengths, choices),
          "apply failed");
      String expected = args.length > 8 ? args[8] : sentence.substring(0, start) + replacement + sentence.substring(end);
      String first = e.nativeInspectCandidates(1)[0][0];
      System.out.println("after apply: input=" + e.nativeGetInput() + " first=" + first);
      require(first.equals(expected), "first candidate " + first + ", expected " + expected);
      System.out.println("PASS " + schema + " " + input + " [" + start + "," + end + ") -> " + first);
    } finally {
      e.nativeDestroy();
    }
  }

  static void require(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }
}
