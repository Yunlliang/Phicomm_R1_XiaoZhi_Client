package cn.workbuddy.r1client;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * 小智官方服务器 WebSocket 客户端（按需连接模式）。
 *
 * 关键决策（吸取 C3 主线经验）：
 * 1. 按需连接：空闲时零连接，避免官方服务器 70s/80s 空闲踢出。
 * 2. 上行裸 opus，绝不加 [0x01,0x01] 之类帧头（C3 的 ASR 单字 bug 根因）。
 * 3. TTS stop 后延迟 10s 主动 close（与 S2 official 固件行为一致）。
 */
public final class XiaoZhiWs {

    private static final String TAG = "R1CLIENT";

    /** 工具名前缀：音乐（走 DLNA，见 PlayerBridge 头注释）。 */
    private static final String PLAYER_PREFIX = "self.r1player.";
    /** v1.3.157：工具名前缀：灯控（氛围灯 / 夜灯），不受「自定义音乐库」开关影响。 */
    private static final String LIGHT_PREFIX = "self.r1light.";

    public interface Listener {
        void onConnected();

        void onHandshakeDone();

        void onTtsStart();

        void onTtsAudio(byte[] opusFrame);

        void onTtsStop();

        void onStt(String text);

        void onClosed(String reason);

        void onError(String msg);
    }

    /** 官方服务器（同 C3 固件）。v1.2.5 起可被 OTA 下发的 websocket.url 覆盖 */
    private static final String WS_URL = "wss://api.tenclass.net/xiaozhi/v1/";
    private volatile String wsUrl = WS_URL;

    /**
     * TTS 结束后多久主动断开（毫秒）—— 本地**兜底**值，可被 flag 覆盖。
     * v1.3.12：10s -> 15s。
     * v1.3.15：15s -> 120s（「延长连续对话」）。官方设备并不在本地做空闲计时，
     * 而是由服务器在 listening 空闲 ~70s 下发 TTS 提醒、~80s 关闭会话。
     * 我们把本地兜底放到 120s（远大于服务器 80s），让服务器先接管会话生命周期：
     * 用户听到的是官方的「要断开了」语音提醒，而不是被我们冷不丁断线。
     */
    private static final long IDLE_CLOSE_DELAY_MS = 120_000L;
    /** v1.3.15：运行期覆盖（来自 files/idle<N>），markIdle 时取此值 */
    private volatile long idleCloseDelayMs = IDLE_CLOSE_DELAY_MS;

    /** 重连退避上限 */
    private static final long BACKOFF_MAX_MS = 30_000L;

    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private OkHttpClient client;
    // ⚠️ v1.2.8 血泪坑：okhttp 的 newWebSocket() 返回值 / onOpen(webSocket) 参数
    // 必须显式赋给本字段！v1.2.2~v1.2.7 里 ws 永远是 null → sendText/sendAudio
    // 全部静默丢弃 → hello 从未真正发出 → 服务器「沉默」被误判成协议问题一整天。
    // volatile：onOpen 在 okhttp 线程赋值，sendAudio 在录音线程读取。
    private volatile WebSocket ws;
    private final AtomicBoolean open = new AtomicBoolean(false);
    /** v1.2.9：每条连接只记前 2 个二进制帧（排查「服务器用二进制回 hello」） */
    private int binLogged = 0;
    // v1.2.13：上行音频帧计数。NAS 实测握手通了但 25s 无 STT —— 服务器到底
    // 收没收到我们的音频，客户端先自证。volatile ++ 非原子，诊断用途足够
    private volatile int uplinkSent;
    private volatile int uplinkFail;

    private String token;              // Authorization: Bearer <token>

    /**
     * v1.3.103：本条 socket 上「服务器 hello 是否已回」。
     *
     * 用途：区分「socket 已开但握手未成」与「会话已就绪」。web 控制台切换服务器后
     * 会先把 socket 连上做一次连通性探测（见 restartWs），此时并没有会话在等握手；
     * 若 SessionController 随后 startSession()，ws.connect() 又会因为
     * `open.get()==true` 被直接忽略 —— hello 永远没人发，状态卡死在 CONNECTING。
     * 有了这个标记，connect() 在「已开未握手」时补发 hello 就能把会话救回来。
     */
    private volatile boolean helloDone = false;
    // v1.2.7：官方服务器靠这两个头把 ws 连接路由到设备会话。
    // 缺失时升级握手照常成功（token 只管鉴权），但 hello 发出后
    // 服务器无法把连接关联到已绑定设备 → 永不回应 hello（v1.2.6 实测 15s 静默）。
    private volatile String deviceId;
    private volatile String clientId;
    private long reconnectBackoff = 1000L;
    private volatile boolean wantConnected;
    private int idleCloseSeq = 0;

    /**
     * v1.3.19：连接代数。**每次 close() / connect() 自增**，每个 WebSocketListener
     * 在建立时捕获当时的代数；回调进来先比对，代际不符 = 陈旧 socket 的迟到回调，
     * 一律丢弃。
     *
     * 修的是什么（实测 2026-10-04 17:10 连续三次「硬重启」零回复）：
     * hardRestart() → close()（把 ws 置空、open 置 false）→ okhttp 的 onClosed
     * 是**异步**的，等它回到主线程时，enterIdle 已经把新会话 connect() 起来了；
     * 此时旧 socket 的 onClosed 仍会执行 `open.set(false); ws = null;` —— 把
     * **新** socket 的字段砸掉，并因为 wantConnected 已是 true 而再触发一次
     * scheduleReconnect → 新会话被自己人拆掉，服务器侧永远等不到音频。
     * 有了代数之后，旧 socket 的一切回调都失效；close() 由自己投递 onClosed，
     * 时序完全可控。
     */
    private volatile int connGen = 0;

    private final Runnable idleCloseTask = new Runnable() {
        public void run() {
            Log.i(TAG, "idle timeout reached, closing ws (on-demand mode)");
            close("idle-timeout");
        }
    };

    public XiaoZhiWs(Listener l) {
        this.listener = l;
    }

    public void setToken(String token) {
        this.token = token;
    }

    /** v1.2.7：设备标识（MAC），必须与 xiaozhi.me 绑定时的一致 */
    public void setDeviceId(String id) {
        this.deviceId = id;
    }

    /** v1.2.7：客户端 UUID，与 OTA 请求头保持同一个 */
    public void setClientId(String id) {
        this.clientId = id;
    }

    /** v1.2.5：OTA 下发的 ws 地址（下次 connect 生效） */
    public void setUrl(String url) {
        if (url != null && url.length() > 0) {
            this.wsUrl = url;
            Log.i(TAG, "ws url set from OTA: " + url);
        }
    }

    public boolean isOpen() {
        return open.get();
    }

    /** v1.3.99：web 控制台显示用（OTA 覆盖后的实际地址） */
    public String getUrl() {
        return wsUrl;
    }

    /** v1.3.15：覆盖空闲断开时长（服务层按 files/idle<N> 传入），markIdle 时生效 */
    public void setIdleCloseDelayMs(long ms) {
        if (ms > 0) {
            this.idleCloseDelayMs = ms;
            Log.i(TAG, "idle close delay = " + ms + "ms");
        }
    }

    // ---------------- 连接管理 ----------------

    public void connect() {
        connectInternal(true);
    }

    /**
     * v1.3.103：连通性探测（切换服务器后用）—— 只想确认「这个地址能握手」，
     * **不维持长连接**。
     *
     * 为什么单独一条路径：handshake 一旦成功，SessionController 会发现当前是 IDLE
     * （没有会话在等）并立刻把这条 socket 拆掉（见 SessionController.onHandshakeDone）；
     * 而 persistent=false 让失败的探测**不再自动重连**，避免地址填错时在后台
     * 按 1/2/4…30s 无限刷 —— 那正是上一轮「OTA 填成 WS 端口」时最碍眼的噪声。
     */
    public void probe() {
        connectInternal(false);
    }

    private void connectInternal(boolean persistent) {
        wantConnected = persistent;
        if (open.get() || ws != null) {
            // v1.3.103：socket 已经开着（多半是「切换服务器后的连通性探测」留下的）。
            // 以前这里直接 return，导致随后真正的 session start 拿不到 hello
            // ⇒ SessionController 卡在 CONNECTING ⇒ 喊破喉咙也没人应。
            // 现在：只要这条连接上还没收到服务器 hello，就补发一次。
            if (!helloDone) {
                Log.w(TAG, "connect() on already-open socket without handshake -> re-send hello");
                SessionLog.i("connect() re-sent hello on pre-opened socket");
                sendHello();
            }
            return;
        }
        helloDone = false;
        main.removeCallbacks(idleCloseTask);

        final Request.Builder rb = new Request.Builder()
                .url(wsUrl)
                .addHeader("User-Agent", "R1Client/1.0")
                .addHeader("Protocol-Version", "1");
        if (token != null && token.length() > 0) {
            rb.addHeader("Authorization", "Bearer " + token);
        }
        // v1.2.7：补齐 ESP32 固件同款握手头 —— 官方服务器以此路由设备会话，
        // 缺了任何一组，升级握手成功但 hello 永远无人回应。
        if (deviceId != null && deviceId.length() > 0) {
            rb.addHeader("Device-Id", deviceId);
        }
        if (clientId != null && clientId.length() > 0) {
            rb.addHeader("Client-Id", clientId);
        }

        final int gen = ++connGen;   // v1.3.19：本条连接的代数，回调进来先校验
        Log.i(TAG, "connecting " + wsUrl);
        SessionLog.i("connecting " + wsUrl);
        getClient().newWebSocket(rb.build(), new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                if (gen != connGen) {
                    // v1.3.19：陈旧连接迟到的 onOpen —— 直接关掉，绝不污染 ws 字段
                    try {
                        webSocket.close(1000, "stale");
                    } catch (Throwable ignored) {
                    }
                    return;
                }
                ws = webSocket;   // v1.2.8：先持有 socket，否则 sendHello/sendAudio 全部落空
                open.set(true);
                reconnectBackoff = 1000L;
                binLogged = 0;
                uplinkSent = 0;
                uplinkFail = 0;
                Log.i(TAG, "ws open");
                SessionLog.i("ws open");
                sendHello();
                main.post(new Runnable() {
                    public void run() {
                        listener.onConnected();
                    }
                });
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                if (gen != connGen) {
                    return;   // v1.3.19：陈旧连接的行一律丢弃
                }
                handleText(text);
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                if (gen != connGen) {
                    return;   // v1.3.19：陈旧连接的下行音频一律丢弃
                }
                // 服务器下发的音频：裸 opus。v1.2.9：只记前 2 帧防刷屏 ——
                // 文本帧有 <- 日志，二进制帧此前完全隐身，无法排除服务器用
                // 二进制回 hello 的可能
                if (binLogged < 2) {
                    binLogged++;
                    Log.i(TAG, "<- bin#" + binLogged + " " + bytes.size() + "B");
                    SessionLog.i("<- bin#" + binLogged + " " + bytes.size() + "B");
                }
                final byte[] data = bytes.toByteArray();
                main.post(new Runnable() {
                    public void run() {
                        listener.onTtsAudio(data);
                    }
                });
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                if (gen != connGen) {
                    return;   // v1.3.19：陈旧连接的失败回调 —— 不能掐掉新会话
                }
                open.set(false);
                ws = null;
                SessionLog.i("uplink summary: sent=" + uplinkSent + " fail=" + uplinkFail);
                String code = (response != null) ? ("http=" + response.code()) : "";
                Log.e(TAG, "ws failure " + code + " : " + t);
                SessionLog.i("ws failure " + code + " : " + t);
                main.post(new Runnable() {
                    public void run() {
                        listener.onError("ws failure " + code + " " + t.getMessage());
                        if (wantConnected) {
                            scheduleReconnect();
                        } else {
                            listener.onClosed("failure");
                        }
                    }
                });
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                if (gen != connGen) {
                    return;   // v1.3.19：陈旧连接的关闭回调 —— close() 已自己投递过 onClosed
                }
                open.set(false);
                ws = null;
                SessionLog.i("uplink summary: sent=" + uplinkSent + " fail=" + uplinkFail);
                Log.i(TAG, "ws closed code=" + code + " reason=" + reason);
                SessionLog.i("ws closed code=" + code + " reason=" + reason);
                final String r = "code=" + code + " " + reason;
                main.post(new Runnable() {
                    public void run() {
                        if (wantConnected) {
                            listener.onClosed(r + " (will reconnect)");
                            scheduleReconnect();
                        } else {
                            listener.onClosed(r);
                        }
                    }
                });
            }
        });
    }

    private void scheduleReconnect() {
        long delay = reconnectBackoff;
        reconnectBackoff = Math.min(BACKOFF_MAX_MS, reconnectBackoff * 2);
        Log.i(TAG, "reconnect in " + delay + "ms");
        main.postDelayed(new Runnable() {
            public void run() {
                if (wantConnected && !open.get()) {
                    connect();
                }
            }
        }, delay);
    }

    /**
     * 主动断开（进入 IDLE）。
     *
     * ⚠️ v1.2.5 关键修复：socket 从未建立时（如连接被服务器 401 拒绝后
     * 看门狗前来关闭），okhttp 不会产生任何回调 —— 之前的实现直接返回，
     * SessionController.enterIdle() 永远不执行 → 会话卡死 CONNECTING、
     * 麦克风永不归还 → 原厂 KWS 永久致聋（v1.2.4 实机「喊小讯就死」根因）。
     * 这里必须手动补发 onClosed 让状态机收尾。
     */
    public void close(String reason) {
        wantConnected = false;
        // v1.3.19：代数自增 —— 现存 socket 的一切回调（onOpen/onMessage/onClosed/
        // onFailure）从此全部失效；onClosed 改由我们自己投递，不再依赖 okhttp 的
        // 异步回调，彻底消除「旧 socket 迟到回调砸掉新会话」的竞态（详见 connGen）。
        connGen++;
        helloDone = false;   // v1.3.103：连接重置，握手状态一并归零
        main.removeCallbacks(idleCloseTask);
        WebSocket w = ws;
        ws = null;
        open.set(false);
        final String r = (w == null) ? (reason + " (socket never opened)") : reason;
        if (w != null) {
            try {
                w.close(1000, reason);
            } catch (Throwable ignored) {
            }
        }
        main.post(new Runnable() {
            public void run() {
                listener.onClosed(r);
            }
        });
    }

    /** 标记「TTS 已结束」，启动空闲断开计时（时长见 idleCloseDelayMs） */
    public void markIdle() {
        main.removeCallbacks(idleCloseTask);
        main.postDelayed(idleCloseTask, idleCloseDelayMs);
    }

    // ---------------- 发送 ----------------

    private void sendHello() {
        try {
            JSONObject audio = new JSONObject();
            audio.put("format", "opus");
            audio.put("sample_rate", AudioCapture.SAMPLE_RATE);
            audio.put("channels", AudioCapture.CHANNELS);
            audio.put("frame_duration", 60);

            JSONObject o = new JSONObject();
            o.put("type", "hello");
            o.put("version", 1);
            // v1.2.9：与 ESP32 固件 hello 逐字段对齐 —— 官方对真 hello 仍静默时，
            // 唯一内容差异就是缺 features（mcp 能力声明）
            JSONObject features = new JSONObject();
            features.put("mcp", true);
            o.put("features", features);
            o.put("transport", "websocket");
            o.put("audio_params", audio);
            // v1.2.8：如实记录 —— 之前 sendText 静默失败后这里照样打 "hello sent"，
            // 把「从没发出过」伪装成「已发送」，误导了 v1.2.2~v1.2.7 的全部诊断。
            if (sendText(o.toString())) {
                Log.i(TAG, "hello sent: " + o);
                SessionLog.i("hello sent: " + o);
            } else {
                Log.e(TAG, "hello NOT sent (socket missing): " + o);
                SessionLog.i("hello NOT sent (socket missing)");
            }
        } catch (Throwable t) {
            Log.e(TAG, "sendHello threw", t);
        }
    }

    /** 发送裸 opus 帧（零帧头）。v1.2.13：逐帧计数落盘（1-3 全记，后每 50 帧记一次） */
    public boolean sendAudio(byte[] opusFrame) {
        WebSocket w = ws;
        if (w == null || !open.get()) {
            uplinkFail++;
            if (uplinkFail <= 3 || uplinkFail % 50 == 0) {
                SessionLog.i("uplink FAIL x" + uplinkFail + " (socket missing/closed)");
            }
            return false;
        }
        boolean ok = w.send(ByteString.of(opusFrame));
        if (ok) {
            uplinkSent++;
            if (uplinkSent <= 3 || uplinkSent % 50 == 0) {
                SessionLog.i("uplink #" + uplinkSent + " (" + opusFrame.length + "B opus)");
            }
        } else {
            uplinkFail++;
            if (uplinkFail <= 3 || uplinkFail % 50 == 0) {
                SessionLog.i("uplink FAIL x" + uplinkFail + " (send() rejected)");
            }
        }
        return ok;
    }

    /**
     * v1.2.14：mode manual→auto。
     * manual 语义 = 客户端自己 VAD、说完发 listen stop —— 我们没有客户端
     * VAD，永远不发 stop，服务器若按 manual 语义等收尾就永远不出 ASR。
     * auto = 对齐 ESP32 官方默认，判停交给服务器 VAD。
     * 背景（NAS 21:24 会话）：hello/listen 都到达，但服务端零音频处理日志。
     */
    public void sendStartListening() {
        // ★ v1.3.130：mode 回到 "auto"，实时打断整条线**作废**（2026-10-12 用户决定）。
        //
        //   历史：v1.3.124~v1.3.129 这里写死过 "realtime"（外加逐帧人声门限放行上行）。
        //   作废原因不是协议不对，而是**本机前端消音撑不起全双工**：
        //   原厂 HAL 的回声抑制是「存在活跃播放流」门控的，而小智 TTS 是服务端逐句
        //   断续流 ⇒ 每句头尾各有 200~400ms 抑制盲区，固有、不可修。门限放行必然被
        //   自己句末的漏音顶开 ⇒ 自己打断自己；收紧又变成「说话打不断」。
        //   要根治只能上客户端软件 AEC / 参考对齐，收益不匹配成本。
        //
        //   "auto" = 对齐 ESP32 官方默认，也是 v1.3.120 及以前长期稳定的行为：
        //   半双工，判停交给服务端 VAD。打断改由**唤醒词打断**承担
        //   （TTS 中喊「小智小智」→ SessionController.handleWakeup 的 TTS_PLAYING 分支）。
        //
        //   官方协议 `listen.mode` 三态：
        //     auto     = 半双工（服务端播完 TTS 才肯听你）
        //     manual   = 客户端说 stop 才停（按键式，我们没有客户端 VAD，永远不发 stop ⇒ 会卡死）
        //     realtime = 全双工（服务端 TTS 期照跑 VAD，听到插话自己 abort）
        sendText("{\"type\":\"listen\",\"state\":\"start\",\"mode\":\"auto\"}");
    }

    public void sendStopListening() {
        sendText("{\"type\":\"listen\",\"state\":\"stop\"}");
    }

    /**
     * v1.3.81：客户端打断服务器当前输出 —— 官方 `Protocol::SendAbortSpeaking()`
     * （xiaozhi-esp32/main/protocols/protocol.cc）：
     *   {"type":"abort","reason":"wake_word_detected"}
     * 反向的 {"type":"tts","state":"abort"} 我们早就处理了（服务器打断我们）；
     * 但**客户端 -> 服务器**这条一直没发过。
     *
     * 后果（2026-10-08 22:3x 实测）：小智放歌时（音乐是服务器下发的音频流，走 TTS
     * 通道）喊「小智小智」，本地 `TtsPlayer.abort()` 只把**已经收到**的帧冲掉，
     * 服务器那边音乐还在往下推 —— 于是「停一下就又播起来」，要等用户真的开口、
     * 服务器出了 STT 才停。发 abort 才能让服务器自己收嘴。
     */
    public void sendAbort(String reason) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", "abort");
            if (reason != null && reason.length() > 0) {
                o.put("reason", reason);
            }
            sendText(o.toString());
        } catch (Throwable t) {
            Log.e(TAG, "sendAbort threw", t);
        }
    }

    /**
     * v1.3.21：上报唤醒词 —— 官方 `Protocol::SendWakeWordDetected()` 的同名动作
     * （xiaozhi-esp32/main/protocols/protocol.cc:67）：
     *   {"session_id":"..","type":"listen","state":"detect","text":"<唤醒词>"}
     * 我们整条链路都没有 session_id（hello 也没带），故省略；服务端
     * `listenMessageHandler` 只读 state / text，缺 session_id 不影响。
     * 服务端收到后会走「唤醒词分支」：命中 wakeup_words 就回一句问候。
     */
    public void sendWakeWordDetected(String wakeWord) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", "listen");
            o.put("state", "detect");
            o.put("text", wakeWord);
            sendText(o.toString());
        } catch (Throwable t) {
            Log.e(TAG, "sendWakeWordDetected threw", t);
        }
    }

    // ---------------- v1.2.13：MCP 最小应答 ----------------

    private void sendMcpInitializeResult(int id) {
        try {
            JSONObject info = new JSONObject();
            info.put("name", "r1-client");
            info.put("version", "1.3.6");
            JSONObject result = new JSONObject();
            result.put("protocolVersion", "2024-11-05");
            result.put("capabilities", new JSONObject());
            result.put("serverInfo", info);
            sendMcpResult(id, result);
        } catch (Throwable t) {
            Log.e(TAG, "sendMcpInitializeResult threw", t);
        }
    }

    /**
     * v1.3.86：音箱播放器工具表（走 DLNA，见 PlayerBridge 头注释）。
     * 工具名统一前缀 self.r1player.*，避免和服务器自带的 self.music.* 撞名。
     *
     * ⚠️ description 的第一句很关键：必须让模型知道「要播歌就得先指定曲目」，
     * 否则它会像 v1.3.85 那样只调一个空 play 就宣布"已经在播了"。
     */
    private void sendMcpToolsResult(int id) {
        // v1.3.139：自定义音乐库关着 ⇒ 不上报 self.r1player.*，播放音乐/电台就只能
        // 用小智服务器自带的那一套。（按需连接架构 ⇒ 下次唤醒握手时才会重新问
        // tools/list，即「下轮生效」）
        // ★ v1.3.157：灯控工具**不受这个开关影响** —— 那是音乐的事，关了音乐
        //   照样能开灯。所以这里上报的是「只有灯」而不是空表（空表会把灯一起弄丢）。
        if (!PlayerBridge.isMyMusic()) {
            try {
                org.json.JSONArray lightOnly = new org.json.JSONArray();
                addLightTools(lightOnly);
                JSONObject result = new JSONObject();
                result.put("tools", lightOnly);
                sendMcpResult(id, result);
                SessionLog.i("-> mcp tools/list: " + lightOnly.length() + " tools"
                        + " (自定义音乐库已关闭 ⇒ 只有灯控)");
            } catch (Throwable t) {
                Log.e(TAG, "sendMcpToolsResult(light only) threw", t);
            }
            return;
        }
        try {
            org.json.JSONArray tools = new org.json.JSONArray();
            tools.put(tool("play_song",
                    "播放**指定的某一首**歌。用户说「播放晴天」「我想听七里香」时用这个，"
                            + "必须传 title（完整歌名）。它会把这一首排在第一位、后面接上整个曲库，"
                            + "播完自动续播；"
                            + "如果你想连播某个歌手的**多首**，改用 play_artist。"
                            + "不知道该播什么时先用 list 或 search 查库里有什么。"
                            + "⚠️ 电台（先锋898、FM91.8 这类）不是歌，要用 play_radio。"
                            + "这是让音箱出声的入口之一，不要只调 play。"
                            // v1.3.157：后台已支持「歌手 + 歌名」分词匹配，必须让模型别自作主张拆分
                            + "★ 用户把**歌手和歌名一起说了**（「播放周杰伦的晴天」"
                            + "「我想听陈奕迅的浮夸」），请把整句**原样**传进 title，"
                            + "不要自己拆成只有歌名 —— 后台会按歌手+歌名一起匹配，"
                            + "你只传歌名的话很可能挑到翻唱版。"
                            // v1.3.175：★ 这条是「只播池子里的歌」的根治 —— 以前模型
                            // 习惯先调 search 查池子，搜不到就回复"没找到"，根本走不到
                            // 后台的在线兜底。现在明确告诉它：点名就要直接调。
                            + "★ 用户**点名要听某首歌**时，直接调这个（哪怕你觉得库里可能没有）"
                            + "—— 后台会先查本地曲库，没有就**自动去在线搜索**并播放；"
                            + "**不要**先调 search 试探（search 只覆盖本地曲库池，"
                            + "搜不到不代表没有这首歌）。"
                            // 同名曲播错了用户只会觉得「点歌很笨」，必须让模型转达
                            + "★ 返回结果里如果写了「还有 N 首同名的」，说明库里有多个版本"
                            + "（原唱/翻唱/Live），**必须把这件事告诉用户**并问要不要换"
                            + "（如「还找到周杰伦版的，要听吗」），不要闷声播完就当完事。",
                    prop("title", "string", "歌名，如「晴天」；歌手+歌名一起说时整句原样传入，"
                            + "如「周杰伦的晴天」")));
            // v1.3.112：补上「按条件筛选后随机」这一档 —— 之前只有「播某一首」和
            // 「全库随机」，用户说「随便放点周杰伦」时模型没有可用工具，只能退化成
            // 播一首或全库随机，体验就是「点歌很笨」。
            tools.put(tool("play_artist",
                    "随机连播某个歌手（或某个关键词）的**多首**歌。"
                            + "用户说「放周杰伦的歌」「来几首邓丽君」「随便放点周杰伦」"
                            + "「我想听王菲的歌」时用这个 —— 凡是**指定了歌手但没指定具体歌名**，"
                                    + "一律用它，不要用 play_song（那个只播一首）、也不要用 random（那是全库随机）。"
                            + "不知道库里有哪些歌手时，先用 list 看歌手列表。"
                            // v1.3.178：以前 play_artist 只会在本地池里转，用户说「随机播放
                            // 周杰伦的歌」听到的永远是池里那 3 首。现在本地 + 在线一起上，
                            // 这条必须写进描述，模型才知道它能覆盖到池外的歌。
                            + "★ 后台会**自动补上在线搜到的同歌手歌曲**（本地曲库 + 在线曲库一起放），"
                            + "所以哪怕本地池里这个歌手只有两三首，也不用担心不够放；"
                            + "本地池里**完全没有**这个歌手时，它也会直接去在线找来播，"
                            + "**不要**因为你觉得库里可能没有就回复用户「找不到」。"
                            + "结果话术里会写明「本地 N 首 + 在线 M 首」，照原样告诉用户即可。"
                            + "注意：如果**连歌手/关键词都没有**（如「随机放 5 首」），请用 random 的 count",
                    props(prop("artist", "string", "歌手名，如「周杰伦」；也可以是风格/专辑等任意关键词"),
                            prop("count", "integer", "要播几首；**不填 = 这个歌手的全部歌曲**。"
                                    + "只有用户明确说了数量（「放 5 首周杰伦」「来 3 首」）才填，"
                                    + "没说数量就不要填"))));
            tools.put(tool("play_index",
                    "播放音乐库里**第 N 首**。用户说「放第 8 首」「第八首」「列表里的第三首」时用。"
                            + "序号从 1 开始，必须先通过 search 或 list 拿到带序号的列表才知道第几首是什么。",
                    prop("index", "integer", "第几首，从 1 开始的正整数")));
            tools.put(tool("search",
                    "只查询不播放：按歌名或歌手搜出匹配的曲目，**返回带序号的列表**。"
                            + "用户问「有没有周杰伦的歌」「库里有什么邓丽君」时用；"
                            + "或者当你需要序号来调 play_index 时先用它。"
                            // v1.3.175：讲清边界 —— 它只覆盖本地曲库池，搜不到≠没有
                            + "⚠️ 它**只覆盖本地曲库池**（约 100 首），搜不到≠没有这首歌；"
                            + "用户明确要听某首歌时请直接调 play_song（后台会自动在线搜），"
                            + "不要用这里的空结果回复用户「没找到」。",
                    prop("keyword", "string", "歌名或歌手名关键词")));
            tools.put(tool("random",
                    "开始随机播放。默认**整个曲库**随机排进播放列表（播完自动续不会停）。"
                            + "用户说「来点音乐」「随便放」「播放音乐」"
                            + "「放点歌」「随机播放」——只要**没有指定歌手也没有指定歌名**，"
                            + "一律用这个，不要用 next。"
                            + "⚠️ 用户说了**数量**（「随机放 5 首」「来 3 首」「放十首」）时必须传 count，"
                            + "**绝对不要用 next 去一首首凑数** —— next 只是切下一曲，"
                            + "既不会限制播放列表长度，还会把正在播的歌切掉。"
                            + "如果指定了歌手（如「放点周杰伦」），请用 play_artist（它也有 count）。"
                            // v1.3.178：把两条随机入口的分工写死，省得模型拿 random 糊弄「在线」
                            + "⚠️ 用户说了「**在线**随机」「网络的歌」「换一批」「来点没听过的」"
                            + "这类意思，请用 random_online（每次重新拉一批新的），"
                            + "不要用这个 —— 这个播的是本地曲库快照，内容每次都一样。"
                            + "这是「从无到有开始播」的入口。",
                    prop("count", "integer", "要随机几首；**不填 = 整个曲库**。"
                            + "用户说「随机放 5 首」就填 5")));
            // v1.3.178：「随机播放在线音乐」—— 与 random 的区别必须讲清楚，否则模型
            // 会把「在线」当成修辞继续调 random，用户听到的还是启动时那批老歌。
            tools.put(tool("random_online",
                    "从**网络在线曲库**随机拉一批歌来放，**每次都是新的一批**。"
                            + "用户说「随机播放在线音乐」「放点在线的歌」「在线随机播放」"
                            + "「换一批歌」「放点没听过的」「来点新歌」时用这个 —— "
                            + "凡是**带「在线 / 网络 / 新的一批 / 换一批」这类意思**、"
                            + "又没有指定歌手和歌名，一律用它，不要用 random。"
                            + "⚠️ 与 random 的区别：random 播的是**本地曲库快照**（启动时加载的那批，"
                            + "内容固定不变）；这个每次都去网络曲库重新摇一批，内容不一样。"
                            + "如果指定了歌手（「在线放点周杰伦」），请用 play_artist —— "
                            + "它现在也会同时搜在线的歌。",
                    prop("count", "integer", "要几首；**不填 = 100 首**（默认一批就是 100）。"
                            + "用户说「在线随机放 20 首」就填 20")));
            tools.put(tool("list",
                    "列出**本地曲库**概况：有哪些歌手（各多少首）、带序号的样例、电台列表。"
                            + "用户问「本地歌曲里有什么」「本地有多少首」「能放什么」「有哪些歌手」时用；"
                            + "想知道某个歌手具体有哪些歌请用 search。"
                            + "★ 返回里「本地共 N 首」才是本地曲库的数量，"
                            + "「另收录了 M 首在线听过的歌」不属于本地 —— 转述时别把两者混在一起。",
                    null));
            tools.put(tool("pause", "暂停当前正在播放的音乐", null));
            tools.put(tool("play", "继续播放（接暂停处）。只有用户说「继续/接着放」、"
                    + "且之前确实在播时才用；这不会选歌，要播新歌请用 play_song", null));
            tools.put(tool("stop", "停止播放", null));
            // v1.3.94：描述必须写死「只有已经在播才用」，否则模型会把「播放音乐」理解成切下一首
            tools.put(tool("next", "下一曲。**只有在音乐正在播放时**用户说「下一首」「换一首」才用；"
                    + "如果当前没在播，用户说「播放音乐」请用 random，用这个不会开始播放。"
                    + "⚠️ 它**不是**选歌/凑数量工具：用户要「放 5 首」请用 random 或 play_artist "
                    + "的 count，连续调 next 只会不停切歌", null));
            tools.put(tool("prev", "上一曲。**只有在音乐正在播放时**才用", null));
            tools.put(tool("volume", "设置音量 0~100",
                    prop("level", "integer", "音量 0~100")));
            // v1.3.117：电台独立成工具。之前只有 play_url 一个「网络地址」入口，
            // 于是模型把**电台名**当网址传（实测传过「先锋898」和编造的
            // 「http://fm91.8」），play_url 又只会回 OK ⇒ 播不出声还说"放起来了"。
            tools.put(tool("play_radio",
                    "播放**网络电台**。用户说「播放先锋898」「听 FM91.8」「放个电台」"
                            + "「换个电台」时用这个，传 name = 电台名（用 list 能查到有哪些电台）。"
                            + "⚠️ 电台名不是网址，**绝对不要**自己拼造 URL"
                            + "（例如把「FM91.8」写成 http://fm91.8），也不要用 play_url 播电台。"
                            + "要说**完整台名**（如「上海经典音乐广播」「Groove Salad」），"
                            + "只说「音乐台」这种泛词匹配不上。"
                            + "网络流要缓冲，可能几秒后才出声。"
                            + "⚠️ 一次只播一个台 —— 用户要把**所有**电台排进播放列表"
                            + "（「所有电台都放一遍」「电台全加进去」）请用 play_radio_all。"
                            + "★ 用户说「第 3 个电台」「第三个」「3 号台」这种**序号**时，"
                            + "**必须传 index = 那个数字**（3），不要去猜台名、也不要改用别的工具；"
                            + "序号就是 list 里电台名字前的那个编号。",
                    props(prop("name", "string", "电台名，如「先锋898」「FM91.8」；"
                            + "也可以直接写「第3个」这类序号"),
                            prop("index", "integer", "可选：第几个电台（1 开始）。"
                                    + "用户说「第 N 个电台」时填这个"))));
            // v1.3.119：一次把所有电台排进播放列表。之前 play_radio 只推一首，
            // 用户说「把所有广播电台添加到播放列表」时无从下手（要么报错，要么只放一个）。
            tools.put(tool("play_radio_all",
                    "把**所有网络电台**排进播放列表并开始播第一个。"
                            + "用户说「把所有电台加进播放列表」「所有电台都放一遍」"
                            + "「电台全部来一遍」时用这个（不用传 name）。"
                            + "想从某个台开始就传 name = 那个台名，它会被排到第一个。"
                            + "排好后说「下一首」「上一首」可以换台；"
                            + "⚠️ 直播流没有结尾，**不会自动跳下一个台**，换台只能手动。"
                            + "⚠️ 用户说的是「第 N 个电台」这种**某一个**台，请改用 play_radio "
                            + "并传 index，不要用这个把整张列表推过去。",
                    props(prop("name", "string", "可选：从哪个台开始播（完整台名）"),
                            prop("index", "integer",
                                    "可选：从第几个电台开始（1 开始）"))));
            tools.put(tool("play_url",
                    "播放一个**用户明确给出的**完整音频网址（http/https）。"
                            + "⚠️ 只有用户真的念出了/给出了完整网址时才用；"
                            + "**不要自己猜测或拼造网址**。"
                            + "要播电台请用 play_radio（传电台名），要播库里的歌用 play_song。",
                    prop("url", "string", "完整的 http/https 音频地址")));
            // v1.3.136：描述必须讲清「本地记录」也算数 —— 以前模型只看到 DLNA 的
            // NO_MEDIA_PRESENT 就断言「播放列表为空」，可歌其实还在响：
            // DLNA 只是外部控制通道，对 player 自己的播放引擎未必同步。
            tools.put(tool("status",
                    "查询音箱**当前是不是在放歌、放的是哪一首**。"
                            + "用户问「现在放的什么歌」「在放吗」「暂停一下」「声音关小点」"
                            + "这类问题**先查这个**，不要凭空回答。"
                            + "返回里有两项：**本地记录**（音箱自己的播放记录，重启后仍保留）"
                            + "和 DLNA状态（外部控制通道看到的状态）。"
                            + "★ 只要「本地记录=在播」就说明**确实在放**，"
                            + "即使 DLNA 状态是 NO_MEDIA_PRESENT / STOPPED，"
                            + "也**绝对不要**说「没在播」「播放列表为空」——"
                            + "DLNA 状态经常不同步（音箱自己起播时尤为如此）。"
                            + "两项都说没在播，才是真的没在播。", null));
            // v1.3.157：灯控工具（氛围灯）
            addLightTools(tools);
            JSONObject result = new JSONObject();
            result.put("tools", tools);
            sendMcpResult(id, result);
            SessionLog.i("-> mcp tools/list: " + tools.length() + " tools"
                    + " (r1player dlna + r1light)");
        } catch (Throwable t) {
            Log.e(TAG, "sendMcpToolsResult threw", t);
        }
    }

    private static JSONObject tool(String op, String desc, JSONObject props) throws Exception {
        return tool(PLAYER_PREFIX, op, desc, props);
    }

    /** v1.3.157：带前缀版 —— 灯控用 self.r1light.*，与音乐工具分开。 */
    private static JSONObject tool(String prefix, String op, String desc, JSONObject props)
            throws Exception {
        JSONObject t = new JSONObject();
        t.put("name", prefix + op);
        t.put("description", desc);
        JSONObject schema = new JSONObject();
        schema.put("type", "object");
        schema.put("properties", (props == null) ? new JSONObject() : props);
        t.put("inputSchema", schema);
        return t;
    }

    /**
     * v1.3.157：灯控工具表（氛围灯）。
     *
     * <p>为什么单独一个前缀：{@code self.r1player.*} 是「自定义音乐库」那一套，
     * 关掉音乐时它整个不上报；灯是独立能力，不该跟着一起消失。</p>
     */
    private void addLightTools(org.json.JSONArray tools) throws Exception {
        tools.put(tool(LIGHT_PREFIX, "ambient",
                "控制音箱的**氛围灯**（彩色灯效，不是唤醒时那圈白灯）。"
                        + "用户说「开氛围灯」「彩灯调成七彩」「灯亮一点」「灯暗一点」"
                        + "「渐变快点」「把灯变成蓝色」「关掉氛围灯」时用这个。"
                        + "所有参数都是可选的，**只传要改的那几项**；"
                        + "要一次改多项（「开灯并调成蓝色」）就同时传多个参数。",
                props(prop("on", "string", "开或关：\"on\" / \"off\"。不传 = 不改这一项"),
                        prop("mode", "integer",
                                "灯效：0 官方 / 1 七彩旋转 / 2 七彩 / 3 七彩旋转1 / 4 单色。"
                                        + "不传 = 不改"),
                        prop("luma", "integer", "亮度 1~200（默认 100）。不传 = 不改"),
                        prop("chroma", "integer", "颜色渐变速度 0~100（默认 4）。不传 = 不改"),
                        prop("color", "string",
                                "单色模式的颜色，如 \"#66CCFF\" 或 \"66CCFF\"；"
                                        + "只有 mode=4 单色时才看得出来"))));
    }

    private static JSONObject prop(String name, String type, String desc) throws Exception {
        JSONObject p = new JSONObject();
        p.put("type", type);
        p.put("description", desc);
        JSONObject props = new JSONObject();
        props.put(name, p);
        return props;
    }

    /** v1.3.112：多参数工具用 —— 合并若干个 prop() 返回的 properties 容器。 */
    private static JSONObject props(JSONObject... containers) throws Exception {
        JSONObject o = new JSONObject();
        if (containers != null) {
            for (JSONObject c : containers) {
                if (c == null) {
                    continue;
                }
                java.util.Iterator<String> it = c.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    o.put(k, c.get(k));
                }
            }
        }
        return o;
    }

    /** v1.3.86：MCP tools/call —— 转给 PlayerBridge（DLNA）。 */
    private void handleMcpToolCall(JSONObject payload) {
        int id = payload.optInt("id", -1);
        String name = "";
        JSONObject args = null;
        try {
            JSONObject params = payload.optJSONObject("params");
            if (params != null) {
                name = params.optString("name", "");
                args = params.optJSONObject("arguments");
            }
        } catch (Throwable t) {
            Log.w(TAG, "tools/call params parse: " + t);
        }
        String text;
        boolean isError;
        if (name.startsWith(PLAYER_PREFIX)) {
            String r = PlayerBridge.dispatch(name.substring(PLAYER_PREFIX.length()), args);
            isError = r.startsWith("ERR");
            text = r;
        } else if (name.startsWith(LIGHT_PREFIX)) {
            // v1.3.157：灯控走 R1Lights（拾音灯/氛围灯两条总线都在那里）
            String r = R1Lights.mcp(name.substring(LIGHT_PREFIX.length()), args);
            isError = r.startsWith("ERR");
            text = r;
        } else {
            isError = true;
            text = "unknown tool: " + name;
        }
        try {
            org.json.JSONArray content = new org.json.JSONArray();
            JSONObject c = new JSONObject();
            c.put("type", "text");
            c.put("text", text);
            content.put(c);
            JSONObject result = new JSONObject();
            result.put("content", content);
            result.put("isError", isError);
            sendMcpResult(id, result);
            SessionLog.i("<- mcp call " + name + " -> " + text);
        } catch (Throwable t) {
            Log.e(TAG, "handleMcpToolCall threw", t);
        }
    }

    private void sendMcpResult(int id, JSONObject result) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("jsonrpc", "2.0");
            payload.put("id", id);
            payload.put("result", result);
            JSONObject o = new JSONObject();
            o.put("type", "mcp");
            o.put("payload", payload);
            if (sendText(o.toString())) {
                SessionLog.i("-> mcp result id=" + id);
            }
        } catch (Throwable t) {
            Log.e(TAG, "sendMcpResult threw", t);
        }
    }

    /** @return 是否真的发出去了（v1.2.8：socket 为 null 时返回 false，不再静默） */
    private boolean sendText(String s) {
        WebSocket w = ws;
        if (w == null) {
            Log.w(TAG, "sendText but ws null: " + s);
            return false;
        }
        return w.send(s);
    }

    // ---------------- 接收 ----------------

    private void handleText(String text) {
        Log.i(TAG, "<- " + text);
        SessionLog.i("<- " + text);
        try {
            JSONObject o = new JSONObject(text);
            String type = o.optString("type");
            if ("tts".equals(type)) {
                String state = o.optString("state");
                if ("start".equals(state)) {
                    main.post(new Runnable() {
                        public void run() {
                            main.removeCallbacks(idleCloseTask);
                            listener.onTtsStart();
                        }
                    });
                // v1.2.16：barge-in 时服务器 VAD 打断下发的是 "abort"（正常播完是 "stop"），
                // 两者都要走 onTtsStop 停播 + 回 LISTENING 接住用户插话
                } else if ("stop".equals(state) || "abort".equals(state)) {
                    main.post(new Runnable() {
                        public void run() {
                            listener.onTtsStop();
                            markIdle();
                        }
                    });
                }
            } else if ("stt".equals(type)) {
                final String t = o.optString("text");
                main.post(new Runnable() {
                    public void run() {
                        listener.onStt(t);
                    }
                });
            } else if ("hello".equals(type)) {
                final String sid = o.optString("session_id");
                helloDone = true;   // v1.3.103：本条连接已握手
                Log.i(TAG, "handshake done, session_id=" + sid);
                main.post(new Runnable() {
                    public void run() {
                        listener.onHandshakeDone();
                    }
                });
            } else if ("mcp".equals(type)) {
                // v1.2.13：hello 声明 features.mcp=true 后，NAS 实测立刻发来
                // MCP initialize。不应答 = 服务器眼里一台哑 MCP 设备。
                // 最小实现：initialize 报真名 + tools/list 报空表，只声明不提供
                JSONObject payload = o.optJSONObject("payload");
                if (payload != null && payload.optInt("id", -1) >= 0) {
                    int id = payload.optInt("id");
                    String method = payload.optString("method");
                    if ("initialize".equals(method)) {
                        sendMcpInitializeResult(id);
                    } else if ("tools/list".equals(method)) {
                        sendMcpToolsResult(id);
                    } else if ("tools/call".equals(method)) {
                        handleMcpToolCall(payload);
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "handleText parse error", t);
        }
    }

    // ---------------- OkHttp 客户端 ----------------

    private OkHttpClient getClient() {
        if (client == null) {
            OkHttpClient.Builder b = new OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)  // ws 长读
                    .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true);
            try {
                // R1 系统根证书可能过期（2017 ROM），信任所有证书以兼容官方 TLS。
                // 仅用于本设备本地部署，不做通用分发。
                // v1.2.9 修复：之前写的是 sslSocketFactory(f, (X509TrustManager) null)
                // —— okhttp 直接 NPE，trust-all 从来没生效过（每次 connect 都 W 一次，
                // 碰巧官方证书链有效所以连接一直能建立）。现在正确传 tm。
                X509TrustManager tm = new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                };
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, new TrustManager[]{tm}, new SecureRandom());
                SSLSocketFactory f = ctx.getSocketFactory();
                b.sslSocketFactory(f, tm);
                b.hostnameVerifier(new javax.net.ssl.HostnameVerifier() {
                    public boolean verify(String hostname, javax.net.ssl.SSLSession session) {
                        return true;
                    }
                });
            } catch (Throwable t) {
                Log.w(TAG, "trust-all ssl setup failed", t);
            }
            client = b.build();
        }
        return client;
    }
}
