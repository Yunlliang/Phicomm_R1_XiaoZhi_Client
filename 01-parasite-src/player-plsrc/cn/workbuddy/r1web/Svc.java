package cn.workbuddy.r1web;

import com.phicomm.speaker.player.model.EasyPlayer;
import com.phicomm.speaker.player.model.RemotePlayer;

import org.json.JSONObject;

/**
 * DLNA / AirPlay 的<b>功能开关</b>（设置页「外部播放源」一栏）。
 *
 * <p>★ 用户 2026-10-17 纠正：要开关的是「程序里的功能」，<b>进程要保留</b>
 * （:DlnaPlayer / :AirPlayer 一直在跑，别动它 —— 第一版做成 unbind+stopService+kill
 * 是错的方向，已废）。</p>
 *
 * <p>★ 真开关是 AIDL 里的 {@code IPlayer.startServer()} / {@code stopServer()}
 * （TRANSACTION 0x11 / 0x12）：
 * <pre>
 *   DlnaPlayer2.startServer()   → startEngine() → initRenderService()  （renderer 起来，手机才搜得到）
 *   AirPortPlayer.startServer() → shairportWrapper.startShairport() + jmdns 广播
 * </pre>
 * 而整个原厂代码里<b>没有任何一处调用过它</b>（只在接口定义和 AIDL Stub/Proxy 里出现）
 * ⇒ 这就是「功能都关着、手机搜不到」的根因：不是开关被拨到关，是压根没人去开。
 * RemotePlayer 没有暴露这两个方法，所以走反射：拿它的 {@code mIPlayer}
 * （服务端的远程代理 {@code IPlayer$Stub$Proxy}）再 invoke。</p>
 *
 * <p>★★ 状态不靠「上次点了什么」的记录（用户 2026-10-17 明确要求「按记录不准」），
 * 直接读内核 socket 表 —— 功能活着就一定占着端口，差分实验实测（2026-10-17）：</p>
 * <pre>
 *   DLNA   开 → /proc/net/udp 出现 :076C（UDP 1900，SSDP/WSDD）
 *   AirPlay 开 → /proc/net/tcp 出现 :138A（TCP 5002，RAOP）LISTEN
 * </pre>
 * 开关与端口痕迹严格同步（关就消失、开就出现），比任何客户端记录都真。
 * 进程也要报（dlnaProc / airProc）—— 进程是功能的载体，进程没了功能一定没，
 * 但进程在 ≠ 功能开着，两个一起看。</p>
 */
public final class Svc {

    /** DLNA renderer 起来后占的 UDP 端口 1900 = 0x76C（SSDP/WSDD）。 */
    private static final int P_DLNA = 0x76C;
    /** AirPlay shairport 起来后占的 TCP 端口 5002 = 0x138A（RAOP）。 */
    private static final int P_AIR = 0x138A;

    private static final String F_DLNA = "mDlnaPlayer";
    private static final String F_AIR = "mAirPortPlayer";

    /**
     * 开 / 关一个外部播放功能（页面按钮入口 = <b>用户手动操作</b>）。
     *
     * <p>pl-1.6.2：执行完把「手动意图」记到账本（{@link #saveManual}）——
     * 这不是状态记录（状态永远读端口），而是「用户想要什么」的账：
     * 被系统自动停掉时按它决定要不要补开（{@link #autoReopenLater}）。</p>
     *
     * @param which "dlna" 或 "airplay"
     * @param on    true = startServer（开功能），false = stopServer（关功能）
     */
    public static String set(String which, boolean on) {
        if (!"dlna".equals(which) && !"airplay".equals(which)) {
            return "未知服务: " + which;
        }
        // 先执行再记账 —— 哪怕这次没生效，用户的意图也已经表达了，
        // 后续自动补开以这本账为准。
        String r = on ? openOnly(which) : closeOnly(which);
        saveManual(which, on);
        return r;
    }

    /** startServer（含 connectRemoteService 确保 bind）。autoReopenLater 也走它。 */
    static String openOnly(String which) {
        String name = "dlna".equals(which) ? "DLNA" : "AirPlay";
        RemotePlayer rp = rp("dlna".equals(which) ? F_DLNA : F_AIR);
        if (rp == null) {
            return "拿不到原厂的 RemotePlayer（EasyPlayer 还没起来？）";
        }
        try {
            // 绑定要在，否则拿不到远端代理（进程本来就在，这里只是确保 bind）
            rp.connectRemoteService();
            Thread.sleep(1500);
            Object ip = PCtl.field(rp, "mIPlayer");
            if (ip == null) {
                return name + " 服务还没连上（mIPlayer 为空），等两秒再点一次";
            }
            ip.getClass().getMethod("startServer").invoke(ip);
            Boot.log("svc " + name + " startServer OK");
            Thread.sleep(1500);
        } catch (Throwable t) {
            Boot.log("svc " + name + ": " + t);
            return name + " 开启失败: " + t;
        }
        /* 读真状态回报，不拿自己的记录忽悠 */
        boolean up = "dlna".equals(which) ? dlnaUp() : airUp();
        return name + " 功能已开启（" + (up ? "端口已就绪" : "端口没起来，可能没生效") + "）";
    }

    /** stopServer。 */
    static String closeOnly(String which) {
        String name = "dlna".equals(which) ? "DLNA" : "AirPlay";
        RemotePlayer rp = rp("dlna".equals(which) ? F_DLNA : F_AIR);
        if (rp == null) {
            return "拿不到原厂的 RemotePlayer（EasyPlayer 还没起来？）";
        }
        try {
            Object ip = PCtl.field(rp, "mIPlayer");
            if (ip == null) {
                return name + " 服务还没连上（mIPlayer 为空），等两秒再点一次";
            }
            ip.getClass().getMethod("stopServer").invoke(ip);
            Boot.log("svc " + name + " stopServer OK");
            Thread.sleep(1500);
        } catch (Throwable t) {
            Boot.log("svc " + name + ": " + t);
            return name + " 关闭失败: " + t;
        }
        boolean up = "dlna".equals(which) ? dlnaUp() : airUp();
        return name + " 功能已关闭（" + (up ? "端口还在，可能没生效" : "端口已退出") + "）";
    }

    /* ---------- pl-1.6.2 手动意图账本 + 被系统停掉后自动补开 ---------- */

    private static final String F_MANUAL = "r1_svc_manual.txt";

    /**
     * 用户是否<b>手动开过</b>这个功能。
     *
     * <p>规则（用户 2026-10-17）：手动开过的，被系统停掉后自动补开；
     * 手动关过的、或从来没手动开过的，绝不自动补开。</p>
     */
    public static boolean manualOn(String which) {
        try {
            java.util.Properties p = manual();
            return "1".equals(p.getProperty(which));
        } catch (Throwable t) {
            return false;
        }
    }

    private static java.util.Properties manual() {
        java.util.Properties p = new java.util.Properties();
        try {
            java.io.File f = new java.io.File(WebSrv.app.getFilesDir(), F_MANUAL);
            if (f.exists()) {
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                p.load(in);
                in.close();
            }
        } catch (Throwable t) {
            Boot.log("svc manual 读账本: " + t);
        }
        return p;
    }

    public static synchronized void saveManual(String which, boolean on) {
        try {
            java.util.Properties p = manual();
            p.setProperty(which, on ? "1" : "0");
            java.io.FileOutputStream o =
                    new java.io.FileOutputStream(new java.io.File(
                            WebSrv.app.getFilesDir(), F_MANUAL));
            p.store(o, "");
            o.close();
        } catch (Throwable t) {
            Boot.log("svc manual 记账: " + t);
        }
    }

    private static final Object REOPEN_LOCK = new Object();
    private static boolean reopenPending = false;

    /**
     * ★ pl-1.6.2：本地接管（{@code PCtl.ensureBusy} → {@code exitWaitingExternalConnect}）
     * 会顺带把 DLNA / AirPlay 引擎停掉 ⇒ 不补开的话，用户想再从手机推流就得
     * 手动去开一遍。
     *
     * <p>规则见 {@link #manualOn(String)}：只补「用户手动开过、现在却被系统停了」的。
     * 实现：起一个延迟 2.5s 的补开任务（等 stopServer 的端口释放完），
     * 2.5s 窗口内的重复触发合并成一次；账本说关 / 没记录的一律跳过。
     * 异步线程执行，不卡页面响应。</p>
     *
     * @param reason 触发原因（进日志）
     */
    public static void autoReopenLater(final String reason) {
        synchronized (REOPEN_LOCK) {
            if (reopenPending) {
                return;
            }
            reopenPending = true;
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(2500);
                    String[] ws = {"dlna", "airplay", "bt"};
                    for (String w : ws) {
                        if (!manualOn(w)) {
                            continue;
                        }
                        if ("bt".equals(w)) {
                            // 蓝牙：adapter 没开就 enable、引擎被停了就 startServer
                            // 拉起（都在 Bt.reopenEngine 里）
                            Boot.log("autoReopen: bt 被系统停掉（" + reason
                                    + "），用户手动开过 ⇒ 补开");
                            Bt.reopenEngine();
                            continue;
                        }
                        boolean up = "dlna".equals(w) ? dlnaUp() : airUp();
                        if (up) {
                            continue;               // 已经有主了（比如推流中），不碰
                        }
                        Boot.log("autoReopen: " + w + " 被系统停掉（" + reason
                                + "），用户手动开过 ⇒ 自动补开");
                        Boot.log("autoReopen: " + openOnly(w));
                    }
                } catch (Throwable t) {
                    Boot.log("autoReopen: " + t);
                } finally {
                    synchronized (REOPEN_LOCK) {
                        reopenPending = false;
                    }
                }
            }
        }, "r1web-svc-reopen").start();
    }

    /**
     * 状态快照 —— 全部是「实际读到的事实」，没有一处是我们自己记的账：
     * <ul>
     *   <li>{@code dlna}/{@code airplay}：内核 socket 表里功能端口在不在</li>
     *   <li>{@code dlnaPlaying}/{@code airPlaying}：问 :DlnaPlayer/:AirPlayer
     *       进程里的真实播放引擎（AIDL {@code isPlaying()}）—— 手机推流中才为 true</li>
     *   <li>{@code dlnaProc}/{@code airProc}：服务进程在不在</li>
     * </ul>
     */
    public static JSONObject state() {
        JSONObject o = new JSONObject();
        try {
            o.put("dlna", dlnaUp());
            o.put("airplay", airUp());
            o.put("dlnaPlaying", playing("dlna"));
            o.put("airPlaying", playing("airplay"));
            o.put("dlnaProc", Sys.hasProc(":DlnaPlayer"));
            o.put("airProc", Sys.hasProc(":AirPlayer"));
        } catch (Throwable t) {
            // ignore
        }
        return o;
    }

    /**
     * 外部引擎是否在出声：走 AIDL 代理问 :DlnaPlayer / :AirPlayer 进程里的
     * {@code IPlayer.isPlaying()}（DLNA 实现在自己进程的 MusicPlayEngineImpl 上，
     * AirPlay 在 ShairportWrapper 上 —— 都不是本进程能直接看到的，只能 Binder 问）。
     * 代理没连上（功能从没开过）/ 进程死了都算 false。
     */
    public static boolean playing(String which) {
        try {
            RemotePlayer rp = rp("dlna".equals(which) ? F_DLNA : F_AIR);
            if (rp == null) {
                return false;
            }
            Object ip = PCtl.field(rp, "mIPlayer");
            if (ip == null) {
                return false;
            }
            Object r = ip.getClass().getMethod("isPlaying").invoke(ip);
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * DLNA renderer 是否真在跑：/proc/net/udp 有没有 UDP 1900(0x076C)。
     * DMR 的 SSDP 监听就是这个端口（renderer 走 UDP，没有 TCP LISTEN，
     * 别拿 netstat -ltn 当失败证据）。
     */
    public static boolean dlnaUp() {
        return portOpen("/proc/net/udp", P_DLNA, false);
    }

    /** AirPlay shairport 是否真在跑：/proc/net/tcp 有没有 TCP 5002(0x138A) LISTEN。 */
    public static boolean airUp() {
        return portOpen("/proc/net/tcp", P_AIR, true);
    }

    /**
     * 读内核 socket 表找端口。
     *
     * @param proto    "/proc/net/udp" 或 "/proc/net/tcp"
     * @param hexPort  端口（十六进制形式写，如 0x76C = 1900）
     * @param listen   true 时还要状态位是 0A（LISTEN），UDP 用 false
     */
    private static boolean portOpen(String proto, int hexPort, boolean listen) {
        java.io.BufferedReader br = null;
        try {
            br = new java.io.BufferedReader(
                    new java.io.FileReader(proto), 1024);
            String port = String.format("%04X", hexPort);
            String l;
            while ((l = br.readLine()) != null) {
                String[] t = l.trim().split("\\s+");
                if (t.length < 4) {
                    continue;
                }
                int c = t[1].indexOf(':');
                if (c < 0 || !t[1].substring(c + 1).equals(port)) {
                    continue;
                }
                if (!listen || "0A".equals(t[3])) {
                    return true;
                }
            }
        } catch (Throwable t) {
            Boot.log("svc port " + proto + ": " + t);
        } finally {
            try {
                if (br != null) {
                    br.close();
                }
            } catch (Throwable t) {
                // ignore
            }
        }
        return false;
    }

    /** 反射拿 EasyPlayer 里那两个 RemotePlayer 字段（编译期不能引用私有成员）。 */
    private static RemotePlayer rp(String field) {
        try {
            EasyPlayer ep = PCtl.ep();
            if (ep == null) {
                return null;
            }
            java.lang.reflect.Field f = EasyPlayer.class.getDeclaredField(field);
            f.setAccessible(true);
            return (RemotePlayer) f.get(ep);
        } catch (Throwable t) {
            Boot.log("svc field " + field + ": " + t);
            return null;
        }
    }

    private Svc() {
    }
}
