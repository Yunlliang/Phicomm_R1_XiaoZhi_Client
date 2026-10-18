# v1.2.3 实施补丁单

目标：**服务必须活下来。** 不追求 KWS 可用，只求「启动 → 不 ANR → 不被 kill → 能收唤醒」。

前提（诊断见 `DIAG-v1.2.2.md`）：
- 故障 A：`TtsPlayer.start()` 里的 `AudioTrack.getMinBufferSize()` 是**同步 binder**，
  mediaserver HAL 死锁时它永不返回 → main 阻塞 → ANR
- 故障 B：KWS native 段错误**必然发生**（`newFromAsset+260`），且在启动路径上 → 拖走进程

**两条都要治，A 治根因，B 先绕开。**

---

## 改动 1 —— `R1ClientService.onCreate` 瘦身

### 现状（R1ClientService.java，崩溃点 68 行）

```java
protected void onCreate() {
    super.onCreate();
    Log.i(TAG, "=========== R1ClientService onCreate (mode=" + WAKEUP_MODE + ") ===========");
    buildPipeline();            // ← 68 行：同步做完了所有事，包括音频
}
```

`buildPipeline()` 里第 77 行就是 `TtsPlayer.start()` → 卡死。

### 改为

```java
private volatile boolean pipelineStarted = false;

@Override
public void onCreate() {
    super.onCreate();

    // ===== ① main 线程只做这一件事，必须 5s 内返回 =====
    startForeground(NOTIF_ID, buildNotification());

    Log.i(TAG, "=========== R1ClientService onCreate (mode=" + WAKEUP_MODE + ") ===========");
    Log.i(TAG, "onCreate: foreground up, spawning pipeline thread");

    // ===== ② 其余全部丢给工作线程 =====
    if (!pipelineStarted) {
        pipelineStarted = true;
        new Thread(this::buildPipelineSafely, "R1Pipeline").start();
    }
}

/** 外层兜底：无论如何不许抛到 main */
private void buildPipelineSafely() {
    try {
        buildPipeline();
    } catch (Throwable t) {
        Log.e(TAG, "pipeline assembly FAILED", t);
    }
}
```

### `buildPipeline()` 分三阶段

```java
private void buildPipeline() {
    // ---------- 阶段 1：不碰音频，先把"能收唤醒"做起来 ----------
    // 这一阶段跑完，服务就已经有用了
    try {
        initOpus();          // 纯 JNI，无 binder
        initWakeupBridge();  // 反射 + 注册总线，无 binder
        Log.i(TAG, "=== stage1 ready (opus + bus), audio deferred ===");
    } catch (Throwable t) {
        Log.e(TAG, "stage1 failed", t);
    }

    // ---------- 阶段 2：音频，带看门狗，失败可降级 ----------
    ensureAudioReady();      // 见改动 2

    // ---------- 阶段 3：准备就绪 ----------
    Log.i(TAG, "=========== R1ClientService ready (mic=" + (capture != null)
             + " kws=pending bus=" + (wakeupBridge != null)
             + " opus=" + (opusOk) + ") ===========");

    // KWS 默认不启动，见改动 4
    if (KWS_AUTOSTART) startKwsAsync();
}
```

> **关键点**：`OnStartCommand` 里的 `started` 幂等标志要保留，
> 否则 ANR-kill-重启 会叠加出多个 pipeline 线程。

---

## 改动 2 —— 干掉 `getMinBufferSize()`，加看门狗

### 2.1 `TtsPlayer.start()`（TtsPlayer.java:46）

```java
// ❌ 现状：这会卡死。binder 调用，mediaserver 死锁时永不返回
int minBuf = AudioTrack.getMinBufferSize(SR, CH, ENC);

// ✅ 改为：固定值，彻底不调用
private static final int MIN_BUF = 4096;   // 16k/mono/16bit，8KB buffer ≈ 256ms 余量
```

**为什么给死值是安全的**：

| | 说明 |
|---|---|
| 传小了 | `AudioTrack.write()` 会自动切块，不会丢数据，最坏是轻微 underrun |
| 传大了 | 只多占几 KB 内存（4096 frame × 2 byte = 8KB） |
| 512MB 内存的机器 | 8KB 完全无压力 |

**`getMinBufferSize` 只是个"建议值"，为它冒 ANR 的风险极不划算。**

### 2.2 `AudioCapture` 同样处理

```java
// 同样不要调 getMinBufferSize，直接给固定值
private static final int MIN_BUF = 4096;
```

### 2.3 看门狗 —— 因为构造/start 本身也是 binder

`getMinBufferSize` 绕开了，但 `new AudioTrack(...)` + `track.play()` 仍然是
binder 调用，**还是可能卡**。所以要包超时：

```java
/** 在独立线程里跑 r，最多等 ms 毫秒；超时返回 false（线程留着不管，它是 daemon） */
private static boolean withTimeout(String what, Runnable r, long ms) {
    final CountDownLatch latch = new CountDownLatch(1);
    Thread t = new Thread(() -> {
        try { r.run(); } catch (Throwable e) {
            Log.e(TAG, what + " threw: " + e);
        } finally { latch.countDown(); }
    }, what + "-wd");
    t.setDaemon(true);
    t.start();
    try {
        if (!latch.await(ms, TimeUnit.MILLISECONDS)) {
            Log.e(TAG, what + " TIMEOUT after " + ms + "ms (audio HAL wedged!)");
            return false;
        }
    } catch (InterruptedException e) {
        return false;
    }
    return true;
}
```

### 2.4 `ensureAudioReady()` 降级链

```java
private volatile TtsPlayer tts;
private volatile AudioCapture capture;

private void ensureAudioReady() {
    // --- 出：TTS ---
    if (!withTimeout("tts-start", () -> {
            TtsPlayer p = new TtsPlayer();
            p.start();
            tts = p;
        }, 2500)) {
        tts = null;
        Log.w(TAG, "DEGRADED: TTS unavailable (audio HAL), ASR still possible");
    }

    // --- 入：Mic ---
    if (!withTimeout("mic-start", () -> {
            AudioCapture c = new AudioCapture();
            c.start();
            capture = c;
        }, 2500)) {
        capture = null;
        Log.w(TAG, "DEGRADED: mic unavailable, bus wakeup only");
    }

    // --- 失败则退避重试（HAL 可能自己恢复）---
    if (tts == null || capture == null) {
        scheduleAudioRetry();
    }
}

private static final long[] RETRY_BACKOFF_MS = { 5_000, 15_000, 60_000, 300_000 };

private void scheduleAudioRetry() {
    long delay = RETRY_BACKOFF_MS[Math.min(retryCount, RETRY_BACKOFF_MS.length - 1)];
    retryCount++;
    Log.i(TAG, "audio retry #" + retryCount + " in " + delay + "ms");
    handler.postDelayed(() -> {
        if (tts == null || capture == null) ensureAudioReady();
    }, delay);
}
```

> ⚠️ **超时的那一刻，被卡住的线程是「泄漏」的**——它还在 binder 里睡着。
> 这是**有意为之**：daemon 线程不阻止进程退出；
> 而拔掉它没有任何 API 能做到（binder 调用不可中断）。
> 一个卡住的线程 ≪ 一次 ANR。

---

## 改动 3 —— 保持服务活着

```java
@Override
public int onStartCommand(Intent intent, int flags, int startId) {
    if (!started) { started = true; Log.i(TAG, "onStartCommand: first start"); }
    else { Log.i(TAG, "onStartCommand: repeat, ignored"); }
    return START_STICKY;
}
```

（这是 v1.1.1 就有的，确认别被改动 1 弄丢。）

**另外建议加一个「存活心跳」**，方便你判断服务到底活了多久：

```java
private void startLivenessTick() {
    handler.postDelayed(new Runnable() {
        int n = 0;
        @Override public void run() {
            n++;
            Log.i(TAG, "alive " + (n * 10) + "s  (tts=" + (tts != null)
                     + " mic=" + (capture != null) + ")");
            if (n < 60) handler.postDelayed(this, 10_000);   // 打 10 分钟
        }
    }, 10_000);
}
```

---

## 改动 4 —— KWS 摘出启动路径

```java
// ① 关掉自启动
private static final boolean KWS_AUTOSTART = false;

// ② 唤醒模式退回纯总线（不碰 sherpa / ORT）
private static final int WAKEUP_MODE = MODE_BUS_ONLY;
```

**为什么必须这么做**：KWS 的崩溃是 native `SIGSEGV`，
`try/catch` 拦不住、独立线程也拦不住，**只要它在启动路径上，服务就活不过 20 秒**。

**为什么可以这么做**：

1. `MODE_BUS_ONLY` 完全不加载 sherpa/ORT → 从根上避开故障 B
2. 总线唤醒走**原厂四麦引擎**，唤醒率比自建 KWS 更高
3. v1.1.0 的设计本来就支持运行期挂载/摘除 KWS（`SessionController.setKws()`）

**顺带收益**：不加载 13MB 模型，APK 可瘦到 6MB 左右，启动也快。

---

## 改动 5 ——（可选，建议一起做）KWS 挪独立进程

即使 v1.2.3 不启用 KWS，也建议**先把进程边界划好**，
这样 v1.2.4 修 KWS 时崩溃不会污染主服务。

`AndroidManifest.xml`：

```xml
<service
    android:name=".KwsService"
    android:process=":kws"          <!-- ← 独立进程 -->
    android:exported="false" />
```

主服务通过 Binder/AIDL 或简单的 Messenger 收 KWS 命中事件。
`:kws` 崩了系统只重启它，**主服务的总线唤醒链路毫发无损**。

> 这是**唯一**能真正隔离 native SIGSEGV 的办法。
> `startKwsAsync()` 那种"放独立线程"的做法，DEPLOY.md §6 自己都承认没用。

---

## 改动 6 —— 给 audio 起不来找个真实原因

日志里现在只有"TIMEOUT"，不知道卡在哪。建议在 TTS/Mic 启动前打一次探针：

```java
/** 在 main 之外的线程跑，2s 超时，只为拿证据 */
private void probeAudioSystem() {
    withTimeout("audio-probe", () -> {
        try {
            int out = AudioTrack.getMinBufferSize(16000,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            Log.i(TAG, "probe: getMinBufferSize(out) = " + out);

            int in = AudioRecord.getMinBufferSize(16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            Log.i(TAG, "probe: getMinBufferSize(in)  = " + in);
        } catch (Throwable t) {
            Log.e(TAG, "probe threw", t);
        }
    }, 2000);
}
```

- 打印出来 → HAL 是好的
- 超时 → HAL 卡住，**我们有证据说明不是我们的问题**（可以去重启 mediaserver）

---

## v1.2.3 验收清单

| # | 检查项 | 期望 |
|---|---|---|
| 1 | `onCreate` 返回耗时 | **< 1s**（只有 `startForeground`） |
| 2 | 服务存活 | **≥ 5 分钟不 ANR** |
| 3 | 日志 | `onCreate: foreground up, spawning pipeline thread` |
| 4 | 日志 | `=== stage1 ready (opus + bus), audio deferred ===` |
| 5 | 日志 | `probe: getMinBufferSize(out) = NNNN`（或 TIMEOUT，也算有效信息） |
| 6 | 日志 | `=========== R1ClientService ready ===` |
| 7 | 日志 | `alive 10s / 20s / 30s ...`（心跳连续） |
| 8 | 喊「小讯小讯」 | `*** BUS WAKEUP: 小讯小讯` |
| 9 | audio TIMEOUT 时 | 服务**依然活着**（DEGRADED 但可用） |
| 10 | ❌ | **不要求 `kws init=true`**（本次主动关闭） |

### 抓验证日志

```bash
adb -s 192.168.2.5:5555 logcat -c
adb -s 192.168.2.5:5555 shell am startservice -n cn.workbuddy.r1client/.R1ClientService
adb -s 192.168.2.5:5555 logcat -v time R1CLIENT:I *:S
```

### 怎么判断"活下来了"

```bash
# 每 10s 看一眼 pid，pid 变了 = 又被 kill 了
adb -s 192.168.2.5:5555 shell "ps | grep r1client"
```

盯着 `alive 10s / 20s / 30s ...` 这几行——
**它们连续出现 = 成功**（v1.2.2 连第一行都到不了）。

---

## 如果 v1.2.3 还是 ANR

那说明卡的不止 `getMinBufferSize`。按这个顺序继续排：

1. `probeAudioSystem()` 有没有打出数值？
   - 没有 → HAL 已经坏了，**先重启 mediaserver** 再试：
     `adb shell killall mediaserver`（会自己重启）
   - 有 → 继续下一步
2. 抓新的 `/data/anr/traces.txt`，看 main 现在停在哪一行
3. **终极手段**：把音频整个挪到 `:audio` 独立进程，
   主服务只做总线唤醒 + WS，通过本地 socket 传 PCM。
   这样即使音频进程卡死，主服务也能重启它。

---

## 附：v1.2.3 不做的事（避免范围蔓延）

| 不做 | 原因 |
|---|---|
| 修 KWS 的 ORT ABI 错配 | 已烧三个版本，需要**换成套产物**，单独立项 |
| 改 `WAKEUP_MODE` 回 HYBRID | KWS 没修好之前回去 = 回到 ANR 循环 |
| 动 `libonnxruntime.so` 的 ELF | 手工 patch 是死胡同，见 `DIAG-v1.2.2.md` §2.6 |
| kill 原厂小讯 | 它是唤醒事件来源；且**治不了故障 B** |
