package cn.workbuddy.r1web;

import android.content.Context;
import android.media.AudioManager;
import android.os.IBinder;
import android.os.Parcel;

import com.phicomm.speaker.player.model.EasyPlayer;
import com.phicomm.speaker.player.model.PlayPositionInfo;
import com.phicomm.speaker.player.model.PlayRequestInfo;
import com.phicomm.speaker.player.model.PlaySong;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 播放器控制面：把原厂 EasyPlayer 包一层，顺带维护「队列真相」。
 *
 * <p>★ 队列不是我们自己猜的：小智（device）推播放列表走消息总线 →
 * 原厂 MsgHandler → EasyPlayer.play(PlayRequestInfo) → 我们在 play() 开头插桩
 * 拿到 {@code pri}，这里的 {@link #noteQueue} 就是那条插桩的下游。
 * 所以队列永远是 player 实际手里那张表，顺序也和 player 一致。</p>
 *
 * <p>★ 跳播用 {@code playIndex(i, true)}：只改播放位置，列表顺序一动不动
 * （原厂 case 1 的 setIndex(0) 只影响 play()，不影响 playIndex）。
 * 但它有状态机闸门（只在 STATUS_BUSY 下执行）⇒ 推流后非 BUSY 时
 * 由 {@link #ensureBusy} 先重推恢复，见那儿的长注释。</p>
 */
public final class PCtl {

    private static EasyPlayer ep;
    private static List<PlaySong> queue = new ArrayList<PlaySong>();
    private static int curIdx = -1;
    private static long queueTs = 0;

    public static synchronized void bind(Context c) {
        try {
            ep = EasyPlayer.getInstance(c);
            Boot.log("EasyPlayer 绑定 " + (ep != null ? "OK" : "null"));
        } catch (Throwable t) {
            Boot.log("bind: " + t);
        }
    }

    /** 惰性取实例：getInstance 是幂等单例，随时取都安全。 */
    static synchronized EasyPlayer ep() {
        try {
            if (ep == null && Boot.app != null) {
                ep = EasyPlayer.getInstance(Boot.app);
            }
        } catch (Throwable t) {
            // ignore
        }
        return ep;
    }

    /**
     * 反射取对象的私有字段（注入层编译期引用不到原厂的私有成员）。
     * 拿不到就返回 null，不抛 —— 调用方自己判断。
     */
    static Object field(Object o, String name) {
        try {
            if (o == null) {
                return null;
            }
            /* ★ 必须沿 superclass 往上找：getDeclaredField 只查**本类**，
               而 RemotePlayer 有可能是匿名子类 ⇒ 直接查会 NoSuchField */
            Class<?> c = o.getClass();
            while (c != null) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(o);
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean ready() {
        return ep() != null;
    }

    public static synchronized void noteQueue(PlayRequestInfo pri) {
        try {
            List<PlaySong> l = pri.getItemList();
            if (l == null || l.isEmpty()) {
                return;
            }
            queue = new ArrayList<PlaySong>(l);
            curIdx = pri.getIndex();
            queueTs = System.currentTimeMillis();
            Boot.log("队列更新 " + queue.size() + " 首, index=" + curIdx
                    + ", 首曲=" + (queue.get(0).getTitle()));
        } catch (Throwable t) {
            Boot.log("noteQueue: " + t);
        }
    }

    public static synchronized void noteIndex(int i) {
        curIdx = i;
    }

    /* ---------------- 控制 ---------------- */

    /**
     * 反射读原厂 {@code EasyPlayer.mPlayerStatus}（私有字段）：
     * <pre>
     *   0 = STATUS_IDLE
     *   1 = STATUS_WAITING_EXTERNAL_CONNECT   （DLNA/AirPlay/蓝牙推流接管中）
     *   2 = STATUS_BUSY                       （正常在播/可控制）
     * </pre>
     * 取不到返回 -1，调用方按「非 BUSY」处理（走重推恢复，最稳）。
     */
    static int playerStatus(EasyPlayer p) {
        try {
            java.lang.reflect.Field f = EasyPlayer.class.getDeclaredField("mPlayerStatus");
            f.setAccessible(true);
            return f.getInt(p);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 当前生效的 OnlinePlayer 是不是**远程源**（DLNA/AirPlay/蓝牙）。
     *
     * <p>原厂结构（{@code EasyPlayer.smali}）：</p>
     * <pre>
     *   mOnlinePlayer : interfaces/Player   ← 当前接管的播放器（可被换成远程的）
     *   mNetPlayer    : RemotePlayer        ← 本地网络播放器
     *   isExternalConnectPlayer(p) ≡ p != null &amp;&amp; p != mNetPlayer
     * </pre>
     *
     * <p>★ pl-1.6.1 实锤（logcat）：DLNA 推流**结束后** mPlayerStatus 仍然是
     * {@code STATUS_BUSY(2)}，但 {@code mOnlinePlayer} 一直挂着
     * {@code RemotePlayer{mRemoteServiceClass=DlnaPlayerService}} —— 此时
     * playIndex/start/next 全部**透传给已停掉的 DLNA 引擎**，静默 false。
     * 这就是「推流后点列表/播放键全死、必须重新载入列表」的真凶：
     * 光看状态机会误判为 BUSY 放行。</p>
     */
    static boolean onlineIsRemote(EasyPlayer p) {
        try {
            java.lang.reflect.Field fo = EasyPlayer.class.getDeclaredField("mOnlinePlayer");
            java.lang.reflect.Field fn = EasyPlayer.class.getDeclaredField("mNetPlayer");
            fo.setAccessible(true);
            fn.setAccessible(true);
            Object o = fo.get(p);
            Object n = fn.get(p);
            return o != null && o != n;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 需不需要走「重推恢复」：非 BUSY，或 BUSY 但 OnlinePlayer 挂着远程源。
     */
    private static boolean needsRecover(EasyPlayer p) {
        return playerStatus(p) != 2 || onlineIsRemote(p);
    }

    /**
     * ★★ 把 player 拉回 {@code STATUS_BUSY} —— 「点播放列表没反应」的根治。
     *
     * <p>原厂 {@code playIndex / start / pause / playNext / playPre} 是同一个
     * 状态机闸门（{@code packed-switch mPlayerStatus}）：只有 2=BUSY 才往下走，
     * 0=IDLE / 1=WAITING_EXTERNAL_CONNECT 一律<b>直接 return false，静默失败</b>。
     * logcat 实锤：{@code EasyPlayer playIndex 1 ... PlayerStatus: STATUS_IDLE}。</p>
     *
     * <p>DLNA/AirPlay 推流时 device 经总线发 {@code startWaitingExternalConnect}
     * ⇒ 主 player 进 1；推流结束停在 0。这之后点队列 / 按 ▶ 全部没反应 ——
     * 只有重新推一次列表（{@code EasyPlayer.play(PlayRequestInfo,true)}，
     * 这个方法任何状态都进得去：1→WaitingConnectToStopped（内部
     * shutdownAllExternalConnectPlayer 会把 DLNA/AirPlay/蓝牙引擎停掉）、
     * 0→StoppedToPlaying）才能恢复。这就是用户说的
     * 「要重新载入列表才能播」。</p>
     *
     * <p>这里在非 BUSY 时自动用当前队列重推一次（顺序一点不变，从 index 首开播），
     * 用户无需自己去 reload。</p>
     *
     * @param index 起始下标（0-based）
     * @return true = 本来就在 BUSY，或重推成功
     */
    private static boolean ensureBusy(EasyPlayer p, int index) {
        try {
            int st0 = playerStatus(p);
            boolean remote = onlineIsRemote(p);
            if (st0 == 2 && !remote) {
                return true;
            }
            int n;
            synchronized (PCtl.class) {
                if (queue.isEmpty()) {
                    return false;
                }
                n = queue.size();
            }
            if (remote) {
                // ★★ 远程源还在场（BUSY 但 mOnlinePlayer 挂着 DlnaPlayerService 等）：
                // 先调原厂 exitWaitingExternalConnect()V（private，内部
                // shutdownAllExternalConnectPlayer()）把推流引擎停掉，交接回本地，
                // 否则 play(info) 重建的命令又被透传给死掉的远程引擎。
                try {
                    java.lang.reflect.Method m = EasyPlayer.class.getDeclaredMethod(
                            "exitWaitingExternalConnect");
                    m.setAccessible(true);
                    m.invoke(p);
                    Boot.log("ensureBusy: 远程源在场，已 exitWaitingExternalConnect 交接回本地");
                } catch (Throwable t) {
                    Boot.log("ensureBusy: exitWaitingExternalConnect 失败（继续重推）：" + t);
                }
                // ★ pl-1.6.2：上面那一下会把 DLNA/AirPlay 引擎一起停掉 ⇒ 按用户
                // 手动意图延迟补开（手动开过的才补；手动关/没开过的不动）
                Svc.autoReopenLater("ensureBusy 本地接管");
            }
            int ix = index < 0 ? 0 : (index >= n ? n - 1 : index);
            PlayRequestInfo info = new PlayRequestInfo();
            info.setItemList(new ArrayList<PlaySong>(queue));
            info.setIndex(ix);
            PlaySong cur = queue.get(ix);
            if (cur != null && cur.getItemId() != null) {
                info.setCurItemId(cur.getItemId());
            }
            boolean r = p.play(info, true);
            Boot.log("ensureBusy: 触发时 状态=" + st0 + " 远程源=" + remote
                    + "，重推 " + n + " 首 index=" + ix + " => " + r
                    + "（now 状态=" + playerStatus(p) + "）"
                    + (remote ? "，推流引擎已停、本地接管" : ""));
            return r;
        } catch (Throwable t) {
            Boot.log("ensureBusy: " + t);
            return false;
        }
    }

    public static String play() {
        EasyPlayer p = ep();
        if (p == null) {
            return "ERR 播放器还没起来";
        }
        if (needsRecover(p)) {
            // ★ start() 在非 BUSY / 远程源在场时是空转（透传给死引擎）⇒ 重推恢复
            return ok(ensureBusy(p, curIdx), "继续播放");
        }
        return ok(p.start(), "继续播放");
    }

    public static String pause() {
        EasyPlayer p = ep();
        if (p == null) {
            return "ERR 播放器还没起来";
        }
        return ok(p.pause(), "暂停");
    }

    public static String stop() {
        return ok(ep() != null && ep().stop(), "停止");
    }

    public static String next() {
        EasyPlayer p = ep();
        if (p == null) {
            return "ERR 播放器还没起来";
        }
        if (needsRecover(p)) {
            return ok(ensureBusy(p, curIdx < 0 ? 0 : curIdx + 1), "下一曲");
        }
        return ok(p.playNext(true), "下一曲");
    }

    public static String prev() {
        EasyPlayer p = ep();
        if (p == null) {
            return "ERR 播放器还没起来";
        }
        if (needsRecover(p)) {
            return ok(ensureBusy(p, curIdx < 0 ? 0 : curIdx - 1), "上一曲");
        }
        return ok(p.playPre(true), "上一曲");
    }

    /** 跳到队列第 i 首（0-based）。列表顺序完全不变。 */
    public static String playAt(int i) {
        EasyPlayer p = ep();
        if (p == null) {
            return "ERR 播放器还没起来";
        }
        synchronized (PCtl.class) {
            if (queue.isEmpty()) {
                return "ERR 队列是空的（先让小智放一首，或用小智推一次列表）";
            }
            if (i < 0 || i >= queue.size()) {
                return "ERR 序号超出范围，共 " + queue.size() + " 首";
            }
        }
        if (needsRecover(p)) {
            // ★★ 非 BUSY 或 OnlinePlayer 挂着远程源时，playIndex 会被状态机吞掉 /
            //    被透传给死掉的推流引擎（静默 false）⇒ 走重推恢复：
            //    play(info,true) 任何状态都能进，从第 i 首直接开播。
            String who0 = titleAt(i);
            boolean r0 = ensureBusy(p, i);
            Boot.log("playAt #" + (i + 1) + " " + who0 + "（重推恢复）=> " + r0);
            return ok(r0, "播放第 " + (i + 1) + " 首：" + who0);
        }
        boolean r = p.playIndex(i, true);
        String who = titleAt(i);
        Boot.log("playAt #" + (i + 1) + " " + who + " => " + r);
        return ok(r, "播放第 " + (i + 1) + " 首：" + who);
    }

    /**
     * 设播放模式。
     *
     * <p>★ pl-1.2.0：以前页面发的是 0/1，而原厂 {@code AbsPlayer.setPlayMode()} 开头
     * 就是 {@code if (mode != 1 && != 2 && != 3) → Log "setPlayMode error, mode = N";
     * return false} —— **0 压根不收**，所以「顺序播放」点了多少次都没用。</p>
     *
     * <p>真值（原厂 {@code util/Constant}）：</p>
     * <pre>
     *   1 = PLAY_MODE_LIST_SHUFFLE  列表随机
     *   2 = PLAY_MODE_LIST_CYCLE    列表循环（=「顺序播放」，也是 PLAY_MODE_DEFAULT）
     *   3 = PLAY_MODE_SINGLE_CYCLE  单曲循环
     * </pre>
     */
    public static String setMode(int m) {
        if (m != 1 && m != 2 && m != 3) {
            return "ERR 播放模式只能是 1=随机 / 2=顺序 / 3=单曲循环（收到 " + m + "）";
        }
        String name = (m == 1) ? "随机" : ((m == 2) ? "顺序播放" : "单曲循环");
        return ok(ep() != null && ep().setPlayMode(m), "播放模式 " + m + "（" + name + "）");
    }

    /* ---------------- 状态 ---------------- */

    public static boolean isPlaying() {
        try {
            EasyPlayer p = ep();
            return p != null && p.isPlaying();
        } catch (Throwable t) {
            return false;
        }
    }

    /* ------------------------------------------------------------------
     * ★★ 假播检测（pl-1.6.9，2026-10-17）
     *
     * 背景（用户实测反馈）：在线歌取链失败时，播放器照样停在 STATUS_BUSY，
     * `isPlaying()` 恒为 true ⇒ 页面一直显示「播放中」，卡片还会继续走时间 ——
     * 但音箱一声不出，且它自己不会跳下一首，就这么干挂着。
     *
     * 判据（两条，任一成立即判「卡住」）：
     *   ① 起播后总时长一直是 0 超过 STALL_NODUR(12s) —— 真在播的流（本地文件、
     *      能取到链的在线流）几秒内就会给出 total_time；拿不到 = 流都没打开。
     *      ★ 这条同时覆盖「进度在涨但没声音」的形态（那种也拿不到总时长）。
     *   ② 进度超过 STALL_FROZEN(15s) 一动不动 —— 缓冲卡死。
     *
     * 采样是懒的：每次 /api/status 顺手采一次（页面 8s 轮询、脚本按需打）。
     * 不给它单独起线程 —— 注入层每多一个常驻线程就多一分拖垮原厂 player 的风险。
     * ------------------------------------------------------------------ */
    private static final long STALL_NODUR = 12000;
    private static final long STALL_FROZEN = 15000;
    private static long sPos = -1;
    private static long sSameSince = 0;
    private static long sNoDurSince = 0;
    private static String sSongId = null;

    /** 每次吐 /api/status 时调一次：更新「卡住」判据的时间戳。 */
    public static synchronized void samplePos() {
        try {
            long now = System.currentTimeMillis();
            if (!isPlaying()) {
                sPos = -1; sSameSince = 0; sNoDurSince = 0; sSongId = null;
                return;
            }
            PlayPositionInfo p = pos();
            long ps = (p == null) ? 0 : p.getPlay_time();
            long du = (p == null) ? 0 : p.getTotal_time();

            /* ★ 换曲要重新计时：切歌那一刻 playing 一直是 true、新歌的 total_time
               还没出来，不重置的话新歌会继承上一首的「卡住」时长 ⇒ 一上来就误报
               （实测本地歌切过来头 6 秒被判 stalled）。 */
            PlaySong sg = song();
            String id = (sg == null) ? "" : String.valueOf(sg.getItemId());
            if (sSongId == null || !sSongId.equals(id)) {
                sSongId = id;
                sPos = -1; sSameSince = 0;
                sNoDurSince = (du > 0) ? 0 : now;
                return;
            }

            if (du > 0) sNoDurSince = 0;
            else if (sNoDurSince == 0) sNoDurSince = now;
            if (ps != sPos) { sPos = ps; sSameSince = 0; }
            else if (sSameSince == 0) sSameSince = now;
        } catch (Throwable t) {
            // 采样失败不影响主流程
        }
    }

    /** 当前是不是「显示播放中但其实没出声」。 */
    public static boolean stalled() {
        return stallMs() > 0;
    }

    /**
     * ★ pl-1.6.9（二）：卡片该显示什么态 —— 把「缓冲中」和「没出声」分开，
     * 别一上来就把十几秒的起播缓冲说成失败。
     * <pre>
     *   idle    —— 没在播
     *   loading —— 已起播但还没出声（在线歌取链 / 缓冲的那十几秒属这类）
     *   playing —— 真在出声（拿到总时长或进度在走）
     *   stalled —— 起播超过 STALL_NODUR 仍没出声，或进度冻住超过 STALL_FROZEN
     * </pre>
     * 注意：本方法依赖 {@link #samplePos()} 采到的时间戳，调用前必须先采一次。
     */
    public static synchronized String phase() {
        if (!isPlaying()) return "idle";
        PlayPositionInfo p = pos();
        long ps = (p == null) ? 0 : p.getPlay_time();
        long du = (p == null) ? 0 : p.getTotal_time();
        long now = System.currentTimeMillis();
        if (du > 0 || ps > 0) {
            return (sSameSince > 0 && now - sSameSince >= STALL_FROZEN) ? "stalled" : "playing";
        }
        return (sNoDurSince > 0 && now - sNoDurSince >= STALL_NODUR) ? "stalled" : "loading";
    }

    /** 已卡住多少毫秒（0 = 没卡）。 */
    public static long stallMs() {
        if (!isPlaying()) return 0;
        long now = System.currentTimeMillis();
        long a = (sNoDurSince > 0 && now - sNoDurSince >= STALL_NODUR) ? (now - sNoDurSince) : 0;
        long b = (sSameSince > 0 && now - sSameSince >= STALL_FROZEN) ? (now - sSameSince) : 0;
        return Math.max(a, b);
    }

    public static PlaySong song() {
        try {
            EasyPlayer p = ep();
            return p == null ? null : p.getCurrentSong();
        } catch (Throwable t) {
            return null;
        }
    }

    public static PlayPositionInfo pos() {
        try {
            EasyPlayer p = ep();
            return p == null ? null : p.getPlayPosition();
        } catch (Throwable t) {
            return null;
        }
    }

    /* ★ pl-1.6.12：拖动进度条 --------------------------------------------------
       AIDL 接口名（seek 事务里 enforceInterface 校验要用，必须和原厂一致）。 */
    private static final String IPLAYER = "com.phicomm.speaker.player.IPlayer";
    /* ★ seek 的新事务码：原厂 0x1~0x14 已被 20 个方法占满 ⇒ 取 0x15。
         接线见 patch_seek.py：Stub 分派 → PlayerBinderWrapper$2 → AbsPlayer
         → NetPlayer4 → MultiMediaPlayer.seek → MediaPlayer.seekTo。 */
    private static final int TX_SEEK = 0x15;

    /**
     * 摸到 :NetPlayer 进程里那个 AIDL 的 Binder。
     * <pre>
     *   EasyPlayer.mOnlinePlayer  (RemotePlayer，当前接管的播放器)
     *     └─ RemotePlayer.mIPlayer (IPlayer$Stub$Proxy)
     *          └─ Proxy.mRemote    (android.os.IBinder)
     * </pre>
     * 拿不到就返回 null（播放器还没起来 / 服务还没绑上 / 字段被改名）。
     */
    static IBinder playerBinder() {
        EasyPlayer p = ep();
        if (p == null) {
            return null;
        }
        Object proxy = field(field(p, "mOnlinePlayer"), "mIPlayer");
        if (proxy == null) {
            return null;
        }
        Object r = field(proxy, "mRemote");
        if (r instanceof IBinder) {
            return (IBinder) r;
        }
        return (proxy instanceof IBinder) ? (IBinder) proxy : null;
    }

    /**
     * 当前这首拖得动吗 = 本地播放态（状态机 BUSY 且没挂远程源）+ 拿得到 Binder。
     * 页面据此决定要不要显示拖动把手。
     */
    public static boolean seekReady() {
        EasyPlayer p = ep();
        if (p == null || needsRecover(p)) {
            return false;
        }
        return playerBinder() != null;
    }

    /**
     * 拖动进度条：跳到指定毫秒。
     *
     * <p>走的是 smali 上新增的 AIDL 事务 0x15（原厂没有 seek）——
     * 客户端这一半自己拼 Parcel + transact，省得再改 IPlayer 接口和 Proxy
     * （Proxy 实现了 IPlayer，少一个方法就 VerifyError）。</p>
     */
    public static String seekTo(int ms) {
        if (ms < 0) {
            ms = 0;
        }
        EasyPlayer p = ep();
        if (p == null) {
            return "ERR 播放器还没起来";
        }
        int st = playerStatus(p);
        if (st != 2 || onlineIsRemote(p)) {
            /* 非 BUSY / 挂着远程源：底层根本没有可读可拖的 MediaPlayer。
               先试一次重推，成了再 seek —— 推流结束后那一下卡死就是这么解的。 */
            if (ensureBusy(p, curIdx)) {
                st = playerStatus(p);
            }
            if (st != 2 || onlineIsRemote(p)) {
                return "ERR 现在不是本地播放态（状态=" + st
                        + " 远程源=" + onlineIsRemote(p) + "），拖不动进度";
            }
        }
        try {
            IBinder b = playerBinder();
            if (b == null) {
                /* ★ 播放服务还没绑上（刚重启 / 进程被回收后常见）。
                   RemotePlayer.connectRemoteService() 是 public，内部
                   bindService + CountDownLatch 等 2s ⇒ 主动补一次再取 */
                Object online = field(p, "mOnlinePlayer");
                if (online != null) {
                    try {
                        java.lang.reflect.Method cm =
                                online.getClass().getMethod("connectRemoteService");
                        cm.invoke(online);
                    } catch (Throwable t2) {
                        Boot.log("seekTo: connectRemoteService " + t2);
                    }
                    b = playerBinder();
                }
            }
            if (b == null) {
                return "ERR 播放服务还没连上（状态=" + st + "）";
            }
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            int pos;
            try {
                data.writeInterfaceToken(IPLAYER);
                data.writeInt(ms);
                b.transact(TX_SEEK, data, reply, 0);
                reply.readException();
                pos = reply.readInt();
            } finally {
                reply.recycle();
                data.recycle();
            }
            if (pos < 0) {
                /* ★ pl-1.6.13：这行以前写「这个音源不支持拖进度」，误导排查 ——
                   走到这里的真因几乎都是「状态还是 2，但 MultiMediaPlayer 已经被
                   stop/释放了」（典型：stop 之后直接拖），跟音源支持不支持没关系。 */
                return "ERR 现在没在播，拖不动（状态=" + st + "）";
            }
            return "OK 跳到 " + fmtSec(pos);
        } catch (Throwable t) {
            Boot.log("seekTo: " + t);
            return "ERR " + t;
        }
    }

    /**
     * seek 通路自检串 —— 「拖了没反应」时一眼看出卡在哪一环：
     * ep / status / remote / binder 四段逐段报。
     */
    public static String diag() {
        try {
            EasyPlayer p = ep();
            if (p == null) {
                return "ep=null";
            }
            Object online = field(p, "mOnlinePlayer");
            Object proxy = field(online, "mIPlayer");
            Object rm = field(proxy, "mRemote");
            return "status=" + playerStatus(p)
                    + " online=" + (online == null ? "null" : online.getClass().getSimpleName())
                    + " iplayer=" + (proxy == null ? "null" : proxy.getClass().getSimpleName())
                    + " mRemote=" + (rm == null ? "null"
                            : (rm.getClass().getSimpleName()
                               + (rm instanceof IBinder ? "(IBinder)" : "(!IBinder)")));
        } catch (Throwable t) {
            return "diag ERR " + t;
        }
    }

    /** 毫秒 → "3:38" */
    private static String fmtSec(long ms) {
        long s = Math.max(0, ms / 1000);
        long ss = s % 60;
        return (s / 60) + ":" + (ss < 10 ? "0" : "") + ss;
    }

    public static int mode() {
        try {
            EasyPlayer p = ep();
            return p == null ? -1 : p.getPlayMode();
        } catch (Throwable t) {
            return -1;
        }
    }

    public static int sort() {
        try {
            EasyPlayer p = ep();
            return p == null ? -1 : p.getPlayerSort();
        } catch (Throwable t) {
            return -1;
        }
    }

    public static boolean netOnline() {
        try {
            EasyPlayer p = ep();
            return p != null && p.isNetPlayerOnline();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean btOnline() {
        try {
            EasyPlayer p = ep();
            return p != null && p.isBluetoothPlayerOnline();
        } catch (Throwable t) {
            return false;
        }
    }

    /* ---------------- 音量：系统 STREAM_MUSIC ----------------
       原厂 player 走系统音乐流（实测 /api/set?vol 改的是 STREAM_MUSIC 的
       80000(spdif) 那一路，对它有效），所以这里直接调 AudioManager。 */

    public static int volMax() {
        try {
            AudioManager am = am();
            return am == null ? 15 : am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        } catch (Throwable t) {
            return 15;
        }
    }

    public static int vol() {
        try {
            AudioManager am = am();
            return am == null ? -1 : am.getStreamVolume(AudioManager.STREAM_MUSIC);
        } catch (Throwable t) {
            return -1;
        }
    }

    public static String setVol(int v) {
        try {
            AudioManager am = am();
            if (am == null) {
                return "ERR 拿不到 AudioManager";
            }
            int mx = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (v < 0) {
                v = 0;
            }
            if (v > mx) {
                v = mx;
            }
            am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
            Boot.log("音量 -> " + v + "/" + mx);
            return "OK 音量 " + v + "/" + mx;
        } catch (Throwable t) {
            return "ERR 设音量失败：" + t;
        }
    }

    private static AudioManager am() {
        if (Boot.app == null) {
            return null;
        }
        return (AudioManager) Boot.app.getSystemService(Context.AUDIO_SERVICE);
    }

    /* ---------------- 队列 ---------------- */

    /**
     * ★ pl-1.1.5：用「player 当前实际在放的那一首」校正 curIdx。
     * 原厂 player 的自动连播、以及我们自己的 next()/prev()（走 playNext/playPre）
     * **都不会**经过 playIndex(i,true) ⇒ 钩子 noteIndex 不会触发，
     * curIdx 会一直停在推表那一刻的值（random 推的是 index=0 ⇒ 永远是 0），
     * 页面上「正在播放」的高亮就再也不动了。
     * 这里在每次出队列 / 出状态时按 itemId 反查一次（退化用 歌名+歌手），最准。
     */
    public static synchronized void syncCur() {
        try {
            if (queue.isEmpty()) {
                return;
            }
            PlaySong s = song();
            if (s == null) {
                return;
            }
            String id = nz(s.getItemId());
            String t = nz(s.getTitle());
            String a = nz(s.getArtist());
            if (id.length() == 0 && t.length() == 0) {
                return;
            }
            int hit = -1;
            if (id.length() > 0) {
                for (int i = 0; i < queue.size(); i++) {
                    PlaySong q = queue.get(i);
                    if (q != null && id.equals(nz(q.getItemId()))) { hit = i; break; }
                }
            }
            if (hit < 0 && t.length() > 0) {
                for (int i = 0; i < queue.size(); i++) {
                    PlaySong q = queue.get(i);
                    if (q == null) { continue; }
                    if (t.equals(nz(q.getTitle()))
                            && (a.length() == 0 || a.equals(nz(q.getArtist())))) { hit = i; break; }
                }
            }
            if (hit >= 0 && hit != curIdx) {
                curIdx = hit;
                Boot.log("syncCur: 当前曲 -> #" + (hit + 1) + " " + t);
            }
        } catch (Throwable t2) {
            Boot.log("syncCur: " + t2);
        }
    }

    public static synchronized JSONObject queueJson(int limit) {
        JSONObject o = new JSONObject();
        try {
            syncCur();
            JSONArray arr = new JSONArray();
            int n = queue.size();
            int lim = limit <= 0 ? n : Math.min(n, limit);
            // ★ pl-1.1.6：当前曲落在窗口外 ⇒ 把窗口挪过去（不然随机模式下
            //   当前曲常常是第 800 首，页面只拿到前 300 首，高亮/定位都找不到它）。
            int start = 0;
            if (limit > 0 && n > limit && curIdx >= limit) {
                start = curIdx - limit / 2;
                if (start < 0) {
                    start = 0;
                }
                if (start > n - limit) {
                    start = n - limit;
                }
            }
            for (int i = start; i < start + lim; i++) {
                PlaySong s = queue.get(i);
                JSONObject it = new JSONObject();
                it.put("n", i + 1);              // 真实曲库序号（1-based）
                it.put("title", s == null ? "" : nz(s.getTitle()));
                it.put("artist", s == null ? "" : nz(s.getArtist()));
                it.put("itemId", s == null ? "" : nz(s.getItemId()));
                arr.put(it);
            }
            o.put("total", n);
            o.put("start", start);               // 窗口偏移（页面用它算高亮位置）
            o.put("cur", curIdx);
            o.put("ts", queueTs);
            o.put("list", arr);
        } catch (Throwable t) {
            Boot.log("queueJson: " + t);
        }
        return o;
    }

    /** 队列摘要（给 /api/status 用）：不带列表，避免上千首每轮都传。 */
    public static synchronized JSONObject queueBrief() {
        JSONObject o = new JSONObject();
        try {
            syncCur();
            o.put("total", queue.size());
            o.put("cur", curIdx);
            o.put("ts", queueTs);
        } catch (Throwable t) {
            // ignore
        }
        return o;
    }

    public static synchronized String titleAt(int i) {
        try {
            if (i >= 0 && i < queue.size() && queue.get(i) != null) {
                return nz(queue.get(i).getTitle());
            }
        } catch (Throwable t) {
            // ignore
        }
        return "?";
    }

    private static String ok(boolean r, String what) {
        Boot.log(what + " => " + r);
        return (r ? "OK " : "ERR ") + what;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private PCtl() {
    }
}
