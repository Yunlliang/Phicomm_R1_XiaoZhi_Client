package cn.workbuddy.r1client;

import android.content.Context;
import android.os.Parcelable;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;

/**
 * v1.3.147：指示灯联动 —— 把原厂「小讯」那套灯效接回小智。
 *
 * <h3>原厂灯效链路（反编译 uni_smali 得出）</h3>
 * <pre>
 * com/unisound/vui/handler/session/light/DefaultLightsHandler     （事件源）
 *   onWakeupResult()          -> 读 DOA 角度 -> LightListener.onWakeupSuccess(angle)
 *   onASREventRecordingStop() -> LightListener.onRecognizeStart()
 *   onTTSEventPlayingEnd()    -> LightListener.onTTSEnd()
 *
 * com/phicomm/speaker/device/custom/lights/PhicommLightListener   （灯效实现）
 *   onWakeupSuccess(angle) -> getIndex(angle) -> turnOnWakeupIndexLight(index)
 *                             ★ 这就是「流动一圈，最后定格在收音方向」的拾音灯
 *   onRecognizeStart()      -> turnOffALLWakeupLight() + turnOnLoadingLight()
 *                             ★ 蓝色播报/思考灯（识别中 + 原厂 TTS 播报期间一直闪）
 *   onTTSEnd()              -> turnOffLoadingLight()
 * </pre>
 *
 * <h3>IPC 出口与真实报文</h3>
 * {@code PhicommLightController} 最终都走
 * {@code MessageDispatchManager.sendMessage(what, arg1, arg2, obj)}：
 * <pre>
 *   what = 0x1000（LIGHT_WHAT）
 *   拾音灯 开：arg1=index(1..24)  arg2=0    关：arg1=index  arg2=1
 *              （turnOffALLWakeupLight = 对 1..24 逐条发 arg2=1）
 *   播报灯 开：arg1=0xcb(203)     arg2=0    关：arg1=203    arg2=1
 *   休眠灯 开：arg1=0x64(100)     arg2=0    关：arg1=100    arg2=1
 * </pre>
 * ★ 原厂 {@code turnOffLoadingLight()} 里 arg2 写成了 1（与 turnOn 同参）—— 那是
 * 原厂笔误：arg2 的语义是「0=亮 / 1=灭」（见 turnOffALLWakeupLight 的循环）。
 * 这里按正确语义发（开=0 / 灭=1）。
 *
 * <h3>为什么不直接 new 原厂的 PhicommLightController</h3>
 * 实测（v1.3.147 首版）：{@code new PhicommLightController(ctx)} 抛
 * {@code InvocationTargetException} —— 它内部 {@code PhicommIpcSender} →
 * {@code MessageSenderDelegate.getInstance()} → 私有构造里用的是静态 {@code sContext}，
 * 而 {@code MessageSenderDelegate.init(ctx)} 从未被调用 ⇒ NPE，且类一旦初始化失败
 * 就永久不可用。PlayerBridge 早就验证过 {@code PhicommMessageManager.messageCenter(ctx)}
 * 这条路是通的（音乐控制就是这么发的），所以灯也走这条总线直发，语义完全等价。
 *
 * <h3>小智侧的映射</h3>
 * <ul>
 *   <li><b>唤醒</b>（{@code UscTap.onWakeup(ctx)}，与 KWS 同刻）⇒ 拾音灯
 *       （读 DOA 角度 → 定格在收音方向）</li>
 *   <li><b>服务器出 STT</b>（听懂了 ⇒ 用户在等回答）⇒ 灭拾音灯 + 开蓝色播报灯</li>
 *   <li><b>TTS 播完</b> ⇒ 灭播报灯；<b>会话结束</b> ⇒ 全灭</li>
 * </ul>
 *
 * <p>开关：{@code files/lights_off}（存在即关闭，默认开）。调试：Web
 * {@code /api/act?op=light&v=wake|busy|off|idx:N|angle:N|raw:A1:A2}。</p>
 */
public final class R1Lights {

    private static final String TAG = "R1Lights";
    private static final String CLS_MSG_MGR =
            "com.phicomm.speaker.device.utils.PhicommMessageManager";
    private static final String CLS_IDX =
            "com.phicomm.speaker.device.custom.lights.PhicommLightIndexProcessor";
    private static final String CLS_OPT = "com.unisound.vui.engine.ANTEngineOption";

    /** 灯的 what（PhicommLightController.LIGHT_WHAT） */
    private static final int LIGHT_WHAT = 0x1000;
    /** 24 颗白色拾音灯（PhicommLightIndexProcessor.WHITE_LIGHT_NUMBER） */
    private static final int LIGHT_COUNT = 24;
    /** 蓝色播报/loading 灯的 id（PhicommLightController.LIGHT_ID_LOADING） */
    private static final int LIGHT_LOADING = 0xcb;
    /** arg2：0=亮，1=灭 */
    private static final int ON = 0;
    private static final int OFF = 1;

    private static Context app;
    private static Object msgMgr;
    private static Method mSend;
    private static Method mGetIndex;
    private static volatile boolean ready = false;
    private static volatile String why = "not init";

    /** 上一次成功点亮的灯位 / 角度（DOA 读不到时复用） */
    private static volatile int lastIndex = -1;
    private static volatile int lastAngle = -1;
    /** 最后一次 sendRaw 的失败原因（调试用，成功置 null） */
    private static volatile String lastErr = null;

    private R1Lights() {
    }

    /** 反射装载原厂消息总线（与 PlayerBridge 同一条路）。失败只记日志。 */
    public static void init(Context c) {
        app = c.getApplicationContext();
        try {
            Class<?> mm = Class.forName(CLS_MSG_MGR);
            msgMgr = mm.getMethod("messageCenter", Context.class).invoke(null, app);
            mSend = findMethod(msgMgr.getClass(), "sendMessage", 4);
            try {
                Class<?> ci = Class.forName(CLS_IDX);
                mGetIndex = ci.getMethod("getIndex", int.class);
            } catch (Throwable t) {
                mGetIndex = null;   // 退化为「角度/15」自算
            }
            ready = true;
            why = "ok";
            Log.i(TAG, "lights ready via msgcenter (indexProcessor=" + (mGetIndex != null) + ")");
            SessionLog.i("light init ok: msgcenter 直发 "
                    + "(indexProcessor=" + (mGetIndex != null) + ")");
        } catch (Throwable t) {
            ready = false;
            why = "" + t;
            Log.w(TAG, "lights init failed", t);
            SessionLog.i("light init FAILED: " + t);
        }
    }

    private static boolean enabled() {
        if (!ready || app == null) {
            return false;
        }
        try {
            if (new File(app.getFilesDir(), "lights_off").exists()) {
                return false;
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    /**
     * v1.3.168：唤醒灯（24 颗拾音灯）独立开关 —— {@code files/wake_light_off} 存在即关。
     * 「灭」不受这个开关限制：关掉时若灯正亮着，必须还能发灭灯报文把它收掉。
     */
    private static boolean wakeEnabled() {
        if (!enabled()) {
            return false;
        }
        try {
            return !new File(app.getFilesDir(), "wake_light_off").exists();
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * v1.3.168：播报灯（203 蓝色氛围环）独立开关 —— {@code files/busy_light_off} 存在即关。
     * 同 wakeEnabled()：只挡「开」，不挡「灭」。
     */
    private static boolean busyEnabled() {
        if (!enabled()) {
            return false;
        }
        try {
            return !new File(app.getFilesDir(), "busy_light_off").exists();
        } catch (Throwable ignored) {
            return true;
        }
    }

    // ---------------- 对外事件 ----------------

    /**
     * 唤醒：点亮拾音灯（流动 → 定格在收音方向）。
     *
     * @param ctx 原厂 {@code ANTHandlerContext}（可为 null，则复用上次的灯位）
     */
    public static void onWakeup(Object ctx) {
        if (!enabled()) {
            SessionLog.i("light wakeup skip: enabled=false (" + why + ")");
            return;
        }
        // v1.3.168：唤醒灯被单独关掉 ⇒ 不点亮（但别去灭灯，免得把氛围灯/播报灯也扫掉）
        if (!wakeEnabled()) {
            SessionLog.i("light wakeup skip: wake_light_off");
            return;
        }
        int angle = readDoa(ctx);
        int idx;
        if (angle >= 0) {
            lastAngle = angle;
            idx = toIndex(angle);
        } else if (lastIndex > 0) {
            idx = lastIndex;      // 复用上次灯位（原厂 turnOnWakeupLastLight 的语义）
        } else {
            idx = 1;              // 实在没有就定在第一颗
        }
        lastIndex = idx;
        send(idx, ON);
        SessionLog.i("light 拾音灯 on: index=" + idx + " (DOA angle=" + angle + ")");
    }

    /** 蓝色播报灯开（识别中 / 小智说话中）：先灭拾音灯，再开 loading。 */
    public static void busyOn() {
        // v1.3.168：播报灯被单独关掉 ⇒ 不开（拾音灯照灭，交给 allOff / busyOff 收尾）
        if (!busyEnabled()) {
            return;
        }
        wakeupAllOff();
        send(LIGHT_LOADING, ON);
        SessionLog.i("light 播报灯 on (loading)");
    }

    /** 蓝色播报灯灭。 */
    public static void busyOff() {
        if (!enabled()) {
            return;
        }
        send(LIGHT_LOADING, OFF);
        SessionLog.i("light 播报灯 off");
    }

    /** 全灭（会话收尾兜底）：播报灯 + 24 颗拾音灯。 */
    public static void allOff() {
        if (!enabled()) {
            return;
        }
        send(LIGHT_LOADING, OFF);
        wakeupAllOff();
        SessionLog.i("light all off");
    }

    /** 灭掉全部拾音灯（原厂 turnOffALLWakeupLight：1..24 逐条）。 */
    private static void wakeupAllOff() {
        for (int i = 1; i <= LIGHT_COUNT; i++) {
            send(i, OFF);
        }
    }

    // ---------------- 发送 ----------------

    private static void send(int arg1, int arg2) {
        sendRaw(LIGHT_WHAT, arg1, arg2);
    }

    /** 直接发一条原厂总线报文（氛围灯走的是 what=4，跟拾音灯不是同一条）。 */
    private static void sendRaw(int what, int arg1, int arg2) {
        try {
            mSend.invoke(msgMgr, Integer.valueOf(what), Integer.valueOf(arg1),
                    Integer.valueOf(arg2), (Parcelable) null);
            lastErr = null;
        } catch (Throwable t) {
            lastErr = "" + t;
            SessionLog.i("light send FAIL what=" + what + " arg1=" + arg1
                    + " arg2=" + arg2 + ": " + t);
        }
    }

    // ---------------- DOA / index ----------------

    /**
     * 读四麦声源角度：{@code ctx.engine().config().getOption(ASR_FOURMIC_DOA_RESULT)}。
     * 与 {@code DefaultLightsHandler$1} 完全同一条路（原厂就是这么拿方向的）。
     *
     * @return 角度（0..359），读不到返回 -1
     */
    private static int readDoa(Object ctx) {
        if (ctx == null) {
            return -1;
        }
        try {
            Object eng = findMethod(ctx.getClass(), "engine", 0).invoke(ctx);
            if (eng == null) {
                return -1;
            }
            Object cfg = findMethod(eng.getClass(), "config", 0).invoke(eng);
            if (cfg == null) {
                return -1;
            }
            Class<?> optCls = Class.forName(CLS_OPT);
            Object opt = optCls.getField("ASR_FOURMIC_DOA_RESULT").get(null);
            Object v = findMethod(cfg.getClass(), "getOption", 1).invoke(cfg, opt);
            if (v instanceof Integer) {
                return ((Integer) v).intValue();
            }
            return -1;
        } catch (Throwable t) {
            SessionLog.i("light DOA read failed: " + t);
            return -1;
        }
    }

    /** 角度 → 灯位。优先用原厂 {@code PhicommLightIndexProcessor.getIndex}，缺失时自算。 */
    private static int toIndex(int angle) {
        if (mGetIndex != null) {
            try {
                return ((Integer) mGetIndex.invoke(null, Integer.valueOf(angle))).intValue();
            } catch (Throwable ignored) {
            }
        }
        // 24 颗灯、每 15° 一颗（PhicommLightIndexProcessor: NUMBER=24, TAG=15）
        int i = angle / 15 + 1;
        if (i > LIGHT_COUNT) {
            i -= LIGHT_COUNT;
        }
        return i;
    }

    /** 按名字+参数个数找方法（跨 ClassLoader / 接口实现都能命中）。 */
    private static Method findMethod(Class<?> c, String name, int argc) throws NoSuchMethodException {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Method[] ms = k.getDeclaredMethods();
                for (int i = 0; i < ms.length; i++) {
                    if (ms[i].getName().equals(name) && ms[i].getParameterTypes().length == argc) {
                        ms[i].setAccessible(true);
                        return ms[i];
                    }
                }
            } catch (Throwable ignored) {
            }
            try {
                Method[] ms = k.getMethods();
                for (int i = 0; i < ms.length; i++) {
                    if (ms[i].getName().equals(name) && ms[i].getParameterTypes().length == argc) {
                        return ms[i];
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        throw new NoSuchMethodException(name + "/" + argc + " on " + c.getName());
    }

    // ---------------- 调试 ----------------

    /**
     * v1.3.147：Web 调试 {@code /api/act?op=light&v=...}。
     * AI 看不见灯，只能靠「喂参数 + 用户肉眼看」来确认哪条报文对应哪盏灯。
     */
    public static String debug(String v) {
        if (!ready) {
            return "lights not ready: " + why;
        }
        try {
            if (v == null || v.length() == 0 || "wake".equals(v)) {
                onWakeup(null);
                return "拾音灯 index=" + lastIndex + " angle=" + lastAngle;
            }
            if ("busy".equals(v)) {
                busyOn();
                return "播报灯(loading) on";
            }
            if ("off".equals(v)) {
                allOff();
                return "全灭";
            }
            // v1.3.160：救急清灯（灯卡住常亮时）。循环 close 耗空 pending 补位链。
            if ("wipe".equals(v)) {
                return wipe();
            }
            if (v.startsWith("idx:")) {
                int idx = Integer.parseInt(v.substring(4));
                lastIndex = idx;
                send(idx, ON);
                SessionLog.i("light debug idx=" + idx);
                return "拾音灯 index=" + idx + "（手动）";
            }
            if (v.startsWith("angle:")) {
                int a = Integer.parseInt(v.substring(6));
                int idx = toIndex(a);
                lastAngle = a;
                lastIndex = idx;
                send(idx, ON);
                SessionLog.i("light debug angle=" + a + " -> index=" + idx);
                return "angle=" + a + " -> index=" + idx;
            }
            if (v.startsWith("raw:")) {
                String[] p = v.substring(4).split(":");
                int a1 = Integer.parseInt(p[0]);
                int a2 = p.length > 1 ? Integer.parseInt(p[1]) : 0;
                send(a1, a2);
                SessionLog.i("light debug raw arg1=" + a1 + " arg2=" + a2);
                return "raw arg1=" + a1 + " arg2=" + a2;
            }
            return "unknown v=" + v + " (wake|busy|off|idx:N|angle:N|raw:A1:A2)";
        } catch (Throwable t) {
            return "light debug failed: " + t;
        }
    }

    /** 给 Web 状态看：灯模块是否就绪。 */
    public static String state() {
        return ready ? ("ok:" + why) : ("off:" + why);
    }

    // ================= 氛围灯（小飞版那套「灯效设置」） =================
    //
    // 命令语义从魔改 player 的 r1_control.min.js 命令表里扒出来，并在本机用
    // get_info 的 music_light_* 字段逐条自证过（发完字段真的会变、也能复原）：
    //   what=4 arg1=64 arg2=1/0       氛围灯总开关
    //   what=4 arg1=65 arg2=1..200    亮度（默认 100）
    //   what=4 arg1=66 arg2=0..100    颜色渐变速度（默认 4）
    //   what=4 arg1=68 arg2=mode      0 官方 / 1 七彩旋转 / 2 七彩 / 3 七彩旋转1 / 4 单色
    //   what=4 arg1=70 arg2=0xRRGGBB  单色模式的颜色
    // ★ 这些命令由设备控制服务（EchoService）处理，不是拾音灯那个 LightsEffectService；
    //   但两边都挂在同一条原厂 messageCenter 总线上，我们用与拾音灯相同的出口直发。
    // ★ 版本门槛：命令表里 arg1=68 的多状态切换标着 min_ver:1848，本机 ver=1882 ⇒ 适用。
    // ★ 状态回显只能靠本地记录（真实状态在 EchoService 进程里，device 侧读不到），
    //   所以「从没在我们的页面设过」的项显示未知，而不是编一个数。
    //
    // ★★★ 2026-10-16 实测（原厂 player，非小飞魔改版）：
    //   com.phicomm.speaker.player.helpers.PlayerVisualizerHelper 注册了 what=4，
    //   但 notifyMsg() 的 switch(arg1) 只有 case 64/65/66/67 ⇒
    //   **68（效果模式）与 70（自定义颜色）没有任何接收者**（发下去 logcat 零日志，
    //   而 65/66 会有 `PlayerVisualizer: set luma gain:50`）。
    //   原厂唯一的氛围灯是 PlayerVisualizer：15 颗灯同色、色相随 FFT 频谱推进
    //   （LedLight.setColor(0x7FFF, HSBtoColor(hue,1,brightness))）。
    //   ⇒ 页面已把「效果模式 / 颜色」两项撤掉，只留开关（64）。

    private static final int AMB_WHAT = 4;
    private static final int AMB_SWITCH = 64;
    private static final int AMB_LUMA = 65;
    private static final int AMB_CHROMA = 66;
    private static final int AMB_MODE = 68;
    private static final int AMB_COLOR = 70;

    /** 下标即 mode 值 */
    private static final String[] AMB_MODE_NAMES =
            {"官方氛围", "七彩旋转", "七彩", "七彩旋转1", "单色"};

    private static final int A_ON = 0, A_MODE = 1, A_LUMA = 2, A_CHROMA = 3, A_COLOR = 4;
    private static final int A_LEN = 5;

    /**
     * 氛围灯控制入口（Web {@code /api/act?op=amb&v=...}）。
     *
     * @param v on|off|luma:N|chroma:N|mode:N|color:#RRGGBB|color:N|raw:A1:A2|state
     * @return 人类可读结果，直接回显到控制台
     */
    public static String ambient(String v) {
        if (!ready) {
            return "灯总线没起来: " + why;
        }
        int[] st = ambLoad();
        String vv = (v == null) ? "state" : v.trim();
        try {
            if ("state".equals(vv)) {
                return ambDesc(st);
            }
            if ("on".equals(vv) || "off".equals(vv)) {
                int on = "on".equals(vv) ? 1 : 0;
                sendRaw(AMB_WHAT, AMB_SWITCH, on);
                st[A_ON] = on;
                ambSave(st);
                SessionLog.i("ambient switch " + (on == 1 ? "on" : "off"));
                return "氛围灯已" + (on == 1 ? "打开" : "关闭") + ambErr();
            }
            // ★ 量纲对齐原厂：PlayerVisualizer.setLumaGain() 只收 0..100（超了直接 return -1
            //   且什么都不写），以前我们放 1..200 ⇒ 拖过 100 的那一半全是静默失败。
            if (vv.startsWith("luma:")) {
                int n = clampInt(vv.substring(5), 0, 100);
                sendRaw(AMB_WHAT, AMB_LUMA, n);
                st[A_LUMA] = n;
                ambSave(st);
                SessionLog.i("ambient luma=" + n);
                return "亮度=" + n + ambErr();
            }
            // ★ 同上：原厂 setChromaGain() 要求 1..100（0 会被拒）。
            if (vv.startsWith("chroma:")) {
                int n = clampInt(vv.substring(7), 1, 100);
                sendRaw(AMB_WHAT, AMB_CHROMA, n);
                st[A_CHROMA] = n;
                ambSave(st);
                SessionLog.i("ambient chroma=" + n);
                return "渐变速度=" + n + ambErr();
            }
            if (vv.startsWith("mode:")) {
                int n = clampInt(vv.substring(5), 0, 4);
                sendRaw(AMB_WHAT, AMB_MODE, n);
                st[A_MODE] = n;
                ambSave(st);
                SessionLog.i("ambient mode=" + n + " (" + AMB_MODE_NAMES[n] + ")");
                // ★ 原厂 player 不认 68（见类注释）：命令发出去了，但没人收。
                return "效果=" + AMB_MODE_NAMES[n] + "（⚠ 原厂 player 没实现 68，"
                        + "只有小飞魔改版才有效果模式，实测灯不会变）" + ambErr();
            }
            if (vv.startsWith("color:")) {
                int c = parseColor(vv.substring(6));
                if (c < 0) {
                    return "颜色格式不对（要 #66CCFF 或十进制整数）";
                }
                sendRaw(AMB_WHAT, AMB_COLOR, c);
                st[A_COLOR] = c;
                ambSave(st);
                SessionLog.i("ambient color=0x" + Integer.toHexString(c));
                // ★ 同 68：原厂 player 不认 70。
                return "颜色=#" + hex6(c) + "（⚠ 原厂 player 没实现 70，只有小飞魔改版认）"
                        + ambErr();
            }
            if (vv.startsWith("raw:")) {
                String[] p = vv.substring(4).split(":");
                int a1 = Integer.parseInt(p[0].trim());
                int a2 = p.length > 1 ? Integer.parseInt(p[1].trim()) : 0;
                sendRaw(AMB_WHAT, a1, a2);
                return "raw arg1=" + a1 + " arg2=" + a2 + ambErr();
            }
            return "unknown v=" + vv + "（on|off|luma:N|chroma:N|mode:N|color:RRGGBB|state）";
        } catch (Throwable t) {
            return "ambient 出错: " + t;
        }
    }

    /** 给 /api/status 用：氛围灯的本地记录（没设过的项是 -1）。 */
    public static JSONObject ambientState() {
        JSONObject o = new JSONObject();
        int[] st = ambLoad();
        try {
            o.put("ready", ready);
            o.put("on", st[A_ON]);
            o.put("mode", st[A_MODE]);
            o.put("luma", st[A_LUMA]);
            o.put("chroma", st[A_CHROMA]);
            o.put("color", st[A_COLOR]);
            if (st[A_MODE] >= 0 && st[A_MODE] < AMB_MODE_NAMES.length) {
                o.put("modeName", AMB_MODE_NAMES[st[A_MODE]]);
            }
            if (st[A_COLOR] >= 0) {
                o.put("colorHex", "#" + hex6(st[A_COLOR]));
            }
        } catch (Throwable ignored) {
        }
        return o;
    }

    public static String[] ambientModeNames() {
        return AMB_MODE_NAMES;
    }

    // ---------------- 氛围灯：本地记录 ----------------

    private static File ambFile() {
        return new File(app.getFilesDir(), "ambient.txt");
    }

    /** 格式：{@code on=1;mode=2;luma=100;chroma=4;color=6737151}（缺项 = -1 未知） */
    private static int[] ambLoad() {
        int[] v = new int[A_LEN];
        for (int i = 0; i < A_LEN; i++) {
            v[i] = -1;
        }
        if (app == null) {
            return v;
        }
        try {
            File f = ambFile();
            if (!f.exists()) {
                return v;
            }
            byte[] b = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            try {
                int n = in.read(b);
                if (n <= 0) {
                    return v;
                }
                for (String kv : new String(b, 0, n, "UTF-8").split(";")) {
                    int eq = kv.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    String k = kv.substring(0, eq).trim();
                    int val = -1;
                    try {
                        val = Integer.parseInt(kv.substring(eq + 1).trim());
                    } catch (Throwable ignored) {
                    }
                    if ("on".equals(k)) {
                        v[A_ON] = val;
                    } else if ("mode".equals(k)) {
                        v[A_MODE] = val;
                    } else if ("luma".equals(k)) {
                        v[A_LUMA] = val;
                    } else if ("chroma".equals(k)) {
                        v[A_CHROMA] = val;
                    } else if ("color".equals(k)) {
                        v[A_COLOR] = val;
                    }
                }
            } finally {
                in.close();
            }
        } catch (Throwable ignored) {
        }
        return v;
    }

    private static void ambSave(int[] v) {
        if (app == null) {
            return;
        }
        try {
            String s = "on=" + v[A_ON] + ";mode=" + v[A_MODE] + ";luma=" + v[A_LUMA]
                    + ";chroma=" + v[A_CHROMA] + ";color=" + v[A_COLOR];
            FileOutputStream out = new FileOutputStream(ambFile());
            try {
                out.write(s.getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
        }
    }

    private static String ambDesc(int[] st) {
        return "氛围灯 开关=" + num(st[A_ON]) + " 效果=" + (st[A_MODE] >= 0
                && st[A_MODE] < AMB_MODE_NAMES.length
                ? AMB_MODE_NAMES[st[A_MODE]] + "(" + st[A_MODE] + ")" : "未知")
                + " 亮度=" + num(st[A_LUMA]) + " 渐变=" + num(st[A_CHROMA])
                + " 颜色=" + (st[A_COLOR] >= 0 ? "#" + hex6(st[A_COLOR]) : "未知")
                + "（这些是我们在本页设过的值）";
    }

    private static String num(int v) {
        return v < 0 ? "未知" : String.valueOf(v);
    }

    private static String ambErr() {
        return lastErr == null ? "" : ("  ⚠ 发送异常:" + lastErr);
    }

    private static int clampInt(String s, int lo, int hi) {
        int v = Integer.parseInt(s.trim());
        if (v < lo) {
            v = lo;
        }
        if (v > hi) {
            v = hi;
        }
        return v;
    }

    /** {@code #66CCFF} / {@code 66CCFF} / {@code 6737151} → 0xRRGGBB；解析不了返回 -1 */
    private static int parseColor(String s) {
        String t = s.trim();
        if (t.startsWith("#")) {
            t = t.substring(1);
        }
        try {
            if (t.matches("^[0-9]+$")) {
                return Integer.parseInt(t) & 0xFFFFFF;
            }
            return Integer.parseInt(t, 16) & 0xFFFFFF;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String hex6(int c) {
        String s = Integer.toHexString(c & 0xFFFFFF).toUpperCase();
        while (s.length() < 6) {
            s = "0" + s;
        }
        return s;
    }

    // ---------------- 强制清灯（救急，v1.3.160 保留） ----------------
    //
    // conf 里可能占住运行位的几个效果 id：
    //   503 = 24 颗拾音灯常亮 #ffffff（FORCE，duration=0 永亮）
    //   505 = 24 颗拾音灯常亮 #000000（FORCE）
    //   504 = 15 颗氛围环全黑（FORCE）
    // ★ close 只是停止调度，**LED 会保持最后一帧颜色**，所以想真的灭灯得先
    //   show 一个「黑色」效果把 LED 写黑，再 close 掉它腾出运行位。
    private static final int[] WIPE_IDS = {503, 505, 504, LIGHT_LOADING};

    /**
     * 强制清灯（救急）：循环 close 掉可能残留的灯效。
     * ★ LightsEffectService 在 close 一个 active 后会从 pending 补位复活下一个，
     *   所以要多轮 close 才能把补位链耗空（终点日志：
     *   "no pending light need to show!" / "no active light, can not close!"）。
     * ★ 老进程（pid 已死）留下的效果我们 close 不掉 ⇒ 真卡住只能重启音箱。
     */
    public static String wipe() {
        if (!ready) {
            return "灯总线没起来: " + why;
        }
        for (int i = 0; i < 6; i++) {
            for (int id : WIPE_IDS) {
                send(id, OFF);
            }
        }
        SessionLog.i("light wipe（" + (6 * WIPE_IDS.length) + " 条 close 清 pending 补位链）");
        return "已发 " + (6 * WIPE_IDS.length) + " 条 close。仍常亮说明是旧进程残留"
                + "（owner pid 不是我们）⇒ 只能重启音箱";
    }

    // ================= MCP：self.r1light.*（v1.3.157） =================

    /**
     * 小智侧的灯控入口。与音乐工具分开前缀（self.r1light.*），
     * 这样「自定义音乐库」关掉时灯还能控 —— 那是两码事。
     *
     * @param op  {@code ambient}
     * @param a   MCP arguments（各项都可省略，只传要改的）
     * @return 直接给模型转述的文本
     */
    public static String mcp(String op, JSONObject a) {
        try {
            if ("ambient".equals(op)) {
                return ambientMcp(a);
            }
            return "unknown light tool: " + op;
        } catch (Throwable t) {
            return "ERR " + t;
        }
    }

    /** 氛围灯可以一次改多项（「开灯并调成蓝色」），逐项下发后拼起来回给模型。 */
    private static String ambientMcp(JSONObject a) {
        if (!ready) {
            return "灯总线没起来: " + why;
        }
        if (a == null || a.length() == 0) {
            return ambient("state");
        }
        StringBuilder sb = new StringBuilder();
        if (a.has("on")) {
            boolean on = parseOnOff(a.optString("on", null), true);
            sb.append(ambient(on ? "on" : "off")).append("；");
        }
        if (a.has("mode")) {
            sb.append(ambient("mode:" + a.optInt("mode", 0))).append("；");
        }
        if (a.has("luma")) {
            sb.append(ambient("luma:" + a.optInt("luma", 100))).append("；");
        }
        if (a.has("chroma")) {
            sb.append(ambient("chroma:" + a.optInt("chroma", 4))).append("；");
        }
        if (a.has("color")) {
            String c = a.optString("color", "");
            if (c.trim().length() > 0) {
                sb.append(ambient("color:" + c)).append("；");
            }
        }
        String s = sb.toString();
        return s.length() == 0 ? ambient("state") : s;
    }

    /** {@code on/开/亮/1/true} ⇒ true；{@code off/关/灭/0/false} ⇒ false；其它 ⇒ 默认。 */
    private static boolean parseOnOff(String v, boolean def) {
        if (v == null) {
            return def;
        }
        String t = v.trim();
        if (t.length() == 0) {
            return def;
        }
        if (t.equalsIgnoreCase("on") || t.equals("1") || t.equalsIgnoreCase("true")
                || t.equals("开") || t.equals("亮") || t.equals("打开")) {
            return true;
        }
        if (t.equalsIgnoreCase("off") || t.equals("0") || t.equalsIgnoreCase("false")
                || t.equals("关") || t.equals("灭") || t.equals("关闭")) {
            return false;
        }
        return def;
    }
}
