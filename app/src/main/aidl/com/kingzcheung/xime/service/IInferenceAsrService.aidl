package com.kingzcheung.xime.service;

import com.kingzcheung.xime.service.IInferenceAsrCallback;

interface IInferenceAsrService {
    /** 加载 ASR 模型并开始识别（返回 false 表示模型缺失/加载失败） */
    boolean startAsr(String modelId, IInferenceAsrCallback callback);
    /** 推送 PCM 音频数据（16k/16bit/mono），服务端内部流式识别 */
    void pushAsrAudio(IBinder session, in byte[] audioData);
    /** 结束识别，返回最终识别文本 */
    String stopAsr(IBinder session);
    /** 取消当前识别会话 */
    void cancelAsr(IBinder session);
    /** 释放 ASR 模型 */
    void releaseAsr(IBinder session);
    /**
     * "保持引擎常驻"开关：true 时服务端不做 60s 空闲自动释放，
     * 模型常驻内存（用户已明确选择以内存换响应速度）；false 恢复默认回收。
     */
    oneway void setKeepModelAlive(boolean keepAlive);
    /** Current input language is part of the recognition session and model cache identity. */
    boolean startAsrForLanguage(String modelId, String languageId, IInferenceAsrCallback callback);
    /** Opt-in: save received PCM and recognition timeline to the app's own speech_tuning folder. */
    oneway void setSaveTuningAudio(boolean enabled);
    /**
     * 语音内存阶梯：空闲 refinerIdleSeconds 秒后只释放第二遍（SenseVoice），
     * 空闲 engineIdleSeconds 秒后释放全部模型。0 表示永不释放这一级。
     */
    oneway void setIdleLadder(int refinerIdleSeconds, int engineIdleSeconds);
    /** 整句定稿：SenseVoice 在真停顿或结束时校正一整句，不再每 6 秒切一次。 */
    oneway void setWholeSentenceFinal(boolean enabled);
    /** 键盘收起：系统缺内存时卸掉空闲的语音模型（常驻设置也卸），见 AsrMemoryPressure。 */
    oneway void onKeyboardHidden();
    /** 设置 › 存储空间 点「释放」：没在说话、字幕没开时立刻卸掉全部语音模型；卸了返回 true。 */
    boolean releaseIdleModelsNow();
    /** 语音进程自己的 PSS（kB）：正式包里别的进程读不到它的 /proc，存储空间页靠这个显示；读不到返回 0。 */
    long memoryKb();
    /** 整句定稿用哪个模型（SpeechModelCatalog 的 SenseVoice / X-ASR id）；下一次录音开始时生效。 */
    oneway void setWholeSentenceFinalizer(String modelId);
}
