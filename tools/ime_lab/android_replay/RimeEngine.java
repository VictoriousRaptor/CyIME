package com.kingzcheung.xime.rime;

/** Minimal ABI declarations for the installed library; no application classes are loaded. */
public final class RimeEngine {
    public native void nativeInitialize(String userDataDir, String sharedDataDir);
    public native boolean nativeStartMaintenance(boolean full);
    public native boolean nativeIsMaintaining();
    public native boolean nativeCreateSession();
    public native boolean nativeSwitchSchema(String schema);
    public native boolean nativeEnsureT9SchemaPatches(String schema);
    public native String nativeGetCurrentSchema();
    public native String nativeGetSchemaString(String schema, String key);
    public native void nativeSetOption(String option, boolean value);
    public native void nativeSetNeighborMap(String encoded);
    public native void nativeSetT9LeftContext(String text);
    public native boolean nativeProcessKey(int keycode, int mask);
    public native void nativeT9FlushRimeInput();
    public native void nativeT9ClearComposition(int mode);
    public native boolean nativeT9SelectPinyinDirect(String pinyin, int digitLength);
    public native boolean nativeT9SelectCandidate(String pinyin, String text, int textLength);
    public native boolean nativeT9FixLetter(String letter);
    public native int nativeT9RefinementState();
    public native boolean nativeRefineT9Sentences();
    public native boolean nativeRefineOmission();
    public native String nativeT9ScoringStatus();
    public native String nativeGetInput();
    public native boolean nativeSetInput(String input);
    public native String[] nativePinyinEditSnapshot();
    public native void nativeSetCaret(int caret);
    public native String[] nativePinyinSyllableOptions();
    public native String[] nativeSentenceEditSnapshot();
    public native String[] nativeSentenceEditCandidates(String code, int[] selected, int originalBegin, int originalEnd);
    public native boolean nativeApplySentenceEdit(String input, String sentence, int start, int end,
                                                  String code, String replacement, int[] lengths, int[] choices);
    public native boolean nativeSelectPinyinSyllable(String expectedInput, String expectedSchema, int start, int end, int syllableId);
    public native int nativePinyinSyllablesChosen();
    public native boolean nativeUndoPinyinSyllable(String expectedInput, String expectedSchema);
    public native boolean nativeSelectCandidateByGlobalIndex(int index);
    public native String nativeCommit();
    public native String nativeT9GetRemainingDigits();
    public native int nativeT9GetAndConsumeUndoneRightCommitCount();
    public native String[][] nativeInspectCandidates(int limit);
    public native void nativeDestroy();
    public native boolean nativeT9Memorize(String text, String pinyin);
    // Host replay only (tools/ime_lab/host_replay/host_replay_jni.cc); absent from the Android library.
    public native String[] nativeHostWorkCounterNames();
    public native long[] nativeHostTakeWorkCounters();
    public native long[] nativeHostRestoreUserDict(String snapshot, String dictName);
}
