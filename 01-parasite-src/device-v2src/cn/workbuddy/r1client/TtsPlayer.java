package cn.workbuddy.r1client;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import java.util.concurrent.LinkedBlockingQueue;

/**
 * TTS 播放：AudioTrack + jitter buffer。
 *
 * 关键修复（吸取 C3 主线死锁教训）：
 *   C3 的 jitter buffer 死锁根因是「可播判定 dlAvail()>=6600 放在 fifoPush 之前」，
 *   导致单帧 FIFO 永远无法触发 startDlPlay。
 *   这里严格保证：**先 push 入队，再判定能否启动播放**。
 *
 * 另一处 C3 踩过的坑：服务器 1.0x 实时节流会在长合成时出现 150~350ms 供给间隙。
 *   对策：缓冲水位设 400ms，低于水位不启动播放；播放中若欠载则输出静音填充而非卡死。
 */
public final class TtsPlayer {

    private static final String TAG = "R1CLIENT";

    /**
     * 启动播放所需的最小缓冲（毫秒）— 抵抗服务器供给抖动。
     * v1.3.14：400 -> 250。服务器按 1.0x 实时供给，凑够水位才出声 = 直接首字延迟。
     * v1.3.18：250 -> 180。v1.3.16 已把欠载处理改成「先短等新帧(100ms)、真见底才补
     * 静音」的软件水位模型，不再依赖大水位抗抖 → 水位可以压到 180ms，首声更快。
     */
    private static final int START_WATERMARK_MS = 180;

    /**
     * 完全空闲多久后判定 TTS 流结束（毫秒）。
     * v1.3.14：1500 -> 600。此值直接决定「TTS 播完后多久服务器才允许重新听到我们」
     * （SessionController 的 resume 依赖 isDrained()）。600ms 仍 > 服务器最大供给
     * 间隙 350ms，安全；但把多轮续听的死区从 ~1.65s 压到 ~0.75s。
     */
    private static final int DRAIN_TIMEOUT_MS = 600;

    /**
     * v1.3.48 ★★ 回声自环根除尝试：TTS 数据发完后**继续以实时节奏往 AudioTrack 写静音**
     * 这么久（毫秒），让"播放流"保持活跃。
     *
     * 实机取证（2026-10-05 21:34~21:36，jp2 v1.3.47，日志 diag/jp2log_now.txt）：
     *   TTS 播放期          mic 电平  77~430    （HAL 回声抑制在线 → 干净）
     *   TTS 停止后 0.7~4.7s mic 电平 479~1387   （抑制失效 → TTS 回声漏进麦克风）
     *   → 上行把这些回声送回服务器 → STT 逐字复现上一轮 TTS 原文 → 无限自环
     *     （STT「听得到听得到，我一直都在呢。」== 5.1s 前我们刚播的那句）
     *
     * 推论：R1 的音频链路只在**存在活跃播放流**时才启用回声抑制。TTS 一停，
     * 播放流 underrun，抑制随即退出，此前被压住的喇叭残余立刻出现在麦克风上。
     * 对策：让播放流别停 —— 数据发完后继续写静音，把抑制"钉"住。
     * 续写的是**纯数字静音**，不含任何声学参考，因此只压喇叭回声、不压用户说话。
     *
     * 0 = 关闭（回退 v1.3.47 行为）。files/ttshold<N> 覆盖（0..15000）。
     */
    public static volatile int holdSilenceMs = 4000;

    private static final int SAMPLE_RATE = AudioCapture.SAMPLE_RATE;

    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<byte[]>();
    private final Object lock = new Object();

    private AudioTrack track;
    private Thread player;
    private volatile boolean running;
    private volatile boolean playing;   // 是否已越过水位启动
    private long lastPushTime;
    /** v1.3.14：本轮首个 TTS 帧到达时刻（毫秒），用于量「首帧->出声」的缓冲等待 */
    private long firstPushMs;

    // ================= v1.3.16：卡顿取证埋点 =================
    /** 本轮实际写入 AudioTrack 的帧数 */
    private long frameCount;
    /** 本轮因欠载补写的静音总时长（毫秒）—— 这个值大 = 就是它在制造卡顿 */
    private long silenceMs;
    /** 上一帧写入时刻，用于算相邻帧到达间隔 */
    private long lastFrameMs;
    /** 本轮相邻两帧的最大到达间隔（毫秒）—— 直接反映服务器供给抖动幅度 */
    private long maxGapMs;

    /**
     * v1.3.16：纯软件「音频轨水位」模型。
     *   buffered = writtenMs - (now - playStartMs)
     * 只有在 buffered 见底时才允许补静音 —— 否则空等（AudioTrack 自己的缓冲
     * 会把服务器抖动吸收掉）。用它替代「队列一空就写静音」的老逻辑。
     */
    private long playStartMs;
    /** 本轮已写入 AudioTrack 的音频总时长（毫秒，含补的静音） */
    private long writtenMs;
    /** 音频轨里始终保留的最小余量（毫秒）—— 留一点避免写到 0 造成爆音 */
    private static final long MIN_RESERVE_MS = 60L;

    /** v1.3.48：静音续播截止时刻（System.currentTimeMillis；0 = 非续播态） */
    private volatile long holdUntilMs = 0L;

    /**
     * v1.3.127：最近一次算出的「播放轨水位」（毫秒）—— player 线程写，其它线程读。
     * 单独拎出来是为了避免跨线程读 32 位平台上的非 volatile long（v7a）撕裂。
     * 语义见 {@link #isStreamStarving()}。
     *
     * v1.3.128 修正：原先只在「队列空」分支刷新 ⇒ 队列非空时这里读到的是**陈旧的大值**，
     * 于是 isStreamStarving() 认为播放流很健康，把饥饿窗口的帧放了行。现在每轮循环都刷新。
     */
    private volatile int streamBufferMs = 0;

    /**
     * v1.3.128：判定「播放流饿了」的水位阈值（毫秒）。
     * 比 MIN_RESERVE_MS(60) 宽 —— 60 是「再不补静音就要爆音」的红线，而这里是
     * 「HAL 回声抑制还靠不靠得住」的判据：水位越浅，参考信号越断续，抑制越容易掉线。
     */
    private static final int STARVE_MS = 100;

    /**
     * v1.3.128：一旦判定饥饿，往后这么久内**保持**饥饿判定（毫秒）。
     * 依据：实测泄漏**滞后**于饥饿 —— 播放轨排空后，泄漏要 ~200~400ms 才出现在麦克风上
     * （AEC 重新收敛需要时间）。只挡「饥饿当下那几帧」会漏掉随后整段泄漏。
     */
    private static final long STARVE_COOLDOWN_MS = 500L;

    /** v1.3.128：饥饿冷却截止时刻（System.currentTimeMillis；0 = 无冷却） */
    private volatile long starveHoldUntilMs = 0L;

    public void start() {
        if (running) {
            return;
        }
        // v1.2.3 关键修复：不再调用 AudioTrack.getMinBufferSize()。
        // 它是同步 binder 调用（AudioSystem::getOutput → AudioFlinger 全局锁），
        // mediaserver 音频 HAL 死锁时永不返回 —— v1.2.2 的 ANR 根因，
        // 当时 main 线程就卡死在这里（见 DIAG-v1.2.2.md §1）。
        // minBuf 只是建议值：R1 实机健康时返回的正是 4096，直接给固定值，
        // write() 会自动分块，行为无差别，却彻底绕开这条 binder 调用。
        final int minBuf = 4096;
        int bufBytes = Math.max(minBuf * 4, SAMPLE_RATE * 2 / 2); // 至少 500ms
        track = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                bufBytes, AudioTrack.MODE_STREAM);
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack init failed");
            track.release();
            track = null;
            return;
        }
        running = true;
        track.play();
        player = new Thread(new Runnable() {
            public void run() {
                loop();
            }
        }, "R1TtsPlayer");
        player.start();
        Log.i(TAG, "TtsPlayer started, minBuf=" + minBuf + "(fixed) buf=" + bufBytes);
    }

    /** 是否已成功启动（AudioTrack 就绪且播放线程在跑） */
    public boolean isStarted() {
        return running;
    }

    /** 送入一包已解码的 PCM */
    public void push(byte[] pcm) {
        if (pcm == null || pcm.length == 0) {
            return;
        }
        if (firstPushMs == 0L) {
            firstPushMs = System.currentTimeMillis();
        }
        queue.offer(pcm);
        synchronized (lock) {
            lastPushTime = System.currentTimeMillis();
            lock.notifyAll();
        }
    }

    /** TTS 开始：清空历史残留 */
    public void onTtsStart() {
        queue.clear();
        synchronized (lock) {
            playing = false;
        }
        firstPushMs = 0L;   // v1.3.14：新一轮重新计首帧时刻
        holdUntilMs = 0L;   // v1.3.48：新一轮 TTS 取消上一轮的静音续播
        resetStats();       // v1.3.16：统计按「句」重置
        Log.i(TAG, "TTS start, buffer cleared");
    }

    /**
     * v1.3.16：本句是否还在出声（含启动前的水位填充期）。
     *
     * 用途：SessionController 收到唤醒词时要区分两种情况 ——
     *   true  = TTS 真的在播 → 正常 barge-in（清队列、转 LISTENING）；
     *   false = 状态机还挂在 TTS_PLAYING 但早就不出声了（服务器发完告别语
     *           却永不下发 tts stop）→ 这是僵尸会话，必须换新会话重来。
     */
    public boolean isPlaying() {
        // v1.3.48：静音续播期（holdUntilMs != 0）对外**不算"在出声"** —— 它只是
        // 用静音把播放流钉住以维持 HAL 回声抑制；语义上此时喇叭是无声的。
        return (playing && holdUntilMs == 0L) || !queue.isEmpty();
    }

    /** TTS 结束：仅标记，剩余音频继续播完（drain） */
    public void onTtsEnd() {
        Log.i(TAG, "TTS end signalled, queued=" + queue.size());
    }

    /**
     * v1.3.49：**真·打断** —— 清空软件队列 **并冲掉 AudioTrack 硬件缓冲**。
     *
     * 为什么必须 flush：`onTtsStart()` 只清 `queue`，而 AudioTrack 里还压着最多
     * 512ms（bufBytes=16384B @16k/mono）已经写进去的 TTS。唤醒词打断之后我们
     * 立刻恢复上行，这 512ms 尾巴会**紧接着**被麦克风录进去（R1 无 AEC）——
     * 正是"自己回自己"的入口。pause→flush→play 让喇叭当场归零。
     */
    public void abort() {
        queue.clear();
        synchronized (lock) {
            playing = false;
            holdUntilMs = 0L;   // 打断也撤销静音续播
            lock.notifyAll();
        }
        AudioTrack t = track;
        if (t != null) {
            try {
                t.pause();
                t.flush();
                t.play();
            } catch (Throwable e) {
                Log.w(TAG, "AudioTrack flush failed", e);
            }
        }
        resetStats();
        Log.i(TAG, "TTS aborted (queue cleared + AudioTrack flushed)");
        SessionLog.i("TTS aborted (queue + AudioTrack flushed)");
    }

    /**
     * v1.3.12：本地播放是否已排空（队列空 且 未在播放）。
     *
     * 用途：TTS 结束后服务器要重新 listen（多轮对话），但我们得等本地喇叭
     * 把缓冲里的 TTS 播完才恢复上行 —— 否则自己 TTS 的尾巴会被服务器 VAD
     * 当作用户插话，触发自环（C3 主线踩过的坑）。
     */
    public boolean isDrained() {
        return !playing && queue.isEmpty();
    }

    /**
     * v1.3.127 ★★ 「播放流饥饿」判定 —— TTS 期上行闸门的唯一依据。
     *
     * 实机取证（2026-10-12 22:53~22:54，jp2 v1.3.126 双域探针，diag/probe1.log）：
     *   TTS 正常播放（轨里有料）   raw 中位 4     ← 与安静本底同值 ⇒ HAL 回声抑制在线
     *   TTS stop 前 ~400ms 的窗口  raw 44~144    ← 抑制失配 ⇒ 喇叭残余漏进麦克风（5/5 轮全落此窗）
     *   用户真人说话               raw 52~112
     * 泄漏（144）与人声（112）**在同一条电平线上**，且 ALC 再按 10x 放大
     * （up 1443 vs 1120）⇒ 这些帧一旦上行，服务器 VAD 必然判成「用户插话」，
     * 于是把自己刚说的那句话打断。这就是「实时打断被小智自己的声音触发」的根因。
     *
     * 为什么水位见底就危险：R1 的板载回声抑制只在**存在活跃播放流**时在线
     * （见 holdSilenceMs 注释里 2026-10-05 的同类取证）。播放轨一旦排空/欠载，
     * 抑制退出，此前被压住的喇叭残余立刻出现在麦克风上。
     *
     * 判据（不猜电平，只看播放器状态机，可复现）：
     *   · 播放流根本没起来    → 饿（true），此时抑制也还没上线
     *   · 轨内水位 ≤ STARVE_MS → 饿（true），并触发 STARVE_COOLDOWN_MS 冷却
     *
     * ★ v1.3.128 修正（2026-10-12 diag/probe2.log，v1.3.127 上机实测）：
     *   v1.3.127 那句 `if (!queue.isEmpty()) return false;` 是错的 —— 队列空/非空
     *   只反映**网络供给节奏**，与实际**播放轨水位**不是一回事：服务端 60ms 一帧，
     *   帧间还有 80~171ms 抖动（maxGapMs），队列在「非空」瞬间被判「不饿」放行，
     *   而那一刻播放轨其实已经排空 ⇒ 泄漏照样送上去。
     *   实测证据：12 条泄漏帧（raw 34~88）**全部**落在 TTS 段末（音频发完、等 stop 的窗口），
     *   而恰被闸门挡住的 68 条（TTS-sup）raw 中位 4 / max 16 —— 干净。
     *   ⇒ 判据只认**播放轨水位**（+ 冷却期），与队列无关。
     *
     * ★ v1.3.129 起：**不再参与 barge-in 放行判定**（连错两版，已放弃这条路）。
     *   原因见 SessionController.bargeVoiceRaw 注释：true 泄漏在 raw 域与人声重叠，
     *   而「播放轨水位」根本不能区分二者 —— 实测水位 130~160（健康）时照样有
     *   raw 40~70 的散点，那些是**用户插话的轻声起头**，不是回声。
     *   现在 barge-in 只看「逐帧 raw > 门限」。本方法保留给探针读 buf= 用。
     */
    public boolean isStreamStarving() {
        if (!playing) {
            return true;    // 播放流未起（TTS 首帧未到 / 已彻底停）⇒ 抑制离线
        }
        return System.currentTimeMillis() < starveHoldUntilMs;
    }

    /** v1.3.128：供上行探针取「当前播放轨水位」（毫秒，可为负；-1 = 播放流未起） */
    public int bufferMsNow() {
        return streamBufferMs;
    }

    private void loop() {
        while (running) {
            // v1.3.128：每轮迭代都刷新「播放轨水位」+ 饥饿冷却。
            // 原实现只在「队列空」分支里刷 streamBufferMs ⇒ 队列非空时该字段是**陈旧大值**，
            // isStreamStarving() 据此判「不饿」并放行上行，而播放轨其实已排空（泄漏源）。
            if (playing) {
                long bufferedNow = writtenMs - (System.currentTimeMillis() - playStartMs);
                streamBufferMs = (int) Math.max(-1L, Math.min(bufferedNow, Integer.MAX_VALUE));
                if (bufferedNow <= STARVE_MS) {
                    starveHoldUntilMs = System.currentTimeMillis() + STARVE_COOLDOWN_MS;
                }
            } else {
                streamBufferMs = -1;
            }

            // --- 第一段：等待越过水位后启动播放 ---
            if (!playing) {
                if (bufferedMs() < START_WATERMARK_MS) {
                    if (!waitForData(200)) {
                        continue;
                    }
                    continue;
                }
                playing = true;
                playStartMs = System.currentTimeMillis();
                writtenMs = 0L;
                long waited = (firstPushMs == 0L) ? -1L
                        : (System.currentTimeMillis() - firstPushMs);
                Log.i(TAG, "playback started, buffered=" + bufferedMs()
                        + "ms, waited=" + waited + "ms");
                SessionLog.i("lat: tts-first-frame->play-start = " + waited + "ms");
                // v1.3.134：小智第一帧音频开播 ⇒ 音乐让路在这一刻执行（空隙≈0）。
                // 唤醒让路 / TTS 互斥都在这里落地，见 MusicResume.onTtsAudioStart()。
                MusicResume.onTtsAudioStart();
            }

            byte[] chunk = queue.poll();
            if (chunk == null) {
                long now = System.currentTimeMillis();
                // 播放中欠载。
                if (playing && holdUntilMs == 0L
                        && now - lastPushTime > DRAIN_TIMEOUT_MS) {
                    // v1.3.48：TTS 数据真的发完了。
                    Log.i(TAG, "TTS drained" + stats());
                    SessionLog.i("tts play summary" + stats());
                    resetStats();
                    if (holdSilenceMs > 0) {
                        // v1.3.48：转入"静音续播" —— 播放流不停，HAL 回声抑制不掉线
                        holdUntilMs = now + holdSilenceMs;
                        Log.i(TAG, "TTS hold-silence " + holdSilenceMs + "ms");
                        SessionLog.i("tts hold-silence start " + holdSilenceMs
                                + "ms (playback stream kept active -> HAL echo-suppression on)");
                    } else {
                        playing = false;
                    }
                }
                if (playing && holdUntilMs != 0L && now >= holdUntilMs) {
                    holdUntilMs = 0L;
                    playing = false;
                    Log.i(TAG, "TTS hold-silence end -> real silence");
                    SessionLog.i("tts hold-silence end -> real silence");
                }
                if (!playing) {
                    // 还没越过水位 / 续播已结束 → 什么都不写，空等新帧
                    waitForData(200);
                    continue;
                }
                // ===== v1.3.16 卡顿根因修复 =====
                // 旧实现：队列一空就 writeSilence(60)，而且循环里没有任何节流 ——
                // 服务器 150~350ms 的供给抖动会让它在一个瞬间连写十几次静音，
                // 把 AudioTrack 的 512ms 缓冲整个灌成静音；随后到达的真音频只能
                // 排在静音后面播 → 听感就是「卡顿 + 吞字」。
                //
                // 现在改用一个纯软件的「音频轨水位」模型来判断能不能什么都不做：
                //     buffered = 已写入音频轨的毫秒数 - 播放已进行的毫秒数
                // buffered > 0 表示轨里还压着这么多音频，此刻完全可以空等；
                // 只有 buffered 见底（真的快饿死）才补静音。
                // v1.3.48：静音续播期（holdUntilMs != 0）同样走这条水位线 —— 于是
                // 静音是以**实时速率**持续写入的，播放流整体保持"正在播"。
                long buffered = writtenMs - (now - playStartMs);
                // v1.3.128：水位与饥饿冷却已在循环顶部统一刷新（见 loop() 开头）
                if (buffered > MIN_RESERVE_MS) {
                    // 轨里还有余量 → 等新帧（push() 会 notifyAll 立刻唤醒），不写静音
                    waitForData(Math.min(200L, buffered - MIN_RESERVE_MS));
                    continue;
                }
                int fill = 60;
                silenceMs += fill;
                writtenMs += fill;
                writeSilence(fill);
                continue;
            }
            long nowMs = System.currentTimeMillis();
            if (lastFrameMs != 0L) {
                long gap = nowMs - lastFrameMs;
                if (gap > maxGapMs) {
                    maxGapMs = gap;
                }
            }
            lastFrameMs = nowMs;
            frameCount++;
            writtenMs += chunk.length * 1000L / (SAMPLE_RATE * 2);
            try {
                track.write(chunk, 0, chunk.length);
            } catch (Throwable t) {
                Log.e(TAG, "AudioTrack.write threw", t);
            }
        }
        Log.i(TAG, "TtsPlayer loop exit");
    }

    /** v1.3.16：本句播放统计（判卡顿只看 silenceMs 与 maxGapMs） */
    private String stats() {
        return " (frames=" + frameCount + " silenceMs=" + silenceMs
                + " maxGapMs=" + maxGapMs + ")";
    }

    private void resetStats() {
        frameCount = 0;
        silenceMs = 0;
        lastFrameMs = 0L;
        maxGapMs = 0;
    }

    private void writeSilence(int ms) {
        if (track == null) {
            return;
        }
        int bytes = SAMPLE_RATE * 2 * ms / 1000;
        byte[] z = new byte[bytes];
        try {
            track.write(z, 0, bytes);
        } catch (Throwable ignored) {
        }
    }

    private int bufferedMs() {
        int bytes = 0;
        for (byte[] b : queue) {
            bytes += b.length;
        }
        return bytes * 1000 / (SAMPLE_RATE * 2);
    }

    private boolean waitForData(long ms) {
        synchronized (lock) {
            try {
                lock.wait(ms);
            } catch (InterruptedException ignored) {
            }
        }
        return true;
    }

    public void stop() {
        running = false;
        holdUntilMs = 0L;   // v1.3.48
        synchronized (lock) {
            lock.notifyAll();
        }
        if (player != null) {
            try {
                player.join(500);
            } catch (InterruptedException ignored) {
            }
            player = null;
        }
        if (track != null) {
            try {
                track.stop();
            } catch (Throwable ignored) {
            }
            try {
                track.release();
            } catch (Throwable ignored) {
            }
            track = null;
        }
        queue.clear();
        Log.i(TAG, "TtsPlayer stopped");
    }
}
