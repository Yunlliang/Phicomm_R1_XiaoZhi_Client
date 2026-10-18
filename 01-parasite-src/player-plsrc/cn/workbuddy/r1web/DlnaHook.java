package cn.workbuddy.r1web;

import android.content.Context;
import android.content.Intent;

/**
 * 被 :DlnaPlayer 进程里打过补丁的 {@code DlnaPlayer2.onPlayCommand} 调用（smali 插桩）。
 *
 * <p>根因：手机 DLNA 推流走的是 {@code DlnaPlayer2} 自己进程里的
 * {@code MusicPlayEngineImpl}（独立播放引擎），曲目信息只留在 :DlnaPlayer 的
 * {@code DlnaMediaModel} 里，主进程控制台的「正在播放」当然看不到。</p>
 *
 * <p>做法：把 model 里的 title/artist/url 转成<b>全局广播</b>
 * （{@code r1web.dlna.push}）带给主进程的 {@link Ext}，控制台就能同步显示。
 * 参数全用 {@code Object} —— 编译期不引用原厂类（stub 不进 dex），
 * 字段/方法走反射，反正原厂类就在同一个 dex 里，运行时一定在。</p>
 */
public final class DlnaHook {

    /** 与 Ext 接收端约定的 action。 */
    public static final String ACT = "r1web.dlna.push";

    /**
     * @param player DlnaPlayer2 实例（只用来反射拿它的 mContext 发广播）
     * @param model  DlnaMediaModel 实例（getTitle / getArtist / getUrl）
     */
    public static void onPush(Object player, Object model) {
        try {
            Object ctx = PCtl.field(player, "mContext");
            if (!(ctx instanceof Context)) {
                return;
            }
            Intent i = new Intent(ACT);
            i.putExtra("t", call(model, "getTitle"));
            i.putExtra("ar", call(model, "getArtist"));
            i.putExtra("u", call(model, "getUrl"));
            ((Context) ctx).sendBroadcast(i);
        } catch (Throwable t) {
            Boot.log("dlnaHook: " + t);
        }
    }

    private static String call(Object o, String m) {
        try {
            Object r = o.getClass().getMethod(m).invoke(o);
            return r == null ? "" : String.valueOf(r);
        } catch (Throwable t) {
            return "";
        }
    }

    private DlnaHook() {
    }
}
