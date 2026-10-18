package cn.workbuddy.r1client;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;

/**
 * v1.3.98：激活码显示（移植自普通安卓版 MainActivity 的 v1.3.36 激活码界面）。
 *
 * 背景：寄生小智跑在借壳后的 com.phicomm.speaker.device 里，**没有自己的 Activity**
 *      （那个 .ui.MainActivity 是原厂 smali 的，我们在 janus dex 里改不了它的布局，
 *      而且 resources.arsc 是签名条目，加不了 layout 资源）。
 *      ⇒ 安卓版那套「开一个 Activity 显示大字」在这里用不了，
 *      改成 **悬浮窗**：任何界面之上都可见，正好 HDMI / 投屏能看到。
 *
 * 判据与蓝本一致（这点很重要）：
 *      SP["r1client"]["activation_code"] 有值 = 未绑定；没有被移除掉 = 已绑定。
 *      **test-token 只是连接凭证，不代表未激活**（它照样能连 ws、能对话）。
 *
 * UI 全部纯代码构建（五依赖 res，颜色/字号照搬安卓版）：
 *      需要激活 - 打开 https://xiaozhi.me 添加设备
 *      ══ 795123 ══            ← 72sp 粗体黄字
 *      设备 MAC / client-id
 *      把这个码填进网页 → 自动消失，并显示 8s 绿色「已激活」
 *
 * 调试：`files/dbg_code` 里写任意字符串（比如 123456），进程起来后就一直显示它，
 *       删掉文件即隐藏。用来在没有真激活需求的时候验证 UI 效果（不用真的重绑）。
 */
public final class ActivationHud {

    private static final String TAG = "R1CLIENT";
    public static final String KEY_ACTIVATION = "activation_code";

    private static Context app;
    private static SharedPreferences sp;
    private static WindowManager wm;
    private static View root;
    private static TextView statusView;
    private static TextView codeView;
    private static TextView hintView;
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static boolean attached = false;
    private static SharedPreferences.OnSharedPreferenceChangeListener listener;

    private ActivationHud() {
    }

    /** 在 XzBootstrap 里调一次（建议在 OTA 之前，保证 OTA 回调一到就能挂窗） */
    public static void init(final Context c) {
        app = c.getApplicationContext();
        sp = app.getSharedPreferences(OtaClient.PREF, Context.MODE_PRIVATE);
        ui.post(new Runnable() {
            public void run() {
                apply();
            }
        });
        listener = new SharedPreferences.OnSharedPreferenceChangeListener() {
            public void onSharedPreferenceChanged(SharedPreferences s, String key) {
                if (KEY_ACTIVATION.equals(key)) {
                    ui.post(new Runnable() {
                        public void run() {
                            apply();
                        }
                    });
                }
            }
        };
        sp.registerOnSharedPreferenceChangeListener(listener);
    }

    public static void refresh() {
        ui.post(new Runnable() {
            public void run() {
                apply();
            }
        });
    }

    /** 在 UI 线程执行：按当前 SP（或 dbg_code）决定显示什么 */
    private static void apply() {
        try {
            String override = readFlag("dbg_code");
            String code = sp.getString(KEY_ACTIVATION, null);
            String dev = sp.getString("device_id", "?");
            String cid = sp.getString("client_id", "?");
            String show = (override != null && override.length() > 0) ? override : code;
            String tail = dev + " / " + cid;

            if (show == null || show.trim().length() == 0) {
                showBound(tail);
                return;
            }
            ensureView();
            if (!attached) {
                wm.addView(root, params());
                attached = true;
            }
            statusView.setText("需要激活 - 打开 https://xiaozhi.me 添加设备");
            statusView.setTextColor(0xFF88C0D0);
            codeView.setText(show.trim());
            codeView.setTextColor(0xFFFFD866);
            hintView.setText(tail);
        } catch (Throwable t) {
            Log.w(TAG, "activation hud apply: " + t);
        }
    }

    /** 已绑定：绿色显示 8 秒自动收起，避免长期占着屏幕 */
    private static void showBound(String tail) {
        try {
            if (!attached) {
                return;   // 从来没显示过激活码，就不用冒出来了
            }
            ensureView();
            statusView.setText("已激活 - 小智运行中");
            statusView.setTextColor(0xFF66DD88);
            codeView.setText("OK");
            codeView.setTextColor(0xFF66DD88);
            hintView.setText("喊“小智小智”开始对话  " + tail);
            ui.removeCallbacks(hideRun);
            ui.postDelayed(hideRun, 8000);
        } catch (Throwable t) {
            Log.w(TAG, "activation hud bound: " + t);
        }
    }

    private static final Runnable hideRun = new Runnable() {
        public void run() {
            hide();
        }
    };

    private static void hide() {
        try {
            if (attached && wm != null && root != null) {
                wm.removeView(root);
                attached = false;
            }
        } catch (Throwable t) {
            Log.w(TAG, "activation hud hide: " + t);
        }
    }

    private static WindowManager.LayoutParams params() {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                2005,                                   // TYPE_TOAST：免 SYSTEM_ALERT_WINDOW 权限
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                android.graphics.PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.LEFT;
        p.x = 0;
        p.y = 0;
        return p;
    }

    private static void ensureView() {
        if (root != null) {
            return;
        }
        wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);

        LinearLayout box = new LinearLayout(app);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xEE101820);
        int pad = dp(16);
        box.setPadding(pad, dp(12), pad, dp(12));

        statusView = new TextView(app);
        statusView.setTextSize(16);
        statusView.setGravity(Gravity.CENTER);
        box.addView(statusView, new LinearLayout.LayoutParams(-1, -2));

        codeView = new TextView(app);
        codeView.setTextSize(72);
        codeView.setTypeface(Typeface.DEFAULT_BOLD);
        codeView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.weight = 1f;
        cp.gravity = Gravity.CENTER;
        box.addView(codeView, cp);

        hintView = new TextView(app);
        hintView.setTextSize(13);
        hintView.setTextColor(0xFF778899);
        hintView.setGravity(Gravity.CENTER);
        box.addView(hintView, new LinearLayout.LayoutParams(-1, -2));

        root = box;
    }

    private static int dp(int v) {
        return (int) (v * app.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String readFlag(String name) {
        try {
            File f = new File(app.getFilesDir(), name);
            if (!f.exists()) {
                return null;
            }
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = in.read(b);
            in.close();
            return new String(b, 0, n > 0 ? n : 0, "UTF-8").trim();
        } catch (Throwable t) {
            return null;
        }
    }
}
