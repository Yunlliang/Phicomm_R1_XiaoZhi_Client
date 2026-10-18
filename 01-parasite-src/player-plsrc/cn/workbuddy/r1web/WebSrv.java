package cn.workbuddy.r1web;

import android.content.Context;

import com.phicomm.speaker.player.model.PlayPositionInfo;
import com.phicomm.speaker.player.model.PlaySong;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 跑在 player 主进程里的控制台（:8080）—— **8088 那套页面的完整宿主**。
 *
 * <p>★ 与 device 侧 8088 的分工：</p>
 * <pre>
 *   本地直供（不依赖小智，停用了照样有）：
 *      播放控制 / 队列 / 播放位置 / 总时长 / 播放模式 / 音量 / 小智开关
 *   反代到 8088（物理上只在 device 侧）：
 *      设置项 / 曲库搜索 / 电台 / 氛围灯 / 唤醒 / 重启 / 日志
 * </pre>
 *
 * <p>★ 返回格式必须与 8088 一致：页面 act()/setKV() 都按 {@code {"ok":..,"msg":..}}
 * 解析，所以这里不再吐裸文本。</p>
 *
 * <p>零依赖：手写 HTTP/1.0，单 accept 线程串行处理（控制台量级够用，
 * 也避免在原厂进程里起一堆线程）。所有异常都吞掉，绝不让 player 挂。</p>
 *
 * <p>加新 op / 新接口时记得三处同改：
 *   ① {@link #actOp} ② ui/player.html 的 JS ③ build 脚本的自检。</p>
 */
public final class WebSrv {

    public static final int DEF_PORT = 8080;

    static Context app;
    static int port = DEF_PORT;
    static volatile long started = 0;

    public static synchronized void start(Context c) {
        if (started > 0) {
            return;
        }
        app = c.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                loop();
            }
        }, "r1web-http").start();
    }

    private static void loop() {
        ServerSocket ss = null;
        try {
            ss = new ServerSocket(port);
            started = System.currentTimeMillis();
            Boot.log("控制台起来了：http://" + localIp() + ":" + port + "/");
        } catch (Throwable t) {
            Boot.log("bind " + port + " 失败: " + t);
            return;
        }
        while (true) {
            Socket s = null;
            try {
                s = ss.accept();
                handle(s);
            } catch (Throwable t) {
                // ignore
            } finally {
                try {
                    if (s != null) {
                        s.close();
                    }
                } catch (Throwable t) {
                    // ignore
                }
            }
        }
    }

    private static void handle(Socket s) throws Exception {
        s.setSoTimeout(10000);
        InputStream in = s.getInputStream();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        String line = br.readLine();
        if (line == null) {
            return;
        }
        String method = line.split(" ")[0];
        // 读完请求头，顺带记下 Content-Length
        int bodyLen = 0;
        while (true) {
            String h = br.readLine();
            if (h == null || h.length() == 0) {
                break;
            }
            if (h.toLowerCase().startsWith("content-length:")) {
                try {
                    bodyLen = Integer.parseInt(h.substring(15).trim());
                } catch (Throwable t) {
                    bodyLen = 0;
                }
            }
        }
        String body = "";
        if (bodyLen > 0 && "POST".equals(method)) {
            char[] buf = new char[bodyLen];
            int got = 0;
            while (got < bodyLen) {
                int n = br.read(buf, got, bodyLen - got);
                if (n < 0) {
                    break;
                }
                got += n;
            }
            body = new String(buf, 0, got);
        }
        String[] parts = line.split(" ");
        if (parts.length < 2) {
            return;
        }
        String raw = parts[1];
        int q = raw.indexOf('?');
        String path = q >= 0 ? raw.substring(0, q) : raw;
        Map<String, String> query = parse(q >= 0 ? raw.substring(q + 1) : "");
        /* ★★ v1.1.1：页面 act()/setKV() 一律用 POST + form body 发参数（op=xxx&v=1）。
             只读 query 会全部拿到 null ⇒ 每个按钮都回「缺 op」（pv1/pv2 的 bug）。
             form body 必须合并进参数表（与 device 侧 8088 同策略：body 优先）。 */
        if (body.length() > 0) {
            query.putAll(parse(body));
        }
        OutputStream os = s.getOutputStream();
        try {
            route(os, path, query, body);
        } catch (Throwable t) {
            try {
                txt(os, 500, jsonOk(false, "ERR " + t));
            } catch (Throwable t2) {
                // ignore
            }
        }
        os.flush();
    }

    private static void route(OutputStream os, String path, Map<String, String> q,
            String body) throws Exception {
        if (path.equals("/") || path.equals("/index.html")) {
            String html = Ui.html();
            if ("1".equals(q.get("embed"))) {
                html = html.replace("<body>", "<body class=\"embed\">");
            }
            write(os, 200, "text/html; charset=utf-8", html);
            return;
        }
        if (path.equals("/api/status")) {
            json(os, statusJson().toString());
            return;
        }
        /* ★★ 设备状态（CPU/内存/温度/存储/进程）—— 本地采集，不依赖小智。
           不塞进 /api/status：CPU 要睡 250ms 取两次 /proc/stat，
           status 是 8s 一轮的主心跳，别让它跟着变慢。页面单独 5s 拉这个。 */
        if (path.equals("/api/device")) {
            json(os, Sys.sample("1".equals(q.get("cpu"))).toString());
            return;
        }
        /* 蓝牙状态（开关 / 已配对 / 扫到的 / A2DP 连接）—— 标准 BluetoothAdapter API，
           与小飞的 WS 命令（BT_enable / BT_Device / BT_createBond …）无关，
           我们注入的是原厂 player，没有那套服务端。 */
        if (path.equals("/api/bt")) {
            json(os, Bt.state().toString());
            return;
        }
        /* 外部播放源（DLNA / AirPlay）在不在 —— 看 :DlnaPlayer / :AirPlayer 进程。 */
        if (path.equals("/api/svc")) {
            json(os, Svc.state().toString());
            return;
        }
        // ★ player 自己的队列 = 钩子抓到的那张（就是 player 手里正在放的那张），
        //   比 device 侧「最后一次 push 的记录」更真。
        if (path.equals("/api/queue")) {
            int lim = 0;
            try {
                lim = Integer.parseInt(q.get("limit"));
            } catch (Throwable t) {
                lim = 0;
            }
            json(os, PCtl.queueJson(lim).toString());
            return;
        }
        // 日志：优先 device 的（对话/唤醒都在那边），停用了才回落到 player 自己的
        // ?local=1 强制看 player 自己的（排障注入层时很有用）
        if (path.equals("/api/log")) {
            String qs = "/api/log?n=" + def(q.get("n"), "80")
                    + ("1".equals(q.get("chat")) ? "&chat=1" : "");
            if ("1".equals(q.get("local"))) {
                int ln = 80;
                try {
                    ln = Integer.parseInt(q.get("n"));
                } catch (Throwable t) {
                    ln = 80;
                }
                txt(os, 200, "(player 侧日志)\n" + Boot.logs(ln));
                return;
            }
            String s = Proxy.get(qs, 2500);
            if (s == null || s.length() == 0) {
                s = "(小智未运行，以下是 player 侧日志)\n" + Boot.logs(80);
            }
            txt(os, 200, s);
            return;
        }
        // 设置：只能转给 device（files/ 在它那儿）
        if (path.equals("/api/set")) {
            String r = Proxy.post("/api/set", body.length() > 0 ? body
                    : buildQuery(q), 4000);
            Proxy.invalidate();
            json(os, r != null ? r
                    : jsonOk(false, "小智未运行（8088 不可达），设置项改不了"));
            return;
        }
        // 曲库搜索：曲库在 device 侧（从 NAS mini-lx 拉的）
        if (path.equals("/api/search")) {
            // v1.3.168：k 留空 = 列全部曲库；limit 原样转给 device（<=0 = 不截断）
            // v1.3.175：online=1 透传给 device —— 池内没搜到时它会附带 NAS 全源在线结果。
            //           在线要跑六级取链兜底，8s 不够（跨源 + lxserver 中继），给 30s。
            // v1.3.178：random=1（在线随机一批）和 olimit（条数）也要逐个透传 ——
            //   反代漏一个参数，8080 页面上「在线搜歌」就静悄悄退化成池内搜索，
            //   用户看到的是「点了没变化」，而 8088 直连一切正常（最难查的那类 bug）。
            String ol = def(q.get("online"), "");
            String rd = def(q.get("random"), "");
            String lim2 = def(q.get("olimit"), "");
            String r = Proxy.get("/api/search?k=" + enc(def(q.get("k"), ""))
                    + "&limit=" + def(q.get("limit"), "60")
                    + (ol.length() > 0 ? "&online=" + enc(ol) : "")
                    + (rd.length() > 0 ? "&random=" + enc(rd) : "")
                    + (lim2.length() > 0 ? "&olimit=" + enc(lim2) : ""),
                    (ol.length() > 0 || rd.length() > 0) ? 25000 : 8000);
            if (r != null) {
                json(os, r);
                return;
            }
            json(os, "{\"list\":[],\"note\":\"小智未运行（8088 不可达），搜不了曲库\"}");
            return;
        }
        // v1.3.188：歌词（+封面）反代 —— 词在 device 侧（经 NAS mini-lx 从落雪拿）。
        //   ?t=曲名&a=歌手 逐个透传；不给就取「当前曲目」。
        //   首次要现场取词（跨源匹配 + 落雪），给 12s，别让页面先判失败。
        if (path.equals("/api/lrc")) {
            String t = def(q.get("t"), "");
            String a = def(q.get("a"), "");
            String r = Proxy.get("/api/lrc?t=" + enc(t) + "&a=" + enc(a), 12000);
            if (r != null) {
                json(os, r);
                return;
            }
            json(os, "{\"ok\":false,\"msg\":\"小智未运行（8088 不可达），取不到歌词\"}");
            return;
        }
        if (path.equals("/api/act")) {
            json(os, actOp(q));
            return;
        }
        /* ---------- 静态资源：赞赏码 + PWA 三件套 ----------
           pv1..pv11 这边只给了一个没有图标的假 manifest，而页面是从 8088 那套
           复制来的（引用 /donate.png、/sw.js、/icon-*.png）⇒ 赞赏码 404、
           Chrome 拿不到「安装应用」的资格。这里整套补齐（Assets 由 _gen_assets.py
           从 device 侧抽取生成，两边内容一模一样）。 */
        if (path.equals("/donate.png")) {
            try {
                byte[] img = android.util.Base64.decode(Assets.DONATE_QR_B64,
                        android.util.Base64.DEFAULT);
                writeBytes(os, 200, "image/png", img,
                        "Cache-Control: public, max-age=604800\r\n");
            } catch (Throwable t) {
                txt(os, 500, "donate decode: " + t);
            }
            return;
        }
        if (path.equals("/favicon.ico")) {
            writeBytes(os, 204, "text/plain", new byte[0], null);
            return;
        }
        if (path.equals("/manifest.webmanifest") || path.equals("/manifest.json")) {
            json(os, Assets.MANIFEST_JSON);
            return;
        }
        if (path.equals("/sw.js")) {
            // Service-Worker-Allowed 默认就是 /，写出来是为了提醒：sw.js 必须在根路径下发。
            writeBytes(os, 200, "application/javascript; charset=utf-8",
                    Assets.SW_JS.getBytes("UTF-8"), "Service-Worker-Allowed: /\r\n");
            return;
        }
        if (path.startsWith("/icon-")) {
            int size = 192;
            try {
                size = Integer.parseInt(path.replaceAll("[^0-9]", ""));
            } catch (Throwable t) {
                size = 192;
            }
            if (size < 48) {
                size = 48;
            }
            if (size > 512) {
                size = 512;
            }
            byte[] img = Assets.iconPng(size, path.indexOf("maskable") > 0);
            if (img != null) {
                writeBytes(os, 200, "image/png", img,
                        "Cache-Control: public, max-age=604800\r\n");
                return;
            }
        }
        txt(os, 404, jsonOk(false, "404 " + path));
    }

    /* ---------------- 动作 ---------------- */

    /**
     * 本地能干的自己干，认不出的 op **一律转给 device**。
     *
     * <p>这样 8088 那 26 个 op 不用在 player 侧重写一遍：搜索/电台/氛围灯/重启/
     * 唤醒这些本来就在 device 里，转发过去它自己执行（推列表回来时钩子会更新队列）。</p>
     */
    private static String actOp(Map<String, String> q) {
        String op = q.get("op");
        if (op == null) {
            return jsonOk(false, "缺 op");
        }
        try {
            if ("play".equals(op)) {
                return jsonOk(true, PCtl.play());
            }
            if ("pause".equals(op)) {
                return jsonOk(true, PCtl.pause());
            }
            if ("stop".equals(op)) {
                return jsonOk(true, PCtl.stop());
            }
            if ("next".equals(op)) {
                return jsonOk(true, PCtl.next());
            }
            if ("prev".equals(op)) {
                return jsonOk(true, PCtl.prev());
            }
            if ("resume".equals(op)) {
                return jsonOk(true, PCtl.play());
            }
            // ★ 跳播本地做：直接 playIndex(i,true)，列表顺序一点不变
            if ("play_at".equals(op)) {
                int i = 1;
                try {
                    i = Integer.parseInt(q.get("index"));
                } catch (Throwable t) {
                    return jsonOk(false, "index 不是数字");
                }
                return jsonOk(true, PCtl.playAt(i - 1));
            }
            /* ★★ 音量量纲：页面滑块是**档位**（0..max，实测 15），但它发给后端的一直
                 是**百分比**（与 device 8088 同构：`pct = val*100/max`）。
                 而原厂 player 的音量就是系统 STREAM_MUSIC 档位 ⇒ 这里必须把百分比
                 换算回档位再 setStreamVolume。pv1/pv2 直接把百分比当档位传
                 （20% ⇒ setStreamVolume(20) ⇒ 被 clamp 成满档 15；更糟的是小于
                 7% 的那些档位全部 clamp 成 0/1，回读后页面把滑块拽回 0）
                 ⇒ 这就是「音量点了跳回 0」的根因。 */
            if ("vol".equals(op)) {
                int pct = -1;
                try {
                    pct = Integer.parseInt(q.get("v"));
                } catch (Throwable t) {
                    return jsonOk(false, "v 不是数字");
                }
                int mx = PCtl.volMax();
                if (mx <= 0) {
                    mx = 15;
                }
                return jsonOk(true, PCtl.setVol(Math.round(pct * mx / 100.0f)));
            }
            /* 直设档位（0..max），curl 调试 / 日后精确控制用 */
            if ("volstep".equals(op)) {
                int v = -1;
                try {
                    v = Integer.parseInt(q.get("v"));
                } catch (Throwable t) {
                    return jsonOk(false, "v 不是数字");
                }
                return jsonOk(true, PCtl.setVol(v));
            }
            /* ★ pl-1.6.12：拖动进度条 —— 跳到 ms 毫秒处。
               原厂 AIDL 里本来没有 seek，是我们在 smali 上新增的事务 0x15
               （见 patch_seek.py）。只在本地播放态放行，见 PCtl.seekTo()。 */
            if ("seek".equals(op)) {
                int ms = -1;
                try {
                    ms = Integer.parseInt(q.get("ms"));
                } catch (Throwable t) {
                    return jsonOk(false, "ms 不是数字");
                }
                return jsonOk(true, PCtl.seekTo(ms));
            }
            if ("mode".equals(op)) {
                int m = -1;
                try {
                    m = Integer.parseInt(q.get("v"));
                } catch (Throwable t) {
                    return jsonOk(false, "v 不是数字");
                }
                return jsonOk(true, PCtl.setMode(m));
            }
            if ("devctl".equals(op)) {
                String v = q.get("v");
                String r;
                if ("off".equals(v)) {
                    r = DevCtl.off();
                } else if ("on".equals(v)) {
                    r = DevCtl.on();
                } else if ("fs".equals(v)) {
                    r = DevCtl.forceStopOnly();
                } else {
                    r = "OK 小智" + (DevCtl.alive() ? "在跑" : "已停用");
                }
                Proxy.invalidate();
                return jsonOk(true, r);
            }
            /* 一键清理无用进程：只杀 Sys.JUNK 里认得的原厂残留。
               ?v=dry = 只列不杀（页面刷新待清理清单用）。 */
            if ("cleanup".equals(op)) {
                if ("dry".equals(q.get("v"))) {
                    return jsonOk(true, "已刷新待清理清单");
                }
                return jsonOk(true, Sys.cleanup());
            }
            /* 清理的反向操作：pm unhide，把停掉的原厂残留放回来。 */
            if ("restore".equals(op)) {
                return jsonOk(true, Sys.restore());
            }
            /* ---- 蓝牙（标准 BluetoothAdapter API，不经过小智） ---- */
            if ("bt_on".equals(op)) {
                return jsonOk(true, Bt.setOn(true));
            }
            if ("bt_off".equals(op)) {
                return jsonOk(true, Bt.setOn(false));
            }
            if ("bt_scan".equals(op)) {
                return jsonOk(true, Bt.scan());
            }
            if ("bt_discover".equals(op)) {
                return jsonOk(true, Bt.discoverable(!"0".equals(q.get("v"))));
            }
            if ("bt_pair".equals(op)) {
                return jsonOk(true, Bt.pair(q.get("addr")));
            }
            if ("bt_unpair".equals(op)) {
                return jsonOk(true, Bt.unpair(q.get("addr")));
            }
            if ("bt_connect".equals(op)) {
                return jsonOk(true, Bt.connect(q.get("addr")));
            }
            if ("bt_disconnect".equals(op)) {
                return jsonOk(true, Bt.disconnect(q.get("addr")));
            }
            /* ---- 外部播放源 DLNA / AirPlay：v=1 开，v=0 关 ---- */
            if ("dlna".equals(op) || "airplay".equals(op)) {
                boolean on = !"0".equals(q.get("v"));
                return jsonOk(true, Svc.set(op, on));
            }
            if ("ping".equals(op)) {
                return jsonOk(true, "pong ver=" + Boot.VER + " proc=" + Boot.proc
                        + " dev=" + (DevCtl.alive() ? "on" : "off"));
            }
            /* ★ pl-1.6.12：seek 通路自检 —— 排「拖了没反应」时先看这个 */
            if ("diag".equals(op)) {
                return jsonOk(PCtl.seekReady(), PCtl.diag());
            }
        } catch (Throwable t) {
            Boot.log("act " + op + ": " + t);
            return jsonOk(false, "ERR " + t);
        }
        // ★ 兜底：转发给 device（search / radio / random / play_index / play_artist /
        //   play_url / amb / light / wakeup / reboot / restart_app / reload / ws_restart …）
        //   小智都停用了就别干等 8s 超时（串行 accept 会拖住整页），直接说清楚。
        if (!DevCtl.alive()) {
            return jsonOk(false, "小智已停用（8088 不可达）—— " + op + " 只在小智进程里有");
        }
        // v1.3.175：在线点歌类 op（取链要跑六级兜底 + lxserver 中继）给足时间，
        //          否则 8s 一过页面就判失败，而 device 那边其实还在忙、随后会播出来。
        int fwdMs = 8000;
        // v1.3.178：play_artist 现在会在线搜 + 并行取链（最多 ~6s），
        //   random_online / play_rand_at 要现拉一批在线曲库 —— 都给 25s，
        //   否则页面先判失败而 device 那边还在忙，随后照样播出来（假失败）。
        if ("play_online".equals(op) || "play_song".equals(op)
                || "search_online".equals(op) || "play_url".equals(op)
                || "play_artist".equals(op) || "random_online".equals(op)
                || "play_rand_at".equals(op)) {
            fwdMs = 25000;
        }
        String r = Proxy.post("/api/act", buildQuery(q), fwdMs);
        if (r != null) {
            Proxy.invalidate();
            return r;
        }
        return jsonOk(false, DevCtl.alive()
                ? "已转发但 8088 没回应（" + op + "）"
                : "小智未运行（8088 不可达）—— " + op + " 只在小智进程里有");
    }

    /* ---------------- 状态 ---------------- */

    /**
     * 组装成 **device 8088 同构**的 status —— 页面搬过来不用改就能渲染。
     *
     * <p>规则：device 段（activation/audio/server/ambient/ws/session）整体拷贝，
     * 再用 player 本地的真实播放数据覆盖 music 段（曲名/进度/总时长/队列位置）。
     * device 停用时这些段给降级值，页面对应面板变灰但不白屏。</p>
     */
    static JSONObject statusJson() {
        JSONObject o = new JSONObject();
        try {
            JSONObject d = null;
            String ds = Proxy.deviceStatus();
            if (ds != null) {
                try {
                    d = new JSONObject(ds);
                } catch (Throwable t) {
                    d = null;
                }
            }
            boolean devOn = DevCtl.alive();
            if (d != null) {
                Iterator<String> it = d.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    o.put(k, d.opt(k));
                }
            }

            /* ---- player 自己的身份 ---- */
            o.put("pl", true);            // 页面据此跳过 8080 WS、启用小智开关
            o.put("proc", Boot.proc);
            o.put("ver", Boot.VER);       // 页面 checkVer 防呆用这个
            o.put("devVer", d != null ? d.optString("ver", "") : "");
            o.put("port", port);
            o.put("ip", localIp());
            o.put("uptimeSec", (int) ((System.currentTimeMillis() - Boot.bootTs) / 1000));
            o.put("ready", PCtl.ready());

            /* ---- music：player 直供（device 拿不到总时长）---- */
            JSONObject m = (d != null && d.optJSONObject("music") != null)
                    ? d.optJSONObject("music") : new JSONObject();
            PlaySong s = PCtl.song();
            if (s != null) {
                m.put("curTitle", nz(s.getTitle()));
                m.put("curArtist", nz(s.getArtist()));
                m.put("curId", nz(s.getItemId()));
            }
            PlayPositionInfo p = PCtl.pos();
            if (p != null) {
                m.put("posMs", p.getPlay_time());
                m.put("durMs", p.getTotal_time());
                m.put("state", p.getStatus());
            }
            m.put("playing", PCtl.isPlaying());
            /* ★ pl-1.6.12：这首拖得动吗（本地播放态 + 已拿到播放服务的 Binder）。
               页面据此显示/隐藏拖动把手 —— 推流中或没在播时不给假的拖动条。 */
            m.put("seekOk", PCtl.seekReady());
            /* pl-1.6.9：假播检测（显示「播放中」但其实没出声 —— 典型是在线歌取链失败）。
               页面据此把「播放中」改成「缓冲中（可能没出声）」并停掉进度条，
               不再用一个假装在走的时间骗人。 */
            PCtl.samplePos();
            m.put("stalled", PCtl.stalled());
            m.put("stallMs", PCtl.stallMs());
            /* pl-1.6.9（二）：三态 —— loading（起播缓冲中）/ playing（真出声）/ stalled（没出声）。
               页面据此：没出声前显示「缓冲中」、出声后才走时间，总时长则一直显示。 */
            m.put("phase", PCtl.phase());
            /* pl-1.5.0：外部推送源真实状态（手机 DLNA/AirPlay 推流时 playing=true，
               「正在播放」页据此同步显示推流曲目 —— 推流走 :DlnaPlayer 独立引擎，
               本地队列是空的，不读这里页面就显示「未播放」） */
            m.put("ext", Ext.snap());
            /* 音量：与 device 同构对外给**百分比**（页面按 volume*max/100 还原档位），
               另外把真实档位 volStep / 满档 volMax 也带上，方便直接核对。 */
            int vs = PCtl.vol();
            int vm = PCtl.volMax();
            m.put("volStep", vs);
            m.put("volMax", vm);
            m.put("volume", (vm > 0 && vs >= 0) ? Math.round(vs * 100.0f / vm) : vs);
            JSONObject qb = PCtl.queueBrief();
            m.put("curIdx", qb.optInt("cur", -1));
            m.put("qTotal", qb.optInt("total", 0));
            m.put("tracks", qb.optInt("total", 0));
            /* pl-1.2.0：播放模式回显（1=随机 2=顺序 3=单曲循环）。 */
            m.put("mode", PCtl.mode());
            /* pl-1.2.0：queue 是本进程的**内存态** —— player 一重启它就是空的，
               而 curTitle 是 device 落盘的（跨重启存活）⇒ 顶栏会挂着上次的曲名却点不动。
               这里给页面一个 stale 标记：提示「上次播过」，并让 ▶ 走 op=replay。 */
            m.put("stale", qb.optInt("total", 0) == 0 && !m.optBoolean("playing", false));
            o.put("music", m);
            o.put("queue", qb);

            /* ---- 小智进程 ---- */
            JSONObject dv = DevCtl.json();
            dv.put("ver", d != null ? d.optString("ver", "") : "");
            o.put("dev", dv);

            /* ---- device 停用时把它的动态段降级，免得页面显示过期数据 ---- */
            if (!devOn) {
                JSONObject ss = new JSONObject();
                ss.put("state", "未运行");
                ss.put("raw", 0);
                ss.put("rawPeak", 0);
                o.put("session", ss);
                JSONObject w = new JSONObject();
                w.put("open", false);
                w.put("url", "");
                o.put("ws", w);
            }
        } catch (Throwable t) {
            Boot.log("statusJson: " + t);
        }
        return o;
    }

    /* ---------------- HTTP 写出 ---------------- */

    private static void write(OutputStream os, int code, String type, String body)
            throws Exception {
        byte[] b = body.getBytes("UTF-8");
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.0 ").append(code).append(" OK\r\n");
        h.append("Content-Type: ").append(type).append("\r\n");
        h.append("Content-Length: ").append(b.length).append("\r\n");
        h.append("Access-Control-Allow-Origin: *\r\n");
        h.append("Cache-Control: no-store\r\n");
        h.append("Connection: close\r\n\r\n");
        os.write(h.toString().getBytes("UTF-8"));
        os.write(b);
    }

    /** 二进制响应（PNG / 204）。extra=null 时给 no-store。 */
    private static void writeBytes(OutputStream os, int code, String type,
            byte[] b, String extra) throws Exception {
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.0 ").append(code).append(" OK\r\n");
        h.append("Content-Type: ").append(type).append("\r\n");
        h.append("Content-Length: ").append(b.length).append("\r\n");
        h.append("Access-Control-Allow-Origin: *\r\n");
        if (extra != null && extra.length() > 0) {
            h.append(extra);
        } else {
            h.append("Cache-Control: no-store\r\n");
        }
        h.append("Connection: close\r\n\r\n");
        os.write(h.toString().getBytes("UTF-8"));
        os.write(b);
    }

    private static void json(OutputStream os, String s) throws Exception {
        write(os, 200, "application/json; charset=utf-8", s);
    }

    private static void txt(OutputStream os, int code, String s) throws Exception {
        write(os, code, "text/plain; charset=utf-8", s);
    }

    /** 与 device 侧一致的返回体：页面 act()/setKV() 都读 {ok,msg}。 */
    private static String jsonOk(boolean ok, String msg) {
        try {
            JSONObject r = new JSONObject();
            r.put("ok", ok);
            r.put("msg", msg);
            return r.toString();
        } catch (Throwable t) {
            return ok ? "{\"ok\":true,\"msg\":\"\"}" : "{\"ok\":false,\"msg\":\"\"}";
        }
    }

    private static Map<String, String> parse(String qs) {
        Map<String, String> m = new HashMap<String, String>();
        if (qs == null || qs.length() == 0) {
            return m;
        }
        String[] kv = qs.split("&");
        for (int i = 0; i < kv.length; i++) {
            int e = kv[i].indexOf('=');
            if (e <= 0) {
                continue;
            }
            try {
                m.put(URLDecoder.decode(kv[i].substring(0, e), "UTF-8"),
                        URLDecoder.decode(kv[i].substring(e + 1), "UTF-8"));
            } catch (Throwable t) {
                // ignore
            }
        }
        return m;
    }

    /** 把 query map 还原成 form-urlencoded（转发给 device 用）。 */
    private static String buildQuery(Map<String, String> q) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        Iterator<Map.Entry<String, String>> it = q.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, String> e = it.next();
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        return sb.toString();
    }

    private static String enc(String s) {
        if (s == null) {
            return "";
        }
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Throwable t) {
            return s;
        }
    }

    private static String def(String s, String dflt) {
        return (s == null || s.length() == 0) ? dflt : s;
    }

    private static String localIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> en =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (en.hasMoreElements()) {
                java.net.NetworkInterface ni = en.nextElement();
                java.util.Enumeration<java.net.InetAddress> ia = ni.getInetAddresses();
                while (ia.hasMoreElements()) {
                    java.net.InetAddress a = ia.nextElement();
                    if (!a.isLoopbackAddress() && a instanceof java.net.Inet4Address) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Throwable t) {
            // ignore
        }
        return "127.0.0.1";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private WebSrv() {
    }
}
