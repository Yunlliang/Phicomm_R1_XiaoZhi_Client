package cn.workbuddy.r1web;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import org.json.JSONObject;

/**
 * 外部推送源（手机 DLNA / AirPlay 推流）的<b>真实</b>状态 —— 「正在播放」页同步用。
 *
 * <p>为什么需要：DLNA/AirPlay 的音频走 :DlnaPlayer / :AirPlayer 进程里各自的引擎
 * （MusicPlayEngineImpl / ShairportWrapper），跟主进程 EasyPlayer 的队列毫无关系。
 * 以前控制台只认本地队列 ⇒ 手机推流时页面显示「未播放」，用户以为没同步。</p>
 *
 * <p>两条数据来源，都是实际事实：</p>
 * <ul>
 *   <li><b>在不在播</b>：{@link Svc#playing(String)} 走 AIDL 代理问真引擎
 *       （IPlayer.isPlaying / ShairportWrapper.isPlaying）</li>
 *   <li><b>播的什么</b>：:DlnaPlayer 里插桩的 {@code DlnaPlayer2.onPlayCommand}
 *       经 {@link DlnaHook} 发广播 {@value DlnaHook#ACT} 带过来（AirPlay 无曲目通道，
 *       只播状态）</li>
 * </ul>
 */
public final class Ext {

    private static volatile String title = "";
    private static volatile String artist = "";
    private static volatile long pushTs = 0;
    private static boolean reged;

    /** 由 DlnaHook 的广播触发（主进程）。 */
    public static void onPush(String t, String a) {
        title = t == null ? "" : t;
        artist = a == null ? "" : a;
        pushTs = System.currentTimeMillis();
        Boot.log("ext: DLNA 推流「" + title + "」" + artist);
    }

    /** 注册广播接收器（Boot.attach 里调，主进程）。 */
    public static synchronized void init(Context c) {
        try {
            if (reged || c == null) {
                return;
            }
            IntentFilter f = new IntentFilter(DlnaHook.ACT);
            c.getApplicationContext().registerReceiver(new Recv(), f);
            reged = true;
            Boot.log("ext: DLNA 推流广播接收已注册");
        } catch (Throwable t) {
            Boot.log("ext init: " + t);
        }
    }

    static final class Recv extends BroadcastReceiver {
        public void onReceive(Context c, Intent i) {
            onPush(i.getStringExtra("t"), i.getStringExtra("ar"));
        }
    }

    /**
     * /api/status 的 {@code music.ext}。
     *
     * <p>只有引擎真在播（playing）才带曲目 —— 推流停了就不占「正在播放」的显示。
     * {@code src}：2=DLNA 3=AirPlay（与页面 tag 字典对齐）。</p>
     */
    public static JSONObject snap() {
        JSONObject o = new JSONObject();
        try {
            boolean d = Svc.playing("dlna");
            boolean a = Svc.playing("airplay");
            o.put("dlnaPlaying", d);
            o.put("airPlaying", a);
            if (d) {
                o.put("src", 2);
                o.put("playing", true);
                o.put("title", title);
                o.put("artist", artist);
                o.put("ageSec", (int) ((System.currentTimeMillis() - pushTs) / 1000));
            } else if (a) {
                o.put("src", 3);
                o.put("playing", true);
                o.put("title", "AirPlay 推流");
                o.put("artist", "");
            } else {
                o.put("playing", false);
            }
        } catch (Throwable t) {
            // ignore
        }
        return o;
    }

    private Ext() {
    }
}
