package cn.workbuddy.r1client;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * jp2-v2 寄生引导器 —— 在原厂 ExampleApp.onCreate 里被调用。
 *
 * 与 R1ClientService 的区别：
 *  - 无 Service（manifest 不能加组件），直接在 Application 进程里装配；
 *  - 无 sherpa KWS（唤醒完全交给原厂 USC 引擎 + WakeupBridge 总线事件）；
 *  - 无 BootReceiver（原厂应用开机自启，ExampleApp.onCreate 天然覆盖）；
 *  - libr1opus.so 从 files/ 目录 System.load（nativeLibraryDir 里没有它）。
 *
 * 装配序列照抄 R1ClientService.buildPipeline()（v1.3.50 定型的参数组合）。
 */
public final class XzBootstrap {

    private static final String TAG = "XZ";

    /**
     * v1.3.100：web 控制台 / 日志里显示的版本号。
     *
     * ★★ 两条版本序列（2026-10-14 定，用户要求分开）★★
     *   · 测试版（日常迭代）：1.3.x —— 源码树里的默认值就是下个待发布的测试版，
     *     每出一个测试版 +1（1.3.150、1.3.151 …），**不进发布目录**。
     *   · release 版：2.0.x —— **只在用户主动要求出 release 时才构建**，
     *     由 release 构建脚本在编译前注入本字段（构建完还原），产物
     *     `jp2-V2.0.x.apk` 放进 `R1专用小智AI斐讯原厂四麦增强AEC降噪/`。
     *     release 一定是某个测试版「验收通过」后的快照，改动内容写在发布目录 readme。
     * ⇒ 看到 1.3.x = 试验中；看到 2.0.x = 正式发布。别把两者混着递增。
     */
    public static final String VERSION = "1.3.190";

    private static final AtomicBoolean booted = new AtomicBoolean(false);
    private static final AtomicBoolean ttsAttemptDone = new AtomicBoolean(true);
    private static final long AUDIO_WATCHDOG_MS = 8000;

    private static Context app;
    private static OpusCodec opus;
    private static WakeupBridge wakeup;
    private static TtsPlayer player;
    private static XiaoZhiWs ws;
    private static OtaClient ota;
    /**
     * v1.3.72：唤醒源开关。
     * true  = 只用原厂 USC KWS（它吃四麦私有通道，播歌/远场都能唤醒），
     *         寄生层不跑 sherpa、IDLE 期不占麦，唤醒后临时接管做上行。
     * false = 用自建 sherpa KWS + 常驻麦（会盖掉原厂 KWS，播歌时唤不醒）。
     * 想回到自建 KWS 改这个常量即可（代码与旗标注入逻辑都还在）。
     */
    private static final boolean USC_KWS_ONLY = true;

    private static AudioCapture mic;
    private static SessionController session;
    /** v1.3.68：自建 sherpa KWS（从 r1client 借 so + assets；失败则回退 bus 唤醒） */
    private static KwsEngine kws;
    /** v1.3.68：借自 cn.workbuddy.r1client 的 nativeLibraryDir（platform_app 域可 exec） */
    private static String r1LibDir;
    /** v1.3.68：借自 r1client 的 base.apk 路径（assets/kws 模型） */
    private static String r1Apk;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** ExampleApp.onCreate 调用入口。幂等，任何异常都不允许带崩宿主。 */
    public static void init(Context ctx) {
        if (!booted.compareAndSet(false, true)) {
            Log.i(TAG, "already booted, skip");
            return;
        }
        try {
            app = ctx.getApplicationContext();
        } catch (Throwable t) {
            Log.e(TAG, "app context failed", t);
            return;
        }
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    buildPipeline();
                } catch (Throwable tr) {
                    Log.e(TAG, "!!! xz pipeline assembly FAILED", tr);
                    try { SessionLog.i("!!! pipeline FAILED: " + tr); } catch (Throwable ignored) {}
                }
            }
        }, "xz-boot");
        t.setDaemon(true);
        t.start();
        startLivenessTick();
    }

    /**
     * 从自身 base.apk 前置盲区（DEX 之后、ZIP 之前）抠出 libr1opus.so 写入 files/。
     *
     * 布局：janus = [DEX][libr1opus.so][原厂 ZIP]。前置区第一个 \x7fELF 即 libr1opus.so，
     * 其后紧跟 ZIP 的 PK\x03\x04 local header。so 是区里唯一 ELF（原厂 ZIP 内的 so 在文件后部）。
     *
     * 幂等：files/ 已有且长度/魔数正确则跳过（避免每次启动重写）。
     * 失败不抛异常：让 OpusCodec 继续走 r1client 借壳候选。
     */
    private static void selfHostLibr1opus() {
        try {
            java.io.File target = new java.io.File(app.getFilesDir(), "libr1opus.so");
            byte[] so = null;

            if (target.exists() && target.length() > 0) {
                // 校验既有副本：ELF 魔数 + 首字节
                java.io.RandomAccessFile rf = new java.io.RandomAccessFile(target, "r");
                byte[] h = new byte[8];
                int n = rf.read(h);
                rf.close();
                if (n == 8 && h[0] == 0x7f && h[1] == 0x45 && h[2] == 0x4c && h[3] == 0x46) {
                    Log.i(TAG, "libr1opus.so already self-hosted at files/ (" + target.length() + "B)");
                    mirrorSoToExecDirs(target);
                    return;
                }
            }

            // 定位自身 base.apk 里的前置 so
            String src = app.getApplicationInfo().sourceDir;
            java.io.File base = new java.io.File(src);
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(base, "r");
            try {
                byte[] first = new byte[16];
                raf.seek(0);
                int gn = raf.read(first);
                boolean isJanus = gn >= 4 && first[0] == 0x64 && first[1] == 0x65
                        && first[2] == 0x78 && first[3] == 0x0a; // "dex\n"
                if (!isJanus) {
                    Log.e(TAG, "base.apk is not janus (no dex prefix), skip self-host");
                    return;
                }
                // 在前置区找第一个 \x7fELF；理论上紧跟 DEX，避免全文件扫描耗时
                long fileLen = base.length();
                long searchEnd = Math.min(fileLen, 64L * 1024 * 1024);
                raf.seek(0);
                int soStart = -1;
                int bufSize = 256 * 1024;
                byte[] buf = new byte[bufSize];
                long offset = 0;
                long mark = -1;
                while (offset < searchEnd) {
                    int need = (int) Math.min(bufSize, searchEnd - offset);
                    raf.seek(offset);
                    int got = raf.read(buf, 0, need);
                    if (got <= 0) break;
                    for (int i = 0; i + 3 < got; i++) {
                        if (buf[i] == 0x7f && buf[i+1] == 0x45 && buf[i+2] == 0x4c && buf[i+3] == 0x46) {
                            mark = offset + i;
                            soStart = (int) mark;
                            break;
                        }
                    }
                    if (soStart >= 0) break;
                    // 读到 ELF 头会中断；为避免碰到 0x7f45 在 DEX 数据里的误判，
                    // 只在"已进入候选区"后才找 —— 但 DEX 本身不含 \x7fELF，
                    // 首个命中几乎必然是 so。继续整区扫描一次。
                    offset += (got - 3);
                }
                if (soStart < 0) {
                    Log.e(TAG, "no ELF found in base.apk prefix, skip self-host");
                    return;
                }
                // 从 soStart 读到 ZIP local header(PK\x03\x04)或文件尾，但只取到 next ELF 之前的完整 so
                // 安全做法：读一段(如 512KB)内的 ELF 段，直到遇到 PK\x03\x04
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                raf.seek(soStart);
                int step = 128 * 1024;
                byte[] chunk = new byte[step];
                int last = -1;
                long soEnd = -1;
                for (int k = 0; k < 1024; k++) { // 上限 128MB
                    long pos = soStart + (long) k * step;
                    if (pos >= fileLen) break;
                    raf.seek(pos);
                    int c = raf.read(chunk);
                    if (c <= 0) break;
                    bos.write(chunk, 0, c);
                    // 找 ZIP local header 起点（PK\x03\x04）—— so 结束标志
                    for (int i = 0; i + 3 < c; i++) {
                        if (chunk[i] == 0x50 && chunk[i+1] == 0x4b
                                && chunk[i+2] == 0x03 && chunk[i+3] == 0x04) {
                            soEnd = pos + i;
                            break;
                        }
                    }
                    if (soEnd >= 0) {
                        so = bos.toByteArray();
                        // 截到 soEnd
                        long trimTo = (soEnd - soStart);
                        if (trimTo > 0 && trimTo < so.length) {
                            byte[] t = new byte[(int) trimTo];
                            System.arraycopy(so, 0, t, 0, (int) trimTo);
                            so = t;
                        }
                        break;
                    }
                }
                if (so == null || so.length == 0) {
                    // 兜底：读满 312804B
                    so = new byte[312804];
                    raf.seek(soStart);
                    raf.readFully(so);
                }
                Log.i(TAG, "self-host so: start=" + soStart + " len=" + so.length);
            } finally {
                raf.close();
            }

            // 写 files/libr1opus.so
            java.io.FileOutputStream fos = new java.io.FileOutputStream(target);
            fos.write(so);
            fos.close();
            boolean ok = target.length() == so.length;
            Log.i(TAG, "libr1opus.so self-hosted -> files/ ok=" + ok
                    + " len=" + target.length());
            SessionLog.i("self-host libr1opus ok=" + ok + " len=" + target.length());
            mirrorSoToExecDirs(target);
        } catch (Throwable t) {
            Log.e(TAG, "selfHostLibr1opus failed (proceed to r1client borrow)", t);
        }
    }

    /**
     * v1.3.85：把自持的 so 再镜像几份到「可能允许 exec」的落点。
     *
     * 目标：做成**单包**（卸载 cn.workbuddy.r1client 也能跑）。
     * 卡点是 SELinux —— platform_app 域对 app_data_file（files/）通常没有
     * execute 权限，System.load 会失败。这里把 so 复制到几个别的落点，
     * 由 OpusCodec 逐个试，哪个能 dlopen 就用哪个。
     *
     * 副作用：/data/local/tmp 下会留一个 300KB 的 libr1opus.so，无害。
     */
    private static void mirrorSoToExecDirs(java.io.File src) {
        String[] dirs = {"/data/local/tmp", "/data/local"};
        for (String d : dirs) {
            java.io.File dst = new java.io.File(d, "libr1opus.so");
            try {
                boolean same = dst.exists() && dst.length() == src.length();
                if (same) {
                    continue;
                }
                java.io.FileInputStream in = new java.io.FileInputStream(src);
                java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                in.close();
                out.close();
                Log.i(TAG, "mirror so -> " + dst.getAbsolutePath() + " len=" + dst.length());
            } catch (Throwable t) {
                Log.i(TAG, "mirror so -> " + d + " failed: " + t);
            }
        }
    }

    private static void buildPipeline() {
        SessionLog.init(app);

        // ---- 0) 自持 so：从自身 base.apk 前置盲区抠出 libr1opus.so -> files/ ----
        // 这样即使卸载 cn.workbuddy.r1client，OpusCodec 也能从
        // /data/data/com.phicomm.speaker.device/files/libr1opus.so 加载（候选路径已含）。
        // 前置盲区布局 = [DEX][libr1opus.so][ZIP...]，so 是区里第一个 ELF。
        selfHostLibr1opus();

        // ---- 1) opus 编解码（失败不终止：bus 唤醒与会话框架仍然装配）----
        try {
            // 先借 r1client 的 nativeLibraryDir（PM 下发路径，SELinux 允许 exec）
            try {
                android.content.pm.ApplicationInfo ri = app.getPackageManager()
                        .getApplicationInfo("cn.workbuddy.r1client", 0);
                if (ri != null && ri.nativeLibraryDir != null) {
                    OpusCodec.overrideLibPath = ri.nativeLibraryDir + "/libr1opus.so";
                    r1LibDir = ri.nativeLibraryDir;
                    r1Apk = ri.sourceDir;
                    Log.i(TAG, "r1client nativeLibraryDir=" + ri.nativeLibraryDir
                            + " apk=" + ri.sourceDir);
                }
            } catch (Throwable t) {
                Log.w(TAG, "r1client not installed: " + t);
            }
            opus = new OpusCodec();
            boolean encOk = opus.openEncoder();
            boolean decOk = opus.openDecoder();
            Log.i(TAG, "opus enc=" + encOk + " dec=" + decOk);
            SessionLog.i("opus enc=" + encOk + " dec=" + decOk);
        } catch (Throwable t) {
            Log.e(TAG, "opus init failed (will keep building other parts)", t);
            SessionLog.i("opus init FAILED: " + t);
            opus = null;
        }

        // ---- 2) 原厂唤醒：msgcenter 总线监听（type=33554432，与音频通路无关）----
        wakeup = new WakeupBridge(app, new WakeupBridge.OnWakeup() {
            public void onWakeup(final String word) {
                SessionController s = session;
                if (s != null) s.onWakeup(word);
            }
        });
        boolean wOk;
        try {
            wOk = wakeup.register();
        } catch (Throwable t) {
            Log.e(TAG, "WakeupBridge.register threw", t);
            wOk = false;
        }
        Log.i(TAG, "WakeupBridge register=" + wOk);
        SessionLog.i("bus wakeup register=" + wOk);

        // ---- 3) TTS（看门狗保护）----
        ensureTts();

        // ---- 4) WS / OTA / 会话控制器 ----
        // v1.3.99：悬浮窗默认**关闭**（改用 web 控制台，见 WebConsole）。
        // 需要屏幕上看码的场景：`run-as … sh -c 'echo 1 > files/hud_on'` 再重启进程。
        // HUD 必须在 OTA 之前挂好：它注册了 SP 监听，activation_code 一变更就能浮出来。
        try {
            if (new File(app.getFilesDir(), "hud_on").exists()) {
                ActivationHud.init(app);
                SessionLog.i("activation hud enabled (files/hud_on)");
            }
        } catch (Throwable t) {
            Log.w(TAG, "activation hud init: " + t);
        }

        ws = new XiaoZhiWs(new XiaoZhiWs.Listener() {
            public void onConnected() { SessionController s = session; if (s != null) s.onConnected(); }
            public void onHandshakeDone() { SessionController s = session; if (s != null) s.onHandshakeDone(); }
            public void onTtsStart() { SessionController s = session; if (s != null) s.onTtsStart(); }
            public void onTtsAudio(byte[] opusFrame) { SessionController s = session; if (s != null) s.onTtsAudio(opusFrame); }
            public void onTtsStop() { SessionController s = session; if (s != null) s.onTtsStop(); }
            public void onStt(String text) { SessionController s = session; if (s != null) s.onStt(text); }
            public void onClosed(String reason) { SessionController s = session; if (s != null) s.onClosed(reason); }
            public void onError(String msg) { SessionController s = session; if (s != null) s.onError(msg); }
        });

        ota = new OtaClient(app, new OtaClient.Callback() {
            public void onToken(String token) {
                Log.i(TAG, "got token from OTA: " + (token == null ? "null" : token));
                ws.setToken(token);
            }
            public void onWsUrl(String u) {
                // v1.3.101：files/ws_url 直连覆盖优先级最高（本地服务器免 OTA 时用）
                if (ws != null && wsOverride() == null) {
                    ws.setUrl(u);
                }
            }
            public void onFail(String msg) {
                Log.w(TAG, "OTA failed: " + msg);
                SessionLog.i("OTA failed: " + msg + " -> ws without token");
            }
        });
        ws.setDeviceId(ota.getDeviceId());
        ws.setClientId(ota.getClientId());
        // v1.3.101：本地服务器直连覆盖（files/ws_url）——有它就跳过 OTA 下发的 ws 地址
        String wso = wsOverride();
        if (wso != null && ws != null) {
            ws.setUrl(wso);
        }
        SessionLog.i("xz server: ota=" + otaUrl()
                + " ws_override=" + (wso == null ? "none" : wso));
        ota.fetch();

        // ---- 5) 音频配置 flags（与 v1.3.50 定型组合一致）----
        try {
            // v1.3.109：会话日志开关（默认开）。files/log_off 存在 ⇒ 完全不落盘，
            // 必须在下面任何 SessionLog.i() 之前设置，否则本行配置照样会被写进去。
            SessionLog.setEnabled(!new File(app.getFilesDir(), "log_off").exists());
            AudioCapture.aecEnabled = new File(app.getFilesDir(), "aec_on").exists();
            AudioCapture.useVoiceRec = new File(app.getFilesDir(), "src_vrec").exists();
            AudioCapture.sessionBind = new File(app.getFilesDir(), "sess_bind").exists();
            SessionController.dumpEnabled = new File(app.getFilesDir(), "uplink_dump").exists();
            SessionController.probeEnabled = new File(app.getFilesDir(), "stereo_probe").exists();
            int g = 16;
            int[] cand = {1, 32, 16, 8, 4};
            for (int gi = 0; gi < cand.length; gi++) {
                if (new File(app.getFilesDir(), "gain" + cand[gi]).exists()) { g = cand[gi]; break; }
            }
            AudioCapture.uplinkGain = g;
            // v1.3.67（2026-10-08）：四麦 B 通路重新可用开关。
            // untrusted_app 域会被 SELinux 拒（avc denied {search} /dev/snd），
            // 但 jp2 跑在 platform_app 域，openAudioIn 能成功 —— 故在此按旗标打开。
            AudioCapture.useUni4mic = new File(app.getFilesDir(), "src_uni4mic").exists();
            AudioCapture.useBridge = false;
            // v1.3.76 ★：搭车模式。寄生层不再开 AudioRecord，改从原厂 USC 的
            // readData() 分流取 PCM。这样唤醒那一刻 USC 重启录音线程的
            // openAudioIn() 一定能成功（设备不被我们占着）→ 原厂 KWS 永不失聪。
            AudioCapture.useUscTap = !new File(app.getFilesDir(), "no_usc_tap").exists();
            // v1.3.109：上行自动电平控制（默认关）。打开后固定的 files/gainN 不再参与，
            // 由 ALC 逐帧把 tap 出来的电平压到同一个目标 RMS —— 专治「原厂那条链
            // 每轮输出的增益都不一样」导致的时远时近。
            AudioCapture.alcEnabled = new File(app.getFilesDir(), "alc_on").exists();
            // v1.3.80：唤醒词隔离（默认开）。touch files/keep_wake_audio 恢复旧行为。
            SessionController.setDropWakeWordAudio(
                    !new File(app.getFilesDir(), "keep_wake_audio").exists());
            // ★ v1.3.130：实时打断整条线**作废**（2026-10-12 用户决定）。
            // 这里不再读任何相关旗标，并把老版本遗留在 files/ 下的旗标删掉 ——
            // 否则「上一版勾过的实时打断」会在升级后继续生效
            //（升级前实测现状：files/vad_on 还在 ⇒ vad_bargein=true ⇒ 被自己的声音打断）。
            SessionController.setVadBargeIn(false);
            int[] staleVad = {600, 800, 1000, 1400, 1800, 2000, 3000};
            for (int i = 0; i < staleVad.length; i++) {
                new File(app.getFilesDir(), "vadrms" + staleVad[i]).delete();
            }
            int[] staleBarge = {60, 70, 80, 90, 100, 120, 150, 200};
            for (int i = 0; i < staleBarge.length; i++) {
                new File(app.getFilesDir(), "barge_raw" + staleBarge[i]).delete();
            }
            new File(app.getFilesDir(), "vad_on").delete();
            // v1.3.130：唤醒期间被系统暂停的音乐，「小智说完话」后自动恢复（默认开）。
            // touch files/no_music_resume 可关掉。
            MusicResume.setEnabled(!new File(app.getFilesDir(), "no_music_resume").exists());
            // v1.3.132：「小智说话时暂停音乐、说完继续」—— TTS 与音乐互斥（默认关）。
            // Web 控制台勾选 / touch files/music_pause_tts 打开。
            MusicResume.setTtsPause(new File(app.getFilesDir(), "music_pause_tts").exists());
            // v1.3.139：自定义音乐库（默认开）。touch files/my_music_off 关掉 ⇒
            // MCP 上报空工具表，播放音乐/电台交回小智服务器自带的那套。
            PlayerBridge.setMyMusic(!new File(app.getFilesDir(), "my_music_off").exists());
            // v1.3.147：指示灯联动（原厂灯控反射装载）。touch files/lights_off 关掉。
            R1Lights.init(app);
            String cfg = "xz audio cfg: aec=" + AudioCapture.aecEnabled
                    + " gain=" + AudioCapture.uplinkGain
                    + " src_vrec=" + AudioCapture.useVoiceRec
                    + " src_uni4mic=" + AudioCapture.useUni4mic
                    + " usc_tap=" + AudioCapture.useUscTap
                    + " music_resume=" + MusicResume.isEnabled()
                    + " music_pause_tts=" + MusicResume.isTtsPause()
                    + " my_music=" + PlayerBridge.isMyMusic()
                    + " lights=" + R1Lights.state()
                    + " drop_wake_audio=" + SessionController.dropWakeWordAudio;
            Log.i(TAG, cfg);
            SessionLog.i(cfg);
        } catch (Throwable t) {
            Log.w(TAG, "audio cfg flag read failed", t);
        }

        mic = new AudioCapture();   // 先创建不启动
        session = new SessionController(app, mic, opus, player, null, ws);
        // v1.3.69 ★★★ 关键接线（此前遗漏，对照主线 R1ClientService 补）：
        // AudioCapture 只通过 FrameListener 往外派帧，而 SessionController.onFrame 是
        // 唯一的分发点 —— 它既喂 KWS（kws.feed）又做上行编码。
        // 漏掉这一行的后果：KWS 拿到的是零帧（自建唤醒永不触发，只剩原厂 bus 在响应），
        // 上行也一帧不发给服务器（会话统计恒 maxRms=0 voicedFrames=0 → 25s 无 TTS 超时）。
        // 表面症状就是「唤醒应答一声，然后怎么说话都没反应」。
        mic.setFrameListener(session);

        // ---- 6) v1.3.72：唤醒源选定 ----
        // 实测（2026-10-08 夜）：原厂 USC KWS 与自建 sherpa KWS 走的是两条不同的采集通路——
        //   · 原厂：AudioFlinger 里查不到它的 client，说明它不经标准 AudioRecord，
        //     走的是四麦私有通道（Uni4micHalJNI / 4mic HAL），自带波束成形与播歌场景适配；
        //     用户实测「放歌时远处轻喊原厂那套仍能唤醒」。
        //   · 自建 sherpa：寄生层只能用 AudioRecord（A 通路），吃的是裸 PCM，
        //     没有波束成形也没有 AEC → 播歌时唤不醒。
        // 而且两套并存时，寄生层常驻 AudioRecord 会在 IDLE 期一直占着麦，
        // 唤醒后又把原厂的会话抢过来，等于用较弱的那一套覆盖掉较强的那一套。
        // ⇒ 定案：USC_KWS_ONLY —— 唤醒全交给原厂（原厂唤醒词已改成「小智小智」），
        //   寄生层不跑 sherpa、不常驻麦，IDLE 期麦完全归原厂；
        //   只在 bus 唤醒事件到达后才 acquire 麦做上行，会话结束立即释放（原厂恢复）。
        if (USC_KWS_ONLY) {
            SessionLog.i("xz pipeline ready (USC-KWS-ONLY: self sherpa KWS off, mic released in IDLE)");
        } else {
            SessionLog.i("xz pipeline ready (self kws starting async; mic always-on)");
            // v1.3.68：自建 sherpa KWS。借 r1client 的 libsherpa-onnx-jni.so + assets/kws，
            // 常驻 A 通路 AudioRecord 喂 KWS。开启时原厂 KWS 会被挤到次位。
            startKwsAsync();
        }

        // ---- 7) v1.3.85：原厂音乐控制桥（小智 MCP 用）----
        // 反射挂 device 自带的 PhicommMusicController，走 ROM 消息总线控 player。
        // 失败只记日志，绝不影响小智主链路。
        PlayerBridge.init(app);

        // ---- 8) v1.3.99：web 控制台（激活码 / 状态 / 设置）----
        // 替代悬浮窗：局域网内任何浏览器可开 http://<音箱IP>:8088/
        // 端口可用 files/web_port 覆盖；bind 失败只记日志。
        try {
            WebConsole.init(app);
        } catch (Throwable t) {
            Log.w(TAG, "web console init: " + t);
        }
    }

    // ---------------- v1.3.99：web 控制台接口 ----------------

    /** 把 ws / 会话 / 音乐状态塞进 web 控制台的 /api/status */
    public static void fillStatus(JSONObject o) {
        try {
            JSONObject w = new JSONObject();
            XiaoZhiWs c = ws;
            w.put("url", c == null ? "" : c.getUrl());
            w.put("open", c != null && c.isOpen());
            o.put("ws", w);

            JSONObject s = new JSONObject();
            SessionController sc = session;
            s.put("state", sc == null ? "-" : sc.getState().name());
            // v1.3.121：RAW（增益前）实时电平 —— 网页上给「实时打断」标门限的那把尺子
            if (sc != null) {
                s.put("raw", sc.rawLevel());
                s.put("rawPeak", sc.rawPeakLevel());
            }
            o.put("session", s);

            o.put("music", PlayerBridge.statusJson());
        } catch (Throwable t) {
            Log.w(TAG, "fill status: " + t);
        }
    }

    // ---------------- v1.3.101：服务器切换（本地自建服务器支持）----------------

    /** @return files/&lt;name&gt; 的内容（取一行）；不存在或为空返回 null */
    public static String readLineFile(String name) {
        try {
            java.io.File f = new java.io.File(app.getFilesDir(), name);
            if (!f.exists() || f.length() == 0 || f.length() > 4096) {
                return null;
            }
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = 0;
            try {
                n = in.read(b);
            } finally {
                in.close();
            }
            if (n <= 0) {
                return null;
            }
            return new String(b, 0, n, "UTF-8").trim();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * v1.3.180：写 files/&lt;name&gt;（整文件覆盖成一行文本）—— {@link #readLineFile} 的反向操作。
     * 控制台里那些「写个数字就生效」的开关（online_max / playlist_max …）都靠它落地。
     *
     * @return true = 写成功
     */
    public static boolean writeLineFile(String name, String val) {
        try {
            java.io.File f = new java.io.File(app.getFilesDir(), name);
            java.io.FileOutputStream out = new java.io.FileOutputStream(f, false);
            try {
                out.write((val == null ? "" : val).getBytes("UTF-8"));
                out.flush();
            } finally {
                out.close();
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "writeLineFile " + name + ": " + t);
            return false;
        }
    }

    /** files/ws_url 直连覆盖（本地服务器免 OTA 用）。无覆盖返回 null */
    public static String wsOverride() {
        String s = readLineFile("ws_url");
        return (s == null || s.length() == 0) ? null : s;
    }

    /** 当前生效的 OTA 地址（files/ota_url 优先，否则官方默认） */
    public static String otaUrl() {
        String s = readLineFile("ota_url");
        return (s == null || s.length() == 0) ? OtaClient.otaUrlOf(app) : s;
    }

    /**
     * web 控制台「切换服务器」：写完 files/ota_url、files/ws_url 之后调用。
     *   1) ws 直连覆盖立即生效；
     *   2) 清掉与旧服务器绑定的缓存（token / ws 地址 / 激活码）—— OtaClient 自己
     *      也会按地址比对作废，这里显式清一次，免得同地址换回来还抱着旧凭证；
     *   3) 重新 OTA（发往新地址）+ 重连 WS。
     */
    public static String applyServerFlags() {
        StringBuilder sb = new StringBuilder("OK 服务器已切换: ");
        try {
            String wso = wsOverride();
            if (wso != null && ws != null) {
                ws.setUrl(wso);
                sb.append("(ws 直连) ");
            }
            sb.append(otaUrl());
            android.content.SharedPreferences sp = app.getSharedPreferences(
                    OtaClient.PREF, android.content.Context.MODE_PRIVATE);
            sp.edit().remove("token").remove("ws_url").remove("ota_url")
                    .remove(OtaClient.KEY_ACTIVATION).apply();
            SessionLog.i("web: server switch -> ota=" + otaUrl()
                    + " ws_override=" + (wso == null ? "none" : wso));
            if (ota != null) {
                ota.fetch();
            }
            restartWs();
            return sb.toString();
        } catch (Throwable t) {
            return "ERR 服务器切换失败: " + t;
        }
    }

    /**
     * web 控制台 / 切服务器后重连 WS：先关旧连接，1s 后**探测式**连一次。
     *
     * ⚠️ v1.3.109：这里必须用 probe() 而不是 connect()。
     * connect() 是「会话要用这条路」，会在 IDLE 期维持一条长连接；而它的
     * handshake 回调会把 SessionController 从 IDLE 强行推到 LISTENING，
     * 凭空造出一个「没开麦的伪会话」—— 之后所有唤醒都被引到
     * 「会话已存在」分支，上行零帧，服务器一句也听不见，
     * 要等 60s 无 TTS 看门狗把它收掉才恢复（用户体感：切完服务器聋一阵）。
     * probe() 只确认地址可握手，握手由 SessionController 判定后立刻拆掉。
     */
    public static void restartWs() {
        final XiaoZhiWs c = ws;
        if (c == null) {
            return;
        }
        SessionLog.i("web: ws probe restart requested");
        mainHandler.post(new Runnable() {
            public void run() {
                try {
                    c.close("web-restart");
                } catch (Throwable t) {
                    Log.w(TAG, "ws close: " + t);
                }
                mainHandler.postDelayed(new Runnable() {
                    public void run() {
                        try {
                            c.probe();
                        } catch (Throwable t) {
                            Log.w(TAG, "ws probe: " + t);
                        }
                    }
                }, 1000);
            }
        });
    }

    /**
     * v1.3.141：只断开当前 ws，**不重连**（与 {@link #restartWs()} 的区别：那个
     * 还会 probe 一次新地址）。
     *
     * <p>用途：切换「自定义音乐库」开关后让下一句立刻用新工具表 ——
     * 服务器只在**握手时问一次** {@code tools/list}（实测 22:02:52 每条新连接问一次），
     * 连接是复用的（idle 60s 内不拆），所以光改开关，正在用的这条连接手里
     * 还是旧工具表 ⇒ 体感「得重启小智才生效」。断掉这条连接，下一句唤醒
     * 就会重新握手、重新问表 —— 比重启进程（5s 不可用）轻得多。</p>
     */
    public static void dropWs(String why) {
        final XiaoZhiWs c = ws;
        if (c == null) {
            return;
        }
        SessionLog.i("web: ws drop (" + why + ") —— 下一句重新握手 / 重新上报 MCP 工具表");
        mainHandler.post(new Runnable() {
            public void run() {
                try {
                    c.close("web-drop:" + why);
                } catch (Throwable t) {
                    Log.w(TAG, "ws drop close: " + t);
                }
            }
        });
    }

    /**
     * v1.3.143：调试唤醒 —— 等价于对着音箱喊了一声「小智小智」（走同一条
     * {@code handleWakeup()}，唤醒让路 / 采样 / 开流全部照跑）。
     *
     * <p>为什么需要：调「音乐让路」这种时序问题，以前只能请用户对音箱喊一遍再拉日志，
     * 一轮往返好几分钟。现在 `curl '.../api/act?op=wakeup'` 就能复现整条链路。</p>
     */
    public static boolean debugWakeup() {
        final SessionController s = session;
        if (s == null) {
            Log.w(TAG, "debugWakeup: session == null");
            SessionLog.i("web: debug wakeup FAIL (session 还没起来)");
            return false;
        }
        mainHandler.post(new Runnable() {
            public void run() {
                try {
                    s.debugWakeup();
                } catch (Throwable t) {
                    Log.w(TAG, "debugWakeup: " + t);
                }
            }
        });
        return true;
    }

    /**
     * v1.3.68：把 r1client 的 nativeLibraryDir 插进本进程 ClassLoader 的
     * DexPathList.nativeLibraryDirectories。
     *
     * 为什么必须反射插：sherpa 的 Java 类 static 块写死 System.loadLibrary("sherpa-onnx-jni")，
     * 而 jp2 自己的 nativeLibraryDir 里没有这个 so；platform_app 域又不能 execute
     * app_data_file（files/ 里自持的 so 加载不了）。只有 PM 下发的 data/app 下的 lib
     * 目录是此域可执行的，插进搜索路径后 loadLibrary 才能命中。
     */
    private static void injectNativeLibDir(String dir) {
        if (dir == null) return;
        try {
            ClassLoader cl = app.getClassLoader();
            java.lang.reflect.Field pf = Class.forName("dalvik.system.BaseDexClassLoader")
                    .getDeclaredField("pathList");
            pf.setAccessible(true);
            Object pathList = pf.get(cl);
            java.lang.reflect.Field nf = pathList.getClass()
                    .getDeclaredField("nativeLibraryDirectories");
            nf.setAccessible(true);
            Object cur = nf.get(pathList);
            if (cur instanceof File[]) {
                File[] a = (File[]) cur;
                File[] b = new File[a.length + 1];
                System.arraycopy(a, 0, b, 0, a.length);
                b[a.length] = new File(dir);
                nf.set(pathList, b);
                Log.i(TAG, "inject nativeLibraryDir(File[]) " + dir);
            } else if (cur instanceof java.util.List) {
                java.util.List<File> l = (java.util.List<File>) cur;
                l.add(new File(dir));
                Log.i(TAG, "inject nativeLibraryDir(List) " + dir);
            } else {
                Log.w(TAG, "unknown nativeLibraryDirectories type: " + cur);
                return;
            }
            SessionLog.i("inject nativeLibraryDir=" + dir);
        } catch (Throwable t) {
            Log.e(TAG, "injectNativeLibDir failed", t);
            SessionLog.i("injectNativeLibDir FAILED: " + t);
        }
    }

    /** v1.3.68：把 r1client base.apk 挂进本进程 AssetManager（KWS 模型在 assets/kws）。 */
    private static void injectAssets(String apk) {
        if (apk == null) return;
        try {
            android.content.res.AssetManager am = app.getAssets();
            java.lang.reflect.Method m = android.content.res.AssetManager.class
                    .getDeclaredMethod("addAssetPath", String.class);
            m.setAccessible(true);
            Object cookie = m.invoke(am, apk);
            StringBuilder sb = new StringBuilder();
            String[] list = am.list("kws");
            if (list != null) for (String s : list) sb.append(s).append(' ');
            Log.i(TAG, "addAssetPath cookie=" + cookie + " assets/kws=[" + sb.toString().trim() + "]");
            SessionLog.i("addAssetPath cookie=" + cookie + " kws=[" + sb.toString().trim() + "]");
        } catch (Throwable t) {
            Log.e(TAG, "injectAssets failed", t);
            SessionLog.i("injectAssets FAILED: " + t);
        }
    }

    /** v1.3.68：异步初始化 sherpa KWS（native 初始化可能崩，必须隔离在独立线程）。 */
    private static void startKwsAsync() {
        final Handler h = mainHandler;
        Thread t = new Thread(new Runnable() {
            public void run() {
                boolean ok = false;
                long t0 = SystemClock.elapsedRealtime();
                try {
                    injectNativeLibDir(r1LibDir);
                    injectAssets(r1Apk);
                    kws = new KwsEngine(app, new KwsEngine.OnKeyword() {
                        public void onKeyword(final String kw) {
                            h.post(new Runnable() {
                                public void run() {
                                    SessionController s = session;
                                    if (s != null) s.onKeyword(kw);
                                }
                            });
                        }
                    });
                    ok = kws.init();
                } catch (Throwable e) {
                    Log.e(TAG, "kws init threw (java-level)", e);
                    SessionLog.i("kws init threw: " + e);
                }
                final boolean okF = ok;
                Log.i(TAG, "kws init=" + okF + " (" + (SystemClock.elapsedRealtime() - t0) + "ms)");
                SessionLog.i("kws init=" + okF);
                h.post(new Runnable() {
                    public void run() {
                        SessionController s = session;
                        if (s != null) {
                            s.setKws(okF ? kws : null);
                            if (okF) s.startAlwaysOnMic();   // 麦常驻：IDLE 期也要喂 KWS
                        }
                        Log.i(TAG, "kws wired=" + okF
                                + (okF ? " (mic -> always-on)" : " (bus fallback)"));
                    }
                });
            }
        }, "xz-kws-init");
        t.setPriority(Thread.NORM_PRIORITY);
        t.start();
    }

    private static void ensureTts() {
        if (!ttsAttemptDone.compareAndSet(true, false)) return;
        final AtomicBoolean ok = new AtomicBoolean(false);
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    TtsPlayer p = new TtsPlayer();
                    p.start();
                    if (p.isStarted()) {
                        player = p;
                        ok.set(true);
                        SessionController s = session;
                        if (s != null) s.setPlayer(p);
                    }
                } catch (Throwable tr) {
                    Log.e(TAG, "TtsPlayer.start threw", tr);
                } finally {
                    ttsAttemptDone.set(true);
                }
            }
        }, "xz-tts-init");
        t.setDaemon(true);
        t.start();
        try { t.join(AUDIO_WATCHDOG_MS); } catch (InterruptedException ignored) {}
        Log.i(TAG, "tts init ok=" + ok.get());
        SessionLog.i("tts init ok=" + ok.get());
    }

    private static void startLivenessTick() {
        mainHandler.postDelayed(new Runnable() {
            private int n = 0;
            public void run() {
                n++;
                Log.i(TAG, "xz alive " + (n * 30) + "s (tts=" + (player != null)
                        + " mic=" + (mic != null && mic.isRunning())
                        + " sess=" + (session != null ? session.getState().name() : "-")
                        + " bus=" + (wakeup != null) + ")");
                mainHandler.postDelayed(this, 30000);
            }
        }, 30000);
    }
}
