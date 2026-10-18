package cn.workbuddy.r1web;

import android.content.Context;
import android.util.Log;

import com.phicomm.speaker.player.model.PlayRequestInfo;

import java.io.BufferedReader;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * player 侧注入层的入口。
 *
 * <p>由 smali 插桩从两处调用（都在同一个 dex 里，所以是普通静态方法调用）：</p>
 * <pre>
 *   Common.onCreate()                  → attach(Context)      // Application 起来了
 *   EasyPlayer.play(PlayRequestInfo,Z) → onPlay(PlayRequestInfo)  // 小智推列表来了
 *   EasyPlayer.playIndex(I,Z)          → onIndex(int)             // 跳播/自动切歌
 * </pre>
 *
 * <p>★ Application.onCreate 比 EchoService.onCreate 早，此时 EasyPlayer 还没建，
 * 所以 attach 只是「登记」，真正 bind + 起 web 放到 3.5s 后的后台线程。</p>
 *
 * <p>★ player 是四进程（主 / :NetPlayer / :DlnaPlayer / :AirPlayer），
 * Application 在每个进程都会 onCreate，只有**主进程**才持有 EasyPlayer 单例
 * （且只有主进程该抢 8080），所以非主进程直接返回。</p>
 */
public final class Boot {

    /** pl-1.1.1：修「所有按钮都缺 op」（POST form body 没被解析）+ 音量档位/百分比量纲。
     *  pl-1.1.2：队列列表保住滚动位置（不再每 8s 重建 DOM 把人弹回顶部）。
     *  pl-1.1.3：氛围灯卡片瘦身——实测原厂 player 只认 what=4 的 64/65/66/67，
     *            68（效果模式）与 70（颜色）无接收者，页面上撤掉这两项，只留开关。 */
    public static final String VER = "pl-1.6.18";
    public static final String TAG = "R1WEB";

    public static Context app;
    public static String proc = "?";
    public static long bootTs = System.currentTimeMillis();

    private static final int LOG_MAX = 300;
    private static final List<String> LOGS = new ArrayList<String>();

    /** 由 Common.onCreate() 调用。全部 try 包住：注入层出任何问题都不能拖垮 player。 */
    public static void attach(Context c) {
        try {
            app = c.getApplicationContext();
            proc = procName();
            log("attach proc=" + proc + " ver=" + VER);
            if (proc.indexOf(':') >= 0) {
                log("skip web: 非主进程");
                return;
            }
            new Thread(new Runnable() {
                public void run() {
                    try {
                        Thread.sleep(3500);
                    } catch (Throwable t) {
                        // ignore
                    }
                    try {
                        PCtl.bind(app);
                    } catch (Throwable t) {
                        log("bind EasyPlayer 失败: " + t);
                    }
                    try {
                        WebSrv.start(app);
                    } catch (Throwable t) {
                        log("起 web 失败: " + t);
                    }
                    try {
                        // 蓝牙广播 + A2DP sink 代理（蓝牙开着才会拿到代理，
                        // 之后 STATE_ON 广播里还会再试一次）
                        Bt.init(app);
                    } catch (Throwable t) {
                        log("bt init 失败: " + t);
                    }
                    try {
                        // 手机 DLNA 推流的曲目广播（:DlnaPlayer 进程插桩发过来）
                        Ext.init(app);
                    } catch (Throwable t) {
                        log("ext init 失败: " + t);
                    }
                }
            }, "r1web-boot").start();
        } catch (Throwable t) {
            try {
                Log.e(TAG, "attach: " + t);
            } catch (Throwable t2) {
                // ignore
            }
        }
    }

    /** 由 EasyPlayer.play() 开头调用：把整张列表记下来（= 队列的真相）。 */
    public static void onPlay(PlayRequestInfo pri) {
        try {
            PCtl.noteQueue(pri);
        } catch (Throwable t) {
            log("onPlay: " + t);
        }
    }

    /** 由 EasyPlayer.playIndex() 开头调用：只换位置，不动列表。 */
    public static void onIndex(int i) {
        try {
            PCtl.noteIndex(i);
        } catch (Throwable t) {
            // ignore
        }
    }

    public static void log(String s) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss");
            String line = f.format(new Date()) + " " + s;
            synchronized (LOGS) {
                LOGS.add(line);
                while (LOGS.size() > LOG_MAX) {
                    LOGS.remove(0);
                }
            }
            Log.i(TAG, s);
        } catch (Throwable t) {
            // ignore
        }
    }

    /** 最近 n 条日志，按时间顺序（老的在前）。 */
    public static String logs(int n) {
        StringBuilder sb = new StringBuilder();
        synchronized (LOGS) {
            int from = LOGS.size() - n;
            if (from < 0) {
                from = 0;
            }
            for (int i = from; i < LOGS.size(); i++) {
                sb.append(LOGS.get(i)).append("\n");
            }
        }
        return sb.toString();
    }

    private static String procName() {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/self/cmdline"), 256);
            String s = r.readLine();
            if (s == null) {
                s = "";
            }
            int z = s.indexOf('\0');
            return z > 0 ? s.substring(0, z) : s;
        } catch (Throwable t) {
            return "?";
        } finally {
            try {
                if (r != null) {
                    r.close();
                }
            } catch (Throwable t) {
                // ignore
            }
        }
    }

    private Boot() {
    }
}
