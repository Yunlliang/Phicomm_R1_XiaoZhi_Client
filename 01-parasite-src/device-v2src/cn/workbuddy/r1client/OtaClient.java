package cn.workbuddy.r1client;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.NetworkInterface;
import java.net.URL;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.UUID;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * OTA 激活客户端 —— 拿官方服务器的 Bearer token。
 *
 * 复刻 C3 固件的激活链路（这是唯一预判需要实机调试的点）：
 *
 *   1. 首次 POST https://api.tenclass.net/xiaozhi/ota/
 *      header: Device-Id / Client-Id / User-Agent
 *      body:   {"version":1,"uuid":"...","application":{"name":"...","version":"..."},
 *               "board":{"type":"..."},"mac_address":"..."}
 *      → 服务器返回 {"activation":{"code":"123456","message":"...",
 *                     "challenge":"..."}}  （新设备，需人工在网页绑定）
 *
 *   2. 用户到 https://xiaozhi.me 输入 code 绑定设备；
 *
 *   3. 后续 POST 同一地址 → 返回 {"activation":{"code":"","message":"","challenge":""}}
 *      并且响应头/体内含 token，或返回 {"token":"..."} 。
 *
 * 实现上做「宽容解析」：先找 body.token，再找 body.data.token，
 * 最后找响应头 Authorization / X-Token。token 缓存到 SharedPreferences，
 * 下次启动直接复用，避免每次开机都打 OTA。
 *
 * ⚠️ 若 OTA 拿不到 token，ws 会退化为「无鉴权连接」，官方服务器大概率返回
 *    401 或直接关闭 —— 此时把 logcat 里 OTA 的原始响应贴出来再调。
 */
public final class OtaClient {

    private static final String TAG = "R1CLIENT";

    /**
     * v1.2.17：回官方，完全模仿 ESP32（2026-10-03 PC 探测实证）。
     *
 *  ⚠️ v1.3.110 更正：**上面第 2 条的「OUI 门检」是误判，已作废。**
 *    2026-10-10 用 diag/official/probe_ota.py 复测五组 MAC，官方是否下发
 *    activation 只取决于 Device-Id 是不是服务器没见过的新身份，与 OUI 无关
 *    （真实 R1 MAC 98:bb:99:36:cd:6b 首请求就给码；见 deviceId() 注释）。
 *    ⇒ 伪装乐鑫 OUI 已无收益，且丢掉厂商烧录的全球唯一性（随机后缀仅 24bit，
 *      万台级装机生日碰撞 ≈95%）⇒ 改用真实网卡 MAC。历史说明保留勿删。
     *  3. 真 ESP32（application.cc:533）拿到含 mqtt 段的响应走 MQTT+UDP 而非 ws；
     *     但未绑定设备 mqtt subscribe_topic="null"（只准说不准听）。绑定时序：
     *     先 OTA 拿激活码 → 用户 xiaozhi.me 绑定 → 再 OTA 拿真 token → 走哪条
     *     通道（ws or mqtt）绑完再实测，ws 优先（客户端现成）。
     *
     * 因此 v1.2.17：合成乐鑫 OUI MAC（每设备一次、持久化）+ 忠实 ESP32 body
     * （version:2/chip_info/uuid=真UUID/partition_table，照抄 board.cc 2.4.2）
     * + Activation-Version 头 + 激活码大字报。绑定后 OTA 自动换发真 token。
     *
     * 回 NAS：OTA_URL 改回 http://192.168.2.189:8003/xiaozhi/ota/ 即可
     * （合成 MAC 对 NAS 无影响，NAS 不校验 OUI）。
     */
    private static final String OTA_URL = "https://api.tenclass.net/xiaozhi/ota/";
    public static final String PREF = "r1client";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_CLIENT_ID = "client_id";
    /** v1.3.98：服务器下发的激活码（未绑定凭证，UI/HUD 显示它，绑定后必须清掉） */
    public static final String KEY_ACTIVATION = "activation_code";
    /** v1.2.17：合成 ESP32 MAC（乐鑫 OUI，官方按 OUI 决定是否发激活码） */
    private static final String KEY_DEVICE_ID = "device_id";
    /** v1.2.6：token 缓存与 OTA 服务器绑定，换服务器时旧缓存自动作废 */
    private static final String KEY_OTA_URL = "ota_url";
    /**
     * v1.2.12：缓存的 ws 地址（websocket.url），与 token 同绑 KEY_OTA_URL。
     *
     * v1.2.11 踩坑（NAS 二分白跑一晚的根因）：缓存命中早退只回调了
     * onToken，onWsUrl 永远不触发 —— 而本机缓存恰好是 v1.2.6 时代
     * 绑的 NAS 地址，v1.2.11 切回 NAS 一命中就整个跳过 OTA POST，
     * websocket.url 无从下发，客户端抱着默认 tenclass 地址连了一晚。
     * 修复：命中条件必须 token+ws 地址双全；POST 路径拿到 ws 地址
     * 即刻落缓存。OTA 全路径埋 SessionLog（v1.2.10 落盘日志），盲区清除。
     */
    private static final String KEY_WS_URL = "ws_url";

    /**
     * v1.3.101：服务器地址可运行时覆盖 —— files/ota_url 里写一行 URL 即生效，
     * 用于切到局域网自建服务器（翻页器固件那套：改 OTA 端点，ws 地址由 OTA 下发）。
     * 文件不存在 / 为空 = 用官方默认地址。
     */
    public static String otaUrlOf(Context ctx) {
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), "ota_url");
            if (f.exists() && f.length() > 0 && f.length() < 4096) {
                byte[] b = new byte[(int) f.length()];
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                int n = 0;
                try {
                    n = in.read(b);
                } finally {
                    in.close();
                }
                if (n > 0) {
                    String s = new String(b, 0, n, "UTF-8").trim();
                    if (s.length() > 0) {
                        return s;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "ota_url read: " + t);
        }
        return OTA_URL;
    }

    public interface Callback {
        void onToken(String token);

        /** OTA 下发的 ws 地址（websocket.url），可覆盖默认硬编码 */
        void onWsUrl(String url);

        void onFail(String msg);
    }

    private final Context ctx;
    private final Callback cb;

    public OtaClient(Context ctx, Callback cb) {
        this.ctx = ctx.getApplicationContext();
        this.cb = cb;
    }

    /** v1.2.7：设备 MAC（与 OTA 请求头同源），供 ws 握手头使用 */
    public String getDeviceId() {
        return deviceId();
    }

    /** v1.2.7：客户端 UUID（读/生成，与 OTA 请求头同源），供 ws 握手头使用 */
    public String getClientId() {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String id = sp.getString(KEY_CLIENT_ID, null);
        if (id == null || id.length() == 0) {
            id = UUID.randomUUID().toString();
            sp.edit().putString(KEY_CLIENT_ID, id).apply();
        }
        return id;
    }

    /**
     * v1.2.13：失败重试（最多 3 次，退避 5s/10s）。
     * v1.2.11 教训：服务启动瞬间的单次 POST 若撞上 NAS 短暂不可达（容器
     * 重启/网络抖动），整个服务生命周期再无 OTA，ws 地址缺失 → 客户端抱着
     * 默认地址连一晚。现在瞬时故障自愈；doFetch 返回 true = 无需再试
     * （缓存命中 / 拿到 ws 地址 / 拿到 token / 需要人工激活）。
     */
    public void fetch() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                for (int attempt = 1; attempt <= 3; attempt++) {
                    boolean done = false;
                    try {
                        done = doFetch();
                    } catch (Throwable t2) {
                        Log.e(TAG, "OTA fetch threw", t2);
                        SessionLog.i("OTA exception: " + t2);
                        if (cb != null) cb.onFail("exception: " + t2);
                    }
                    if (done) {
                        return;
                    }
                    if (attempt < 3) {
                        long backoff = 5000L * attempt;
                        Log.w(TAG, "OTA retry in " + backoff + "ms");
                        SessionLog.i("OTA retry in " + backoff + "ms (attempt "
                                + (attempt + 1) + "/3)");
                        try {
                            Thread.sleep(backoff);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
            }
        }, "R1Ota");
        t.start();
    }

    /** @return true = 收场（缓存命中/拿到 url/token/需人工激活），false = 值得重试 */
    private boolean doFetch() throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);

        // 先复用缓存 token（避免每次开机都打 OTA）。
        // v1.2.6：缓存与 OTA 服务器绑定 —— 换服务器时旧 token 作废，
        // 否则会拿着旧服务器的 token 去连新服务器（v1.2.5 已踩坑：官方
        // 下发的 "test-token" 被缓存，切 NAS 后会继续拿它连 NAS）。
        String cached = sp.getString(KEY_TOKEN, null);
        String cachedUrl = sp.getString(KEY_OTA_URL, null);
        String cachedWs = sp.getString(KEY_WS_URL, null);
        // v1.3.101：服务器地址以 files/ota_url 为准（本地服务器用）
        final String url = otaUrlOf(ctx);
        // v1.2.12：命中缓存必须 token + ws 地址双全 —— 只有 token 没有
        // ws 地址（v1.2.6 时代旧缓存）时必须走全量 POST，否则 onWsUrl
        // 永远不触发、连接永远走默认官方地址
        // v1.2.17：test-token = 官方「未绑定」占位 token，必须当缓存未命中处理，
        // 否则绑定后永远不会重打 OTA 换真 token（22:46 实机缓存恰好就是 test-token）
        boolean isPlaceholder = cached != null && "test-token".equals(cached);
        if (isPlaceholder) {
            SessionLog.i("OTA cache holds placeholder test-token -> force full POST");
        }
        if (!isPlaceholder && cached != null && cached.length() > 0
                && url.equals(cachedUrl)
                && cachedWs != null && cachedWs.length() > 0) {
            Log.i(TAG, "OTA: reuse cached token+ws (server=" + url + ")");
            SessionLog.i("OTA cache hit: ws=" + cachedWs);
            if (cb != null) {
                cb.onToken(cached);
                cb.onWsUrl(cachedWs);
            }
            return true;
        }
        SessionLog.i("OTA cache miss (token=" + (cached == null ? "null" : "yes")
                + " urlMatch=" + url.equals(cachedUrl)
                + " ws=" + cachedWs + ") -> full POST");

        String clientId = sp.getString(KEY_CLIENT_ID, null);
        if (clientId == null) {
            clientId = UUID.randomUUID().toString();
            sp.edit().putString(KEY_CLIENT_ID, clientId).apply();
        }
        String deviceId = deviceId();

        String body = buildBody(deviceId);
        Log.i(TAG, "OTA POST " + url);
        Log.i(TAG, "OTA req body: " + body);
        SessionLog.i("OTA POST " + url);

        HttpURLConnection conn = null;
        try {
            URL u = new URL(url);
            conn = (HttpURLConnection) u.openConnection();
            if (conn instanceof HttpsURLConnection) {
                try {
                    SSLContext ssl = SSLContext.getInstance("TLS");
                    ssl.init(null, new TrustManager[]{TRUST_ALL}, new SecureRandom());
                    ((HttpsURLConnection) conn).setSSLSocketFactory(ssl.getSocketFactory());
                    ((HttpsURLConnection) conn).setHostnameVerifier(new javax.net.ssl.HostnameVerifier() {
                        public boolean verify(String h, javax.net.ssl.SSLSession s) {
                            return true;
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            // v1.2.17：与 ESP32 ota.cc SetupHttp() 逐头对齐（缺 Activation-Version
            // 是 v1.2.11 之前与真机的可观测差异之一）
            conn.setRequestProperty("Device-Id", deviceId);
            conn.setRequestProperty("Client-Id", clientId);
            conn.setRequestProperty("Activation-Version", "1");
            conn.setRequestProperty("User-Agent", "bread-compact-wifi/2.4.2");
            conn.setRequestProperty("Accept-Language", "zh-CN");

            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.close();

            int code = conn.getResponseCode();
            Log.i(TAG, "OTA resp code=" + code);
            SessionLog.i("OTA resp code=" + code);

            // 顺带打印关键响应头，方便排查 token 是否走 header
            String hdrToken = conn.getHeaderField("Authorization");
            if (hdrToken == null) hdrToken = conn.getHeaderField("X-Token");
            if (hdrToken != null) {
                Log.i(TAG, "OTA resp header token: " + hdrToken);
            }

            InputStream in = (code >= 200 && code < 300) ? conn.getInputStream()
                    : conn.getErrorStream();
            String text = readAll(in);
            Log.i(TAG, "OTA resp body: " + text);
            SessionLog.i("OTA resp body: " + (text.length() > 500
                    ? text.substring(0, 500) + "...(trunc)" : text));

            String token = extractToken(text);
            if (token == null && hdrToken != null) {
                token = hdrToken.replace("Bearer ", "").trim();
            }
            // v1.2.5：官方 OTA 的 token 实际在 websocket.token（顶层/data/activation
            // 都没有！v1.2.4 实测 resp 里有 "websocket":{"url":...,"token":"..."}）
            String wsUrl = extractWsUrl(text);
            if (wsUrl != null) {
                // v1.2.12：ws 地址即刻落缓存（绑定当前 OTA 服务器）——
                // NAS 可能下发空 token（不进 token 缓存分支），ws 地址也要独立保住
                sp.edit().putString(KEY_WS_URL, wsUrl)
                          .putString(KEY_OTA_URL, url).apply();
                Log.i(TAG, "OTA ws url: " + wsUrl);
                SessionLog.i("OTA ws url: " + wsUrl);
                if (cb != null) cb.onWsUrl(wsUrl);
            }

            // v1.3.98（移植自安卓版 v1.3.33）：test-token 是**未绑定占位**，不能当成功
            // token —— 否则下面的激活码提取会被这行提前 return 跳过（服务器在同一份
            // 响应里同时给 websocket.token=test-token 和 activation.code=xxxxxx）。
            boolean placeholderTok = token != null && "test-token".equals(token);

            if (token != null && token.length() > 0 && !placeholderTok) {
                sp.edit().putString(KEY_TOKEN, token)
                          .putString(KEY_OTA_URL, url)
                          .remove(KEY_ACTIVATION).apply();
                if (cb != null) cb.onToken(token);
                return true;
            }

            // test-token / 无 token：先找激活码（未绑定设备的正门）
            // ⚠️ 这里**不能**有「wsUrl 到手就 return」的分支：
            //    未绑定设备的响应里 wsUrl + test-token + activation.code 三者同时在，
            //    提前 return 会把激活码整条吞掉（安卓版 v1.3.33 踩过，此处照搬修复）。
            String actCode = extractActivationCode(text);
            if (actCode != null && actCode.length() > 0) {
                sp.edit().putString(KEY_ACTIVATION, actCode).apply();
                String msg = "=== 需要激活 === 请登录 https://xiaozhi.me 控制台，"
                        + "添加设备并输入激活码: " + actCode
                        + " （设备身份: " + deviceId + " / " + clientId + "）";
                Log.w(TAG, msg);
                SessionLog.i("OTA " + msg);
                // v1.3.98：代替安卓版 startActivity —— 这里没有自己的 Activity，
                // 直接让悬浮窗显示（HDMI / 投屏可见）。
                try {
                    ActivationHud.refresh();
                } catch (Throwable ignored) {
                }
                if (cb != null) cb.onFail(msg);
                return true;   // 激活需人工，重试无意义；绑定后下次 OTA 换发真 token
            }

            // v1.3.98：test-token 且服务器**不再下发激活码** = 设备已绑定
            // （test-token 只是连接凭证，不代表未激活；activation.code 的有无才是凭证）
            if (wsUrl != null) {
                if (sp.getString(KEY_ACTIVATION, null) != null) {
                    sp.edit().remove(KEY_ACTIVATION).apply();
                    SessionLog.i("OTA: no activation code from server -> device bound, clearing old code");
                }
                if (cb != null) cb.onToken(token != null ? token : "");
                return true;
            }

            SessionLog.i("OTA fail: no token in resp (code=" + code + ")");
            if (cb != null) cb.onFail("no token in OTA resp (code=" + code + ")");
            return false;  // 响应没料，值得重试
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ---------------- 工具 ----------------

    /**
     * v1.2.17：忠实复刻 ESP32 OTA body（xiaozhi-esp32 2.4.2，Board::GetSystemInfoJson）。
     * 逐字段照抄 board.cc：version:2（不是 1！）、language、chip_info、
     * uuid=Client-Id（真 UUID，v1.2.16 及以前发的是 mac 去冒号——身份错位）、
     * partition_table/ota/board 一应俱全。字段值用 esp32s3 通用值（服务器不校验
     * 真伪，只校验格式与 OUI）。
     */
    private String buildBody(String deviceId) {
        try {
            String clientId = getClientId();

            JSONObject chipInfo = new JSONObject();
            chipInfo.put("model", 9);          // ESP32-S3
            chipInfo.put("cores", 2);
            chipInfo.put("revision", 2);
            chipInfo.put("features", 18);

            JSONObject app = new JSONObject();
            app.put("name", "xiaozhi-esp32");
            app.put("version", "2.4.2");
            app.put("compile_time", "2025-09-01T12:00:00Z");
            app.put("idf_version", "v5.4.1");
            app.put("elf_sha256", "abababababababababababababababab"
                    + "ababababababababababababababababab");

            JSONObject nvs = new JSONObject();
            nvs.put("label", "nvs"); nvs.put("type", 1); nvs.put("subtype", 2);
            nvs.put("address", 0x9000); nvs.put("size", 0x6000);
            JSONObject otadata = new JSONObject();
            otadata.put("label", "otadata"); otadata.put("type", 1); otadata.put("subtype", 0);
            otadata.put("address", 0xE000); otadata.put("size", 0x2000);
            JSONObject appP = new JSONObject();
            appP.put("label", "app"); appP.put("type", 0); appP.put("subtype", 0);
            appP.put("address", 0x10000); appP.put("size", 0x640000);
            JSONObject model = new JSONObject();
            model.put("label", "model"); model.put("type", 1); model.put("subtype", 1);
            model.put("address", 0x650000); model.put("size", 0x9D0000);
            org.json.JSONArray parts = new org.json.JSONArray();
            parts.put(nvs); parts.put(otadata); parts.put(appP); parts.put(model);

            JSONObject ota = new JSONObject();
            ota.put("label", "app");

            JSONObject board = new JSONObject();
            board.put("type", "bread-compact-wifi");

            JSONObject o = new JSONObject();
            o.put("version", 2);
            o.put("language", "zh-CN");
            o.put("flash_size", 8388608);
            o.put("minimum_free_heap_size", 8312316);
            o.put("mac_address", deviceId);
            o.put("uuid", clientId);
            o.put("chip_model_name", "esp32s3");
            o.put("chip_info", chipInfo);
            o.put("application", app);
            o.put("partition_table", parts);
            o.put("ota", ota);
            o.put("board", board);
            return o.toString();
        } catch (Throwable t) {
            return "{\"version\":2}";
        }
    }

    /**
     * v1.3.110：设备身份 = **真实网卡 MAC**（首选），不再伪装乐鑫 OUI。
     *
     * 2026-10-10 用 diag/official/probe_ota.py 做五组对照，推翻了 v1.2.17 的
     * 「OUI 门检」结论：
     *   A 真实 R1 MAC  98:bb:99:36:cd:6b → activation.code=355692（首请求即给）
     *   C 随机乐鑫 MAC 24:0a:c4:aa:bb:cc → 给码
     *   D 随机非乐鑫  00:11:22:33:44:55  → 给码（第二次同一 MAC 不再给）
     *   E 随机非乐鑫  52:54:00:9a:1f:77  → 给码
     *   B 现役合成 MAC 24:0a:c4:87:58:29 → 不给（这个身份服务器侧已见过）
     * ⇒ 官方 OTA 是否下发 activation 只看 Device-Id 是不是**没见过的新身份**，
     *   与 OUI 无关。当年之所以"换了 MAC 就有码"，是因为换了个未被占用的身份，
     *   OUI 是与因变量共线的混杂变量，不是原因。
     *
     * ⇒ 乐鑫伪装既无收益，又丢掉了「厂商烧录 ⇒ 全球唯一」这一保证：
     *   合成方案的随机后缀只有 24bit，万台级装机必然撞号（生日碰撞 ≈95%），
     *   撞号的两台会被服务端当成同一设备互相顶 session。
     *
     * 取值优先级（生成一次即持久化，全小写 + 冒号，与 ESP32 %02x 格式一致）：
     *   ① 真实网卡 MAC（wlan0/eth0，厂商分配，全球唯一）
     *   ② ANDROID_ID 的 MD5 前 6 字节（置 locally-administered 位，避免撞真厂商号）
     *   ③ 随机 MAC（兜底，与 ② 同位规则）
     * 来源会写进 SessionLog，便于一眼看出走了哪条路。
     */
    private String deviceId() {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String saved = sp.getString(KEY_DEVICE_ID, null);
        if (saved != null && saved.length() > 0) {
            return saved;
        }
        String mac = hardwareMac();
        String src = "hw-nic";
        if (mac == null) {
            mac = macFromAndroidId();
            src = "android_id";
        }
        if (mac == null) {
            mac = macRandom();
            src = "random";
        }
        sp.edit().putString(KEY_DEVICE_ID, mac).apply();
        Log.i(TAG, "device identity: mac=" + mac + " src=" + src);
        SessionLog.i("device identity: mac=" + mac + " src=" + src);
        return mac;
    }

    /** ① 真实网卡地址（纯 Java ioctl，不需要 ACCESS_WIFI_STATE 权限，API22 可读） */
    private static String hardwareMac() {
        String fallback = null;
        try {
            Enumeration<NetworkInterface> it = NetworkInterface.getNetworkInterfaces();
            while (it != null && it.hasMoreElements()) {
                NetworkInterface ni = it.nextElement();
                byte[] hw;
                try {
                    if (ni.isLoopback() || ni.isVirtual()) {
                        continue;
                    }
                    hw = ni.getHardwareAddress();
                } catch (Throwable t) {
                    continue;
                }
                if (hw == null || hw.length < 6) {
                    continue;
                }
                boolean zero = true;
                for (int i = 0; i < hw.length; i++) {
                    if (hw[i] != 0) {
                        zero = false;
                        break;
                    }
                }
                if (zero) {
                    continue;   // 全 0 占位地址（Android 6+ 才会这样，兜个底）
                }
                String s = formatMac(hw);
                String name = ni.getName();
                if (name != null && ("wlan0".equals(name) || "eth0".equals(name))) {
                    return s;   // 首选名单，直接返回
                }
                if (fallback == null) {
                    fallback = s;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "hardwareMac: " + t);
        }
        return fallback;
    }

    /** ② ANDROID_ID 派生（64bit 空间，本机碾压万台装机；工厂重置才会变） */
    private String macFromAndroidId() {
        try {
            String aid = android.provider.Settings.Secure.getString(
                    ctx.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
            if (aid == null || aid.length() == 0) {
                return null;
            }
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(aid.getBytes("UTF-8"));
            byte[] mac = new byte[6];
            System.arraycopy(d, 0, mac, 0, 6);
            mac[0] = (byte) ((mac[0] & 0xfc) | 0x02);   // locally administered, unicast
            return formatMac(mac);
        } catch (Throwable t) {
            Log.w(TAG, "macFromAndroidId: " + t);
            return null;
        }
    }

    /** ③ 随机兜底（同样置 locally administered 位） */
    private static String macRandom() {
        byte[] mac = new byte[6];
        new SecureRandom().nextBytes(mac);
        mac[0] = (byte) ((mac[0] & 0xfc) | 0x02);
        return formatMac(mac);
    }

    private static String formatMac(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(String.format("%02x", b[i] & 0xff));
        }
        return sb.toString();
    }

    private static String extractToken(String json) {
        if (json == null) return null;
        try {
            JSONObject o = new JSONObject(json);
            if (o.has("token")) return o.optString("token");
            JSONObject d = o.optJSONObject("data");
            if (d != null && d.has("token")) return d.optString("token");
            JSONObject a = o.optJSONObject("activation");
            if (a != null && a.has("token")) return a.optString("token");
            // v1.2.5：官方 OTA 真正放 token 的位置
            JSONObject w = o.optJSONObject("websocket");
            if (w != null && w.has("token")) return w.optString("token");
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** OTA 下发的 ws 地址（websocket.url）。v1.2.5 起支持服务器动态下发 */
    private static String extractWsUrl(String json) {
        if (json == null) return null;
        try {
            JSONObject o = new JSONObject(json);
            JSONObject w = o.optJSONObject("websocket");
            if (w != null) {
                String u = w.optString("url");
                if (u != null && u.length() > 0 && u.startsWith("ws")) {
                    return u;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String extractActivationCode(String json) {
        if (json == null) return null;
        try {
            JSONObject o = new JSONObject(json);
            JSONObject a = o.optJSONObject("activation");
            if (a != null) {
                return a.optString("code");
            }
            if (o.has("code")) return o.optString("code");
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) {
                bos.write(buf, 0, r);
            }
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private static final X509TrustManager TRUST_ALL = new X509TrustManager() {
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };
}
