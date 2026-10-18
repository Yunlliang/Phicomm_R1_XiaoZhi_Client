package cn.workbuddy.r1web;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;

/**
 * 小智（device 进程）的停用 / 启用。
 *
 * <p>为什么要这个：player 里跑着蓝牙 / DLNA / AirPlay（三个独立子进程），
 * 停用小智后音箱就是一台纯蓝牙音箱 + DLNA/AirPlay 接收器，
 * 麦克风、唤醒、云端 WS 全部停掉，CPU 和内存都还回来。</p>
 *
 * <p>★ 不用 force-stop 也能成：先礼貌地让 device 自己了断（HTTP {@code op=halt}），
 * 它自杀最干净（同 uid 杀自己不需要任何权限）。system uid 的 force-stop 只作兜底 ——
 * player 没声明 FORCE_STOP_PACKAGES，实测大概率被拒，失败也不影响结果。</p>
 *
 * <p>★ device 不是 persistent（dumpsys 里 flags 没有 PERSISTENT），
 * 杀掉不会被系统自动拉起 ⇒ 恢复必须显式 startservice（这一步同时清掉 stopped 标记）。</p>
 */
public final class DevCtl {

    public static final String DEV_PKG = "com.phicomm.speaker.device";
    public static final String DEV_SVC = DEV_PKG + "/.ui.service.WindowsService";
    public static final String XZ_PKG = "cn.workbuddy.r1client";

    private static long aliveTs = 0;
    private static boolean aliveCache = false;

    /** device 是否在跑（HTTP 探测 8088，带 2.5s 缓存）。 */
    public static synchronized boolean alive() {
        long now = System.currentTimeMillis();
        if (now - aliveTs < 2500) {
            return aliveCache;
        }
        String s = httpGet("http://127.0.0.1:8088/api/status", 1200);
        aliveCache = s != null && s.indexOf("\"ver\"") >= 0;
        aliveTs = now;
        return aliveCache;
    }

    public static JSONObject json() {
        JSONObject o = new JSONObject();
        try {
            o.put("pkg", DEV_PKG);
            o.put("on", alive());
        } catch (Throwable t) {
            // ignore
        }
        return o;
    }

    /**
     * 停用小智 —— 走 {@code pm hide}。
     *
     * <p>★ 为什么必须是 pm hide（血泪踩坑）：</p>
     * <pre>
     *   ① 只让 device 自己 killProcess：它里面有 START_STICKY / 被别进程绑定的服务，
     *      自杀后系统 6s 内就用新 pid 把它拉回来（实测 pid 809 → 1457）。
     *   ② am force-stop：player 没声明 FORCE_STOP_PACKAGES，system uid 也被拒。
     *   ③ pm hide：包级隐藏 —— 杀掉整包所有进程、组件全部失效、
     *      BOOT_COMPLETED 也不再拉起。恢复只能靠 pm unhide。这才是能停住的那一档。
     * </pre>
     * <p>顺序：先礼貌地让它自己了断（省得半路被打断），再 pm hide 落闸。</p>
     */
    public static String off() {
        StringBuilder r = new StringBuilder();
        String h = httpGet("http://127.0.0.1:8088/api/act?op=halt", 2500);
        r.append("halt=").append(h == null ? "无回应" : tail(h, 60));
        try {
            Thread.sleep(1200);
        } catch (Throwable t) {
            // ignore
        }
        String hs = exec("pm hide " + DEV_PKG);
        r.append(" | pm hide=").append(tail(hs, 120));
        try {
            Thread.sleep(2000);
        } catch (Throwable t) {
            // ignore
        }
        aliveTs = 0;
        if (alive()) {
            String hs2 = exec("pm hide " + DEV_PKG);
            r.append(" | 二次=").append(tail(hs2, 60));
            try {
                Thread.sleep(1500);
            } catch (Throwable t) {
                // ignore
            }
            aliveTs = 0;
        }
        r.append(" | ").append(alive() ? "仍在跑（pm hide 没生效？看日志）" : "已停");
        Boot.log("devctl off: " + r);
        return "OK " + r;
    }

    /**
     * 启用小智 —— {@code pm unhide} + 显式 startservice。
     * 隐藏包不能直接被启动，必须先 unhide；startservice 顺便把 stopped 标记清掉。
     */
    public static String on() {
        String u = exec("pm unhide " + DEV_PKG);
        String r = exec("am startservice -n " + DEV_SVC);
        Boot.log("devctl on: unhide=" + u.trim() + " start=" + r.trim());
        boolean up = false;
        for (int i = 0; i < 8; i++) {
            try {
                Thread.sleep(1500);
            } catch (Throwable t) {
                // ignore
            }
            aliveTs = 0;
            if (alive()) {
                up = true;
                break;
            }
        }
        return up ? "OK 小智已起来（unhide 后 startservice）"
                : "WARN 已 unhide 并发出启动命令，但 12s 内没探到 8088："
                  + tail(u, 60) + " / " + tail(r, 60);
    }

    /** 取证：只跑一次 pm hide，把原始输出回回来（看 system uid 到底有没有权限）。 */
    public static String forceStopOnly() {
        String s = exec("pm hide " + DEV_PKG);
        Boot.log("pm hide 取证: " + s);
        return "pm hide => " + s;
    }

    /** 取文本最后一行并截断 —— HTTP 响应 / am 输出太长时页面只显示尾巴。 */
    private static String tail(String s, int max) {
        if (s == null) {
            return "无回应";
        }
        String[] ls = s.split("\n");
        String last = "";
        for (int i = ls.length - 1; i >= 0; i--) {
            String t = ls[i].trim();
            if (t.length() > 0) {
                last = t;
                break;
            }
        }
        return last.length() > max ? last.substring(0, max) : last;
    }

    /* ---------------- 工具 ---------------- */

    /** 极简 HTTP GET，超时 ms。用于本机 loopback 探测。 */
    public static String httpGet(String url, int timeoutMs) {
        Socket s = null;
        try {
            java.net.URL u = new java.net.URL(url);
            int port = u.getPort() > 0 ? u.getPort() : 80;
            s = new Socket();
            s.connect(new java.net.InetSocketAddress(u.getHost(), port), timeoutMs);
            s.setSoTimeout(timeoutMs);
            OutputStream os = s.getOutputStream();
            os.write(("GET " + u.getPath()
                    + (u.getQuery() != null ? "?" + u.getQuery() : "")
                    + " HTTP/1.0\r\nHost: " + u.getHost() + "\r\n\r\n").getBytes("UTF-8"));
            os.flush();
            InputStream in = s.getInputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
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

    public static String exec(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            final StringBuilder sb = new StringBuilder();
            Thread t1 = new Thread(new Runnable() {
                public void run() {
                    try {
                        BufferedReader br = new BufferedReader(
                                new InputStreamReader(p.getInputStream(), "UTF-8"));
                        String l;
                        while ((l = br.readLine()) != null) {
                            sb.append(l).append(" ");
                        }
                    } catch (Throwable t) {
                        // ignore
                    }
                }
            });
            Thread t2 = new Thread(new Runnable() {
                public void run() {
                    try {
                        BufferedReader br = new BufferedReader(
                                new InputStreamReader(p.getErrorStream(), "UTF-8"));
                        String l;
                        while ((l = br.readLine()) != null) {
                            sb.append(l).append(" ");
                        }
                    } catch (Throwable t) {
                        // ignore
                    }
                }
            });
            t1.start();
            t2.start();
            p.waitFor();
            t1.join(1500);
            t2.join(1500);
            String out = sb.toString().trim();
            return out.length() == 0 ? "(无输出)" : out;
        } catch (Throwable t) {
            return "执行失败: " + t;
        }
    }

    private DevCtl() {
    }
}
