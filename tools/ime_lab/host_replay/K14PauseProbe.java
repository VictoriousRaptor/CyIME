import com.kingzcheung.xime.rime.RimeEngine;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 14-key full pinyin after a pause (cyime_pause_14jian): one key left out, one key pressed twice and two keys typed
 * in the wrong order inside one syllable gain the intended word after nativeRefineOmission; correctly typed input
 * keeps its menu; the first two candidates never move. A slip whose word the typing already offers (abbreviations,
 * sentence parts) only has to keep the first two. One exception (t9/model_sentences_on_pause): the sentence the word
 * model builds in the same refresh may take the first place, and the candidate that led while typing then follows it.
 * Arguments: LIB USER [CASES.tsv]. USER is a disposable user
 * directory listing pinyin_14jian; CASES.tsv rows are "slip<TAB>input<TAB>target", "keep<TAB>input" (a slip whose
 * target must not be added, or correctly typed input: the menu stays), "lead<TAB>input<TAB>target" (after the pause
 * the target leads and the candidate that led while typing is second) or "off<TAB>omission|extra|swap|all|none" (the
 * keyboard's 漏键, 多键, 顺序 switches as the session options the app sets; all turns the three off, none back on) or
 * "trace<TAB>input" (lists the pause prism's own readings of the whole input, in its order, with no gate: it patches
 * an engine into cyime_pause_14jian in USER before deploying, so use a disposable copy).
 */
public final class K14PauseProbe {
  static String off = "none";

  public static void main(String[] args) throws Exception {
    System.load(new File(args[0], "librime_jni.so").getAbsolutePath());
    new File(args[1], "logs").mkdirs();
    List<String[]> cases = new ArrayList<>();
    if (args.length > 2) {
      for (String line : Files.readAllLines(new File(args[2]).toPath()))
        if (!line.isBlank() && !line.startsWith("#")) cases.add(line.split("\t"));
    } else {
      // Tiny dictionary used by the cloud check: 喜欢 (xi huan), 今天 (jin tian), 时候 (shi hou), 我们 (wo men).
      cases.add(new String[]{"slip", "ZUGAB", "喜欢"});        // huan without u
      cases.add(new String[]{"slip", "JUUBTUAB", "今天"});     // jin with u twice
      cases.add(new String[]{"slip", "QOmBE", "我们"});         // men as mne
      cases.add(new String[]{"keep", "ZUGUAB"});
      cases.add(new String[]{"keep", "JUBTUAB"});
      cases.add(new String[]{"keep", "QOmEB"});
    }
    if (cases.stream().anyMatch(c -> c[0].equals("trace")))
      Files.writeString(new File(args[1], "cyime_pause_14jian.custom.yaml").toPath(),
          "patch:\n  engine/processors: [speller, express_editor]\n  engine/segmentors: [abc_segmentor]\n" +
          "  engine/translators: [script_translator]\n  menu/page_size: 9\n");
    RimeEngine e = new RimeEngine();
    e.nativeInitialize(args[1], args[1]);
    int failed = 0;
    try {
      e.nativeStartMaintenance(true);
      long until = System.currentTimeMillis() + 180000;
      while (e.nativeIsMaintaining()) {
        if (System.currentTimeMillis() > until) throw new Exception("deployment timeout");
        Thread.sleep(100);
      }
      if (!e.nativeCreateSession()) throw new Exception("session unavailable");
      for (String[] c : cases) {
        if (c[0].equals("off")) {
          off = c[1];
          System.out.println("switch off: " + off);
          continue;
        }
        String input = c[1];
        if (c[0].equals("trace")) {
          trace(e, input);
          continue;
        }
        type(e, input);
        String[][] before = e.nativeInspectCandidates(5);
        boolean changed = e.nativeRefineOmission();
        String[][] after = e.nativeInspectCandidates(5);
        List<String> words = new ArrayList<>();
        for (String[] row : after) words.add(row[0]);
        String result;
        if (c[0].equals("slip") && first(before).contains(c[2])) {
          // The typing already offers it (abbreviations, sentence parts): nothing for the pause to add.
          boolean ok = protectedKept(before, after);
          result = (ok ? "PASS" : "FAIL") + " slip " + input + " -> " + c[2] + " already offered while typing";
          if (!ok) ++failed;
        } else if (c[0].equals("slip")) {
          int at = words.indexOf(c[2]);
          boolean ok = changed && at >= 0 && Integer.parseInt(after[at][6]) == input.length() && protectedKept(before, after);
          result = (ok ? "PASS" : "FAIL") + " slip " + input + " -> " + c[2] + " rank " + (at + 1);
          if (!ok) ++failed;
        } else if (c[0].equals("lead")) {
          boolean ok = changed && sentenceLed(before, after) && after[0][0].equals(c[2]);
          result = (ok ? "PASS" : "FAIL") + " lead " + input + " -> " + c[2];
          if (!ok) ++failed;
        } else {
          boolean ok = !changed;
          result = (ok ? "PASS" : "FAIL") + " keep " + input;
          if (!ok) ++failed;
        }
        System.out.println(result + "  before=" + first(before) + " after=" + words);
      }
    } finally {
      e.nativeDestroy();
    }
    if (failed > 0) throw new AssertionError(failed + " case(s) failed");
  }

  static void type(RimeEngine e, String code) throws Exception {
    e.nativeProcessKey(0xff1b, 0);
    if (!e.nativeSwitchSchema("pinyin_14jian")) throw new Exception("pinyin_14jian unavailable");
    e.nativeSetOption("ascii_mode", false);
    // Options starting with _ are cleared on a schema switch; the app sets them again after one.
    for (String kind : new String[]{"omission", "extra", "swap"})
      e.nativeSetOption("_cyime_pause_" + kind + "_off", off.equals(kind) || off.equals("all"));
    for (char ch : code.toCharArray()) if (!e.nativeProcessKey(ch, 0)) throw new Exception("unhandled key " + ch);
    if (!code.equals(e.nativeGetInput())) throw new Exception("input changed: " + e.nativeGetInput());
  }

  static void trace(RimeEngine e, String code) throws Exception {
    e.nativeProcessKey(0xff1b, 0);
    if (!e.nativeSwitchSchema("cyime_pause_14jian")) throw new Exception("cyime_pause_14jian unavailable");
    e.nativeSetOption("ascii_mode", false);
    for (char ch : code.toCharArray()) if (!e.nativeProcessKey(ch, 0)) throw new Exception("unhandled key " + ch);
    List<String> whole = new ArrayList<>();
    int rank = 0;
    for (String[] row : e.nativeInspectCandidates(200)) {
      ++rank;
      if (Integer.parseInt(row[6]) == code.length()) whole.add(rank + ":" + row[0] + "(" + row[1] + ")");
    }
    System.out.println("TRACE " + code + " whole-input readings of the pause prism: " + whole);
  }

  /** The sentence the word model built after the pause leads; the candidate that led while typing follows it. */
  static boolean sentenceLed(String[][] before, String[][] after) {
    return before.length > 0 && after.length > 1 && !before[0][0].equals(after[0][0]) && before[0][0].equals(after[1][0]);
  }

  static boolean protectedKept(String[][] before, String[][] after) {
    if (sentenceLed(before, after)) return true;
    for (int i = 0; i < 2 && i < before.length; ++i)
      if (after.length <= i || !before[i][0].equals(after[i][0])) return false;
    return true;
  }

  static List<String> first(String[][] rows) {
    List<String> words = new ArrayList<>();
    for (String[] row : rows) words.add(row[0]);
    return words;
  }
}
