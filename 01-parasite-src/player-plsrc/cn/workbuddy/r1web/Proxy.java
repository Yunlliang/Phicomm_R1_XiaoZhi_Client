package cn.workbuddy.r1web;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 反向代理：把 device（小智，:8088）的能力接到 player 的控制台上。
 *
 * <p>★ 为什么要代理：整页搬到 player 之后，有一部分能力**物理上只在 device 侧**——
 * 会话/唤醒、增益等音频设置、氛围灯、曲库搜索与电台、设备重启、日志。
 * 这些在 player 进程里没有对应实现（player 只有播放本身），所以直接转发到
 * 127.0.0.1:8088，让 device 自己干。</p>
 *
 * <p>★ 为什么不全部代理：播放控制/队列/进度/时长 player 自己就有，而且更准
 * （{@code EasyPlayer.getPlayPosition()} 能拿到真实总时长，device 侧一直拿不到）。
 * 所以 {@link WebSrv} 先本地处理，认不出的 op 才落到这里。</p>
 *
 * <p>device 被停用（pm hide）时 8088 不可达 ⇒ 全部返回 ok=false，
 * 页面只是对应面板降级，播放与本页面照常。</p>
 */
public final class Proxy {

    /** device 侧控制台。同机回环，不走外网。 */
    public static final String BASE = "http://127.0.0.1:8088";

    /** device 段状态缓存（页面 2s 拉一次 status，没必要每次都打 8088）。 */
    private static volatile String devStatusCache = "";
    private static volatile long devStatusTs = 0;
    private static final long TTL = 1200;

    /** 拉 device 的 /api/status 原文（带 1.2s 缓存）。失败返回 null。 */
    public static String deviceStatus() {
        long now = System.currentTimeMillis();
        if (devStatusCache.length() > 0 && now - devStatusTs < TTL) {
            return devStatusCache;
        }
        String s = get("/api/status", 1800);
        if (s != null && s.length() > 0) {
            devStatusCache = s;
            devStatusTs = now;
            return s;
        }
        return devStatusCache.length() > 0 ? devStatusCache : null;
    }

    /** 动过 device 状态（停用/启用）后清缓存，下次立刻重取。 */
    public static void invalidate() {
        devStatusCache = "";
        devStatusTs = 0;
    }

    public static String get(String pathAndQuery, int ms) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(BASE + pathAndQuery).openConnection();
            c.setConnectTimeout(ms);
            c.setReadTimeout(ms);
            c.setRequestMethod("GET");
            c.setInstanceFollowRedirects(false);
            int code = c.getResponseCode();
            if (code != 200) {
                return null;
            }
            return read(c.getInputStream());
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable t) {
                    // ignore
                }
            }
        }
    }

    /** POST（form-urlencoded）。device 的 /api/set 与 /api/act 都吃这个。 */
    public static String post(String path, String body, int ms) {
        HttpURLConnection c = null;
        OutputStream os = null;
        try {
            c = (HttpURLConnection) new URL(BASE + path).openConnection();
            c.setConnectTimeout(ms);
            c.setReadTimeout(ms);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type",
                    "application/x-www-form-urlencoded");
            byte[] b = body.getBytes("UTF-8");
            c.setFixedLengthStreamingMode(b.length);
            os = c.getOutputStream();
            os.write(b);
            os.flush();
            int code = c.getResponseCode();
            if (code != 200) {
                return null;
            }
            return read(c.getInputStream());
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (os != null) {
                    os.close();
                }
            } catch (Throwable t) {
                // ignore
            }
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable t) {
                    // ignore
                }
            }
        }
    }

    private static String read(InputStream in) {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[4096];
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, "UTF-8"));
            }
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                in.close();
            } catch (Throwable t) {
                // ignore
            }
        }
        return sb.toString();
    }

    private Proxy() {
    }
}
