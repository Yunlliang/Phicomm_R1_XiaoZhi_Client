package cn.workbuddy.r1client;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.InputStream;

/**
 * v1.3.150：整机重启 + 取证的 shell 通道。
 *
 * <h3>为什么 v1.3.109 的 reboot 点了没反应</h3>
 * 当时只试了三条：{@code /system/bin/reboot}、{@code setprop sys.powerctl}、
 * 8080 通道。全军覆没 —— 因为我们的包（寄生在 Pandora 里）实测跑在 <b>u0_a8</b>，
 * 既没有 {@code android.permission.REBOOT}，{@code /system/bin/reboot} 也没有
 * setuid，{@code setprop} 写系统属性同样被拒。
 *
 * <h3>这次换的路：借道 adbd</h3>
 * 小飞版控制页 JS 里「重启音箱」的实现是
 * <pre>ws_send({type:"shell", shell:"stop adbd&&start adbd&&adb reboot"})</pre>
 * 注意是 {@code adb reboot} 而不是 {@code reboot} —— 它压根没打算让调用方自己有
 * 权限，而是把「重启」这件事<b>交给 adbd 去做</b>。本机三条前提恰好都成立：
 * <ul>
 *   <li>{@code /system/bin/adb} 存在（117KB，root:shell 755，others 可执行）</li>
 *   <li>{@code ro.adb.secure=0} —— adbd 免认证，设备上自己连自己不会被踢</li>
 *   <li>{@code service.adb.tcp.port=5555} —— adb 客户端会自动走 TCP 回环连 adbd</li>
 * </ul>
 * adbd 自己有重启的能力，所以「普通 app → adb client → adbd → reboot」这条链
 * 绕开了 CAP_SYS_BOOT 的限制。这就是本次能重新加回来的原因。
 *
 * <p>仍然保留原来的三条作为兜底，并且每条都把 exit code / stdout / stderr 回显，
 * 一次点击就能看清到底哪条走得通（AI 看不见灯箱，只能靠回显取证）。</p>
 */
public final class DeviceCtl {

    private static final String TAG = "DeviceCtl";
    private static Context app;

    private DeviceCtl() {
    }

    public static void init(Context c) {
        if (c != null) {
            app = c.getApplicationContext();
        }
    }

    // ---------------- 重启整机 ----------------

    /**
     * 整机重启。按成功率从高到低逐条尝试，第一条 exit=0 就收工。
     *
     * @return 每一条的 exit/out/err，末尾给结论
     */
    public static String reboot() {
        // 先把「我们到底是谁」打出来（/system/bin/id 不一定存在，走 sh 稳一点）
        String head = "uid=" + exec(new String[]{"/system/bin/sh", "-c", "id"}, 4000);
        SessionLog.i("web: reboot requested | " + head);

        String[][] tries = new String[][]{
                // ① 正路：adb 客户端回环连 adbd，让 adbd 去 reboot
                {"/system/bin/adb", "reboot"},
                // ② 万一来不及读 service.adb.tcp.port：显式 connect 再指定 -s
                {"/system/bin/sh", "-c",
                        "/system/bin/adb connect 127.0.0.1:5555 >/dev/null 2>&1; "
                                + "/system/bin/adb -s 127.0.0.1:5555 reboot"},
                // ③ 换一种说法：让 adbd 自己 shell 里执行 reboot
                {"/system/bin/adb", "shell", "reboot"},
                // ④ 老路（v1.3.109 失败过，留着看报错）
                {"/system/bin/reboot"},
                // ⑤ 写属性（v1.3.109 失败过，留着看报错）
                {"/system/bin/setprop", "sys.powerctl", "reboot"},
        };

        StringBuilder rep = new StringBuilder(head).append('\n');
        for (int i = 0; i < tries.length; i++) {
            String r = exec(tries[i], 6000);
            rep.append(i + 1).append(") ").append(joined(tries[i]))
                    .append("\n   -> ").append(r).append('\n');
            if (r.startsWith("exit=0")) {
                rep.append("\n已发出重启指令（第 ").append(i + 1)
                        .append(" 条成功）。音箱大约 30s 后回来，本页会自动重连。");
                SessionLog.i("reboot: 命中策略 " + (i + 1) + " -> " + joined(tries[i]));
                return rep.toString();
            }
        }
        rep.append("\n五条全失败：本进程是 u0_a8，没有 REBOOT 权限。"
                + "只能从电脑上 adb reboot，或者直接断电。");
        SessionLog.i("reboot: 全部策略失败");
        return rep.toString();
    }

    // ---------------- 取证用 shell（默认关） ----------------

    /**
     * 以本 app 的身份执行一条 shell 命令，回显 stdout/stderr。
     * 默认关闭 —— 需要在 {@code files/shell_api} 存在时才启用（排障用，别常开）。
     */
    public static String shell(String v) {
        if (!shellEnabled()) {
            return "shell 接口未开启（要在 files/shell_api 建一个空文件，排障完记得删）";
        }
        if (v == null || v.trim().length() == 0) {
            return "empty cmd";
        }
        SessionLog.i("web: shell " + v);
        return exec(new String[]{"/system/bin/sh", "-c", v}, 8000);
    }

    private static boolean shellEnabled() {
        if (app == null) {
            return false;
        }
        try {
            return new File(app.getFilesDir(), "shell_api").exists();
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ---------------- 执行 ----------------

    /**
     * 起一个进程并把输出捞回来。
     *
     * @return {@code exit=N out=<..> err=<..>}，超时是 {@code exit=timeout(...)}
     */
    public static String exec(String[] cmd, int timeoutMs) {
        StringBuilder sb = new StringBuilder();
        Process p = null;
        try {
            String[] env = null;
            File dir = null;
            if (app != null) {
                String home = app.getFilesDir().getAbsolutePath();
                dir = app.getFilesDir();
                env = new String[]{
                        "PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin",
                        "HOME=" + home,
                        "TMPDIR=" + home,
                        "ANDROID_DATA=/data",
                        "ANDROID_ROOT=/system",
                        "LD_LIBRARY_PATH=/vendor/lib:/system/lib",
                };
            }
            p = Runtime.getRuntime().exec(cmd, env, dir);

            final StringBuilder out = new StringBuilder();
            final StringBuilder err = new StringBuilder();
            Thread t1 = pump(p.getInputStream(), out);
            Thread t2 = pump(p.getErrorStream(), err);
            t1.start();
            t2.start();

            int exit = -999;
            boolean done = false;
            long t0 = System.currentTimeMillis();
            while (System.currentTimeMillis() - t0 < timeoutMs) {
                try {
                    exit = p.exitValue();
                    done = true;
                    break;
                } catch (IllegalThreadStateException running) {
                    try {
                        Thread.sleep(80);
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (!done) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
                sb.append("exit=timeout(>").append(timeoutMs).append("ms)");
            } else {
                sb.append("exit=").append(exit);
            }
            t1.join(400);
            t2.join(400);
            sb.append(" out=").append(sq(out.toString()));
            sb.append(" err=").append(sq(err.toString()));
        } catch (Throwable t) {
            sb.append("exec FAIL ").append(t);
            Log.w(TAG, "exec fail", t);
        }
        return sb.toString();
    }

    private static Thread pump(final InputStream is, final StringBuilder sink) {
        return new Thread(new Runnable() {
            public void run() {
                try {
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = is.read(b)) > 0) {
                        if (sink.length() < 4000) {
                            sink.append(new String(b, 0, n, "UTF-8"));
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    try {
                        is.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "ctl-pump");
    }

    /** 压成一行、截到 200 字符，免得回显把控制台刷爆 */
    private static String sq(String s) {
        String t = s.replace('\r', ' ').replace('\n', ' ').trim();
        if (t.length() > 200) {
            t = t.substring(0, 200) + "…";
        }
        return "[" + t + "]";
    }

    private static String joined(String[] cmd) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cmd.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(cmd[i]);
        }
        String s = sb.toString();
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }
}
