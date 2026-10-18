package cn.workbuddy.r1client;

import android.os.SystemClock;
import java.util.ArrayDeque;

/**
 * v1.3.76：寄生层不再自己开 AudioRecord，改从原厂 USC 的采集流上"搭车"。
 *
 * 背景（2026-10-08 夜实证）：
 *   唤醒那一刻 USC 会重启它的录音线程（sdk/o.start(String) -> sdk/ax.n() 停掉旧的
 *   + new ax -> IAudioSource.openAudioIn()）。而同一时刻寄生层也在 acquireMicLoop()
 *   里开自己的 AudioRecord —— 原厂那次 openAudioIn() 失败后再也不会重试，
 *   从此 mediaserver HAL 走 do_in_standby / uni_4mic_pcm_close，
 *   采集彻底归零（AF 里连 Input thread 都没有）。表现为：
 *      「小智占麦后原厂 KWS 就不起效了」「顶部拾音灯常亮但叫不醒」。
 *
 * 根治办法 = 寄生层永远不开麦，只在 smali 层把 USC 每次 readData() 拿到的 PCM
 * 复制一份到这里（见 uni_smali/com/unisound/common/e.smali 的 readData 补丁）。
 * 附带收益：上行吃到的是**原厂那条经 HAL 处理的流**（含四麦波束成形/降噪），
 * 而不是我们自己新开的一条裸流。
 */
public final class UscTap {

    private static final int MAX_BLOCKS = 80;
    private static final Object LOCK = new Object();
    private static final ArrayDeque<byte[]> Q = new ArrayDeque<byte[]>();

    /** 只在会话期置 true（避免空闲期白拷数据）。 */
    public static volatile boolean enabled = false;
    public static volatile long lastFeedMs = 0L;
    public static volatile long fedBytes = 0L;
    public static volatile long droppedBlocks = 0L;

    private UscTap() {
    }

    /** 由 smali 侧 com/unisound/common/e.readData([BI)I 调用。 */
    public static void feed(byte[] buf, int len) {
        if (!enabled || buf == null || len <= 0) {
            return;
        }
        if (len > buf.length) {
            len = buf.length;
        }
        byte[] copy = new byte[len];
        System.arraycopy(buf, 0, copy, 0, len);
        synchronized (LOCK) {
            if (Q.size() >= MAX_BLOCKS) {
                Q.poll();          // 丢弃最旧一块，宁可掉帧也不堆积陈旧音频
                droppedBlocks++;
            }
            Q.add(copy);
            fedBytes += len;
            lastFeedMs = SystemClock.elapsedRealtime();
        }
    }

    /** 寄生层读线程取一块；无数据返回 0（调用方自行退避）。 */
    public static int poll(byte[] out) {
        byte[] b;
        synchronized (LOCK) {
            b = Q.poll();
        }
        if (b == null) {
            return 0;
        }
        int n = Math.min(b.length, out.length);
        System.arraycopy(b, 0, out, 0, n);
        return n;
    }

    public static void reset() {
        synchronized (LOCK) {
            Q.clear();
        }
    }

    public static int depth() {
        synchronized (LOCK) {
            return Q.size();
        }
    }

    // ------------------------------------------------------------------
    // v1.3.79：延迟触发原厂 enterASR()
    // ------------------------------------------------------------------
    /**
     * 由 smali 侧 DefaultANTLaunchHandler.b()（原 playWakeupTips）调用，参数是
     * ANTHandlerContext。这里**不**立刻 enterASR —— 唤醒事件链里还有一个
     * doCancelEngine() 正在跑（约 90ms），同步 enterASR 会被它秒关：
     *   22:12:57.212 AudioSourceImplopenIn  -> 22:12:57.235 closeIn（23ms 就关麦）
     * 所以等这条 cancel 走完再进 ASR，麦才开得住，tap 才有 PCM。
     */
    public static void onWakeup(final Object ctx) {
        if (ctx == null) {
            return;
        }
        lastCtx = ctx;
        // v1.3.147：唤醒这一刻点亮「拾音灯」—— 与原厂 DefaultLightsHandler.onWakeupResult
        // 同一时点，能拿到四麦 DOA 角度。原厂那条链在 v1.3.79 之后不再走完
        // （playWakeupTips 被短接），所以灯改由这里点。
        R1Lights.onWakeup(ctx);
        new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(ASR_ENTER_DELAY_MS);
                } catch (Throwable ignored) {
                }
                enterAsr(ctx);
            }
        }, "xz-enter-asr").start();
    }

    /**
     * v1.3.79：唤醒后重新进 ASR 的等待时间（等原厂 doCancelEngine 收尾）。
     *
     * ⚠️ v1.3.83 曾加「150ms 抢先 + 400ms 兜底」双档。实测是**负优化**：
     * enterASR 对原厂是重操作（closeIn → 跑云端 ASR → openIn），隔 250ms 连打
     * 两次会把 USC 状态机顶死 —— 22:57:26 之后 tap 再没喂进一帧。已回退为单档。
     */
    private static final int ASR_ENTER_DELAY_MS = 400;

    private static volatile Object lastCtx = null;

    /** 反射调用 ctx.engine().enterASR()（不引入 unisound 编译期依赖）。 */
    public static boolean enterAsr(Object ctx) {
        if (ctx == null) {
            ctx = lastCtx;
        }
        if (ctx == null) {
            SessionLog.i("usc enterASR: no ctx");
            return false;
        }
        try {
            Object eng = ctx.getClass().getMethod("engine").invoke(ctx);
            if (eng == null) {
                SessionLog.i("usc enterASR: engine()==null");
                return false;
            }
            eng.getClass().getMethod("enterASR").invoke(eng);
            SessionLog.i("usc enterASR issued (delay " + ASR_ENTER_DELAY_MS + "ms)");
            return true;
        } catch (Throwable t) {
            SessionLog.i("usc enterASR failed: " + t);
            return false;
        }
    }
}
