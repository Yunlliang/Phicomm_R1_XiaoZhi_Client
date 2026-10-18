package cn.workbuddy.r1client;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 会话状态机 + 上行调度。
 *
 * v1.2.4 核心改造 —— 会话期开麦（connect-on-talk）：
 *
 *   实测结论（2026-10-03）：R1 的 4 麦 HAL 是独占资源，且原厂云知声引擎
 *   （com.phicomm.speaker.device，经 uni4micHalJNI 直开设备、绕过 AudioFlinger）
 *   靠它常驻做 KWS。v1.2.3 的常开 AudioRecord 会把原厂 KWS 永久挤瞎
 *   → 「小讯小讯」喊破喉咙也没反应；我们一死，它 1 秒内满血复活。
 *
 *   因此：
 *   - IDLE 期完全不碰 AudioRecord，麦归原厂（唤醒照常工作）；
 *   - 总线唤醒事件到达后才开麦 —— 与原厂自己的 ASR 录音竞争（它的云端
 *     已死，实测 ~1s 内报 609 放手），这里以 250ms 快速重试接管设备；
 *   - 会话结束（ws 关闭 / 无 TTS 超时）release 麦，还给原厂 KWS。
 *
 * 状态流转：
 *   IDLE ──bus wakeup──▶ CONNECTING ──hello ok──▶ LISTENING
 *     ▲                     │    │                  │ ▲
 *     │   15s 无 TTS / ws 关闭   │             TTS start │ TTS stop
 *     │                     │    ▼                  ▼ │
 *     └─────────────────────┴──────────────── TTS_PLAYING
 *
 * 上行要点：
 *  - 麦克风从唤醒瞬间才开始录，开麦到握手完成之间的音频先进 pending
 *    缓冲（上限 15s），握手完成后一次性补发 —— 替代 v1.2.3 的 500ms
 *    preroll 机制（常开麦时代才有"唤醒词前回溯"，按需开麦不需要）；
 *  - 音频帧裸 opus 上行，零帧头（C3 的 ASR 单字 bug 根因）；
 *  - TTS 播放期间暂停上行（防自环），barge-in 时补发 preroll。
 */
public final class SessionController implements
        AudioCapture.FrameListener,
        XiaoZhiWs.Listener,
        KwsEngine.OnKeyword,
        WakeupBridge.OnWakeup {

    private static final String TAG = "R1CLIENT";

    public enum State {IDLE, CONNECTING, LISTENING, TTS_PLAYING}

    private final AudioCapture mic;
    private final OpusCodec opus;
    // v1.2.3：player 可为 null（音频 HAL 卡死时降级启动），
    // 后续重试成功后经 setPlayer() 补挂。所有解引用必须判空。
    private volatile TtsPlayer player;
    private volatile KwsEngine kws;
    private final XiaoZhiWs ws;

    private volatile State state = State.IDLE;

    /** v1.3.44/45：**状态写入的唯一入口** —— 切状态时顺带把「会话中 / TTS 播放中」
     *  两个标志同步给 KWS。
     *
     *  为什么 KWS 要看状态：TTS 播放期喇叭回声灌进 mic 会把门顶开、把 KWS 喂满，
     *  纯白烧算力（zipformer 开销 ~= 音频时长），还有「TTS 自己被识别成唤醒词」
     *  的自环风险；所以 TTS 期暂停喂帧。
     *
     *  ⚠️ v1.3.44 的教训：当时把「整个会话期」都算作暂停范围，默认开。实机事故
     *  2026-10-05 21:00 —— 会话进 LISTENING 后「回 IDLE」出口迟迟不触发，KWS
     *  永久停摆，设备彻底失聪。**凡是能让 KWS 无限期停摆的状态依赖都是单点故障**，
     *  所以 v1.3.45 把暂停缩到只 TTS 期，并在 KwsEngine 里加 pauseMaxMs 兜底超时。
     */
    private void setState(State s) {
        state = s;
        boolean active = (s != State.IDLE);
        KwsEngine.sessionActive = active;
        // v1.3.146：让路只在会话内生效 —— IDLE 期 TtsPlayer 放的是本地提示音
        // （60s 自动断开的断连 cue），不该触发「小智开口 ⇒ 音乐让路」。
        MusicResume.setSessionActive(active);
        KwsEngine.ttsPlaying = (s == State.TTS_PLAYING);
        // v1.3.45：只在「TTS 暂停/恢复」真的翻转时记一行，便于取证
        //（门控/底噪的实际重置由 KwsEngine.feed() 捕获翻转沿后自己做）
        if (KwsEngine.pauseInTts && KwsEngine.ttsPlaying != ttsLatch) {
            ttsLatch = KwsEngine.ttsPlaying;
            SessionLog.i("kws " + (ttsLatch ? "PAUSE(tts)" : "RESUME(tts)")
                    + " (state=" + s + ")");
        }
    }

    /** v1.3.45：TTS 暂停状态去重，避免状态抖动时刷屏。 */
    private boolean ttsLatch = false;

    /** TTS 播放中是否抑制上行（防自环） */
    private volatile boolean suppressUplink = false;

    /** 唤醒来源去重窗口。
     *
     * 双模唤醒下同一次唤醒可能被两条路径同时捕获：
     *   - 总线事件（WakeupBridge）
     *   - 自建 KWS 误命中（喂进 KWS 的「小讯小讯」被识别成「小智小智」）
     * 这里用 800ms 窗口去抖，避免一次唤醒开两次会话。
     */
    private volatile long lastWakeupMs = 0L;
    private static final long WAKEUP_DEBOUNCE_MS = 800L;

    // ================= v1.3.14：端到端延迟埋点 =================
    /** 本轮唤醒时刻（elapsedRealtime），所有 lat 日志以它为基准 */
    private volatile long wakeMs = 0L;
    private volatile long latFirstUplinkMs = 0L;
    private volatile long latSttMs = 0L;
    private volatile long latFirstTtsMs = 0L;
    /** v1.3.18：上一轮 TTS 结束时刻 —— 量「多轮周转」（说完 -> 服务器开口） */
    private volatile long lastTtsStopMs = 0L;

    /**
     * v1.3.14：取证落盘开关（默认关）。
     * 每帧 clone（dump + probe 各一次）+ 会话结束在主线程写 ~2MB 到 flash，
     * 纯粹是诊断用途，日常运行不需要；改成 flag 门控、默认关闭。
     *   files/uplink_dump  -> 写 files/r1-uplink.wav
     *   files/stereo_probe -> 写 files/r1-probe.wav
     */
    public static volatile boolean dumpEnabled = false;
    public static volatile boolean probeEnabled = false;

    // ================= v1.2.4：会话期开麦参数 =================

    /** 开麦重试间隔：原厂 ASR 云端已死（609），实测 ~1s 内放手，250ms 足够密 */
    private static final long MIC_RETRY_INTERVAL_MS = 250L;

    /** 单次开麦看门狗：AudioRecord 的 openRecord binder 可能卡（设备忙/HAL 死） */
    private static final long MIC_ATTEMPT_WATCHDOG_MS = 1500L;

    /** 开麦总预算：覆盖原厂 ASR 放手窗口 + 充分余量 */
    private static final long MIC_DEADLINE_MS = 20000L;

    /**
     * 唤醒/说话后无 TTS 的收会话时限。
     * v1.2.9：15s → 25s —— 20:03 实测原厂 USC 云端没死，它会攥麦 ~16s 等自己
     * 的云端超时（"服务器已经超时，客户端两次请求间隔太长"）才放手。我们 20s
     * 抢麦预算没耗尽，15s 看门狗却先把会话杀了（差 1.1s 错过到手的麦）。
     * 不变式：会话寿命 ≥ 抢麦预算 + 余量，否则「麦快到手时会话先死」。
     */
    private static final long NO_TTS_TIMEOUT_MS = 25000L;

    // ================= v1.3.15：延长连续对话 + 断开前提醒 =================

    /**
     * TTS 结束后的「续听窗口」——保持 listening 等用户接着说多久（毫秒）。
     * v1.3.14 及以前这里复用 NO_TTS_TIMEOUT_MS(25s)、外加 XiaoZhiWs 的 15s 空闲
     * 断开（15s 先到），实测用户稍一停顿（思考/找词）会话就没了。
     * 现改为 90s，可被 files/idle<N> 覆盖（服务层调用 setListenIdleMs）。
     * 意图：让官方服务器在 listening 空闲 ~70s 下发的 TTS 提醒、~80s 的会话关闭
     * 先发生 —— 用户体感 = 官方设备的连续对话。
     */
    private volatile long listenIdleMs = 90000L;

    /** 续听窗口将尽时再提前这么久，播一声本地「即将断开」提示（服务器没提醒时的兜底） */
    private static final long IDLE_ALERT_LEAD_MS = 15000L;

    /** v1.3.15：本地提示音总开关（files/idle_alert_off 关闭）。服务器发了 TTS 提醒时不播。 */
    public static volatile boolean idleAlertEnabled = true;

    /** 当前看门狗时长（日志用；armNoTtsWatchdog 时更新） */
    private volatile long watchdogMs = NO_TTS_TIMEOUT_MS;

    /** v1.3.15：服务层按 flag 覆盖续听窗口 */
    public void setListenIdleMs(long ms) {
        if (ms > 0) {
            listenIdleMs = ms;
        }
    }

    // ============ v1.3.101：会话空闲自动断开（移植安卓版 v1.3.62 / v1.3.63）============
    /**
     * 「60s 自动断开」开关 files/auto_disconnect。
     *   开 = 对话结束 60s 主动挂断，抢在官方服务器 ~70s 那条「我先挂啦」告别 TTS
     *        之前 —— 用户不会听到服务器啰嗦。代价是服务器没机会开口，所以本地
     *        要在**真正挂断那一刻**补一声（见 scheduleIdleAlert），否则体感无声掉线。
     *   关 = 90s 续听，让服务器先说话；那时它的告别语就是提示音（本地一声不播）。
     * ★ 每次装配计时器都**现读文件旗标**，不依赖任何广播 / 进程间时序 ——
     *   这是安卓版根治「UI 改了不生效」的做法。
     */
    private boolean autoDisconnectOn() {
        try {
            if (new java.io.File(appContext.getFilesDir(), "auto_disconnect").exists()) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** files/idle&lt;N&gt;：显式指定续听窗口秒数（20..600），优先级高于上面那个开关。0 = 没指定 */
    private long filesIdleMs() {
        try {
            java.io.File[] fs = appContext.getFilesDir().listFiles();
            if (fs == null) {
                return 0L;
            }
            long best = 0L;
            for (int i = 0; i < fs.length; i++) {
                String n = fs[i].getName();
                if (n == null || !n.startsWith("idle")) {
                    continue;
                }
                try {
                    long v = Long.parseLong(n.substring(4));
                    if (v >= 20L && v <= 600L) {
                        best = v * 1000L;
                    }
                } catch (Throwable ignored) {
                }
            }
            return best;
        } catch (Throwable t) {
            return 0L;
        }
    }

    private volatile long lastWinMs = -1L;

    /** 本次会话是否需要在**真正挂断那一刻**补一声（语义见 scheduleIdleAlert） */
    private volatile boolean cueOnDisconnect = false;

    /**
     * 续听窗口取值优先级：files/idle&lt;N&gt; &gt; auto_disconnect 开关(60s) &gt; 默认 90s。
     * 顺手做两件事：同步 listenIdleMs 让日志/其它读它的地方口径一致；把 ws 侧那套
     * 独立空闲计时器拉到 24h 停掉，免得两组计时器（这里 vs XiaoZhiWs.idleCloseTask）
     * 互相打架提前把连接掐了。
     */
    private long idleWindowMs() {
        long f = filesIdleMs();
        boolean on = autoDisconnectOn();
        long v = f > 0L ? f : (on ? 60000L : 90000L);
        listenIdleMs = v;
        if (ws != null) {
            ws.setIdleCloseDelayMs(24L * 3600L * 1000L);
        }
        if (v != lastWinMs) {
            lastWinMs = v;
            SessionLog.i("idle window=" + (v / 1000L) + "s (auto_disconnect=" + on
                    + " filesIdle=" + (f > 0L ? (f / 1000L) + "s" : "none") + ")");
        }
        return v;
    }

    // ================= v1.3.20：唤醒应答音 + 会话人声取证 =================

    /**
     * 唤醒应答音总时长（毫秒）。
     * ⚠️ 必须 ≥ TtsPlayer.START_WATERMARK_MS(180)：TtsPlayer.loop() 第一段是
     * 「凑够水位才 playing=true」，推入的 PCM 若短于水位，播放线程会一直空等、
     * 提示音**永不出声**（队列里只有 100ms，bufferedMs 永远 < 180）。
     */
    private static final int WAKE_ACK_MS = 260;
    /**
     * 应答音回声丢弃窗口（毫秒）—— **v1.3.22 起默认 0 = 不丢**。
     *
     * v1.3.20 原设 320ms，理由是「R1 无 AEC，喇叭里的『叮』会被四麦拾回、
     * 可能被服务器 VAD 当成用户开口」。**实测证伪，而且它本身就是丢字元凶。**
     *
     * 2026-10-04 17:54:49 那次首次唤醒（IDLE + 应答音）：
     *   prewake tail queued: 20 frames (1200ms)   ← 「小智小智」在这 1.2s 里
     *   wake ack cue played (260ms), mic echo dropped 320ms
     *   ... 上行时间线 = [preWake 1.2s] ⊕ [被删掉的 320ms] ⊕ [之后的实时帧]
     * 而连读的用户此刻正在说内容 —— 硬丢把 [唤醒时刻, +320ms] 整段删掉再
     * 把前后**直接粘起来**，等于把用户开口的第一个词从时间轴上剪走：
     * 服务器 STT 出来是垃圾「小智小智千鸡地。」，
     * `TTS start (heard: voicedFrames=6)` 说明整轮只有 360ms 人声过关，
     * 模型只能回「蛤？"千鸡地"是什么意思呀」。—— 用户体感就是「少听了两个字」。
     *
     * 为什么现在根本不需要丢：v1.3.21 把 prewakeFrames 提到 20（1.2s），
     * **唤醒词音频必然完整排在这声「叮」前面**。回声再响也只是这一轮对话里的
     * 一段噪声，不可能成为「唯一人声」去凭空造出一轮假对话 —— 硬丢窗口当初
     * 成立的前提（preWake=6，唤醒词没进流）已经不存在了。
     *
     * 万一现场仍出现「唤醒后凭空多一轮」（例如总线唤醒时 preWake 没抓到
     * 唤醒词），用 files/ackdrop<N>（0..600ms）把窗口加回来即可，无需改代码。
     */
    private static volatile long wakeAckEchoDropMs = 0L;

    /** v1.3.22：服务层解析 files/ackdrop<N> 后调用（0 = 不丢） */
    public static void setWakeAckEchoDropMs(long ms) {
        if (ms >= 0 && ms <= 600) {
            wakeAckEchoDropMs = ms;
        }
    }

    public static long getWakeAckEchoDropMs() {
        return wakeAckEchoDropMs;
    }

    /** 回声丢弃截止时刻（elapsedRealtime）；0 = 不丢（判定恒假） */
    private volatile long micDropUntilMs = 0L;
    /** v1.3.20：唤醒应答音总开关（files/wakeack_off 关闭）。关掉时**连丢帧窗口也不开**。 */
    public static volatile boolean wakeAckEnabled = true;

    // ================= v1.3.21：唤醒词上报（listen/detect） =================

    /**
     * 是否在全新会话握手后补发一条 `{"type":"listen","state":"detect","text":"<唤醒词>"}`。
     *
     * 这是官方固件 `Protocol::SendWakeWordDetected()`（protocol.cc:67）的标准动作，
     * 开关对应 Kconfig 的 `SEND_WAKE_WORD_DATA`（默认 y）。**默认关**，原因见下。
     *
     * 服务端语义（`core/handle/textMessageHandler/listenMessageHandler.py`）：
     *   state=="detect" 且有 text → `conn.reset_audio_states()`；
     *   若 text 命中 `wakeup_words` 配置且 `enable_greeting` 为真 →
     *     `just_woken_up = True` + `startToChat(conn, "嘿，你好呀")`  ← 设备会答一句；
     *   命中但 `enable_greeting` 为假 → 只回 stt + tts stop，**不开口**；
     *   未命中 → `startToChat(conn, 原文)`。
     *
     * ⚠️ **代价（为什么默认关）**：`conn.just_woken_up = True` 会让服务端在
     * `receiveAudioHandle.py` 里把接下来 **2 秒**（`resume_vad_detection` 的
     * `asyncio.sleep(2)`）的**所有**上行音频 `return` 掉 —— 连 `receive_audio`
     * 都不进，也就是**彻底耳聋 2 秒**。
     *   · 只说唤醒词（本次要修的场景）→ 无损失，还能听到「嘿，你好呀」。✅
     *   · 连读「小智小智 + 指令」→ 指令的前 2 秒被吞，用户得等应答后重说。❌
     * 官方设备就是这节奏（唤醒词 -> 应答 -> 再说指令），但我们的用户一直在用
     * 连读，所以**不默认打开**。files/detect_on 可开。
     */
    public static volatile boolean detectEnabled = false;

    /**
     * v1.3.21：待上报的唤醒词原文。只有「IDLE 起来的全新会话」且在
     * detectEnabled 时才置位，`onHandshakeDone()` 消费一次即清空
     * （barge-in 打断、探测判僵尸后的影子回放都不上报 —— 那两轮服务器本来就在听）。
     */
    private volatile String pendingWakeReport = null;

    /** v1.3.21：最近一次唤醒词原文（KWS 命中词 / 总线词），供 detect 上报 */
    private volatile String lastWakeWord = null;

    /**
     * 本会话上行音频的「最大逐帧 RMS」+「有人声的帧数」。
     *
     * 用途（2026-10-04 17:27 实测教训）：一次「唤醒后没回复」的现场，连接侧全绿
     * （handshake 395ms、44 帧补发、400+ 帧上行、fail=0），服务器却 25s 一声不吭。
     * 翻 micTelem 才发现整段会话的 rms 平在底噪 220~230 —— **麦克风根本没听到人说话**。
     * 有这两个数，下次看一行日志就能定性：
     *   maxRms≈230 / voicedFrames=0        → 客户端没问题，是没人说话（或麦没拾到）
     *   maxRms 数千 / voicedFrames 数十，服务器仍沉默 → 才轮到怪服务器
     */
    private volatile int sessionMaxRms = 0;

    // ===== v1.3.126：raw/up 双域探针（为「TTS 期泄漏 raw」vs「真人说话 raw」同域定量）=====
    // 背景：v1.3.121 的本地 VAD 门限档位（600/800/1000/1400/1800/2000/3000）是按
    // **增益后**的电平定的，而它实际读的是**增益前(raw)**的电平 —— 两个域差着 ALC 的
    // 9.99 倍。这版只测量、不改行为，把两个域同时打出来，一次实测即可判定：
    //   · 若「泄漏 raw 峰值」远低于「人声 raw 谷值」⇒ 电平域可分，本地 VAD 只是档位定错；
    //   · 若两者重叠 ⇒ 电平域不可分，必须换判据（内容级/结构级）。
    // v1.3.128：300ms -> 150ms。TTS 段只有 1.5~2.5s，300ms 采样太稀（每段才 5~8 点），
    // 看不清「饥饿窗口」的起止边界；150ms 能把每段铺到 10~16 点，仍不至于淹日志。
    private static final long RAW_PROBE_MS = 150L;
    private volatile long rawProbeLastMs = 0L;
    private volatile int sessRawMax = 0;        // 全程 raw 峰值
    private volatile int sessRawTtsMax = 0;     // TTS 播放期 raw 峰值（= 回声泄漏）
    private volatile int sessRawVoiceMax = 0;   // LISTENING 放行上行期 raw 峰值（= 真人说话）
    private long sessRawTtsSum = 0L;
    private int sessRawTtsN = 0;
    private long sessRawVoiceSum = 0L;
    private int sessRawVoiceN = 0;
    private long sessUpTtsSum = 0L;             // TTS 期「服务端实际收到」的均值参照
    private long sessUpVoiceSum = 0L;
    private volatile int sessionVoicedFrames = 0;
    /** 「有人声」判定门限（逐帧 RMS）。实测：底噪 220~230，说话 330~1400 */
    private static final int VOICE_FLOOR_RMS = 400;

    // ============ v1.3.47：上行回声闸门（自环的根除性修复） ============

    /**
     * v1.3.47：**不再靠「时间到了」放行上行，改靠「麦克风真的安静了」。**
     *
     * 根因（2026-10-05 21:25 用户实测「小智又会听到自己说的了」）：
     *   TTS 播完 -> `isDrained()` 真 -> `resumeUplinkTask` 放行上行。
     *   但 `isDrained()` 只判「软件队列空 + 距最后一次 push 超过 600ms」，
     *   **完全不管 AudioTrack 的 512ms 硬件缓冲、HAL 采集链延迟与房间余响** ——
     *   实测放行那一刻喇叭仍在响，上行把自家的 TTS 原样送回服务器。
     *   逐字铁证（本轮日志）：
     *     TTS 原文「在啦在啦，我人就在这儿呢，跑不掉的。」
     *     -> 下一轮 STT「跑不掉的。」        （一字不差）
     *   随后服务器回复 -> TTS -> 再被录 -> 无限自环（一轮约 2.6s，
     *   STT 依次是「在呢。」「在哦？」「跑不掉的。」，全是它自己刚说过的话）。
     *
     * 本机**无 AEC**（`audio cfg: src=UNI4MIC-HAL aec=false`），喇叭把四麦打到削顶
     * （`micTelem peak=32432`，满幅 32768）。所以唯一可靠的判据是**电平**：
     *   真人声 rms 330~5391（本会话实测上限 5391）
     *   TTS 回声 rms ≈13807、峰值削顶
     * 取 ECHO_RMS(8000) 落在两者之间：高于它只可能是喇叭，不可能是用户说话。
     *
     * 代价（明确记录）：TTS 刚停的那 0.5~1s 里如果用户立刻接话，开头会被吃掉
     * （闸门要连续 8 帧安静才开）；上限 ECHO_GATE_MAX_MS 保证最坏也只压 2s。
     * 这是无 AEC 设备做半双工必须付的账，比自环好得多。
     */

    /** 关掉回声闸门（调试用：`files/echogateoff`）。 */
    public static volatile boolean echoGateEnabled = true;

    /** 判定「喇叭在响」的逐帧 RMS（0..32767）。落在真人声上限与 TTS 回声之间。 */
    public static volatile int echoRms = 8000;

    /** 连续这么多帧低于 echoRms 才认为喇叭真停了（帧长 60ms -> 8 帧 = 480ms）。 */
    public static volatile int echoClearFrames = 8;

    /**
     * v1.3.48 ★ 闸门**最小强制压制时长**（毫秒）：TTS stop 后，无论麦克风多安静，
     * 都必须先压满这么久才允许进入"安静确认"。
     *
     * 根因（2026-10-05 21:34~21:36 实机取证，jp2 v1.3.47，diag/jp2log_now.txt）：
     *   · TTS 播放期      mic 电平  77~430   —— HAL 回声抑制在线，**假安静**；
     *   · TTS 停止后 0.7~4.7s mic 电平 479~1387 —— 抑制退线，TTS 回声漏进麦克风。
     *   v1.3.47 的判据是「连续 8 帧 rms<8000」→ 在 TTS stop 后 0.5s 就满足（因为
     *   播放期本来就安静）→ 闸门 557ms 即 OPEN —— 而真正的回声还要 0.7s 才现身。
     *   于是上行恢复窗口 100% 覆盖回声窗口，回声被原样送回服务器：
     *       TTS「听得到听得到，我一直都在呢。」(21:35:24.5~27.4 播)
     *       → 21:35:28.056 上行恢复 → 28.1~32.1 送出回声
     *       → 21:35:32.578 STT「听得到听得到，我一直都在呢。」  ← 逐字一致
     *       → 服务器再回 → 再被录 → 无限自环（本轮实测连续 6 轮）。
     *
     * 现在：gheld < echoGateHoldMs 期间一律不放行（echoQuietRun 强制清零），
     * 压满之后才认"安静"。0 = 关闭（回退 v1.3.47 语义）。files/echoholdms<N> 覆盖。
     */
    public static volatile long echoGateHoldMs = 1200L;

    /**
     * 闸门最长压住多久（兜底：环境持续吵闹也不能把上行永久焊死）。
     * v1.3.48：2000 -> 4000。实测回声拖尾最长到 TTS stop + 4.7s，2s 上限会被
     * 拖尾后半段（831/479）穿透；4s 与 TtsPlayer 的静音续播窗（ttshold 默认
     * 4000ms）同长，两者同步收尾。
     */
    public static volatile long echoGateMaxMs = 4000L;

    // ============ v1.3.50：TTS 期禁 STT（KWS 独占入口）+ TTS 后自动续听 ============

    /**
     * ★★ 「TTS 期静音」的**加强版**开关（默认**关**）。用户 2026-10-05 要求的架构：
     *   「TTS 的时候应该禁用 STT，只开 KWS；KWS 只听有没有『小智小智』；
     *     听到唤醒词才打断小智，把唤醒词和新的内容发给小智。」
     *
     * 注意：**默认路径已经满足这个要求**，用不着本开关 ——
     *   · TTS 播放期：`onTtsStart` 里 `suppressUplink = !bargeInUplink || echoDisabled`
     *     = true（bargeInUplink 默认关）→ 上行关闭 = STT 天然禁用；
     *   · KWS 全程在跑（kwsSess=none）→ 唤醒词随时可命中；
     *   · 命中唤醒词 → `handleWakeup` 打断：`TtsPlayer.abort()`（连 AudioTrack 硬件
     *     缓冲一起 flush）+ 放行上行 + `flushPreroll()` 补发「唤醒词 + 后文」。
     *
     * 本开关（true）额外的行为 = **TTS 结束后也不恢复上行**，必须再喊一次唤醒词
     * 才能续问。v1.3.49 默认开这个，用户实测体感是「每句话都要加『小智小智』开头」
     * （2026-10-05 21:55 反馈）—— 因为把「TTS 期禁听」错误地延长到了整个续听窗口。
     *
     * false（默认）= TTS stop 后走 v1.3.48 的路径：
     *   `resumeUplinkTask` 等本地播放排空（0.25~0.9s）→ `echoGate` 放行（强制压制期
     *   `echoGateHoldMs` 之后才认「安静」）→ 自动恢复上行，用户可以直接接话。
     *   防自环靠 `TtsPlayer.holdSilenceMs`：TTS 数据发完继续按实时速率写数字静音，
     *   把播放流钉住 → HAL 回声抑制不掉线。实测（21:53 TTS stop）：hold-silence
     *   4s 内 mic 稳在 70~430，4s 之后回到底噪 ~70，**无回声残留**。
     *
     * files/halfduplex 打开，files/halfduplex_off 关闭（默认即关）。
     */
    public static volatile boolean halfDuplex = false;

    /** 打断 TTS 后，上行恢复前最多再等这么久（覆盖 preroll 补发本身的排空）。 */
    public static volatile long bargeInMuteMs = 0L;

    /** 闸门是否生效中（true = 本帧不上行）。 */
    private volatile boolean echoGateOn = false;
    /** 闸门落下时刻（elapsedRealtime）。 */
    private volatile long echoGateArmedMs = 0L;
    /** 闸门内连续「安静」帧计数。 */
    private int echoQuietRun = 0;
    /** v1.3.48：闸门内取证日志节流（每 500ms 一行，看 mic 在压制期到底多响）。 */
    private volatile long echoProbeLastMs = 0L;

    /** 落闸：TTS stop 后调用（播放期由 suppressUplink 挡，闸门守的是「尾巴」）。 */
    private void armEchoGate() {
        if (!echoGateEnabled) {
            return;
        }
        echoGateOn = true;
        echoGateArmedMs = android.os.SystemClock.elapsedRealtime();
        echoQuietRun = 0;
        echoProbeLastMs = echoGateArmedMs;
    }

    /** 收闸（带原因，便于取证）。 */
    private void releaseEchoGate(String why) {
        if (!echoGateOn) {
            return;
        }
        long held = android.os.SystemClock.elapsedRealtime() - echoGateArmedMs;
        echoGateOn = false;
        echoQuietRun = 0;
        String m = "echoGate OPEN (" + why + ", held " + held + "ms)";
        Log.i(TAG, m);
        SessionLog.i(m);
    }

    // ============ v1.3.23：服务器「空闲告别」识别（僵尸会话的根除性修复） ============

    /**
     * 最近一次**用户说话**的时刻（elapsedRealtime）。
     *
     * 统计口径刻意很窄：只算 `state == LISTENING && !suppressUplink && rms > 语音门限`
     * 的帧 —— 排除 IDLE 期、排除 TTS 播放期（喇叭回声）、排除 TTS 结束后的 400ms
     * 上行恢复窗口（喇叭尾巴）。也就是说它约等于「服务器真的听到了人说话」。
     *
     * 用途：服务器**主动开口**且距离上次人声已经很久 → 那几乎必然是它在念
     * 「空闲告别语」，而官方服务器念完告别就**不再理会这条会话**了。
     */
    private volatile long lastUserVoiceMs = 0L;

    /**
     * 「服务器在讲空闲告别语」的静默阈值（秒）。默认 35s。
     * 可用 files/farewell<N> 覆盖（范围 10..120）。
     */
    private static volatile int farewellIdleSec = 35;

    public static void setFarewellIdleSec(int s) {
        if (s >= 10 && s <= 120) {
            farewellIdleSec = s;
        }
    }

    public static int getFarewellIdleSec() {
        return farewellIdleSec;
    }

    /** 本轮 TTS 被判定为「服务器空闲告别」→ 播完即结束会话，不再续听 */
    private volatile boolean serverFarewell = false;

    /**
     * 「本轮服务器是在回应用户」的标记：收到 STT 置位，收到 tts stop 清零。
     * 为什么要它：只靠「静默多久」会把**慢回复**误判成告别 —— STT 之后如果
     * LLM/工具调用拖了 40s 才出 TTS（本机实测 GLM 9s + TTS 4s，带工具会更久），
     * 静默时长同样 ≥35s。有 STT = 服务器确实在回应我们，就不是告别。
     */
    private volatile boolean sttThisTurn = false;

    /**
     * 2026-10-04 18:19:42 实测（用户：「说的是小智小智明天下雨吗」，STT 只出「下雨吗？」）：
     *
     *   18:18:19 IDLE 唤醒 -> 新会话；18:18:20 STT「智小智。」-> 18:18:23 TTS stop
     *   18:19:25 服务器**主动**下发 TTS：「那我先不打扰你啦，有空再来找我聊天喔。」
     *            ← 这是官方服务器的「空闲告别」。它念完这句，这条会话就作废了。
     *   18:19:42 用户唤醒（此刻下行已静默 13.2s）-> 我们判「会话还活着，继续用，探测 7s」
     *            -> 用户整句话喂进死 socket；7s 后探测到期才发现是僵尸
     *   18:19:49 硬重启 + 回放影子；18:19:50.982 STT「下雨吗？」← 影子从唤醒时刻才开始
     *            录，话的前半截早就发给死会话了 -> 只剩尾巴
     *   ⇒ 唤醒到出字 8.1s，且**丢字**。用户体感「还是出现丢字了」。
     *
     * 根因：客户端把「服务器已告别」的会话当成健康会话，继续挂着等 90s（listenIdle）。
     * 修法：**服务器主动开口且上次人声已过去 farewellIdleSec 秒 → 判为告别语，
     * 播完即关会话回 IDLE**。这样下一次唤醒走的是 IDLE 全新会话路径
     * （应答音 + 预缓冲 1.2s 补发 + 实时帧），既不静默也不丢字。
     */
    private final Runnable farewellEndTask = new Runnable() {
        private int polls = 0;

        public void run() {
            if (state != State.LISTENING || !farewellActive) {
                polls = 0;
                return;
            }
            TtsPlayer p = player;
            boolean stillPlaying = (p != null) && p.isPlaying() && !p.isDrained();
            if (stillPlaying && polls < 20) {   // 最多等 20*300ms = 6s
                polls++;
                main.postDelayed(this, 300L);
                return;
            }
            polls = 0;
            farewellActive = false;
            String m = "server farewell playback done -> closing session"
                    + " (server already ended it; next wake = fresh session)";
            Log.i(TAG, m);
            SessionLog.i(m);
            ws.close("farewell");
        }
    };

    /** farewellEndTask 是否已排队（enterIdle / barge-in 要撤） */
    private volatile boolean farewellActive = false;

    /** CONNECTING 期间 pending 缓冲上限（60ms/帧，250 帧 = 15s） */
    private static final int MAX_PENDING_FRAMES = 250;

    /** 麦克风操作串行化线程：开麦重试循环与 release 都在这条线上排队，杜绝 start/stop 交错 */
    private final Handler micHandler;

    /** CONNECTING 期间攒下的音频帧（握手完成即补发，详见类注释） */
    private final ArrayList<byte[]> pending = new ArrayList<byte[]>();

    // ================= v1.3.2：flush 匀速节流 =================

    /**
     * 补发中标志：true 时 LISTENING 期的实时帧也改道进 pending 排队，
     * 保证服务器收到的时间线严格单调（积压音频按 60ms/帧实时速率先发，
     * 排队帧跟在后面），绝不与实时流交错。
     */
    private volatile boolean flushing = false;

    // ================= v1.3.3：上行音频自证（WAV dump） =================

    /**
     * 背景：官方服务器对 v1.3.x 会话「控制面秒回、对话面零 ASR」。服务器侧
     * 无法取证，只能自证「我们发出去的音频内容本身是否完好」。
     * 本会话所有准备上行的 PCM 攒内存（800 帧 = 48s 封顶），会话结束落盘
     * files/r1-uplink.wav。拉回 PC 试听/看波形：
     *   有人声 = 客户端发送内容无损 → 问题在服务器侧；
     *   垃圾/静音 = 客户端音频路径问题。
     */
    private final java.util.ArrayList<byte[]> uplinkDump = new java.util.ArrayList<byte[]>();

    // ================= v1.3.5：原始 stereo 取证（r1-probe.wav） =================

    /**
     * HAL stereo 原始帧（每帧 3840B），上限 300 帧 = 18s，会话结束落盘
     * files/r1-probe.wav（2ch WAV）。用途：v1.3.5 改 stereo 打开后，若下混
     * (L+R)/2 仍异常，可直接判定 L/R 两路各自有没有信号（哪路死/哪路活）。
     */
    private final java.util.ArrayList<byte[]> stereoProbe = new java.util.ArrayList<byte[]>();

    /** v1.3.6：与 pending 平行的 stereo 帧队列（补发帧也要进 probe 取证） */
    private final java.util.ArrayList<byte[]> stereoPending = new java.util.ArrayList<byte[]>();

    private void probeStereo(byte[] st) {
        if (!probeEnabled) {
            return; // v1.3.14：取证默认关（免每帧 clone）
        }
        synchronized (stereoProbe) {
            if (stereoProbe.size() < 300) {
                stereoProbe.add(st.clone());
            }
        }
    }

    private void dumpUplinkPcm(byte[] f) {
        if (!dumpEnabled) {
            return; // v1.3.14：取证默认关（免每帧 clone）
        }
        synchronized (uplinkDump) {
            if (uplinkDump.size() < 800) {
                uplinkDump.add(f.clone()); // onFrame 的 data 是复用缓冲，必须拷贝
            }
        }
    }

    private void writeUplinkDump() {
        byte[] pcmAll;
        synchronized (uplinkDump) {
            if (uplinkDump.isEmpty()) {
                return;
            }
            int total = 0;
            for (byte[] f : uplinkDump) {
                total += f.length;
            }
            pcmAll = new byte[total];
            int off = 0;
            for (byte[] f : uplinkDump) {
                System.arraycopy(f, 0, pcmAll, off, f.length);
                off += f.length;
            }
            uplinkDump.clear();
        }
        final byte[] pcm = pcmAll;
        // v1.3.5：stereo probe 一并落盘（可能为空 -> 不写）
        byte[] probePcm;
        synchronized (stereoProbe) {
            if (stereoProbe.isEmpty()) {
                probePcm = null;
            } else {
                int total = 0;
                for (byte[] f : stereoProbe) {
                    total += f.length;
                }
                probePcm = new byte[total];
                int off = 0;
                for (byte[] f : stereoProbe) {
                    System.arraycopy(f, 0, probePcm, off, f.length);
                    off += f.length;
                }
                stereoProbe.clear();
            }
        }
        final byte[] probe = probePcm;
        Thread t = new Thread(new Runnable() {
            public void run() {
                writeWavFile("r1-uplink.wav", pcm, 1, "uplink dump written");
                if (probe != null) {
                    writeWavFile("r1-probe.wav", probe, 2, "stereo probe written");
                }
            }
        }, "uplink-dump");
        t.setDaemon(true);
        t.start();
    }

    /** v1.3.5：通用 WAV 落盘（16bit PCM，rate 固定 AudioCapture.SAMPLE_RATE） */
    private void writeWavFile(String name, byte[] pcm, int ch, String logPrefix) {
        java.io.FileOutputStream fo = null;
        try {
            java.io.File out = new java.io.File(appContext.getFilesDir(), name);
            fo = new java.io.FileOutputStream(out);
            int dataLen = pcm.length;
            int rate = AudioCapture.SAMPLE_RATE;
            java.io.ByteArrayOutputStream hdr =
                    new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream d = new java.io.DataOutputStream(hdr);
            d.writeBytes("RIFF");
            d.writeInt(Integer.reverseBytes(36 + dataLen));
            d.writeBytes("WAVE");
            d.writeBytes("fmt ");
            d.writeInt(Integer.reverseBytes(16));
            d.writeShort(Short.reverseBytes((short) 1));      // PCM
            d.writeShort(Short.reverseBytes((short) ch));
            d.writeInt(Integer.reverseBytes(rate));
            d.writeInt(Integer.reverseBytes(rate * 2 * ch));
            d.writeShort(Short.reverseBytes((short) (2 * ch)));
            d.writeShort(Short.reverseBytes((short) 16));
            d.writeBytes("data");
            d.writeInt(Integer.reverseBytes(dataLen));
            d.flush();
            fo.write(hdr.toByteArray());
            fo.write(pcm);
            fo.close();
            fo = null;
            int durSec = dataLen / (2 * rate * ch);
            String m = logPrefix + ": " + name
                    + " (" + durSec + "s pcm, " + ch + "ch)";
            Log.i(TAG, m);
            SessionLog.i(m);
        } catch (Throwable tr) {
            Log.e(TAG, "wav write failed: " + name, tr);
        } finally {
            if (fo != null) {
                try {
                    fo.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ================= v1.3.1：常驻麦守卫修复 + 唤醒词尾巴透传 =================

    /**
     * v1.3.0 踩坑：acquireMicLoop 的循环条件是 state != IDLE（on-demand 语义），
     * 而 startAlwaysOnMic 恰在 IDLE 期调用 → 循环体一次不进 → attempts=0 放弃。
     * 本标记由 startAlwaysOnMic 置位：常驻模式下 IDLE 也允许进抢麦循环。
     */
    private volatile boolean alwaysOnRequested = false;

    /**
     * 唤醒前的环形缓冲帧数（60ms/帧）。
     *
     * v1.3.18：曾 20 -> 6（1.2s -> 0.36s）治「首响漏延」——但**真正的根因是
     * pacedFlushLoop 的 1.0x 匀速**（输入=输出 → 队列永不排空、服务器被永久
     * 甩在 backlog 之后），v1.3.18 已用「自适应追赶（2~3 帧/60ms）」根治。
     * 缩到 6 帧只是当时的保险动作，却留下一个**严重副作用**（2026-10-04 用户实测）。
     *
     * v1.3.21：6 -> 20（0.36s -> 1.2s）。**唤醒词压根没送达服务器的根因**：
     * 只喊「小智小智」不带后文时，能上行的只有 preWake 这 0.36s ——
     *   ① 0.36s 只够装唤醒词的尾巴（KWS 命中在词尾），音频本身不足以被识别；
     *   ② 更致命的是服务端门槛：`core/providers/asr/base.py` 里
     *      `if len(pcm_bytes) > 1920 * 15` —— **不足 900ms 的音频直接不送 ASR**。
     *      0.36s + 静音永远凑不够 15 帧，服务器于是「什么话都没收到」→ 静默。
     * 官方固件的做法（`application.cc` ContinueWakeWordInvoke, Kconfig
     * SEND_WAKE_WORD_DATA 默认 y）正是把**唤醒词音频**当会话第一条消息发上去：
     * `CustomWakeWord` 缓存 `Initialize(16000 * 2)` = **2 秒**音频，检测到唤醒词后
     * 一次性补发 + 再发一条 `listen/detect`。我们取 1.2s：够装 4 音节唤醒词
     * （「小智小智」~0.9s）+ 余量，又不至于把首轮 backlog 推回 v1.3.18 之前。
     * ⚠️ 代价：首轮补发多 0.84s 音频，自适应追赶下多花 ~0.3s 排空。
     *
     * v1.3.24：20 -> 34（1.2s -> 2.04s）。**1.2s 仍然不够，仍在啃唤醒词的头**：
     * 2026-10-04 18:43/18:44 实测（用户：「说的是小智小智明天下雨吗」/「明天星期几？」，
     * STT 出来分别是「智小智。」「志小智，明天星期几？」—— 每次都少开头那一个字）：
     *   · 18:43:31.150 `prewake tail queued: 20 frames (1200ms)` → STT「智小智。」
     *   · 18:44:54.067 `wake probe armed, shadow pre-seeded 20 preWake frames`
     *     → 18:45:03 STT「志小智，明天星期几？」
     * micTelem 对齐后可见：说话段在 18:44:52.3~55.3（≈3s），而 KWS 命中在 **18:44:54.065**。
     * preWake 只回溯到 18:44:52.87 → **前 0.37s（正好是第一个「小」）没进缓冲**，
     * 上行时间线变成「智小智」⊕「，明天星期几？」→ ASR 就是「志小智，明天星期几？」。
     * 即：**唤醒词实际时长 ≈1.5s（语速慢时更长），1.2s 环形缓冲在 KWS 命中晚于词尾时
     * 必然啃掉词头**。官方缓存正是 **2s**（`Initialize(16000 * 2)`），我们 2.04s 对齐并略超。
     * ⚠️ 代价：首轮补发多 0.84s 音频，自适应追赶（2~3 帧/60ms）下多花 ~0.3s 排空。
     * 可用 files/prewake<N>（2..60）覆盖。
     */
    private static volatile int prewakeFrames = 34;

    /** v1.3.18：服务层解析 files/prewake<N> 后调用 */
    public static void setPrewakeFrames(int n) {
        if (n >= 2 && n <= 60) {
            prewakeFrames = n;
        }
    }

    /** 当前 prewake 帧数（供日志） */
    public static int getPrewakeFrames() {
        return prewakeFrames;
    }

    /**
     * IDLE 期（常驻麦）滚动缓冲：唤醒前最近 prewakeFrames 帧的音频。
     * 唤醒 -> startSession 时整体倒进 pending，与 CONNECTING 期帧一起在
     * 握手后补发 —— 实现「唤醒词+紧跟的话一起发给服务器」，不用等。
     */
    private final java.util.ArrayDeque<byte[]> preWake = new java.util.ArrayDeque<byte[]>();

    /**
     * v1.3.80：唤醒词隔离。
     *
     * 根因（2026-10-08 22:23 实测）：搭车模式下 USC 的麦是**常开**的（第一次
     * enterASR 之后就不会自己关），上行也就跟着常开。于是用户喊的「小智小智」
     * 会原封不动进服务器 VAD，被当成一句话识别：
     *   22:23:59.373 STT: 小志小智猪。   22:20:30.510 STT: 小智小智。
     * 体感就是「喊了没反应 / 打断不了 / 答非所问」—— 实际上小智听到的是唤醒词
     * 本身，用户在唤醒词**之后**说的指令被当成第二轮，经常压根没进 VAD。
     *
     * 旧代码还雪上加霜：LISTENING 态唤醒会 `flushPreroll()`，把预缓冲里那一秒
     * 的唤醒词**再补发一遍**。
     *
     * 修法：唤醒即丢弃预缓冲（preroll + preWake），并对已有会话重发 listen start
     * 让服务器把已累积的那一段丢掉、从唤醒点之后重新听。
     *
     * 关掉：touch files/keep_wake_audio（保留旧行为，用于对照实验）。
     */
    public static volatile boolean dropWakeWordAudio = true;

    /** 供 XzBootstrap 读旗标 */
    public static void setDropWakeWordAudio(boolean on) {
        dropWakeWordAudio = on;
    }

    // v1.3.83 曾加 wakeTailKeepMs（唤醒保留 300ms 尾巴）并改写 dropWakeAudio()。
    // 用户实测「完全没有改善」且伴生 tap 断流，已整体回退到 v1.3.82 语义：
    // 唤醒即丢弃全部预缓冲（preroll + preWake）。

    private final Handler main = new Handler(Looper.getMainLooper());

    // ================= v1.2.15：原厂 USC 错误提示静音 =================

    /**
     * 会话结束后的媒体流静音时长。
     * 实测（2026-10-03 20:45/20:52）：还麦后 ~5.6s，原厂 USC 引擎发现自己
     * 云端凭证被拒（authStatus status=230 Mcode 校验失败），离线 TTS 现说
     * 一句「网络连接失败，请检查」——走 STREAM_MUSIC，端点配置烘焙在 APK
     * 里且无音频文件可替换，修配置的路全堵死。
     * 客户端 band-aid：还麦瞬间把媒体流静音罩住，下次唤醒立即解除。
     * v1.2.16：20s → 45s —— 实测 USC 恢复期偶发二次播报（30s 左右才出现），
     * 20s 盖不住；唤醒即解除逻辑保证延长无副作用（下次对话立即恢复出声）。
     */
    private static final long USC_PROMPT_MUTE_MS = 45000L;

    private final Context appContext;

    /** 当前媒体流是否处于我们设置的静音状态（防重复 mute，AudioService 按次计数） */
    private boolean uscMuted = false;

    // ================= v1.2.16：TTS 播放中 barge-in（服务器 VAD 打断） =================

    /**
     * TTS 播放中是否继续上行音频，让服务器 VAD 检测人声并打断当前回复。
     *
     * v1.2.16 引入时默认 true。**v1.3.16 改为默认 false**：
     * R1 没有 AEC（实测开系统 AEC 会把近端人声一起压掉，所以是关的），
     * TTS 播放中上行 = 把我们自己喇叭里的 TTS 声原样喂回服务器 ——
     * 服务器 VAD 很容易据此误判「用户插话」→ 打断/重合成 → 听感就是
     * 「卡顿 + 丢字 + 话没说完就没声」。
     * 打断改由自建 KWS 唤醒词承担（TTS 中喊「小智小智」→ handleWakeup 走
     * barge-in 分支），既可靠又不会自环。
     * files/bargein_on 存在 → 恢复「直接说话即打断」的旧行为。
     */
    public static volatile boolean bargeInUplink = false;

    /** TTS 持续短于此值就被打断 → 判定为喇叭自环误触发 */
    private static final long ECHO_LOOP_MIN_TTS_MS = 2000L;
    /** v1.3.124：连续多少次「短 TTS」才判定自环熔断（旧版是 1 次，太容易误伤） */
    private static final int ECHO_LOOP_STRIKES = 3;

    // ============ v1.3.129：TTS 期「人声触发放行」（替代 v1.3.127/128 的饥饿闸门）============
    //
    // 前两版都栽在同一个地方：**把「TTS 播放期的所有上行帧」当成一个整体**，要么全放
    // （v1.3.126/127 → 被自己的声音打断），要么全挡（v1.3.128 → 说话打不断）。
    //
    // v1.3.129 的判据是**逐帧的**，而且只用两个数（diag/probe3.log，6 轮 TTS / 321 帧实测）：
    //
    //   TTS 播放期 raw 分布      p50=6  p75=11  p90=34  p95=57  max=150
    //   非 TTS（LIS）raw 分布    p50=4  p75=4   p90=5   p95=15  max=85
    //
    //   ★ 关键观察：TTS 期的**底色 p50=6，与安静本底 4 一样** ⇒ 原厂 HAL 的 AEC 一直在
    //     工作，TTS 播放期**没有持续泄漏**。「被自己的声音打断」是被那 10% 的散点
    //     （raw 34~88）触发的 —— 那是**用户插话的轻声起头**，不是喇叭回声。
    //   ★ 真人插话 raw = **93~150**（第 6 轮长 TTS 实测 6 次触发），正常音量问话 raw=85。
    //
    //   门限扫描（单帧触发，门限 90）：
    //     前 5 轮 TTS 峰值 54/88/59/62/70 → **0 次触发**（= 没有插话时不误放行）
    //     第 6 轮（用户真插话）        → 触发 6 次（93/101/112/138/143/150）
    //
    //   ⇒ 一张单门限把「泄漏 + 轻声散点」和「用户主动插话」分得很干净。
    //      代价：刻意压低声音插话可能触不到门限（1.5s 保持窗口内不会）。
    //      可调：`files/barge_raw<N>` 覆盖（N=60..200），Web 端写这个旗标。

    /**
     * TTS 期判定「用户在插话」的 raw 门限（ALC **之前**的电平，同探针的 `raw=`）。
     * 默认 90 —— 实测泄漏/散点上限 88，真人插话下限 93，取中间。
     */
    public static volatile int bargeVoiceRaw = 90;

    /** 触发一次后的放行保持时长：让服务端拿到连续语音，VAD 才判得动。 */
    private static final long BARGE_VOICE_HOLD_MS = 1500L;

    /** 人声触发的放行截止时刻（elapsedRealtime；0 = 未触发） */
    private volatile long bargeVoiceUntilMs = 0L;

    /** 本会话「人声触发」累计次数 + 最后一次触发的 raw（取证用） */
    private volatile int bargeVoiceTriggers = 0;
    private volatile int bargeVoiceLastRaw = 0;

    /** v1.3.129：供 Web 控制台读取当前生效的门限 */
    public static int bargeVoiceRaw() {
        return bargeVoiceRaw;
    }

    /** v1.3.129：Web / bootstrap 设置门限（夹到 40..400，防手滑写成 0 = 全放行） */
    public static void setBargeVoiceRaw(int v) {
        if (v < 40) {
            v = 40;
        }
        if (v > 400) {
            v = 400;
        }
        bargeVoiceRaw = v;
    }

    // ============ v1.3.121：实时打断（说话即打断，不必喊唤醒词） ============

    /**
     * ★★ 实时打断总开关（默认**关**）。Web「实时打断」勾选 / `files/vad_on` 打开。
     *
     * 为什么默认关：本机没有软件 AEC，只有 HAL 那一路回声抑制（见 echoDisabled 注释）。
     * TTS 播放期麦克风电平被压得很低是**常态**，判断「用户是不是在说话」只能靠电平 ——
     * 门限定错就是两种极端：
     *   太松 → 把自己的喇叭声当成插话 ⇒ 自打断（体感：小智说到一半自己停）；
     *   太紧 → 怎么喊都不断（等于没这功能）。
     * 与其替用户猜，不如默认关 —— 让他在自己的声学环境里开、用 `?op=` 或 Web 试，
     * SessionLog 里每 300ms 一行 `vad probe raw=` 就是标定门限的尺子。
     *
     * ★ 判据用的是**增益前的 RAW 电平**（AudioCapture.rawFrameRms()），不是
     * `frameRms()`：ALC/固定增益会把「TTS 期被 HAL 压住的那点底噪」抬到与说话
     * 同量级（都≈目标值 1400），用增益后的值判，两态完全分不开。
     */
    public static volatile boolean vadBargeIn = false;

    /**
     * RAW 电平门限：连续 vadBargeInFrames 帧 ≥ 此值 ⇒ 判定「用户在说话」。
     * files/vadrms&lt;N&gt; 覆盖（600/800/1000/1400/1800/2000/3000）。
     * 默认 1000 = 预设落在「TTS 期残余」与「远近场人声」之间，需实机标定。
     */
    public static volatile int vadBargeInRms = 1000;

    /** 需要连续命中的帧数（60ms/帧）：默认 3 = 180ms。files/vadframes&lt;N&gt;（2..10） */
    public static volatile int vadBargeInFrames = 3;

    /**
     * TTS 起播后的保护窗口（毫秒），这段时间内**一律不判**。
     * 理由：①起播瞬间 HAL 回声抑制还没收敛，喇叭起音会漏进麦克风；
     *       ②上一句是用户说的，尾音/气息还在；
     *       ③顺手滤掉清嗓、衣物摩擦这类毫秒级突发。
     * 默认 600ms。files/vadarmms&lt;N&gt;（0..3000）。
     */
    public static volatile int vadBargeInArmMs = 600;

    public static void setVadBargeIn(boolean on) {
        vadBargeIn = on;
        // v1.3.122：★ 按官方协议做实时打断 —— 客户端唯一的动作是「TTS 期间照常上行」，
        // 判定交给服务器 VAD。以前我们把 TTS 期上行彻底关掉（bargeInUplink=false，
        // 怕把喇叭声喂回去造成自环），服务器压根收不到音频，自然无从打断。
        // 现在两者绑成同一个开关：开 = 放行上行（由用户的原厂 HAL 负责回声抑制），
        // 本地只留观测电平（见 probeVadBargeIn），不再自己猜电平、自己 abort。
        bargeInUplink = on;
    }

    public static void setVadRms(int v) {
        if (v >= 0) {
            vadBargeInRms = v;
        }
    }

    public static int vadRms() {
        return vadBargeInRms;
    }

    /** TTS 期内连续超门限的帧数 */
    private int vadRun = 0;
    /** VAD 取证节流（TTS 期每 300ms 一行 `vad probe raw=`） */
    private volatile long vadProbeLastMs = 0L;

    // ---- v1.3.121：RAW 实时电平（网页电平条 + 门限标定用的那把尺子）----

    /** 最近一帧的 RAW（增益前）电平 */
    private volatile int rawLast = 0;
    /** 最近 RAW_PEAK_MS 窗口内的 RAW 峰值 */
    private volatile int rawPeak = 0;
    private volatile long rawPeakMs = 0L;
    private static final long RAW_PEAK_MS = 3000L;

    private void noteRawLevel(int raw) {
        long tn = android.os.SystemClock.elapsedRealtime();
        rawLast = raw;
        if (raw > rawPeak || tn - rawPeakMs > RAW_PEAK_MS) {
            rawPeak = raw;
            rawPeakMs = tn;
        }
    }

    /** 最近一帧 RAW 电平（0 = 还没出帧 / 会话不在） */
    public int rawLevel() {
        return rawLast;
    }

    /** 最近 3 秒内的 RAW 电平峰值 */
    public int rawPeakLevel() {
        return (android.os.SystemClock.elapsedRealtime() - rawPeakMs > RAW_PEAK_MS)
                ? 0 : rawPeak;
    }

    /** 自环熔断标志：置位后本进程内 TTS 播放期停上行（回退 v1.2.15 行为） */
    private volatile boolean echoDisabled = false;
    /** v1.3.124：连续短 TTS 计数；任何一次正常长度的播放都会清零并解除熔断 */
    private volatile int echoLoopStrikes = 0;

    /** 当前轮 TTS 开始时刻（算持续时间用） */
    private volatile long ttsStartElapsed = 0L;

    // ================= v1.3.16：僵尸会话自愈 =================

    /**
     * 最近一次收到**任何**服务器下行帧的时刻（elapsedRealtime）。
     * 用途：唤醒时判断手里这条会话是不是已经死了。
     */
    private volatile long lastDownlinkMs = 0L;

    /** 最近一次收到 TTS 音频帧的时刻（TTS 流静默看门狗用） */
    private volatile long lastTtsFrameMs = 0L;

    /**
     * TTS 流静默阈值：进入 TTS_PLAYING 后连续这么久既收不到 TTS 音频、
     * 也等不到 tts stop → 判定这路 TTS 流已经结束（哪怕服务器没说）。
     */
    private static final long TTS_STALE_MS = 15000L;

    /**
     * v1.3.17：判定「TTS 流还活着」的最近帧窗口。
     *
     * 唤醒打在 TTS_PLAYING 上时要二分：TTS 真在出声（barge-in）还是流已经断了
     * （服务器发完告别语却不下发 tts stop 的僵尸 TTS）？旧实现用
     * player.isPlaying() —— 但它在越过 250ms 水位之前恒为 false，TTS 刚起步就被
     * 唤醒会被误判成僵尸，白重启一次会话。改用「最近一帧 TTS 音频到达时刻」判活，
     * 与播放器的水位状态解耦。
     */
    private static final long TTS_ALIVE_MS = 2000L;

    /**
     * v1.3.17：LISTENING 态下「服务器仍在推 TTS 音频」的判据窗口。
     * 用途：barge-in 后服务器可能还在往下塞 TTS 帧（它没真的停）；此时麦克风
     * 拾到喇叭声可能再次触发 KWS 唤醒词，若照样硬重启就会来回抽搐。这个短窗口
     * 专门吞掉这类「TTS 尾巴误命中」——真正的用户唤醒，距离最后一帧 TTS 音频
     * 通常已过去数秒（实测 2.4~3.3s），不会被它误吞。
     */
    private static final long TTS_STREAM_QUIET_MS = 1000L;

    /** 置位后 enterIdle 收尾时不停在 IDLE，而是立刻开一条新会话 */
    private volatile boolean restartPending = false;

    /** v1.3.16：任何服务器下行都刷新「会话存活」时间戳 */
    private void touchDownlink() {
        lastDownlinkMs = android.os.SystemClock.elapsedRealtime();
    }

    // ================= v1.3.19：唤醒响应探测（僵尸会话快速自愈） =================

    /**
     * 背景（2026-10-04 17:10 实测 session log）：服务器把告别语讲完、**甚至发了
     * tts stop** 之后就静默了 —— 它那一侧的会话已经作废，但 ws 还开着、我们这边
     * 状态仍是 LISTENING、下行静默才 2s。「健康会话」与「已死会话」在本地**没有
     * 可靠的静态判据**：唯一判据是「服务器会不会对新语音作出反应」。
     *
     * 走过的两条弯路：
     *   v1.3.16 用「下行静默 > 75s」猜 → 太慢，用户要干等 80s；
     *   v1.3.17 改成「LISTENING 态唤醒一律硬重启」→ 太武断，每一次唤醒都把健康
     *   会话拆掉重建，多轮交互全部多背 5~7s，而且频繁重连本身会失败
     *   （17:10:38 / 17:10:59 / 17:11:19 连续三次重启零回复）。
     *
     * v1.3.19 的取舍：**默认会话是活的**。唤醒时不再拆会话，直接把上行放行、让它
     * 听到用户说话；同时装一个「响应探测」——把唤醒后真正上行出去（已发给这条
     * 会话）的音频在本机留一份影子，若 wakeProbeSec 秒内服务器**毫无反应**
     * （既无 STT 也无 TTS），才判定僵尸、硬重启，并把影子音频回放进新会话 ——
     * 用户说过的话不用重说。
     */
    private volatile boolean probeArmed = false;

    /** startSession 消费：本次启动是否把影子音频（而非 preWake）倒进 pending */
    private volatile boolean replayShadow = false;

    /** 探测窗口内实际送出的上行 PCM（60ms/帧） */
    private final java.util.ArrayList<byte[]> shadowBuf = new java.util.ArrayList<byte[]>();
    /** 影子缓冲上限（200 帧 = 12s，16k mono = 384KB） */
    private static final int SHADOW_MAX_FRAMES = 200;

    /** 影子缓冲里出现的最大样本绝对值 —— 判「这段里到底有没有人声」 */
    private volatile int shadowPeak = 0;

    /**
     * 「影子里确实有人声」的峰值门限（16bit 满量程 32767；本机实测上行已 ×16 增益，
     * 说话 peak ≈ 600~700、静默 ≈ 100 量级）。
     * 低于它说明这声唤醒是 TTS 尾巴/环境噪声的 KWS 误命中、或者用户根本没开口 ——
     * 那就不要为了它去重启会话。
     */
    private static final int PROBE_VOICE_PEAK = 260;

    /**
     * 探测窗口秒数（files/wakeprobe<N> 覆盖，2..20；默认 **3s**）。
     *
     * v1.3.19 定 7s，v1.3.24 收到 3s：健康会话对「唤醒词」的响应极快 —— 实测
     * 18:43:31.145 唤醒 → 18:43:32.760 STT，`wake->STT = 1618ms`。也就是说
     * 「服务器会回话」这件事 2s 内就能确认；7s 纯属空等（18:44:54 唤醒的那次
     * 白白等到 18:45:01，加上重连与回放，用户 `wake->STT = 8953ms`）。
     * 3s 给了近 2 倍余量，同时把僵尸会话的恢复时间从 ~9s 压到 ~4s。
     */
    private static volatile int wakeProbeSec = 3;

    public static void setWakeProbeSec(int s) {
        if (s >= 2 && s <= 20) {
            wakeProbeSec = s;
        }
    }

    public static int getWakeProbeSec() {
        return wakeProbeSec;
    }

    private void armWakeProbe() {
        clearShadow();
        // v1.3.23：影子先「预置」上唤醒前的 preWake 环形缓冲（默认 20 帧 = 1.2s）。
        // 原实现只从「唤醒时刻」开始录 —— 而连读时用户的前半句恰恰在唤醒**之前**
        // （KWS 要等「小智小智」念完才命中），于是 2026-10-04 18:19 那次回放出来的
        // 只有尾巴：说「小智小智明天下雨吗」，回放后 STT 只出「下雨吗？」。
        // 这里只**复制**不清空（探测若被解除，preWake 还要留给下一次 IDLE 唤醒用）。
        int seeded = 0;
        synchronized (preWake) {
            for (byte[] f : preWake) {
                shadowAdd(f, f.length);
                seeded++;
            }
        }
        probeArmed = true;
        main.removeCallbacks(wakeProbeTask);
        main.postDelayed(wakeProbeTask, wakeProbeSec * 1000L);
        if (seeded > 0) {
            String m = "wake probe armed (" + wakeProbeSec + "s), shadow pre-seeded with "
                    + seeded + " preWake frames (" + (seeded * 60) + "ms)";
            Log.i(TAG, m);
            SessionLog.i(m);
        }
    }

    private void disarmWakeProbe() {
        probeArmed = false;
        main.removeCallbacks(wakeProbeTask);
    }

    private void clearShadow() {
        synchronized (shadowBuf) {
            shadowBuf.clear();
        }
        shadowPeak = 0;
    }

    /** v1.3.20：一帧 16bit 小端 PCM 的均方根（0..32767）。960 样点/帧，代价可忽略。 */
    private static int frameRms(byte[] d, int len) {
        int n = len / 2;
        if (n <= 0) {
            return 0;
        }
        long acc = 0L;
        for (int i = 0; i + 1 < len; i += 2) {
            short v = (short) ((d[i] & 0xff) | (d[i + 1] << 8));
            acc += (long) v * v;
        }
        return (int) Math.sqrt((double) acc / n);
    }

    /** v1.3.20：本会话人声统计（一行，直接嵌进各种收尾/进展日志） */
    private String voiceStats() {
        // v1.3.126：raw/up 双域汇总 —— 会话结束时一眼看出「泄漏 vs 人声」的余量。
        // rawTts=  TTS 播放期 raw 峰值/均值（回声泄漏）
        // rawVoice= LISTENING 放行上行期 raw 峰值/均值（真人说话）
        // ratio = rawVoice峰值 / rawTts峰值 ⇒ 电平域可分性（>3 才有意义，>6 很稳）
        String extra = "";
        if (sessRawTtsN > 0 || sessRawVoiceN > 0) {
            long ttsAvg = sessRawTtsN > 0 ? sessRawTtsSum / sessRawTtsN : 0L;
            long voiceAvg = sessRawVoiceN > 0 ? sessRawVoiceSum / sessRawVoiceN : 0L;
            long upTts = sessRawTtsN > 0 ? sessUpTtsSum / sessRawTtsN : 0L;
            long upVoice = sessRawVoiceN > 0 ? sessUpVoiceSum / sessRawVoiceN : 0L;
            double ratio = sessRawTtsMax > 0
                    ? (double) sessRawVoiceMax / (double) sessRawTtsMax : 0.0;
            extra = " | rawTts=" + sessRawTtsMax + "/" + ttsAvg + "(n=" + sessRawTtsN + ")"
                    + " rawVoice=" + sessRawVoiceMax + "/" + voiceAvg
                    + "(n=" + sessRawVoiceN + ")"
                    + " upTts=" + upTts + " upVoice=" + upVoice
                    + " ratio=" + String.format(java.util.Locale.US, "%.2f", ratio);
        }
        // v1.3.129：TTS 期「人声触发」次数 —— 判断实时打断到底有没有被用户触发过。
        // bargeTrig=0 且用户说「打不断」⇒ 门限太高（调低 files/barge_rawNNN）；
        // bargeTrig>0 但仍打不断 ⇒ 服务端侧没认（那就是另一条线了）。
        extra += " | bargeTrig=" + bargeVoiceTriggers
                + "(lastRaw=" + bargeVoiceLastRaw + ",thr=" + bargeVoiceRaw + ")";
        return " maxRms=" + sessionMaxRms + " voicedFrames=" + sessionVoicedFrames + extra;
    }

    /**
     * v1.3.20：播放唤醒应答音，并开一个回声丢弃窗口。
     * 只有「从 IDLE 起来的全新会话」才播 —— TTS 播放中的 barge-in、
     * 探测判僵尸后的硬重启（用户已经在说话了，影子音频正等着回放）都不该插一声「叮」。
     */
    private void playWakeAck() {
        if (!wakeAckEnabled) {
            return;   // files/wakeack_off：不出声，也就没有回声要丢
        }
        if (soundOff()) {
            return;   // v1.3.99：web 控制台「提示音」总开关
        }
        long now = android.os.SystemClock.elapsedRealtime();
        // v1.3.22：默认 0 —— 一帧都不丢（硬丢会剪断连读的时间线，见 wakeAckEchoDropMs 注释）
        micDropUntilMs = (wakeAckEchoDropMs > 0L) ? now + wakeAckEchoDropMs : 0L;
        TtsPlayer p = player;
        if (p == null) {
            // 降级中（音频 HAL 死过）：不出声，无回声，窗口也就没有意义
            Log.i(TAG, "wake ack skipped (no player), mic echo drop "
                    + wakeAckEchoDropMs + "ms");
            return;
        }
        // v1.3.144：告知 MusicResume「接下来这帧是应答音，不是小智在说话」——
        // 它走 TtsPlayer 的同一个播放循环，不标记的话让路会提前到「叮」之前执行。
        MusicResume.noteWakeCue();
        p.push(buildWakeAckPcm());
        String m = "wake ack cue played (" + WAKE_ACK_MS + "ms), mic echo dropped "
                + wakeAckEchoDropMs + "ms";
        Log.i(TAG, m);
        SessionLog.i(m);
    }

    /**
     * 合成唤醒应答音 PCM（16kHz mono）：单声 1046Hz「叮」160ms + 静音补齐到
     * WAKE_ACK_MS。尾部静音既做 5ms 淡出后的保护余量，也保证长度越过
     * TtsPlayer 的启动水位（否则播放线程永远不启动，见 WAKE_ACK_MS 注释）。
     */
    private static byte[] buildWakeAckPcm() {
        int rate = AudioCapture.SAMPLE_RATE;
        int toneMs = 160;
        int n = rate * toneMs / 1000;
        byte[] out = new byte[rate * WAKE_ACK_MS / 1000 * 2];
        int idx = 0;
        int fade = rate / 200;   // 5ms
        for (int i = 0; i < n; i++) {
            double v = Math.sin(2.0 * Math.PI * 1046.0 * i / rate);
            double env = 1.0;
            if (i < fade) {
                env = (double) i / fade;
            } else if (i > n - fade) {
                env = (double) (n - i) / fade;
            }
            short s = (short) (v * env * 0.30 * 32767);
            out[idx++] = (byte) (s & 0xff);
            out[idx++] = (byte) ((s >> 8) & 0xff);
        }
        return out;   // 余下为 0（静音）
    }

    /** 影子捕获：探测窗口内真正送出的上行 PCM 留档 + 量峰值 */
    private void shadowAdd(byte[] data, int len) {
        int pk = 0;
        for (int i = 0; i + 1 < len; i += 2) {
            short v = (short) ((data[i] & 0xff) | (data[i + 1] << 8));
            int a = (v < 0) ? -v : v;
            if (a > pk) {
                pk = a;
            }
        }
        if (pk > shadowPeak) {
            shadowPeak = pk;
        }
        synchronized (shadowBuf) {
            if (shadowBuf.size() >= SHADOW_MAX_FRAMES) {
                return;
            }
            byte[] copy = new byte[len];
            System.arraycopy(data, 0, copy, 0, len);
            shadowBuf.add(copy);
        }
    }

    /**
     * 探测到期：服务器对这次唤醒没有任何反应。
     * 若影子里确实有人声 → 判僵尸会话，硬重启并回放；否则只是误命中/没人说话，
     * 保持会话不动。
     */
    private final Runnable wakeProbeTask = new Runnable() {
        public void run() {
            if (!probeArmed) {
                return;
            }
            probeArmed = false;
            if (state != State.LISTENING) {
                clearShadow();
                return;
            }
            int n;
            int pk = shadowPeak;
            synchronized (shadowBuf) {
                n = shadowBuf.size();
            }
            if (n == 0 || pk < PROBE_VOICE_PEAK) {
                String m = "wake probe: no speech in shadow (frames=" + n
                        + " peak=" + pk + ") -> session kept, no restart";
                Log.i(TAG, m);
                SessionLog.i(m);
                clearShadow();
                return;
            }
            String m = "wake probe: server silent " + wakeProbeSec
                    + "s after wake (frames=" + n + " peak=" + pk
                    + ") -> session likely dead, hard restart + replay";
            Log.i(TAG, m);
            SessionLog.i(m);
            replayShadow = true;   // 交给 startSession 回放到新会话
            hardRestart();
        }
    };

    /**
     * v1.3.0：麦克风常驻开关（KWS 独立唤醒的前提）。
     * true  = KWS 初始化成功后麦不再释放，IDLE 期持续喂自建 KWS（「成品」架构：
     *         随时可唤醒、TTS 播放中喊唤醒词即可打断）；原厂引擎永久断粮。
     * false = v1.2.4 行为：麦是会话期资源，IDLE 期还给原厂（bus 唤醒兜底）。
     * 实际由服务层在 KWS init 成功后才启用（startAlwaysOnMic），失败自动回退。
     */
    private static final boolean MIC_ALWAYS_ON = true;

    /** 45s 定时解除（唤醒早于定时器到达时会被 removeCallbacks 撤销） */
    private final Runnable uscUnmute = new Runnable() {
        public void run() {
            setMediaMute(false, "45s timer");
        }
    };

    /** 无 TTS 看门狗：CONNECTING/LISTENING 停留过久 → 收会话还麦 */
    private final Runnable noTtsWatchdog = new Runnable() {
        public void run() {
            State s = state;
            if (s == State.CONNECTING || s == State.LISTENING) {
                String m = "no TTS within " + (watchdogMs / 1000)
                        + "s (state=" + s + ")" + voiceStats()
                        + " -> closing session, mic goes back to unisound";
                Log.i(TAG, m);
                SessionLog.i(m);
                // close 会触发 onClosed -> enterIdle（还麦）
                ws.close("no-tts-timeout");
            }
        }
    };

    public SessionController(Context appContext, AudioCapture mic, OpusCodec opus,
                             TtsPlayer player, KwsEngine kws, XiaoZhiWs ws) {
        this.appContext = appContext;
        this.mic = mic;
        this.opus = opus;
        this.player = player;
        this.kws = kws;
        this.ws = ws;
        HandlerThread th = new HandlerThread("R1MicCtl");
        th.start();
        micHandler = new Handler(th.getLooper());
        // 防御性复位：若上一进程在静音期间崩溃，AudioService 的 mute 计数
        // 可能残留（5.x 不随客户端死亡清理），开机先清一次零。
        setMediaMute(false, "startup reset");
    }

    public State getState() {
        return state;
    }

    /**
     * 运行期挂载/摘除 KWS。
     * KWS 是异步初始化的（native 初始化可能崩，必须隔离），
     * 初始化成功后才通过这里接上；失败则传 null，走纯总线唤醒。
     */
    public void setKws(KwsEngine k) {
        this.kws = k;
        Log.i(TAG, "setKws: " + (k == null ? "disabled" : "enabled"));
    }

    /**
     * v1.2.3：运行期补挂 TtsPlayer。
     * 音频 HAL 卡死时服务会以 player=null 降级启动（纯 ASR 也可用），
     * 退避重试成功后把新 player 接进来。
     */
    public void setPlayer(TtsPlayer p) {
        this.player = p;
        Log.i(TAG, "setPlayer: " + (p == null ? "null" : "attached"));
    }

    // ---------------- 唤醒（双源入口） ----------------

    /**
     * 源 A：自建 sherpa-onnx KWS 命中「小智小智」。
     * 语音「小智小智」时，这里会立刻触发，并补发麦克风预缓冲。
     */
    @Override
    public void onKeyword(String keyword) {
        lastWakeWord = keyword;   // v1.3.21：留档供 detect 上报
        handleWakeup("kws:" + keyword, true);
    }

    /**
     * 源 B：原厂云知声引擎唤醒后投递的总线事件（「小讯小讯」）。
     *
     * 这条路径的关键优势：唤醒判定完全由固件里的四麦引擎完成，
     * 唤醒率/抗噪远高于我们自己跑的软件 KWS，且零 CPU 开销。
     * v1.2.4 起这更是唯一可行的唤醒方式 —— 我们的麦在 IDLE 期
     * 必须让给原厂，自建 KWS 没有音频可听。
     */
    @Override
    public void onWakeup(String word) {
        lastWakeWord = word;      // v1.3.21：留档供 detect 上报
        handleWakeup("bus:" + word, true);
    }

    /**
     * v1.3.8：adb 调试唤醒 —— am broadcast 直接触发，不走总线/KWS。
     * 用于精确时序控制实验（如「USC 会话结束 N 秒后再开流」），
     * 解耦唤醒源与开流时机的耦合。
     */
    public void debugWakeup() {
        lastWakeWord = "小智小智";   // v1.3.21：adb 调试唤醒也用真唤醒词上报
        handleWakeup("debug:adb", true);
    }

    /**
     * 统一唤醒处理。
     * @param src      来源标记，仅用于日志
     * @param usePreroll barge-in 时是否补发预缓冲
     */
    private void handleWakeup(String src, boolean usePreroll) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastWakeupMs < WAKEUP_DEBOUNCE_MS) {
            Log.i(TAG, "wakeup ignored (debounce) src=" + src + " state=" + state);
            return;
        }
        lastWakeupMs = now;
        wakeMs = now;               // v1.3.14：延迟埋点基准
        latFirstUplinkMs = 0L;
        latSttMs = 0L;
        latFirstTtsMs = 0L;
        Log.i(TAG, "wakeup src=" + src + " state=" + state);
        SessionLog.i("wakeup src=" + src + " state=" + state);

        // v1.3.130：新一轮对话开始 —— 撤掉上一轮排队的「音乐恢复」。
        // 注意只 removeCallbacks，**不清 pending** —— 唤醒中断刚刚才把我们标成待恢复。
        MusicResume.cancel();

        // v1.3.143：★ 唤醒这一刻主动挂起「音乐让路」—— 必须放在 playWakeAck() 之前：
        // 应答音也是 STREAM_MUSIC，一播出来 isMusicActive() 就恒真，采样就脏了。
        // 以前只靠原厂 music handler 的 doInterrupt 来触发 deferPause，实测那条分支
        // 在放歌唤醒时压根不走 ⇒ 让路只能靠软状态兜底 ⇒ 软状态一丢就「不灵」。
        MusicResume.onWakeup();

        // v1.3.80：唤醒词隔离 —— 必须在任何补发/放行上行之前执行，否则 preWake
        // 与 preroll 里的唤醒词会先一步进入上行（详见 dropWakeWordAudio 注释）。
        dropWakeAudio();

        // v1.2.15：唤醒即解除错误提示静音 —— 本轮会话要出声（TTS/回声提示），
        // 且 USC 的下一条错误提示要等这轮会话结束才会播。
        main.removeCallbacks(uscUnmute);
        if (uscMuted) {
            setMediaMute(false, "wakeup");
        }

        if (state == State.IDLE) {
            // v1.3.20：唤醒即出声 —— 先播应答音再开流。
            // 根因（2026-10-04 17:27 实测）：唤醒后设备全程静默直到服务器开口，
            // 用户无从判断「到底醒了没」，等了几十秒才说话，而那时 25s 无 TTS 看门狗
            // 早已把会话收掉 → 用户的话落在 IDLE 上，体感就是「唤醒没回复」。
            // 一声「叮」= 对讲机式的「你说」，用户听到就开口，整条时序就顺了。
            playWakeAck();
            // v1.3.21：全新会话才上报唤醒词（握手后发 listen/detect）。默认关，
            // 详见 detectEnabled 字段注释（服务端会因此耳聋 2 秒，连读会被吞）。
            pendingWakeReport = detectEnabled ? lastWakeWord : null;
            startSession();
        } else if (state == State.TTS_PLAYING) {
            long sinceTts = android.os.SystemClock.elapsedRealtime() - lastTtsFrameMs;
            TtsPlayer p = player;
            // v1.3.17：判活改用「最近一帧 TTS 音频的到达时刻」而非 isPlaying()
            // —— isPlaying() 在越过 250ms 水位之前恒为 false，TTS 刚起步就被
            // 唤醒会被误判成僵尸 TTS，白重启一次会话。
            if (sinceTts < TTS_ALIVE_MS || (p != null && p.isPlaying())) {
                // 真·打断：TTS 流还活着（音频帧仍在到达，或播放器正在出声）——
                // 清掉本地剩余音频、转 LISTENING 接住用户，并重发 listen start
                // 让服务器切回 listening（打断本轮 TTS）。
                Log.i(TAG, "barge-in during TTS (playing, sinceTts=" + sinceTts + "ms)");
                SessionLog.i("barge-in during TTS (playing)");
                if (p != null) {
                    // v1.3.49：真·打断 —— 清队列 **并 flush AudioTrack**。
                    // 只清队列的话，硬件缓冲里最多 512ms 的 TTS 会在打断之后紧接着
                    // 被麦克风录进上行（R1 无 AEC）—— 那正是自环的入口。
                    p.abort();
                }
                // v1.3.23：用户在这句里插话了 -> 撤销「告别即结束」的预定，会话继续
                main.removeCallbacks(farewellEndTask);
                serverFarewell = false;
                farewellActive = false;
                main.removeCallbacks(ttsStaleCheck);
                setState(State.LISTENING);
                ws.sendStartListening();
                // v1.3.49：硬件尾巴已被 abort() 冲掉 → 立刻放行上行接住唤醒词与后文。
                // 下面的 flushPreroll() 会把「小智小智」本身也一并带给服务器。
                main.removeCallbacks(resumeUplinkTask);
                suppressUplink = false;
                echoGateOn = false;   // 打断路径不设电平闸门（尾巴已 flush）
                echoQuietRun = 0;
                armNoTtsWatchdog(idleWindowMs());
                scheduleIdleAlert();
                // v1.3.80：不再 flushPreroll —— 那一段正是「小智小智」本身，
                // 补发上去服务器就会把唤醒词识别成一句话（实测 STT: 小智小智。）。
                if (usePreroll && !dropWakeWordAudio) {
                    flushPreroll();
                }
                String bm = "barge-in: uplink resumed at once (AudioTrack flushed, wake audio dropped)";
                Log.i(TAG, bm);
                SessionLog.i(bm);
            } else {
                // ===== v1.3.16：僵尸 TTS =====
                // 状态机还挂在 TTS_PLAYING，但播放器早就不出声了 —— 说明服务器
                // 已经把这轮 TTS 讲完却**没有下发 tts stop**（实测：发完
                // 「那我先休息一下好啦」告别语后就这么静默了）。
                // 旧代码在这里无条件 player.onTtsStart() + 转 LISTENING，
                // 等于继续用这条服务器早就不要了的死会话 → 用户怎么喊都没反应
                // （2026-10-04 16:36 实测：连喊两次「小智小智」全无响应）。
                // 现在：直接掐掉这条会话、开一条全新的。
                long idle = android.os.SystemClock.elapsedRealtime() - lastDownlinkMs;
                String m = "wake during stalled TTS (downlink idle=" + idle
                        + "ms) -> hard restart session";
                Log.i(TAG, m);
                SessionLog.i(m);
                hardRestart();
            }
        } else if (state == State.LISTENING) {
            // ===== v1.3.19：默认会话是活的 —— 接着说话，不再拆会话 =====
            // v1.3.17 在这里「无条件硬重启」，把健康会话也一起拆了：每一次唤醒都要
            // 重连（多背 5~7s），而且频繁重连本身会失败（实测 17:10:38 / 17:10:59 /
            // 17:11:19 连续三次重启零回复）—— 用户体感就是「唤醒概率失败」。
            // 现在：唤醒 = 「我要说话」，服务器本来就在 listening，把上行放行让它听到
            // 即可；只有服务器对这次唤醒**毫无反应**时，才由 wakeProbe 判僵尸并重启
            // （并把影子音频回放，用户不用重说）。
            long sinceTts = android.os.SystemClock.elapsedRealtime() - lastTtsFrameMs;
            if (sinceTts < TTS_STREAM_QUIET_MS) {
                // 刚刚还在推 TTS 音频 —— 这一声多半是喇叭声产生的 KWS 误命中
                Log.i(TAG, "wake ignored (TTS still streaming, sinceTts=" + sinceTts + "ms)");
                return;
            }
            // 可能还压在「TTS 结束后的上行恢复窗口」里 —— 立刻放行，别吃掉用户开头的话
            main.removeCallbacks(resumeUplinkTask);
            suppressUplink = false;
            echoGateOn = false;   // v1.3.49：唤醒是明确的"我要说话"，撤掉电平闸门
            echoQuietRun = 0;
            // 用户开口了：撤掉本地断开提示，续听窗口重新计时
            main.removeCallbacks(idleAlertTask);
            armNoTtsWatchdog(idleWindowMs());
            scheduleIdleAlert();
            // v1.3.80：这里原来会 flushPreroll() 把「小智小智 + 后文」补发上去 ——
            // 那正是服务器把唤醒词识别成一整句话的来源（实测 STT: 小智小智。）。
            // 唤醒词不该上行；用户唤醒**之后**说的话才是，而那些帧本来就在实时
            // 上行里（dropWakeAudio 已经把服务器侧 VAD 归零）。想恢复旧行为：
            // touch files/keep_wake_audio。
            if (usePreroll && !dropWakeWordAudio) {
                flushPreroll();
            }
            // 安全网：服务器若对这次唤醒毫无反应 → 判僵尸 → 重启 + 回放
            armWakeProbe();
            String m = "wake during LISTENING (downlink idle="
                    + (android.os.SystemClock.elapsedRealtime() - lastDownlinkMs)
                    + "ms, sinceTts=" + sinceTts + "ms) -> continue session, probe "
                    + wakeProbeSec + "s";
            Log.i(TAG, m);
            SessionLog.i(m);
        }
        // CONNECTING 期间的重复唤醒忽略
    }

    /**
     * v1.3.80：唤醒词隔离 —— 把「唤醒词自己被当成一句话」这条路堵死。
     *
     * 三件事：
     *   1. 丢弃 mic 的 preroll（唤醒前最近 ~1s，含完整唤醒词）；
     *   2. 清空 preWake（IDLE 唤醒会被 startSession 倒进 pending 补发）；
     *   3. 已有会话重发 listen start —— 服务器侧 VAD 归零，已经实时上行过去的
     *      唤醒词音频随之作废，用户唤醒**之后**说的话才是这一轮的开头。
     *
     * IDLE 态没有会话可重发（下面 startSession 会新建），只要丢弃缓冲即可。
     */
    private void dropWakeAudio() {
        if (!dropWakeWordAudio) {
            return;
        }
        if (mic != null) {
            mic.drainPreroll();          // 取出即丢弃：不再补发给服务器
        }
        synchronized (preWake) {
            preWake.clear();
        }
        boolean live = (ws != null && (state == State.LISTENING || state == State.TTS_PLAYING));
        if (live) {
            // v1.3.81：先让服务器收嘴（放歌/TTS 时它还在往下推音频帧，光冲本地
            // 缓冲只是「停一下又播起来」），再重发 listen start 让 VAD 归零。
            ws.sendAbort("wake_word_detected");
            ws.sendStartListening();
        }
        SessionLog.i("wake audio dropped (preroll+preWake cleared"
                + (live ? ", abort + listen re-sent)" : ")"));
    }

    //（v1.3.124：本地 VAD 猜电平的 probeVadBargeIn / bargeInByVoice 整段删除 ——
    //  那是把服务端该做的事抢到客户端来做。实时打断改由服务端 VAD 判定，
    //  客户端只负责「宣告 realtime + TTS 期照常上行 + 收到服务端的判决就闭嘴」，
    //  见 XiaoZhiWs.sendStartListening() 与下面的 serverBargeIn()。）

    /**
     * v1.3.122：**服务端判定**的实时打断（本地松手、服务器做主）。
     *
     * 时机：TTS_PLAYING 期间收到了 STT。只有在「服务器听到并认可了插话」时它才会
     * 一轮 TTS 没讲完就吐出新的识别文本 —— 这本身就是服务器打断了它自己的证据。
     *
     * 客户端要做的只有一件事：**立刻闭嘴**。AudioTrack 里最多还压着 512ms 已解码
     * 音频（24k 采样、件60ms 一帧），不清掉的话「打断」听起来是延迟半拍才停，用户
     * 会以为没打断成功；更糟的是这段残余会从喇叭漏出去被麦克风录回上行。
     *
     * 与 bargeInByVoice 的区别：这里**不再**发 sendAbort / sendStartListening —— 服务器
     * 已经自己中止了本轮并进入新的聆听，再补一脚反而把它的状态机搅乱。
     */
    private void serverBargeIn(String sttText) {
        String m = "barge-in by SERVER: stt=" + sttText + " ttsPlayed="
                + (android.os.SystemClock.elapsedRealtime() - ttsStartElapsed) + "ms";
        Log.i(TAG, m);
        SessionLog.i(m);
        TtsPlayer p = player;
        if (p != null) {
            p.abort();      // 清软件队列 + flush AudioTrack 硬件缓冲
        }
        main.removeCallbacks(farewellEndTask);
        serverFarewell = false;
        farewellActive = false;
        main.removeCallbacks(ttsStaleCheck);
        setState(State.LISTENING);
        // TTS 期间滚动的 preroll / preWake 全是这一轮的喇叭回声 ⇒ 必须丢弃。
        // 补发上去服务器听到的就是它自己刚说过的话（自环的经典入口）。
        if (mic != null) {
            mic.drainPreroll();
        }
        synchronized (preWake) {
            preWake.clear();
        }
        main.removeCallbacks(resumeUplinkTask);
        suppressUplink = false;     // 本就在上行，这里只是确保不被别的逻辑重新拉闸
        echoGateOn = false;
        echoQuietRun = 0;
        armNoTtsWatchdog(idleWindowMs());
        scheduleIdleAlert();
        SessionLog.i("barge-in by server: local playback flushed, uplink live");
    }

    /**
     * v1.3.16：硬重启会话。
     *
     * 场景：服务器已经静默结束了这一轮（不发 tts stop、也不关 ws），
     * 我们手里这条连接对服务器而言已经无效 —— 唤醒打在它上面毫无反应。
     * 做法：标 restartPending 后主动 close，close 回调进入 enterIdle 收尾时
     * 消费该标记，直接开一条全新会话（不需要用户再喊第二遍）。
     */
    private void hardRestart() {
        restartPending = true;
        // v1.3.19：无论 socket 在不在，一律走 close() —— close() 会自增连接代数
        // （让旧 socket 的全部迟到回调失效），并**自己投递 onClosed**；enterIdle
        // 消费 restartPending 直接开一条新会话。旧代码在 socket 未建立时绕过
        // close() 直接 enterIdle，会漏掉代数自增，把新会话暴露在旧回调之下。
        ws.close("wake-restart");
    }

    /**
     * v1.3.0：麦克风常驻入口（服务层在 KWS init 成功后调用一次）。
     * 复用 acquireMicLoop 的重试逻辑；若开麦最终失败，设备保持原厂持麦
     * （bus 唤醒继续兜底），KWS 静默失效 —— 与 v1.2.4 行为等价，不劣化。
     */
    public void startAlwaysOnMic() {
        alwaysOnRequested = true; // v1.3.1：先置位，acquireMicLoop 的 IDLE 守卫放行
        micHandler.post(new Runnable() {
            public void run() {
                Log.i(TAG, "always-on mic: acquiring for KWS duty");
                acquireMicLoop();
            }
        });
    }

    // ---------------- 会话启动 / 收尾 ----------------

    /**
     * v1.2.4：会话开始 = 开麦 + 连 ws，两路并行。
     * ws 握手约 1-2s，通常麦在握手完成前就已到手。
     */
    private void startSession() {
        // v1.3.1：唤醒前的音频尾巴（含唤醒词+紧跟的话）先倒进 pending，
        // 与 CONNECTING 期帧一起在握手后补发 —— 服务器一次听到完整utterance。
        // v1.3.19：若本次启动是「响应探测判僵尸」触发的，改倒**影子音频** —— 那是
        // 用户在这声唤醒之后真正说过、并且已经发给那具僵尸会话的话；回放给新会话，
        // 用户不用重说。
        boolean useShadow = replayShadow;
        replayShadow = false;
        java.util.ArrayList<byte[]> tail = new java.util.ArrayList<byte[]>();
        if (useShadow) {
            synchronized (shadowBuf) {
                tail.addAll(shadowBuf);
                shadowBuf.clear();
            }
        } else {
            synchronized (preWake) {
                tail.addAll(preWake);
                preWake.clear();
            }
        }
        shadowPeak = 0;
        if (!tail.isEmpty()) {
            synchronized (pending) {
                for (byte[] f : tail) {
                    if (pending.size() >= MAX_PENDING_FRAMES) {
                        break;
                    }
                    pending.add(f);
                }
            }
            String m = (useShadow ? "probe replay: " : "prewake tail queued: ")
                    + tail.size() + " frames (" + (tail.size() * 60) + "ms)";
            Log.i(TAG, m);
            SessionLog.i(m);
        }
        // v1.3.20：本会话人声取证归零
        sessionMaxRms = 0;
        sessionVoicedFrames = 0;
        // v1.3.129：人声触发放行窗口归零（新会话不该继承上一轮的放行）
        bargeVoiceUntilMs = 0L;
        bargeVoiceTriggers = 0;
        bargeVoiceLastRaw = 0;
        // v1.3.126：raw/up 双域统计归零
        sessRawMax = 0;
        sessRawTtsMax = 0;
        sessRawVoiceMax = 0;
        sessRawTtsSum = 0L;
        sessRawTtsN = 0;
        sessRawVoiceSum = 0L;
        sessRawVoiceN = 0;
        sessUpTtsSum = 0L;
        sessUpVoiceSum = 0L;
        rawProbeLastMs = 0L;
        // v1.3.47：新会话从干净状态开始（残留闸门会把开局的头几帧压掉）
        echoGateOn = false;
        echoQuietRun = 0;
        // v1.3.23：新会话的「上次人声」从现在算起 —— 刚开的会话绝不能被误判成告别
        lastUserVoiceMs = android.os.SystemClock.elapsedRealtime();
        // v1.3.24：告别判定的新基准也要归零，否则**新会话的第一条 TTS** 会拿着
        // 上一条会话留下的 lastTtsStopMs 算出「静默 5 分钟」→ 被当成告别语提前关掉。
        lastTtsStopMs = android.os.SystemClock.elapsedRealtime();
        sttThisTurn = false;
        serverFarewell = false;
        main.removeCallbacks(farewellEndTask);
        farewellActive = false;
        setState(State.CONNECTING);
        synchronized (uplinkDump) {
            uplinkDump.clear(); // v1.3.3：dump 只覆盖本会话
        }
        Log.i(TAG, "session start -> CONNECTING (mic acquire + ws connect in parallel)");
        SessionLog.i("session start -> CONNECTING");
        micHandler.post(new Runnable() {
            public void run() {
                acquireMicLoop();
            }
        });
        ws.connect();
        armNoTtsWatchdog();
    }

    /**
     * 会话期开麦：快速重试直到拿到麦 / 超时 / 会话结束。
     *
     * 竞争背景：唤醒瞬间原厂自己也会开 ASR 录音（uni4micHalJNI 直开设备），
     * 我们的 AudioRecord 此刻会被挤开。实测它的云端 ASR ~1s 内报
     * 609 放手（云已死），所以重试很快就能接管。若原厂哪天云端复活，
     * 它会话期间我们也插不进来 —— 那是明确的"让原厂先说话"语义，可接受。
     */
    private void acquireMicLoop() {
        if (mic.isRunning()) {
            Log.i(TAG, "mic already running, skip acquire");
            return;
        }
        // v1.3.1：常驻模式下 IDLE 也允许进循环（否则 attempts=0 直接放弃）
        final boolean persist = alwaysOnRequested;
        long deadline = android.os.SystemClock.elapsedRealtime() + MIC_DEADLINE_MS;
        int attempt = 0;
        while ((persist || state != State.IDLE)
                && android.os.SystemClock.elapsedRealtime() < deadline) {
            attempt++;
            if (tryStartMic(MIC_ATTEMPT_WATCHDOG_MS)) {
                if (persist) {
                    Log.i(TAG, "always-on mic ACQUIRED (attempt=" + attempt
                            + ") -> KWS on duty, unisound starved");
                    SessionLog.i("always-on mic ACQUIRED (attempt=" + attempt + ")");
                } else {
                    Log.i(TAG, "mic ACQUIRED (attempt=" + attempt
                            + ") -> unisound KWS paused, session owns the mic");
                    SessionLog.i("mic ACQUIRED (attempt=" + attempt + ")");
                }
                return;
            }
            if (!persist && state == State.IDLE) {
                break;
            }
            String m = "mic acquire attempt " + attempt
                    + " failed (device busy? unisound ASR holding) -> retry in "
                    + MIC_RETRY_INTERVAL_MS + "ms";
            Log.i(TAG, m);
            SessionLog.i(m);
            try {
                Thread.sleep(MIC_RETRY_INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
        String g = "mic acquire gave up (state=" + state + " attempts=" + attempt
                + ") -- session continues without mic, no TTS watchdog will clean up";
        Log.w(TAG, g);
        SessionLog.i(g);
        // v1.3.1：常驻模式开麦失败不自弃 —— 30s 后再试一轮（USC 可能正在
        // 播报占着 input；等它放手）。KWS 无人喂期间 bus 唤醒兜底。
        if (persist) {
            micHandler.postDelayed(alwaysOnRetry, 30000L);
        }
    }

    /** 常驻麦自愈重试（gave-up 后 30s 再来一轮；mic 已活时 acquireMicLoop 直接跳过）。跑在 micHandler。 */
    private final Runnable alwaysOnRetry = new Runnable() {
        public void run() {
            if (alwaysOnRequested && !mic.isRunning()) {
                Log.i(TAG, "always-on mic retry");
                acquireMicLoop();
            }
        }
    };

    /**
     * 单次开麦尝试，带看门狗。
     * 卡死的尝试线程（binder 里）留在原地，无害 —— v1.2.3 已验证该模式。
     * AudioCapture.start() 内部有 generation 否决：stop() 发生在卡死期间时，
     * 迟到的 start 会自我了断，不会复活成僵尸录音。
     */
    private boolean tryStartMic(final long timeoutMs) {
        final AtomicBoolean done = new AtomicBoolean(false);
        final AtomicBoolean ok = new AtomicBoolean(false);
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    ok.set(mic.start());
                } catch (Throwable tr) {
                    Log.e(TAG, "mic.start threw", tr);
                } finally {
                    done.set(true);
                }
            }
        }, "mic-acquire");
        t.setDaemon(true);
        t.start();
        try {
            t.join(timeoutMs);
        } catch (InterruptedException ignored) {
        }
        if (t.isAlive()) {
            Log.e(TAG, "mic.start TIMEOUT after " + timeoutMs + "ms (binder stuck)");
            return false;
        }
        return ok.get();
    }

    /**
     * 回到 IDLE —— 会话收尾。
     * 关键动作：release 麦还给原厂 KWS（「小讯小讯」要靠它继续活着）。
     * 麦操作投到串行线程，与开麦循环天然排队，不会交错。
     */
    private void enterIdle() {
        main.removeCallbacks(noTtsWatchdog);
        main.removeCallbacks(idleAlertTask);   // v1.3.15：会话已结束，撤掉待播的断开提醒
        main.removeCallbacks(ttsStaleCheck);   // v1.3.16：TTS 静默看门狗一并撤
        main.removeCallbacks(wakeProbeTask);   // v1.3.19：唤醒响应探测一并撤
        probeArmed = false;
        main.removeCallbacks(farewellEndTask); // v1.3.23：告别收尾一并撤
        serverFarewell = false;
        farewellActive = false;
        boolean wasSession = (state != State.IDLE);
        if (wasSession) {
            // v1.3.20：一行定性「这次没回复到底怪谁」——
            // voicedFrames=0 / maxRms≈底噪 ⇒ 麦没听到人说话，客户端无过。
            String vs = "session end voice:" + voiceStats()
                    + " (voicedFrames=0 & maxRms 低 = 全程没人说话)";
            Log.i(TAG, vs);
            SessionLog.i(vs);
        }
        // v1.3.101：60s 模式抢在服务器告别之前挂断，服务器没机会开口 ⇒ 这里补一声
        // （语义见 scheduleIdleAlert；90s 模式 cueOnDisconnect=false，本地不出声）。
        //
        // v1.3.149：★ restartPending 时必须跳过 —— 那条路径不是「掉线」，是
        // **唤醒触发的会话硬重启**（wake probe 判定服务器已死 → close("wake-restart")
        // → enterIdle → 下一毫秒就 restart 开新会话）。
        //   实测（2026-10-13 23:58）：用户喊「小智小智」后 3s，服务器没回，客户端硬重启，
        //   enterIdle 顺手把「断连提示音」播了出来 —— 用户体感就是「喊完小智就响断开音」。
        //   真掉线（no-tts-timeout / 60s 自动断开）restartPending=false，照旧出声。
        if (cueOnDisconnect && wasSession) {
            cueOnDisconnect = false;
            if (restartPending) {
                SessionLog.i("disconnect cue skipped (会话正在硬重启，不是掉线)");
            } else {
                SessionLog.i("disconnect cue played (会话空闲挂断)");
                main.post(new Runnable() {
                    public void run() {
                        if (!soundOff()) {
                            playLocalCue();
                        }
                    }
                });
            }
        }
        setState(State.IDLE);
        suppressUplink = false;
        clearPending();
        // v1.3.147：会话收尾 ⇒ 灯全灭（唤醒后没人说话时拾音灯会一直亮着，这里兜底）。
        R1Lights.allOff();
        // v1.3.130：会话收尾兜底 —— 万一 TTS stop 那次没接回（例如用户中途接话把
        // 排队的恢复撤了），会话真的结束时就补上。
        MusicResume.onTurnEnded("session-end");
        // v1.3.16：preWake 不再清空 —— 常驻麦下它现在是「一直滚动的最近 1.2s」，
        // 保留下来，唤醒触发硬重启时才能把「唤醒词 + 紧跟的话」带进新会话。
        // v1.3.0：麦常驻（KWS 唤醒岗）时会话结束不再还麦 —— IDLE 期继续喂 KWS。
        // 原厂引擎断粮，错误提示/总线唤醒均不会再出现；静音罩保留为防御逻辑。
        // v1.3.8：仅当 KWS 引擎真实在岗才持麦 —— bus-only / micprobe 模式下
        // kws==null，继续持麦 = 纯占坑（输入流被握住、小讯聋、且无人消费）。
        if (MIC_ALWAYS_ON && kws != null) {
            if (mic.isRunning()) {
                Log.i(TAG, "mic KEPT (always-on, KWS on duty)");
                SessionLog.i("mic KEPT (always-on, KWS on duty)");
            }
        } else if (mic.isRunning()) {
            micHandler.post(new Runnable() {
                public void run() {
                    try {
                        mic.stop();
                        Log.i(TAG, "mic RELEASED -> unisound KWS back on duty");
                        SessionLog.i("mic RELEASED -> unisound KWS back on duty");
                    } catch (Throwable t) {
                        Log.e(TAG, "mic.stop threw", t);
                        SessionLog.i("mic.stop threw: " + t);
                    }
                }
            });
        }
        if (wasSession) {
            Log.i(TAG, "back to IDLE");
            SessionLog.i("back to IDLE");
            writeUplinkDump(); // v1.3.3：会话结束落盘本会话上行 PCM
            // v1.3.16：若是「唤醒触发的硬重启」，马上要开新会话，绝不能把媒体
            // 流静音罩上（否则新一轮 TTS 直接哑掉）—— 静音罩只给真收会话用。
            if (!restartPending) {
                // v1.2.15：还麦后 ~5.6s USC 播「网络连接失败，请检查」错误提示，
                // 静音罩住；唤醒或 20s 定时先到者解除。
                main.removeCallbacks(uscUnmute);
                if (!uscMuted) {
                    setMediaMute(true, "usc error prompt cover");
                }
                main.postDelayed(uscUnmute, USC_PROMPT_MUTE_MS);
            }
        }

        // ===== v1.3.16：唤醒打在了僵尸会话上 → 不停 IDLE，立刻开一条新会话 =====
        if (restartPending) {
            restartPending = false;
            Log.i(TAG, "restart pending -> starting fresh session");
            SessionLog.i("restart pending -> starting fresh session");
            startSession();
        }
    }

    // ---------------- 上行 ----------------

    /**
     * v1.3.127 ★★ TTS 期是否放行上行（barge-in）—— 唯一定义处，标签与发送判定共用。
     *
     * 旧逻辑只看 `bargeInUplink && !echoDisabled`（v1.2.16 起），于是 TTS 全程上行。
     * 但实测（2026-10-12，v1.3.126 双域探针 diag/probe1.log）证明 TTS 期不是铁板一块：
     *   · 轨里有料 → HAL 回声抑制在线 → 泄漏 raw 中位只有 4，与人声(raw 52~112)差 20 倍，安全；
     *   · 轨排空/欠载（TTS stop 前 ~400ms，5/5 轮实测）→ 抑制失配 → 泄漏 raw 44~144，
     *     与人声完全重叠，ALC 再放大 10x ⇒ 服务器 VAD 必判插话 ⇒ 打断小智自己。
     * 所以这里补一道「播放流饥饿」闸门：只在播放流确实活着时才放行。
     * 代价：饿的那几百毫秒内用户插话会被吞（TTS stop 到达后立刻恢复，实测 <1s）。
     *
     * ★ v1.3.128 上机复测（2026-10-12 diag/probe2.log，v1.3.127）：闸门方向对、判据错。
     *   v1.3.127 的 isStreamStarving() 里 `if (!queue.isEmpty()) return false;` 让
     *   「队列非空」= 「不饿」—— 但队列只是网络节奏，和播放轨水位不是一回事。
     *   实测 12 条泄漏帧（raw 34~88，**全部**落在 TTS 段末"音频发完等 stop"的窗口）
     *   仍被标成 TTS（放行），而真正被挡住的 68 条（TTS-sup）raw 中位 4 / max 16。
     *   ⇒ 已改为只认播放轨水位 + 500ms 饥饿冷却（见 TtsPlayer.isStreamStarving）。
     *   旁证：四轮 TTS 的播放帧数 30/28/27/34 与回复文本长度（29/21/10/13 字）**无关**，
     *   都卡在 ~1.8s ⇒ 服务端每轮都在中途把 TTS 掐了（它被上方泄漏帧触发了 VAD）。
     */
    private boolean bargeInAllowed() {
        if (state != State.TTS_PLAYING || !bargeInUplink || echoDisabled) {
            return false;
        }
        // ★ v1.3.129：不再看播放轨水位，只认「刚检测到用户在说话」这一个事实。
        //   理由见 bargeVoiceRaw 上方注释：TTS 期底色 p50=6（AEC 在线、无持续泄漏），
        //   真正需要挡的是散点，而散点的上限(88)与真人插话的下限(93)分得开。
        return android.os.SystemClock.elapsedRealtime() < bargeVoiceUntilMs;
    }

    @Override
    public void onFrame(byte[] data, int len, byte[] stereo) {
        // KWS 永远要喂（即使 TTS 播放中，以支持 barge-in）
        // 若走「纯总线唤醒」模式，kws 为空实现，这里等同 no-op
        if (kws != null) {
            kws.feed(data, len);
        }

        // v1.3.20 / v1.3.22：唤醒应答音回声窗口。micDropUntilMs 默认 0
        // → 判定恒假、**一帧不丢**（硬丢会剪断连读的时间线，见 wakeAckEchoDropMs 注释）。
        if (micDropUntilMs != 0L
                && android.os.SystemClock.elapsedRealtime() < micDropUntilMs) {
            return;
        }

        // v1.3.20：本会话人声取证（放在丢帧判定之后，统计的是「真有机会上行」的帧）
        int rmsNow = (state == State.IDLE) ? 0 : frameRms(data, len);
        if (state != State.IDLE) {
            if (rmsNow > sessionMaxRms) {
                sessionMaxRms = rmsNow;
            }
            if (rmsNow > VOICE_FLOOR_RMS) {
                sessionVoicedFrames++;
                // v1.3.23：只把「LISTENING 且上行已放行」的人声当作「用户说话」——
                // 排除 TTS 播放回声与 TTS 尾巴的恢复窗口，避免告别判定被喇叭声骗过。
                if (state == State.LISTENING && !suppressUplink && !farewellActive) {
                    lastUserVoiceMs = android.os.SystemClock.elapsedRealtime();
                }
            }
        }

        // v1.3.124：此处原本是 v1.3.121 的「本地 VAD 猜电平 → 本地 abort」，已删除。
        // v1.3.127 实测修正（2026-10-12 diag/probe1.log，raw/up 双域探针，v1.3.126 采集）：
        //   · TTS 轨里有料时      泄漏 raw 中位 **4**（=安静本底）⇒ HAL 抑制在线，与人声差 20 倍；
        //   · TTS stop 前 ~400ms  轨排空 ⇒ 抑制失配 ⇒ 泄漏 raw 44~144，**与人声 52~112 重叠**。
        // 即「电平不可分」只对**饥饿窗口**成立，不是 TTS 全程 —— 客户端只需在饥饿窗口闸住上行
        // （bargeInAllowed），其余照常上行交给服务端 VAD（listen.mode=realtime）判插话。
        int rawRms = (mic != null) ? mic.rawFrameRms() : rmsNow;
        noteRawLevel(rawRms);

        // ★ v1.3.129：TTS 期「人声触发」—— raw 冲过门限即打开 1.5s 放行窗口。
        //   放在探针之前，保证本帧的标签与实际发送判定同源。
        //   注意：这里判 raw（增益前），与探针的 raw= 是同一个数；用 up（ALC 后）
        //   会让门限随 ALC 增益漂移，无法标定。
        if (state == State.TTS_PLAYING && bargeInUplink && !echoDisabled
                && rawRms >= bargeVoiceRaw) {
            long tnow = android.os.SystemClock.elapsedRealtime();
            if (tnow >= bargeVoiceUntilMs) {
                bargeVoiceTriggers++;
                SessionLog.i("barge-trigger #" + bargeVoiceTriggers
                        + " raw=" + rawRms + " (>= " + bargeVoiceRaw + ")"
                        + " -> uplink open " + BARGE_VOICE_HOLD_MS + "ms");
            }
            bargeVoiceLastRaw = rawRms;
            bargeVoiceUntilMs = tnow + BARGE_VOICE_HOLD_MS;
        }

        // ===== v1.3.126：raw/up 双域探针（只测量，不改任何行为）=====
        // raw = ALC/固定增益**之前**的原始电平（AudioCapture.rawFrameRms）
        // up  = ALC 之后、**服务端实际收到**的电平（frameRms(data,len)）
        // 分三个状态分别统计，会话结束时由 voiceStats() 汇总成 ratio。
        if (state != State.IDLE) {
            if (rawRms > sessRawMax) {
                sessRawMax = rawRms;
            }
            if (state == State.TTS_PLAYING) {
                if (rawRms > sessRawTtsMax) {
                    sessRawTtsMax = rawRms;
                }
                sessRawTtsSum += rawRms;
                sessRawTtsN++;
                sessUpTtsSum += rmsNow;
            } else if (state == State.LISTENING && !suppressUplink) {
                if (rawRms > sessRawVoiceMax) {
                    sessRawVoiceMax = rawRms;
                }
                sessRawVoiceSum += rawRms;
                sessRawVoiceN++;
                sessUpVoiceSum += rmsNow;
            }
            long rnow = android.os.SystemClock.elapsedRealtime();
            if (rawProbeLastMs == 0L || rnow - rawProbeLastMs >= RAW_PROBE_MS) {
                rawProbeLastMs = rnow;
                String st = (state == State.TTS_PLAYING)
                        ? (bargeInAllowed() ? "TTS-voice" : "TTS-sup")
                        : (state == State.LISTENING ? (suppressUplink ? "LIS-sup" : "LIS") : "oth");
                int bms = (player != null) ? player.bufferMsNow() : -2;
                String pm = "raw probe " + st + " raw=" + rawRms + " up=" + rmsNow
                        + " buf=" + bms;
                Log.i(TAG, pm);
                SessionLog.i(pm);
            }
        }

        // ===== v1.3.47：上行回声闸门 =====
        // 必须放在 suppressUplink 判定**之前**求值：否则被 suppress 短路 return 后，
        // 这里的「安静确认」永远跑不起来，闸门就再也没机会开。
        // 详见 echoGateEnabled 字段注释（自环根因 + 电平判据）。
        // v1.3.48：加"最小强制压制期"（echoGateHoldMs）—— 实测 TTS 播放期 mic 本来
        // 就被 HAL 压到 77~430（假安静），所以"安静确认"必须在压满之后才算数。
        if (echoGateOn) {
            long gnow = android.os.SystemClock.elapsedRealtime();
            long gheld = gnow - echoGateArmedMs;
            if (state == State.IDLE) {
                echoGateOn = false;   // 会话没了，闸门无意义
                echoQuietRun = 0;
            } else if (gheld < echoGateHoldMs) {
                // v1.3.48：强制压制期 —— 不管 mic 多安静都不做确认（播放期/刚停时
                // 的"安静"是 HAL 回声抑制造成的假象，不是喇叭真停了）。
                echoQuietRun = 0;
            } else if (rmsNow < echoRms) {
                if (++echoQuietRun >= echoClearFrames) {
                    releaseEchoGate("quiet " + echoQuietRun + " frames @held" + gheld + "ms");
                }
            } else {
                echoQuietRun = 0;     // 又听到喇叭 -> 重新确认
            }
            if (echoGateOn && gheld >= echoGateMaxMs) {
                releaseEchoGate("timeout " + echoGateMaxMs + "ms (mic still loud)");
            }
            if (echoGateOn) {
                // v1.3.48：闸门内取证 —— 每 500ms 记一次电平。判据：
                //   压制期全程 rms≈80~430 且松开后也安静 → HAL 抑制在线（ttshold 生效）
                //   压制期后段 rms 跳到 500~1500          → 抑制退线（自环燃料仍在）
                if (gnow - echoProbeLastMs >= 500L) {
                    echoProbeLastMs = gnow;
                    String pm = "echoGate probe rms=" + rmsNow + " held=" + gheld
                            + "ms quietRun=" + echoQuietRun;
                    Log.i(TAG, pm);
                    SessionLog.i(pm);
                }
                return;   // 闸门内：喇叭还在响，本帧不上行
            }
        }

        // v1.2.4：CONNECTING（开麦~握手完成）期间的音频先进 pending，
        // 握手完成后统一补发 —— 用户张口早于握手也不丢字
        if (state == State.CONNECTING) {
            stashPending(data, len, stereo);
            probeStereo(stereo); // v1.3.5：开局原始 stereo 取证
            return;
        }

        // v1.3.1 / v1.3.16：常驻麦下滚动缓冲最近 1.2s（含唤醒词本身 + 紧跟的话），
        // 唤醒 -> startSession 时倒进 pending，握手后一并补发 —— 「唤醒即说话」。
        // v1.3.16：IDLE 之外（LISTENING / TTS_PLAYING）也继续滚动 —— 唤醒若打在
        // 僵尸会话上触发硬重启，这段尾巴才有东西可带进新会话。
        if (alwaysOnRequested) {
            synchronized (preWake) {
                if (preWake.size() >= prewakeFrames) {
                    preWake.pollFirst(); // 环形：保尾巴丢旧头
                }
                byte[] copy = new byte[len];
                System.arraycopy(data, 0, copy, 0, len);
                preWake.addLast(copy);
            }
            if (state == State.IDLE) {
                return;   // IDLE 期只喂 KWS + 滚缓冲，不上行
            }
        }

        // v1.2.16 / v1.3.16：TTS 播放中放行上行（服务器 VAD 打断）由 bargeInUplink
        // 控制 —— 默认关（R1 无软件 AEC，播放中上行 = 把喇叭声喂回服务器）；熔断
        // （echoDisabled）后同样回退抑制；其余非 LISTENING 状态照旧丢弃。
        // v1.3.127：**再加一道播放流饥饿闸门** —— 见 bargeInAllowed()。TTS stop 前
        // ~400ms 播放轨排空 ⇒ HAL 回声抑制失配 ⇒ 泄漏 raw 44~144 与人声 52~112 重叠，
        // 这些帧上行必被服务端当成插话（这就是「被小智自己的声音打断」）。
        boolean bargeIn = bargeInAllowed();
        if (suppressUplink || (state != State.LISTENING && !bargeIn)) {
            return;
        }
        // v1.3.2：补发进行中实时帧改道排队（服务器端时间线单调，防交错）
        if (flushing) {
            stashPending(data, len, stereo);
            return;
        }
        dumpUplinkPcm(data); // v1.3.3：自证 dump（只记真正上行的帧）
        if (probeArmed) {
            shadowAdd(data, len); // v1.3.19：探测窗口内的上行影子（僵尸重启后回放）
        }
        probeStereo(stereo); // v1.3.5：原始 stereo 取证（L/R 动死判定）
        byte[] pkt = opus.encode(data, len);
        if (pkt != null) {
            if (ws.sendAudio(pkt) && latFirstUplinkMs == 0L && wakeMs > 0L) {
                latFirstUplinkMs = android.os.SystemClock.elapsedRealtime();
                long d = latFirstUplinkMs - wakeMs;
                Log.i(TAG, "lat: wake->first-uplink = " + d + "ms");
                SessionLog.i("lat: wake->first-uplink = " + d + "ms");
            }
        }
    }

    private void stashPending(byte[] data, int len, byte[] stereo) {
        synchronized (pending) {
            if (pending.size() >= MAX_PENDING_FRAMES) {
                return; // 满 15s：丢新帧（保留最初的话更有价值）
            }
            byte[] copy = new byte[len];
            System.arraycopy(data, 0, copy, 0, len);
            pending.add(copy);
            // v1.3.6：stereo 平行入队（同锁同步，保序；probe 取证用）
            stereoPending.add(stereo == null ? null : stereo.clone());
        }
    }

    /**
     * 握手完成：把 CONNECTING 期攒的音频补发上行。
     * v1.3.2 根因修复：突发灌入（71 帧 / 几十 ms）会把官方服务器的 opus
     * 解码/VAD 时序打坏 —— 实测 09:36/09:37/09:44 三连会话全沉默，且突发后
     * 的实时语音（用户连续说话）也无人识别 = 解码状态被污染。改为 60ms/帧
     * 实时速率匀速补发，与 ESP32 真实时流行为一致。
     */
    private void flushPending() {
        synchronized (pending) {
            if (pending.isEmpty()) {
                Log.i(TAG, "flush pending: empty (mic opened after handshake?)");
                SessionLog.i("flush pending: empty");
                return;
            }
        }
        flushing = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                pacedFlushLoop();
            }
        }, "pending-flush");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 补发循环：跑独立线程（绝不睡 WS 回调线程，否则下行全堵）。
     * 队列追平即放行实时上行；期间实时帧经 onFrame 的 flushing 分支排队，
     * 顺序天然 FIFO。若 TTS 先到（服务器响应快于积压排空），积压已是
     * 过时音频，直接丢弃。
     *
     * v1.3.18：由「严格 60ms/帧匀速」改为**自适应追赶** —— 旧的匀速恰等于麦克风
     * 产出速率，导致队列永不排空、服务器被永久甩在 backlog 之后（详见
     * prewakeFrames 字段注释）。现在按积压深度最多 2~3 帧/60ms。
     */
    private void pacedFlushLoop() {
        int sent = 0;
        int maxBacklog = 0;
        while (state == State.LISTENING) {
            // ===== v1.3.18：自适应追赶 =====
            // 旧实现 60ms/帧（1.0x）**严格等于**麦克风产出速率 —— 补发期间实时帧
            // 又在往 pending 里排（onFrame 的 flushing 分支），输入 = 输出 →
            // 队列永远排不空、flushing 永远不复位，服务器被永久甩在 backlog 之后。
            // 现在按积压深度一次多发几帧：2 帧/60ms=2x、3 帧/60ms=3x。单位仍是
            // 「帧/60ms」的温和倍速，与 v1.3.2 踩过的「71 帧几十 ms 突发」(~1000x)
            // 不是一个量级，服务器 jitter buffer 吸收得了。
            // 阈值要点：1x 是「输入=输出」的死区，积压会永远停在阈值上不动 ——
            // 所以 burst=2 的门槛必须压到很小（>2 帧），让队列被真正排空到 ~1 帧
            // （60ms）以内；burst=3 只在积压 >12 帧（>0.7s）时用来快速消肿。
            int burst = 1;
            synchronized (pending) {
                int n = pending.size();
                if (n > maxBacklog) {
                    maxBacklog = n;
                }
                if (n > 12) {
                    burst = 3;
                } else if (n > 2) {
                    burst = 2;
                }
            }
            boolean drainedAll = false;
            for (int i = 0; i < burst; i++) {
                byte[] f;
                byte[] st;
                synchronized (pending) {
                    f = pending.isEmpty() ? null : pending.remove(0);
                    st = stereoPending.isEmpty() ? null : stereoPending.remove(0);
                }
                if (f == null) {
                    drainedAll = true; // 队列追平
                    break;
                }
                dumpUplinkPcm(f); // v1.3.3：补发帧同样入 dump
                if (st != null) {
                    probeStereo(st); // v1.3.6：补发帧 stereo 也取证
                }
                byte[] pkt = opus.encode(f, f.length);
                if (pkt != null) {
                    ws.sendAudio(pkt);
                }
                sent++;
            }
            if (drainedAll) {
                break;
            }
            try {
                Thread.sleep(60L);
            } catch (InterruptedException e) {
                break;
            }
        }
        if (state != State.LISTENING) {
            synchronized (pending) {
                pending.clear();
                stereoPending.clear(); // v1.3.6：平行队列同清
            }
        }
        // v1.3.18：原子清尾巴 + 复位 flushing。循环退出与 flushing=false 之间有
        // 一个窗口，onFrame 看到 flushing 仍为 true 会把新帧 stash 进来 —— 不复位
        // 就丢 60ms，或在 pending 里躺到下次会话。旧版因为队列永不排空、根本不会
        // 走到这里，所以没有这个问题；新版能真正排空，必须补上。
        synchronized (pending) {
            while (!pending.isEmpty()) {
                byte[] f = pending.remove(0);
                byte[] st = stereoPending.isEmpty() ? null : stereoPending.remove(0);
                dumpUplinkPcm(f);
                if (st != null) {
                    probeStereo(st);
                }
                byte[] pkt = opus.encode(f, f.length);
                if (pkt != null) {
                    ws.sendAudio(pkt);
                }
                sent++;
            }
            flushing = false;
        }
        String m = "paced flush done: sent " + sent + " frames ("
                + (sent * 60) + "ms audio), maxBacklog=" + maxBacklog
                + " frames (" + (maxBacklog * 60) + "ms backlog cap)";
        Log.i(TAG, m);
        SessionLog.i(m);
    }

    private void clearPending() {
        synchronized (pending) {
            pending.clear();
            stereoPending.clear(); // v1.3.6：平行队列同清
        }
    }

    /** barge-in 用：补发 TTS 抑制窗口内的预缓冲（v1.2.3 语义保留） */
    private void flushPreroll() {
        byte[] pre = mic.drainPreroll();
        if (pre.length == 0) {
            return;
        }
        int frames = pre.length / AudioCapture.FRAME_BYTES;
        Log.i(TAG, "flush preroll " + frames + " frames (" + (frames * 60) + "ms)");
        for (int i = 0; i < frames; i++) {
            byte[] f = new byte[AudioCapture.FRAME_BYTES];
            System.arraycopy(pre, i * AudioCapture.FRAME_BYTES, f, 0, AudioCapture.FRAME_BYTES);
            byte[] pkt = opus.encode(f, AudioCapture.FRAME_BYTES);
            if (pkt != null) {
                ws.sendAudio(pkt);
            }
        }
    }

    // ---------------- WS 回调 ----------------

    @Override
    public void onConnected() {
        Log.i(TAG, "ws connected");
    }

    @Override
    public void onHandshakeDone() {
        // ===== v1.3.103：拒绝「无人等待的握手」把伪会话挂进 LISTENING =====
        // 现象（2026-10-09 23:5x～00:0x 实机三次复现，完全可重置）：
        //   00:00:58 web 控制台切服务器 → restartWs() 立刻 reconnect
        //   00:00:59.766 handshake done -> LISTENING      ← 此时根本没有会话
        //   00:01:07 用户喊「小智小智」→ state=LISTENING → 走「会话已存在」分支
        //            → 不发 session start、不开麦 → 上行零帧 → 服务器听不见
        //   00:02:07 no TTS within 60s → 伪会话才被收 → 00:02:41 再喊才真正能用
        // 根因：握手回调无条件 setState(LISTENING)，而 restartWs 纯为了探测地址
        // 连通性先连了 socket —— 两者叠加，就把 IDLE 强行伪造成了一个"活会话"。
        // 用户体感正是「切换服务器后聋一阵，放着不管几分钟才好，重启立刻好」。
        // ⇒ IDLE 期的握手一律视作连通性探测：确认可达后立即拆掉，状态机留干净。
        if (state == State.IDLE) {
            String m = "handshake while IDLE (server probe, no session waiting)"
                    + " -> keep IDLE, close probe socket; next wake = fresh session";
            Log.w(TAG, m);
            SessionLog.i(m);
            ws.close("ipp-probe-done");
            return;
        }
        Log.i(TAG, "handshake done -> LISTENING");
        SessionLog.i("handshake done -> LISTENING");
        touchDownlink();   // v1.3.16：会话存活的第一个时间戳
        setState(State.LISTENING);
        suppressUplink = false;
        ws.sendStartListening();
        // v1.3.21：唤醒词上报（官方 Protocol::SendWakeWordDetected 同名动作）。
        // 只在新会话上报一次；默认关（见 detectEnabled 注释 —— 服务器会因此
        // 静默丢弃接下来 2s 的上行音频，连读会被吞）。
        String wr = pendingWakeReport;
        pendingWakeReport = null;
        if (wr != null && wr.length() > 0) {
            ws.sendWakeWordDetected(wr);
            String wm = "wake word reported (listen/detect text=\"" + wr + "\")";
            Log.i(TAG, wm);
            SessionLog.i(wm);
        }
        // v1.3.14：延迟埋点 —— 唤醒 -> 握手完成（含 WS 建连 + hello 往返）
        if (wakeMs > 0L) {
            long d = android.os.SystemClock.elapsedRealtime() - wakeMs;
            Log.i(TAG, "lat: wake->handshake = " + d + "ms");
            SessionLog.i("lat: wake->handshake = " + d + "ms");
        }
        // 把开麦~握手之间的语音补上（此刻 hello 握手已完成，可以直接推流）
        flushPending();
        armNoTtsWatchdog();
    }

    @Override
    public void onTtsStart() {
        Log.i(TAG, "TTS start");
        SessionLog.i("TTS start (heard:" + voiceStats() + ")");   // v1.3.20：顺手记下本轮麦看到了什么
        // v1.3.144：服务器真的开口了 ⇒ 之后开播的帧都是「小智在说话」，
        // 让路可以在首帧那一刻执行（应答音不在此列，见 MusicResume.inWakeCue()）。
        MusicResume.noteServerTts();
        main.removeCallbacks(noTtsWatchdog);
        main.removeCallbacks(idleAlertTask);   // v1.3.15：服务器开口了 = 已提醒，撤掉本地提示
        disarmWakeProbe();                     // v1.3.19：服务器有反应了，探测解除
        clearShadow();
        touchDownlink();                       // v1.3.16
        long nowEl = android.os.SystemClock.elapsedRealtime();
        // v1.3.23：**空闲告别识别**。服务器不会无缘无故主动开口 —— 上一次人声
        // 已经过去 farewellIdleSec 秒它才说话，那几乎必然是它在念告别语，而官方
        // 服务器念完告别就不理会这条会话了（18:19 实测）。这里先标记，等
        // onTtsStop 播完再关会话（不让用户听半句被切断）。
        main.removeCallbacks(farewellEndTask);
        farewellActive = false;
        // v1.3.24：静默基准从「麦克风最后人声」换成「距上一次 tts stop 多久」——
        // **不再吃麦克风噪声**。v1.3.23 用 lastUserVoiceMs 判定实测失效：
        // 2026-10-04 18:44 那条「好像没人应我，那我先休息啦」本该判告别却没判 ——
        // micTelem 的 1 秒 RMS 全程只有 214~292（远低于门限 400），看着确实没人说话，
        // 但那是**秒级平均**；逐帧统计里偶发的单帧脉冲（房噪/风扇/喇叭残响）只要有一帧
        // 超过 400，就会把 lastUserVoiceMs 顶到「刚刚」→ quietMs 永远凑不满 35s。
        // 换成服务器自己的时序后与音频完全无关。
        // 真正的守护依旧是 sttThisTurn：STT 必然先于 TTS 到达（实测 18:43:32.762 STT
        // -> 18:43:32.776 tts start），所以「本轮没有 STT」等价于「服务器在自言自语」。
        long sinceTtsStop = (lastTtsStopMs > 0L) ? (nowEl - lastTtsStopMs) : -1L;
        long quietMs = (lastUserVoiceMs > 0L) ? (nowEl - lastUserVoiceMs) : -1L;
        if (!sttThisTurn && sinceTtsStop >= farewellIdleSec * 1000L) {
            serverFarewell = true;
            String fm = "TTS start with NO stt this turn, " + (sinceTtsStop / 1000)
                    + "s after last tts stop (mic quiet " + (quietMs / 1000)
                    + "s) -> treated as server idle-farewell"
                    + " (session ends after playback)";
            Log.i(TAG, fm);
            SessionLog.i(fm);
        } else {
            serverFarewell = false;
        }
        lastTtsFrameMs = nowEl;                // v1.3.16：TTS 静默看门狗起点
        main.removeCallbacks(ttsStaleCheck);
        main.postDelayed(ttsStaleCheck, 2000L);
        setState(State.TTS_PLAYING);
        ttsStartElapsed = nowEl;
        latFirstTtsMs = 0L;   // v1.3.14：新一轮 TTS 重新计首帧
        // v1.3.47：新一轮 TTS 开始 —— 放掉上一轮的回声闸门（播放期本就由
        // suppressUplink 挡着，闸门留到本轮的 tts stop 再落）。
        echoGateOn = false;
        echoQuietRun = 0;
        // v1.3.49：撤掉可能还在排队的"上行恢复"任务 —— TTS 期上行必须全程关闭，
        // 否则上一轮排下的 resumeUplinkTask 会在本轮 TTS 播放中途把上行打开。
        main.removeCallbacks(resumeUplinkTask);
        // v1.2.16 barge-in：默认关（bargeInUplink=false → 抑制上行，防自环）；
        // 开了且未熔断才放行上行，让服务器 VAD 打断
        suppressUplink = !bargeInUplink || echoDisabled;
        // v1.3.121：新一轮 TTS —— 实时打断的连续命中计数与之起跑
        vadRun = 0;
        vadProbeLastMs = 0L;
        // v1.3.130：新一轮 TTS 来了 —— 撤销上一轮排队的「音乐恢复」
        MusicResume.cancel();
        // v1.3.134：音乐让路**不在这里做** —— tts start 事件比首帧音频还早
        // 0.75~3.9s（2026-10-13 实测 STT->tts-frame1），在这儿暂停照样有静音空隙。
        // 让路统一挪到「第一帧音频真正开播」那一刻：
        // TtsPlayer 播放循环 → MusicResume.onTtsAudioStart()。
        if (player != null) {
            player.onTtsStart();
        } else {
            Log.w(TAG, "TTS start but player is null (degraded), skip");
        }
    }

    @Override
    public void onTtsAudio(byte[] opusFrame) {
        if (opusFrame == null || opusFrame.length == 0) {
            return;
        }
        touchDownlink();     // v1.3.16
        lastTtsFrameMs = android.os.SystemClock.elapsedRealtime();  // v1.3.16：喂静默看门狗
        // v1.3.14：延迟埋点 —— STT 出字 -> 服务器首个 TTS 音频帧
        if (latFirstTtsMs == 0L) {
            latFirstTtsMs = android.os.SystemClock.elapsedRealtime();
            if (latSttMs > 0L) {
                long d = latFirstTtsMs - latSttMs;
                Log.i(TAG, "lat: STT->tts-frame1 = " + d + "ms");
                SessionLog.i("lat: STT->tts-frame1 = " + d + "ms");
            }
            // ===== v1.3.18：两个「总账」埋点，一眼看出首轮/周转各花多少 =====
            // ① 首轮总账：唤醒 -> 服务器第一个 TTS 音频帧（含建连 + 补发 + ASR + LLM + TTS）
            if (wakeMs > 0L) {
                long d = latFirstTtsMs - wakeMs;
                Log.i(TAG, "lat: wake->tts-frame1 = " + d + "ms (first-turn total)");
                SessionLog.i("lat: wake->tts-frame1 = " + d + "ms");
                wakeMs = 0L;   // 首轮账结清，避免后续轮次重复打「wake->*」
            }
            // ② 周转总账：上一轮 TTS 停 -> 本轮首个 TTS 音频帧（纯多轮周转）
            if (lastTtsStopMs > 0L) {
                long d = latFirstTtsMs - lastTtsStopMs;
                Log.i(TAG, "lat: tts-stop->tts-frame1 = " + d + "ms (turnaround)");
                SessionLog.i("lat: tts-stop->tts-frame1 = " + d + "ms");
            }
        }
        TtsPlayer p = player;
        if (p == null) {
            return; // 降级中：无播放器，丢弃 TTS 音频（ASR 链路不受影响）
        }
        byte[] pcm = opus.decode(opusFrame, opusFrame.length);
        if (pcm != null) {
            p.push(pcm);
        }
    }

    @Override
    public void onTtsStop() {
        Log.i(TAG, "TTS stop");
        SessionLog.i("TTS stop");
        // v1.3.147：小智说完了 ⇒ 关蓝色播报灯（原厂 onTTSEventPlayingEnd -> onTTSEnd）。
        R1Lights.busyOff();
        main.removeCallbacks(ttsStaleCheck);   // v1.3.16：正常收到 stop，撤掉静默看门狗
        touchDownlink();                       // v1.3.16
        lastTtsStopMs = android.os.SystemClock.elapsedRealtime(); // v1.3.18：周转埋点基准
        sttThisTurn = false;   // v1.3.23：本轮结算完毕，下一轮重新判定
        // v1.3.47：**落回声闸门**。上行何时恢复不再看时间（isDrained 判不到
        // AudioTrack 硬件缓冲 + HAL 采集延迟 + 房间余响），改看麦克风电平。
        // 必须放在下面 serverFarewell 早退分支之前，告别语同样要守。
        armEchoGate();
        // v1.2.16：回环熔断 —— TTS 开始 <2s 就被服务器打断，特征是喇叭里
        // 自己的 TTS 人声被 VAD 误判为用户插话 → 自打断 → 自言自语循环。
        // 置位 echoDisabled 后本进程内禁用 barge-in 上行（回退 v1.2.15 行为），
        // 重启服务重置。真插话一般发生在回复中后段（>2s），不受影响。
        // v1.3.124：熔断改为「连续计数 + 自愈」。
        //   旧逻辑：一次 TTS 不足 2s 就永久 echoDisabled=true（本进程内再也放不开上行）。
        //   在 realtime 模式下这条判据本身不成立 —— 服务端因任何原因提前收嘴
        //   （它自己的句长、网络抖动、真的插话）都会被算成自环，一误判就把实时打断废掉。
        //   新逻辑：连续 3 次短 TTS 才熔断；任何一次 >= ECHO_LOOP_MIN_TTS_MS 的正常播放
        //   都把计数清零并自动解除熔断。
        if (bargeInUplink && ttsStartElapsed > 0L) {
            long dur = android.os.SystemClock.elapsedRealtime() - ttsStartElapsed;
            if (dur >= ECHO_LOOP_MIN_TTS_MS) {
                if (echoDisabled || echoLoopStrikes != 0) {
                    echoLoopStrikes = 0;
                    echoDisabled = false;
                    SessionLog.i("echo-loop watchdog reset (tts lasted " + dur + "ms)");
                }
            } else {
                // v1.3.126：★ 只记录，**不再**置 echoDisabled。
                // 旧逻辑把「TTS 短于 2s」当成自环嫌疑，连中 3 次就把实时打断整个关掉
                // （echoDisabled=true ⇒ suppressUplink 恒 true）。而正常短回复本来就只有
                // 1.5~2s（2026-10-12 21:11:19 实测：1.92s 的「听得到啦！」被判 3 连击熔断），
                // 于是功能被自己的保护机制废掉 —— 现象正是「实时打断时好时坏/开了像没开」。
                // 真伪判据应该是「TTS 期 raw 是否确实抬起来了」（见 rawTts 统计），
                // 不再用"时长"这个与回声无关的代理指标。
                echoLoopStrikes++;
                String m = "echo-loop? short tts " + dur + "ms (strike "
                        + echoLoopStrikes + "/" + ECHO_LOOP_STRIKES
                        + ") — v1.3.126 log-only, barge-in kept ON";
                Log.i(TAG, m);
                SessionLog.i(m);
            }
        }
        ttsStartElapsed = 0L;
        if (player != null) {
            player.onTtsEnd();
        }

        // ===== v1.3.23：这一轮是「服务器空闲告别」→ 播完就结束会话 =====
        // 官方服务器念完告别语就不再理会这条 ws（本地没有任何信号能看出来）。
        // 此前我们照旧「续听 listenIdleMs(90s)」，于是在这具尸体会话上再挂 30~50s；
        // 用户此时唤醒 -> 话喂进死 socket -> 探测 7s 才重启 -> 影子回放丢头 ->
        // 8s 延迟 + 丢字（2026-10-04 18:19 实测）。现在：播完即关，回 IDLE，
        // 下一次唤醒走全新会话（应答音 + 1.2s 预缓冲补发），既不静默也不丢字。
        if (serverFarewell) {
            serverFarewell = false;
            farewellActive = true;
            suppressUplink = true;   // 告别语还在喇叭里，别把回声喂回服务器
            setState(State.LISTENING); // 保持状态自洽（上行被 suppressUplink 挡住）
            main.removeCallbacks(noTtsWatchdog);
            main.removeCallbacks(idleAlertTask);
            main.removeCallbacks(farewellEndTask);
            main.postDelayed(farewellEndTask, 800L);
            String fm = "server farewell -> end session after playback"
                    + " (skip " + (listenIdleMs / 1000) + "s listen idle)";
            Log.i(TAG, fm);
            SessionLog.i(fm);
            return;
        }

        // ============ v1.3.12：TTS 结束后必须【重发 listen start】 ============
        // 根因（2026-10-04 15:57 用户实测「只听得见你好小智，后面的话没反应」）：
        // 小智服务器按「一条 listen start 服务一轮对话」工作。我们此前只在握手
        // 完成时发过一条，此后从不重发 —— 服务器第一轮（唤醒词）ASR 完就不再
        // 武装 VAD，用户后续所有语音全部石沉大海。
        // 依据 = 官方 ESP32 固件 application.cc（本机 _xz/xiaozhi-esp32）：
        //   L624-633   tts "stop" -> SetDeviceState(kDeviceStateListening)
        //   L1026-1044 进入 Listening 状态 -> StartListeningAudio()
        //   L1070-1078 StartListeningAudio() 第一件事 protocol_->SendStartListening()
        // 即：官方每进入一次「听」状态就重发一条 listen start（mode 随 AEC：
        // AEC 关 = auto，见 application.cc L1187-1188）。
        suppressUplink = true;   // 先压住上行（防自环），等本地播完再放
        setState(State.LISTENING);
        ws.sendStartListening();
        Log.i(TAG, "TTS stop -> re-arm listening (listen start re-sent)");
        SessionLog.i("TTS stop -> re-arm listening (listen start re-sent)");
        resumeWaitedMs = 0L;
        main.removeCallbacks(resumeUplinkTask);
        if (halfDuplex) {
            // v1.3.49「严格静音」：**不再自动恢复上行**。服务器收不到音频 = STT 天然禁用；
            // KWS 继续听唤醒词，命中后由 handleWakeup 打断 + 放行 + 补发 preroll。
            // v1.3.50：默认关（用户实测这么做等于「每句话都要喊唤醒词」）。
            String hm = "half-duplex(strict): uplink muted until wake word (STT disabled)";
            Log.i(TAG, hm);
            SessionLog.i(hm);
        } else {
            // v1.3.50（默认）：TTS 后自动恢复上行 —— 用户可直接接话，不用再喊唤醒词。
            // 放行时机由 resumeUplinkTask（等播放排空）+ echoGate（强制压制期后认「安静」）
            // 共同把关，防自环交给 TtsPlayer.holdSilenceMs 钉住 HAL 回声抑制。
            main.postDelayed(resumeUplinkTask, RESUME_POLL_MS);
            String rm = "TTS stop -> uplink auto-resume armed (no wake word needed)";
            Log.i(TAG, rm);
            SessionLog.i(rm);
        }
        // v1.3.15：续听窗口 = listenIdleMs（默认 90s，非 25s）；并在窗口末端安排
        // 本地断开提醒（服务器若在 ~70s 下发 TTS 提醒，本 task 会被 onTtsStart 撤销）。
        armNoTtsWatchdog(idleWindowMs());
        scheduleIdleAlert();
        // ★ v1.3.130：小智说完话 —— 若唤醒时音乐被系统中断暂停过、且用户本轮没让它停，
        // 2.5s 内没有新一轮对话就把音乐接回去（见 MusicResume 类注释）。
        MusicResume.onTurnEnded("tts-stop");
    }

    /**
     * v1.3.12：恢复上行前至少压住这么久（覆盖 AudioTrack 缓冲里的 TTS 尾巴）。
     * v1.3.18：700 -> 400。这段窗口里用户的话是**被丢弃**的 —— 压 700ms 意味着
     * 用户「听到回复立刻接话」时开头 0.7s 被吃掉，多轮体感就是「要等一下才认」。
     * 真正的防自环靠 `isDrained()`（本地播放确实排空），min 只做下限兜底，
     * 400ms 足够覆盖 AudioTrack 尾巴，且显著缩短被吃掉的窗口。
     */
    private static final long RESUME_MIN_MS = 250L;
    /** v1.3.12：等本地播放排空的兜底上限。
     *  v1.3.46 ★ 3000 -> 900。实机取证（2026-10-05 21:08~21:10，jp2 v1.3.45）：
     *      21:08:58.601 TTS stop -> 21:09:01.618 uplink resumed (waited=3000ms)
     *      21:09:10.391 TTS stop -> 21:09:13.411 uplink resumed (waited=3000ms)
     *      21:09:53.282 TTS stop -> 21:09:56.300 uplink resumed (waited=3000ms)
     *  每次 TTS stop 都撞满 3000ms 上限（isDrained 迟迟不真，见下方注释），
     *  而这 3 秒里用户说的话**被整句丢弃** —— 用户「听到回复立刻接话」说
     *  「现在几点？」（约 1s）正好整句落在窗口内 ⇒ 服务器一个字都没收到 ⇒
     *  体感「它回一句就不理我了」。上限收到 900ms 后最坏窗口缩短 70%。 */
    private static final long RESUME_MAX_MS = 900L;
    private static final long RESUME_POLL_MS = 150L;

    private long resumeWaitedMs = 0L;

    /**
     * v1.3.12：TTS 结束后延迟恢复上行 —— 等本地播放队列排空（TtsPlayer.isDrained），
     * 至少 RESUME_MIN_MS、至多 RESUME_MAX_MS。期间服务器已回到 listening，
     * 但我们不送音频，避免把自己的 TTS 尾巴当作用户语音（自环）。
     */
    private final Runnable resumeUplinkTask = new Runnable() {
        public void run() {
            resumeWaitedMs += RESUME_POLL_MS;
            if (state != State.LISTENING) {
                // v1.3.46 ★ **死锁修复**。旧实现在这里直接 return 且不再排下一次：
                // 只要这次 run() 赶在状态切到 LISTENING 之前（或状态卡在
                // TTS_PLAYING / CONNECTING / 僵尸 LISTENING），`suppressUplink`
                // 就**永久卡在 true** —— 用户直接说话被 onFrame 丢弃，而唯一会
                // 无条件放行上行的是唤醒路径（handleWakeup 里 suppressUplink=false）
                // ⇒ 体感正是「回一句后彻底沉默，必须重新喊唤醒词才能继续」。
                // 现在：继续排到硬上限，超限则无条件放行（宁可放进一点 TTS 尾巴，
                // 也绝不能让上行死锁）。
                if (resumeWaitedMs >= RESUME_MAX_MS) {
                    suppressUplink = false;
                    Log.i(TAG, "uplink force-resumed (state=" + state
                            + " stuck, " + resumeWaitedMs + "ms)");
                    SessionLog.i("uplink force-resumed (state=" + state + " stuck)");
                    return;
                }
                main.postDelayed(this, RESUME_POLL_MS);
                return;
            }
            TtsPlayer p = player;
            boolean drained = (p == null) || p.isDrained();
            if (resumeWaitedMs < RESUME_MIN_MS
                    || (!drained && resumeWaitedMs < RESUME_MAX_MS)) {
                main.postDelayed(this, RESUME_POLL_MS);
                return;
            }
            suppressUplink = false;
            Log.i(TAG, "uplink resumed after TTS (waited=" + resumeWaitedMs
                    + "ms drained=" + drained + ")");
            SessionLog.i("uplink resumed after TTS (waited=" + resumeWaitedMs + "ms)");
        }
    };

    @Override
    public void onStt(String text) {
        Log.i(TAG, "STT: " + text);
        SessionLog.i("STT: " + text);
        disarmWakeProbe();   // v1.3.19：服务器听懂了 = 会话是活的，探测解除
        clearShadow();
        sttThisTurn = true;  // v1.3.23：有 STT = 服务器确实在回应，本轮不可能是「空闲告别」
        // v1.3.147：服务器听懂了 ⇒ 用户在等回答 ⇒ 拾音灯换蓝色播报灯（原厂是在
        // onASREventRecordingStop 做这件事：关掉方向灯 + turnOnLoadingLight）。
        R1Lights.busyOn();
        MusicResume.cancel();   // v1.3.130：用户又说话了 ⇒ 撤掉排队的「音乐恢复」
        touchDownlink();   // v1.3.16
        // v1.3.122：官方协议的实时打断落地在同一刻 ——
        // 服务器之所以能在 TTS 播放期间吐出 STT，正是因为我们在 TTS 期照常上行，
        // 它自己的 VAD 判定「用户在插话」并中止了本轮。客户端此刻唯一的职责是闭嘴。
        if (state == State.TTS_PLAYING) {
            serverBargeIn(text);
        }
        // v1.3.14：延迟埋点 —— 唤醒 -> 服务器出 STT 文字（用户说完到出字）
        latSttMs = android.os.SystemClock.elapsedRealtime();
        if (wakeMs > 0L) {
            long d = latSttMs - wakeMs;
            Log.i(TAG, "lat: wake->STT = " + d + "ms");
            SessionLog.i("lat: wake->STT = " + d + "ms");
        }
    }

    @Override
    public void onClosed(String reason) {
        Log.i(TAG, "ws closed: " + reason);
        SessionLog.i("ws closed: " + reason);
        enterIdle();
    }

    @Override
    public void onError(String msg) {
        Log.e(TAG, "ws error: " + msg);
        SessionLog.i("ws error: " + msg);
    }

    // ---------------- 无 TTS 看门狗 ----------------

    private void armNoTtsWatchdog() {
        armNoTtsWatchdog(NO_TTS_TIMEOUT_MS);
    }

    /** v1.3.15：带时长的看门狗（TTS 后用 listenIdleMs，握手后仍用 25s） */
    private void armNoTtsWatchdog(long timeoutMs) {
        watchdogMs = timeoutMs;
        main.removeCallbacks(noTtsWatchdog);
        main.postDelayed(noTtsWatchdog, timeoutMs);
    }

    // ---------------- v1.3.16：TTS 流静默看门狗（僵尸会话根治） ----------------

    /**
     * 根因（2026-10-04 16:34 实测 session log）：
     * ```
     * 16:34:39.923 <- {"type":"tts","state":"start"...}
     * 16:34:41.224 <- {"type":"tts","state":"sentence_start","text":"欸，好像没人应我，我先休息一下…"}
     * （此后 2 分 22 秒再没有任何下行，既无 tts stop，也无 sentence_end）
     * ```
     * 服务器把这轮告别语讲完就静默了，**不下发 tts stop**。而 onTtsStart 里
     * removeCallbacks(noTtsWatchdog) 已经把唯一的计时器撤掉 → 状态机永久停在
     * TTS_PLAYING、本地空闲断开（120s）也武装不上 → 这条会话变成僵尸：
     * 唤醒要么做无意义的 barge-in、要么被当重复唤醒忽略（16:36 两次喊
     * 「小智小智」全无响应就是这个）。最后靠 socket 自己超时（16:37:01）才解脱。
     *
     * 这里补一个看门狗：只要 TTS_STALE_MS(15s) 内既没有新 TTS 音频、也没等到
     * tts stop，就判定这轮 TTS 已经结束。
     *
     * v1.3.19：判定之后**不再「补一次 onTtsStop 收成 LISTENING」**，而是直接关掉
     * 这条会话。理由：服务器 TTS 讲完却迟迟不发 tts stop = 它已经把会话收了
     * （实测告别语就是这种形态）。旧做法把它收尾成 LISTENING，等于留下一具
     * 「假装在听」的僵尸会话 —— 用户唤醒打在它上面毫无反应（2026-10-04 16:36
     * 连喊两次「小智小智」全无响应）。现在直接 close → IDLE，下一次唤醒干净地
     * 开新会话。
     */
    private final Runnable ttsStaleCheck = new Runnable() {
        public void run() {
            if (state != State.TTS_PLAYING) {
                return;
            }
            long idle = android.os.SystemClock.elapsedRealtime() - lastTtsFrameMs;
            if (idle > TTS_STALE_MS) {
                String m = "TTS stream stalled " + idle
                        + "ms (no audio, no tts stop) -> server likely ended session, closing";
                Log.i(TAG, m);
                SessionLog.i(m);
                ws.close("tts-stale");
                return;
            }
            main.postDelayed(this, 2000L);
        }
    };

    // ---------------- v1.3.15：断开前提醒 ----------------

    /**
     * 续听窗口将尽时的本地提示。
     * **只在服务器没发提醒时才有意义**：官方服务器在 listening 空闲 ~70s 会下发
     * TTS 提醒（走 onTtsStart），那样本 task 会被 onTtsStart 撤销；若服务器始终
     * 沉默，才轮到我们出声，避免用户毫无预兆地被断线。
     */
    private final Runnable idleAlertTask = new Runnable() {
        public void run() {
            if (state != State.LISTENING || !idleAlertEnabled) {
                return;
            }
            Log.i(TAG, "idle alert: no server prompt, cueing local disconnect hint");
            SessionLog.i("idle alert: cueing local disconnect hint");
            playLocalCue();
        }
    };

    /**
     * v1.3.101：本地提示音的摆位（按用户口径简化 —— 90s 由服务器自己播告别语）。
     *   60s 模式：服务器没机会开口 ⇒ 提示音挪到**真正挂断那一刻**（enterIdle 里播）。
     *   90s 模式：服务器 ~70s 会自己播「我先休息一下」 ⇒ **本地一声不播**，
     *            提前 15s 的预告音随之取消（会与服务器的告别语打架，纯噪声）。
     */
    private void scheduleIdleAlert() {
        main.removeCallbacks(idleAlertTask);
        cueOnDisconnect = autoDisconnectOn() && idleAlertEnabled;
    }

    /**
     * v1.3.99：提示音总开关（files/sound_off）。
     * 每次播放时读文件（一次 stat，开销可忽略）⇒ web 控制台改完**立即生效**，
     * 不用重启进程。覆盖唤醒应答音与断连/预览提示音两处。
     */
    private boolean soundOff() {
        try {
            return new java.io.File(appContext.getFilesDir(), "sound_off").exists();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 本地提示音：两声上行短音（880 / 1175Hz），复用 TtsPlayer 的 AudioTrack
     * 播放 —— 不新建音频流，规避 R1 脆弱的 audio HAL（新建 AudioTrack 有卡死史）。
     */
    private void playLocalCue() {
        if (soundOff()) {
            return;   // v1.3.99：web 控制台「提示音」总开关
        }
        TtsPlayer p = player;
        if (p == null) {
            return;
        }
        p.push(buildCuePcm());
    }

    /** 合成提示音 PCM（16kHz mono，5ms 淡入淡出防爆音） */
    private static byte[] buildCuePcm() {
        int rate = AudioCapture.SAMPLE_RATE;
        int[] freqs = {880, 0, 1175};   // 0 = 静音间隔
        int[] mss = {170, 80, 220};
        int total = 0;
        for (int ms : mss) {
            total += rate * ms / 1000;
        }
        byte[] out = new byte[total * 2];
        int idx = 0;
        int fade = rate / 200;          // 5ms
        for (int k = 0; k < freqs.length; k++) {
            int n = rate * mss[k] / 1000;
            for (int i = 0; i < n; i++) {
                double v = (freqs[k] == 0) ? 0.0
                        : Math.sin(2.0 * Math.PI * freqs[k] * i / rate);
                double env = 1.0;
                if (i < fade) {
                    env = (double) i / fade;
                } else if (i > n - fade) {
                    env = (double) (n - i) / fade;
                }
                short s = (short) (v * env * 0.35 * 32767);
                out[idx++] = (byte) (s & 0xff);
                out[idx++] = (byte) ((s >> 8) & 0xff);
            }
        }
        return out;
    }

    // ---------------- v1.2.15：USC 错误提示静音 ----------------

    /**
     * 媒体流静音开关。setStreamMute 在 API 23 弃用，但设备停在 5.1.1(API 22)，
     * 这是该版本唯一不弹 UI 的静音 API（adjustStreamVolume ADJUST_MUTE 是 22 新增
     * 但在本机型 AudioService 上行为未验证，不做首选）。
     * AudioService 按「调用次数」计 mute：true 一次必须配 false 一次，
     * uscMuted 守卫保证配对，防计数紊乱把设备永久哑掉。
     */
    /**
     * v1.3.70：置 true = 原厂 TTS 已停用，不再需要静音罩（改回 false 即恢复旧行为）。
     */
    private static final boolean USC_TTS_SHORTED = true;

    @SuppressWarnings("deprecation")
    private void setMediaMute(boolean mute, String why) {
        // v1.3.70：原厂 TTS 出口已在 smali 层短路（SpeechSynthesizer.playText/playBuffer/
        // playSynWav/synthesizeText 全部 return），原厂不再发声 ⇒ 静音罩失去存在意义。
        // 保留方法体为空（而不是删掉调用点）：一处开关即可整体回退，且 uscMuted 计数
        // 不会再被打乱（setStreamMute 按调用次数配对，误用会把设备永久哑掉）。
        if (USC_TTS_SHORTED) {
            Log.i(TAG, "media mute skip=" + mute + " (" + why + ") [usc TTS shorted]");
            return;
        }
        try {
            AudioManager am = (AudioManager)
                    appContext.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                return;
            }
            am.setStreamMute(AudioManager.STREAM_MUSIC, mute);
            uscMuted = mute;
            String m = "media mute=" + mute + " (" + why + ")";
            Log.i(TAG, m);
            SessionLog.i(m);
        } catch (Throwable t) {
            Log.e(TAG, "setMediaMute threw", t);
        }
    }

    /** 服务销毁兜底：绝不能让设备哑着进入下一个生命周期 */
    public void release() {
        main.removeCallbacks(uscUnmute);
        if (uscMuted) {
            setMediaMute(false, "service release");
        }
    }
}
