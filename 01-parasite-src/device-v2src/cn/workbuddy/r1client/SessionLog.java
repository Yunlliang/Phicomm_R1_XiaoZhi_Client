package cn.workbuddy.r1client;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * v1.2.10：抗淹没会话日志（落盘到 app 内部存储）。
 *
 * 为什么存在：原厂 uni4mic 在 USC 录音期间每秒 100+ 行刷爆 logcat，
 * 256KB 环形缓冲只留最后 ~20 秒 —— 会话期间的关键日志（connecting/
 * hello/服务器回应）在事后抓取时已被挤出，当天全部诊断被活埋。
 *
 * v1.2.9 曾写外部存储（getExternalFilesDir），实测该 ROM 上目录根本
 * 建不出来（FUSE 异常，20:46 验证），v1.2.10 改内部存储 getFilesDir()
 * —— 必定可写、无权限要求；用 run-as 读取（debug 包可用，无需 root，
 * 20:46 已验证 run-as 在本机可用）：
 *
 *   adb shell run-as cn.workbuddy.r1client cat files/r1client.log
 *
 * 256KB 轮转成 .old（run-as cat 一秒内可拉完）。写失败只向 logcat
 * 报一次，之后静默，绝不影响主流程。
 */
final class SessionLog {

    private static final Object LOCK = new Object();
    private static volatile File file;
    private static volatile boolean failureLogged = false;
    /**
     * v1.3.108：写日志总开关（默认开）。关掉后 write() 直接返回，
     * 不再有任何文件 IO —— 想彻底不落盘时用（flag：files/log_off）。
     */
    private static volatile boolean enabled = true;
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    /** 在 Service.onCreate 调一次 */
    static void init(Context ctx) {
        try {
            file = new File(ctx.getFilesDir(), "r1client.log");
            // v1.3.108：在这里就读开关（尽早），否则第一行日志已经写进去了
            enabled = !new File(ctx.getFilesDir(), "log_off").exists();
        } catch (Throwable t) {
            file = null;
        }
    }

    static void i(String msg) {
        write(msg);
    }

    /** v1.3.108：web 控制台开关用，立即生效（写之前每次读这个字段） */
    static void setEnabled(boolean on) {
        enabled = on;
    }

    static boolean isEnabled() {
        return enabled;
    }

    private static void write(String msg) {
        if (!enabled) {
            return;             // v1.3.108：关掉后零磁盘写入
        }
        File f = file;
        if (f == null) {
            return;
        }
        try {
            synchronized (LOCK) {
                if (f.exists() && f.length() > 256L * 1024L) {
                    File old = new File(f.getParentFile(), "r1client.log.old");
                    //noinspection ResultOfMethodCallIgnored
                    old.delete();
                    //noinspection ResultOfMethodCallIgnored
                    f.renameTo(old);
                }
                FileOutputStream fos = new FileOutputStream(f, true);
                fos.write((TS.format(new Date()) + " " + msg + "\n").getBytes("UTF-8"));
                fos.close();
            }
        } catch (Throwable t) {
            if (!failureLogged) {
                failureLogged = true;
                Log.w("R1CLIENT", "SessionLog write failed (logged once): " + t);
            }
        }
    }

    /**
     * v1.3.99：取最后 n 行（web 控制台日志面板用）。
     * 直接从落盘文件读，与内存无关；256KB 上限，全读进内存也就几百 KB。
     */
    static String tail(int n) {
        return tail(n, false);
    }

    /**
     * v1.3.139：{@code chatOnly=true} ⇒ 只吐「对话」—— 用户说了什么、小智回了什么。
     *
     * <p>实现上是**读的时候从现有日志派生**，不额外写盘（256KB 轮转已经很挤了）：</p>
     * <ul>
     *   <li>{@code STT: xxx}（{@code SessionController.onStt}）⇒ {@code 我：xxx}</li>
     *   <li>{@code <- {"type":"tts","state":"sentence_start","text":"xxx"}}
     *       ⇒ {@code 小智：xxx}（其余控制类 JSON 全部丢掉）</li>
     * </ul>
     * 因此历史日志也能立刻用这个视图看，不必等新日志产生。
     *
     * @param n 对话条数（不是原始行数）
     */
    static String tail(int n, boolean chatOnly) {
        File f = file;
        if (f == null || !f.exists()) {
            return "";
        }
        try {
            byte[] b = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int r = in.read(b);
            in.close();
            String s = new String(b, 0, r > 0 ? r : 0, "UTF-8");
            String[] lines = s.split("\n");
            StringBuilder sb = new StringBuilder();
            if (chatOnly) {
                ArrayList<String> chat = new ArrayList<String>();
                for (int i = 0; i < lines.length; i++) {
                    String c = chatLine(lines[i]);
                    if (c != null) {
                        chat.add(c);
                    }
                }
                int from = chat.size() - n;
                if (from < 0) {
                    from = 0;
                }
                for (int i = from; i < chat.size(); i++) {
                    sb.append(chat.get(i)).append('\n');
                }
                if (chat.size() == 0) {
                    sb.append("(这一段里没有对话 —— 只有 STT / TTS 文本才算；"
                            + "连调试日志一起看请关掉「仅显示对话」)\n");
                }
            } else {
                int from = lines.length - n;
                if (from < 0) {
                    from = 0;
                }
                for (int i = from; i < lines.length; i++) {
                    sb.append(lines[i]).append('\n');
                }
            }
            if (!enabled) {
                sb.append("\n(日志写入已关闭 —— 以上是关闭前留下的内容；"
                        + "在「设置」里重新勾选「写日志」后才会继续追加)\n");
            }
            return sb.toString();
        } catch (Throwable t) {
            return "(log read failed: " + t + ")";
        }
    }

    /**
     * v1.3.157：从 "10-13 21:37:46.644" 里抠出 "10-13 21:37:46"（**保留日期**）。
     *
     * <p>以前只取最后一个空格后的 token（"21:37:46"），翻记录时不知道是哪一天的 ——
     * 日志轮转是 256KB，跨天是常态，只有时间等于没法定位。现在连月日一起带出来。</p>
     */
    private static String tsOf(String head) {
        if (head == null || head.length() == 0) {
            return "";
        }
        String t = head.trim();
        int dot = t.indexOf('.');
        if (dot > 0) {
            t = t.substring(0, dot);        // 去掉 .SSS 毫秒
        }
        // 极端情况：万一这一行的时间戳前面还带了别的东西，就只留最后两个 token
        String[] p = t.split(" ");
        if (p.length > 2) {
            t = p[p.length - 2] + " " + p[p.length - 1];
        }
        return t;
    }

    /**
     * v1.3.139：把一行原始日志翻成「对话行」，不是对话就返回 null。
     *
     * <p>只认两类，其余（延迟打点、唤醒、DLNA、电平探针……）全部丢弃 ——
     * 那些是排障用的，正是「仅显示对话」想藏起来的东西。</p>
     */
    private static String chatLine(String raw) {
        if (raw == null) {
            return null;
        }
        String l = raw.trim();          // 设备落盘行尾可能带 \r
        if (l.length() == 0) {
            return null;
        }
        // 时间前缀：不猜日期格式（实测是 "10-13 21:37:46.644 "，MM-dd），
        // 只取**第一个空格之后的第二个 token**（HH:mm:ss.SSS → HH:mm:ss）。
        int iStt = l.indexOf(" STT: ");
        int iRcv = l.indexOf(" <- ");
        // 找「消息体」起点 = 两个标记里更靠前的那个；时间戳就是它前面的尾部 token
        int body = -1;
        if (iStt >= 0 && (iRcv < 0 || iStt < iRcv)) {
            body = iStt + 1;
        } else if (iRcv >= 0) {
            body = iRcv + 1;
        }
        if (body < 0) {
            return null;
        }
        String ts = tsOf(l.substring(0, body).trim());
        l = l.substring(body);
        if (l.startsWith("STT: ")) {
            String t = l.substring(5).trim();
            return t.length() == 0 ? null : ts + " 我：" + t;
        }
        if (l.startsWith("<- ")) {
            String j = l.substring(3).trim();
            if (j.length() == 0 || j.charAt(0) != '{') {
                return null;
            }
            try {
                JSONObject o = new JSONObject(j);
                if (!"tts".equals(o.optString("type"))) {
                    return null;        // stt / hello / mcp / listen … 都不是「小智说的话」
                }
                String st = o.optString("state");
                // 官方协议：sentence_start 带 text（说出口的内容）；
                // start / stop / abort 是控制帧，没有文本。
                if (!"sentence_start".equals(st)) {
                    return null;
                }
                String t = o.optString("text");
                if (t == null) {
                    return null;
                }
                t = t.trim();
                if (t.length() == 0) {
                    return null;
                }
                // v1.3.140：服务器把「正在调工具」也当成一句话下发
                // （如 "% search_music..." / "% intent__HassTurnOff..."）—— 那是动作不是对话
                if (t.charAt(0) == '%') {
                    return null;
                }
                return ts + " 小智：" + t;
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private SessionLog() {
    }
}
