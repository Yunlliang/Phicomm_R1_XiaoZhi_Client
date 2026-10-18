package cn.workbuddy.r1client;

import android.content.Context;
import android.content.SharedPreferences;   // v1.3.136：播放状态落盘（跨进程重启存活）
import android.media.AudioManager;          // v1.3.137：isMusicActive() 真状态判据
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;                    // v1.3.188：歌词/封面/rid 小缓存
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * v1.3.86（jp2 v44）：音箱播放器 MCP 桥 —— 走 **DLNA**，不再走原厂消息总线。
 *
 * 为什么要改（2026-10-09 实测，详见 .workbuddy/memory/2026-10-09.md）：
 *   v1.3.85 走 PhicommMusicController -> MessageDispatchManager.sendMessage(4, subType, -1)
 *   消息**确实送达了 player**（logcat: <SentTo> pid:810 msg:{ what:4 arg1:3 }），
 *   但 player 的播放列表是空的：
 *     · /list -> {"playList":[]}
 *     · GetTransportInfo -> NO_MEDIA_PRESENT
 *   根因：魔改固件把音源换成了 mod 作者私服
 *     （MUSIC_SERVICE_CONFIG provider=default/endpoint 空、x-r1-provider=youtube、
 *      ASR_ENDPOINT=https://r1-py.thd.dpdns.org），歌单靠 device（小讯）登录私服拉取后
 *     推给 player。jp2 顶替 device 后这条链路断了 ⇒ 任何 play/pause 都是空转。
 *
 * 所以改成：MCP 自己带 URL，直接推给音箱的 DLNA 渲染器。
 *   · DMR：Phicomm_R1_CD6B（com.phicomm.speaker.player:DlnaPlayer 进程，Neptune/Platinum 栈）
 *   · ⚠️⚠️ **端口不是固定的**：实测 2026-10-09 同一个 player 进程先后 LISTEN 在
 *     1930 →（进程重启）→ 1502。写死端口 = player 一重启就废（这正是用户报的
 *     「以前 DLNA 能播，现在播不了」的根因）。
 *     ⇒ 必须在运行时**动态发现**：读 /proc/net/tcp 拿本机 LISTEN 端口，
 *       逐个 GET "/" 找返回 UPnP 设备描述且含 AVTransport 的那个，再从描述里
 *       解析 controlURL（形如 /AVTransport/98bb9936cd6b/control.xml）。
 *   · 实测：SetAVTransportURI -> Play -> GetTransportInfo 从 NO_MEDIA_PRESENT 变 PLAYING
 *   不依赖任何登录态 / 第三方服务器。
 *
 * 音源：NAS 上的音乐索引 JSON（默认 http://192.168.2.189:8003/music/index.json，
 * 可用 files/music_index 覆盖，内容写一行 URL）。格式见 R1/music_index.example.json：
 *   {"base":"http://.../music/",
 *    "tracks":[{"title":"晴天","artist":"周杰伦","file":"周杰伦/晴天.mp3"}],
 *    "radios":[{"name":"Hit FM","url":"http://..."}]}
 */
public final class PlayerBridge {

    private static final String TAG = "PlayerBridge";

    /** DLNA 服务在本机，走回环最稳（设备 IP 可能变），但**端口每次启动都变**，要动态发现。 */
    private static volatile String avtUrl;
    private static volatile String rcsUrl;

    /**
     * v1.3.179：默认指向合并后的媒体服务器 R1XiaoZhi_MediaServer（8100，
     * 一个容器一份 index.json 里装着本地 + 在线 + 电台）。
     * 以前这里指 r1-music 的 8010 —— 那个容器已经并进 8100 了。
     * 仍可用 files/music_index（一行或多行 URL）覆盖。
     */
    private static final String DEFAULT_INDEX_URL =
            "http://192.168.2.189:8100/index.json";

    /**
     * 一次推进 player 的曲目**硬上限**（只是防止 Binder 事务爆掉的安全阀）。
     *
     * v1.3.92 曾把软上限定死在 100 首（当时怕 Binder 事务爆），v1.3.113 用户要求
     * 「把整个音乐库放进播放列表」⇒ 默认改成**全库**：1185 首 × ~110B ≈ 130KB，
     * 相对 Binder 事务 1MB 缓冲仍有余量。想退回小歌单就在 files/playlist_max 写数字。
     */
    private static final int MAX_PLAYLIST_CAP = 2000;

    /**
     * 当前生效的歌单上限：默认 MAX_PLAYLIST_CAP（=全库），可用 files/playlist_max
     * 覆盖（写一行数字，如 100），clamp 到 [1, MAX_PLAYLIST_CAP]。
     */
    private static int maxPlaylist() {
        int v = MAX_PLAYLIST_CAP;
        try {
            String s = XzBootstrap.readLineFile("playlist_max");
            if (s != null && s.length() > 0) {
                v = Integer.parseInt(s.trim());
            }
        } catch (Throwable t) {
            v = MAX_PLAYLIST_CAP;
        }
        if (v < 1) {
            v = 1;
        }
        if (v > MAX_PLAYLIST_CAP) {
            v = MAX_PLAYLIST_CAP;
        }
        return v;
    }

    /**
     * v1.3.180：**在线曲目上限** —— 所有「从 NAS 拉在线歌」的途径统一受它限制。
     *
     * <p>以前在线条数是各写各的常量：<code>play_artist</code> 补 20 首、
     * <code>random_online</code> 默认 100、页面「在线搜歌」默认 20 —— 三处互不相干，
     * 用户在设置里改一个数却只影响其中一条路，其余照旧。现在统一由
     * <code>files/online_max</code> 一个数管（默认 {@value #DEFAULT_ONLINE_MAX}）。</p>
     *
     * <p>为什么现在敢给 300：v1.3.179 起在线曲目走<b>惰性地址</b>（推列表不取链，
     * 真正播放那一刻才让 NAS 取链 + 流式转发），拉一批列表只是几次 HTTP，
     * 「多拿几首」不再有任何代价。上限取 300 是因为 mini-lx 的
     * <code>/index.json?n=</code> 与随机批本身就是 300 封顶，再大也拿不到更多。</p>
     */
    private static final int DEFAULT_ONLINE_MAX = 300;
    /** 在线上限的硬上边界（= NAS 一次能给的最多条数）。 */
    private static final int ONLINE_MAX_CAP = 300;

    public static int onlineMax() {
        int v = DEFAULT_ONLINE_MAX;
        try {
            String s = XzBootstrap.readLineFile("online_max");
            if (s != null && s.length() > 0) {
                v = Integer.parseInt(s.trim());
            }
        } catch (Throwable t) {
            v = DEFAULT_ONLINE_MAX;
        }
        if (v < 10) {
            v = 10;
        }
        if (v > ONLINE_MAX_CAP) {
            v = ONLINE_MAX_CAP;
        }
        return v;
    }

    /** v1.3.180：本地曲目上限（= {@link #maxPlaylist()}），供状态/控制台读取。 */
    public static int playlistMax() {
        return maxPlaylist();
    }

    /** v1.3.180：把在线上限写进 files/online_max（Web 控制台「在线曲目上限」用）。 */
    public static String setOnlineMax(int v) {
        if (v < 10) {
            v = 10;
        }
        if (v > ONLINE_MAX_CAP) {
            v = ONLINE_MAX_CAP;
        }
        boolean ok = XzBootstrap.writeLineFile("online_max", String.valueOf(v));
        return ok ? ("在线曲目上限 " + v + " 首（已保存，立即生效）")
                : "保存失败（写 files/online_max 失败）";
    }

    /** v1.3.180：把歌单上限写进 files/playlist_max（Web 控制台「本地曲目上限」用）。 */
    public static String setPlaylistMax(int v) {
        if (v < 1) {
            v = 1;
        }
        if (v > MAX_PLAYLIST_CAP) {
            v = MAX_PLAYLIST_CAP;
        }
        boolean ok = XzBootstrap.writeLineFile("playlist_max", String.valueOf(v));
        return ok ? ("本地曲目上限 " + v + " 首（已保存，立即生效）")
                : "保存失败（写 files/playlist_max 失败）";
    }

    private static Context app;
    private static String indexUrl = DEFAULT_INDEX_URL;
    /**
     * v1.3.102：多源索引。files/music_index 从「一行 URL」升级为「每行一个 URL」，
     * 逐个拉取后**合并成一个虚拟曲库**（NAS 本地 + 公网源同时可用）。
     * 单源失败只记该源的错误，不影响其它源 —— 公网源挂了本地照样能放。
     */
    private static final List<String> indexUrls = new ArrayList<String>();
    /** v1.3.99：DLNA 控制端口（每次 player 重启都变，web 控制台显示用） */
    private static volatile int dlnaPort = 0;

    private static String base = "";
    /**
     * 每条：{title, artist, file, url, album, duration, pic, via}
     * v1.3.102：末尾的 url 是**最终绝对地址** —— 索引条目里写了 "url" 字段就用它
     * （公网源必须走这个，因为它们没有 NAS 的 base），否则在加载时按该源的
     * base 拼好存进来。这样 urlOf() 不用再关心 base，多源才不会互相串味。
     * v1.3.178：第 8 位 via（"" = 本地曲库池，"online" = 在线搜/在线随机拉来的）。
     *   以前这条信息只活在内存里的 curVia 三个变量上，而**绝大多数推播路径
     *   （random / play_artist / play_index / 队列跳播）都只调 notePlaying(曲名)**，
     *   于是专辑/时长/来源留在上一次的值上 —— 用户看到的就是「上一首在线歌的专辑
     *   还挂在这一首下面」。把 via 跟曲目绑在一起，任何路径都能取到正确值。
     */
    private static final List<String[]> tracks = new ArrayList<String[]>();
    /** v1.3.178：在线 append 进 tracks 的条数（loadIndex 清零）。用来兜底防无限膨胀。 */
    private static volatile int onlineAppends = 0;
    /** 在线 append 上限：超过就回收最早收录的一半在线 append（v1.3.185，不再停摆等 reloadIndex）。 */
    private static final int ONLINE_APPEND_CAP = 400;
    /**
     * v1.3.180：play_artist 补多少首在线 —— <b>不再写死常量</b>，统一走
     * {@link #onlineMax()}（files/online_max，默认 300）。
     *
     * <p>历史：v1.3.178 定 12（逐条取链要几秒一首），v1.3.179 提到 20（惰性地址）。
     * 用户 2026-10-17 指出「在线只是拉列表、起播才取链，20 首太保守」⇒ 交给设置项。</p>
     */
    /**
     * v1.3.178：并行取链的并发数 —— **不是越大越好**，实测踩过坑：
     * <pre>
     *   并发 12：12 个请求同时砸向 NAS，上游（lxserver 中继 + 跨源）直接限流，
     *            半数请求 5s 超时 ⇒ 一首都没取到（"本地 5 首"，在线全丢）
     *   并发  6：两轮，约 5s，12/12 成功
     *   并发  4：约 3~12s（首次），12/12 成功 —— 最稳
     * </pre>
     * 取链是「每首一次 HTTP，内部还要跑中继 + 五级兜底」，上游扛不住突发；
     * 而 mini-lx 对拿到的直链有缓存，第二次起基本 0.2s 命中。
     * ⇒ 选 4 + 错峰启动 + 整体预算，宁可少几首也不要整批超时。
     */
    private static final int URL_CONC = 4;
    /** 每个线程启动前的小错峰（ms）—— 把「同时 12 个」摊成「每 250ms 一个」。 */
    private static final long URL_STAGGER = 250L;
    /**
     * v1.3.179：★ **不走惰性时**（老 NAS 没有 /r/ 路由）一次最多取几条链。
     *
     * <p>正常路径（NAS 支持 /r/）推 20 首是 0 次取链，这里根本用不上。
     * 只有回退路径才真的会对着上游开火 —— 那时 20 条并发请求 = 送上门被限流/封 IP。
     * 这是**兜底路径**，宁可少给几首（用户还能听本地的），也不能把上游惹毛：
     * 一旦被封，连"起播时取链"都全部哑火，得不偿失。</p>
     */
    private static final int LEGACY_URL_MAX = 8;
    /** 兜底路径的并发数（4 → 2，配合上面 250ms 错峰 = 实际每 250ms 最多 1 条上路）。 */
    private static final int LEGACY_URL_CONC = 2;
    /** 每条：{name, url}（电台本来就是绝对地址，天然支持公网源） */
    private static final List<String[]> radios = new ArrayList<String[]>();
    /** v1.3.102：每个源的加载结果，每条 {url, tracks数, radios数, 错误信息} */
    private static final List<String[]> srcStats = new ArrayList<String[]>();
    private static volatile boolean idxLoaded;
    private static String idxErr = "";

    private PlayerBridge() {
    }

    // ---------------- 原厂消息总线（"音箱自己播"的主通道） ----------------
    //
    // v1.3.88 新增。之前（v1.3.85）只发了空 play（subType=3），player 手里没歌所以空转；
    // 真正的入口是 **subType=1 playSpecifiedMusic**，它把一首/一组「带 URL 的曲目」推进
    // player 自己的播放引擎 —— 之后 player 就有播放列表了，next/prev/模式全都能用。
    //
    // 协议（反编译 PhicommMusicController.smali 得出）：
    //   MessageDispatchManager.sendMessage(int type, int subType, int arg2, Parcelable obj)
    //   type = 4 (MSG_TYPE_PLAYER)
    //   subType: 1=playSpecifiedMusic  2=pause  3=play  4=exit  5=playPrev  6=playNext  7=setMode
    //   obj    : ParcelableUtil.obtain(String)  —— 它是个通用包装器
    //            （writeToParcel 就是 dest.writeValue(val)），内容只有 title/url 两个字段：
    //            [{"title":"晴天","url":"http://..."}, ...]
    //   ⇒ 不需要碰 nluparser.scheme.MusicResult$Music，用反射直接拼 JSON 即可。

    private static Object msgMgr;
    private static volatile boolean msgTried;

    /** v1.3.94：本次进程是否已经往 player 里推过歌单（决定 play 要不要兜底成 random）。 */
    private static volatile boolean pushed;

    /** v1.3.100：最近一次设置的音量（0~100，-1=未设置过），仅供状态回显。 */
    private static volatile int lastVolume = -1;

    /**
     * v1.3.132：粗粒度「音乐在播」软状态 —— 只跟踪经过本类的播放动作。
     * 供 MusicResume 的「TTS 让路」判断用（见 {@link #isSoftPlaying()}）。
     */
    private static volatile boolean softPlaying = false;

    /**
     * v1.3.143：{@link #softPlaying} 的「新鲜度」—— 只有**本进程内真发生过播放动作**
     * （dispatch 的 play 族 / resumeMusic / kickPlay 推歌）才为 true。
     *
     * <p>为什么需要它：{@link #restorePlayState()} 从 SP 恢复的「在播」是**上一个进程**
     * 留下的结论，歌早就播完了它也照样写着 true（实测 22:13 恢复出「Topmodel 160s 前」、
     * 更早在播记录甚至挂着 832s 前的《The Tradition》）。拿这种过期结论去让路会连错两次：
     * ① 明明没在播却发 pause；② 说完话再 resume ⇒ **把上一曲凭空拽回来**。</p>
     *
     * <p>分工：</p>
     * <ul>
     *   <li>{@code softPlaying}（含恢复值）⇒ 只供 status / 小智问答显示「当前曲目」；</li>
     *   <li>{@code softFresh} ⇒ 才配当「让路」判据（另有 {@link #isMusicActive()} 兜底）。</li>
     * </ul>
     */
    private static volatile boolean softFresh = false;

    /**
     * v1.3.136：「当前曲目名」。来源有二 ——
     * <ol>
     *   <li>我们推的播放列表的第一首（{@link #pushPlaylist}）；</li>
     *   <li>DLNA {@code GetPositionInfo} 的 {@code TrackMetaData}（DIDL-Lite 里的
     *       {@code <dc:title>}）—— 这条能覆盖「不是我们推的播放」（DLNA 推送、原厂起播）。</li>
     * </ol>
     */
    private static volatile String curTitle = "";
    /** v1.3.175：当前曲目的「实际歌曲信息」——专辑/时长（翻唱/DJ 版与歌名不一致时看这里）。 */
    private static volatile String curAlbum = "";
    private static volatile String curDuration = "";
    /** v1.3.175：播放来源标记（"" = 曲库池，online = 在线搜到的）—— 告诉用户这版是不是原唱库里的。 */
    private static volatile String curVia = "";
    /** v1.3.188：当前曲目封面 URL（搜索结果 img / NAS {@code /api/pic}），页面直接当 {@code <img src>}。 */
    private static volatile String curPic = "";
    /** v1.3.136：DLNA 报告的真状态（PLAYING / PAUSED_PLAYBACK / NO_MEDIA_PRESENT …），"" = 还没查到 */
    private static volatile String dlnaState = "";
    /** v1.3.136：上次播放状态写盘的时间戳（0 = 从未写过） */
    private static volatile long playStateTs = 0;

    // ---------------- v1.3.163：自有播放队列（彻底脱离 8080 小飞 WS） ----------------
    //
    // 背景：web 播放页的「队列 / 正在播放 / 点播第 N 首 / 音量」原先全部走
    // ws://<ip>:8080 —— 那是**魔改 player（小飞）**自己开的 WS，原厂 player 没有。
    // 把 player 刷回原厂后 8080 永远连不上，页面就一直「正在等音箱播放服务（8080）…」。
    //
    // 实测（2026-10-16，原厂 /system/app/EchoService/EchoService.apk）：
    //   · 我们的 type=4 总线指令（1=playSpecified 3=play 2=pause 5/6=prev/next）**照单全收**，
    //     推 `100_local_MUSIC_4` 后 MultiMediaPlayer 真的 buffered→STATE_PLAYING；
    //   · player 会主动把「当前曲目」回推到 type=33554432（USER_DATA）总线，arg1=1，
    //     val=[{"singer":"…","songName":"…","music_id":"100_local_MUSIC_1149",…}]
    //     ⇒ 曲名/歌手/库内下标全都能拿到，而且比 8080 轮询更实时；
    //   · 我们 push 进去的 itemId 自带库内下标 ⇒ 队列和高亮都能自己重建。
    // ⇒ 缺的只剩「播放进度 / 总时长」（那个只上报原厂云 MQTT，本地没有通道）。

    /** 最近一次 push 进 player 的列表（tracks/radios 的下标序列，顺序即播放顺序）。 */
    private static final List<Integer> lastIdx = new ArrayList<Integer>();
    /** lastIdx 是电台列表还是歌曲列表。 */
    private static volatile boolean lastIsRadio = false;
    /** 当前曲目在 lastIdx 中的位置（0-based，-1 = 还没收到过总线事件）。 */
    private static volatile int curIdx = -1;
    /** 当前曲目的歌手（总线事件带的，自有链路之前拿不到）。 */
    private static volatile String curArtist = "";
    /** 最近一次收到总线曲目事件的时间戳（0 = 从未收到）。 */
    private static volatile long busSongTs = 0;
    /** v1.3.172：最近一次设的播放模式（1=随机 2=顺序 3=单曲，默认 2）。给 status 回显用。 */
    private static volatile int lastMode = 2;

    /**
     * v1.3.139：自定义音乐库总开关（默认开；{@code files/my_music_off} 存在 = 关）。
     *
     * <p>关掉后 {@code XiaoZhiWs.sendMcpToolsResult()} 上报<b>空工具表</b> ⇒ 模型
     * 看不到 {@code self.r1player.*}，自然退回小智服务器自带的音乐/电台。
     * Web 卡片上的手动按钮不受影响（那是调试通道，不是小智的视野）。</p>
     */
    private static volatile boolean myMusic = true;

    /** v1.3.139：自定义音乐库是否启用（给 MCP 工具表门禁用）。 */
    public static boolean isMyMusic() {
        return myMusic;
    }

    /** v1.3.139：Web 控制台切换，立即生效（下次唤醒建立新连接即按新工具表走）。 */
    public static void setMyMusic(boolean on) {
        myMusic = on;
        Log.i(TAG, "myMusic=" + on);
        SessionLog.i("music 自定义音乐库" + (on ? "已开启（小智用我们的曲库/电台）"
                : "已关闭（交给小智服务器自带音乐）"));
    }

    // v1.3.136：跨进程重启存活的播放状态（softPlaying/curTitle 以前是纯内存态，
    // device 进程一重启就丢，而音乐在 player 独立进程里照旧在放 ⇒ 小智"不知道歌在放"，
    // 用户问「现在放的什么 / 暂停」时模型只能答「播放列表为空」）。
    // 落盘到独立 SP（MODE_MULTI_PROCESS：寄生层跑在 device 主进程，:r1client 进程也要读）。
    private static final String PREF_PLAYER = "r1player";
    private static final String K_PLAYING = "playing";
    private static final String K_TITLE = "cur_title";
    /** v1.3.175：专辑/时长/来源随曲名一起落盘（重启后「正在播放」信息不丢）。 */
    private static final String K_ALBUM = "cur_album";
    private static final String K_DURATION = "cur_duration";
    private static final String K_VIA = "cur_via";
    private static final String K_TS = "play_ts";

    private static SharedPreferences playerPref() {
        return app.getSharedPreferences(PREF_PLAYER, Context.MODE_MULTI_PROCESS);
    }

    /** v1.3.136：记一次「开始播」（内存 + 落盘）。title 为 null 表示沿用旧曲名。 */
    private static void notePlaying(String title) {
        if (title != null && title.length() > 0) {
            curTitle = title;
            // v1.3.178 ★：曲名一换，上一首的专辑/时长/来源必须一起作废。
            //   以前这里只改 curTitle，三个「实际歌曲信息」字段留在上一次的值上，
            //   于是 random / play_artist / play_index / 切歌 这些只报曲名的路径，
            //   卡片上会一直挂着上一首在线歌的专辑 —— 用户原话：
            //   「之前放过《不能说的秘密》，现在放其他歌还是有个不能说的秘密显示在那里」。
            //   （《不能说的秘密》是专辑名，正是 curAlbum 的残留。）
            curAlbum = "";
            curDuration = "";
            curVia = "";
            curPic = "";   // v1.3.188：换曲 ⇒ 上一首的封面也必须作废（同专辑残留那个坑）
        }
        softPlaying = true;
        softFresh = true;      // v1.3.143：本进程内真播过 ⇒ 可作让路判据
        savePlayState(true);
    }

    /**
     * v1.3.175：记一次「开始播」并带上**实际歌曲信息**（专辑/时长/来源）。
     *
     * <p>池内歌：album 来自 index.json 条目（duration/pic 元数据源没有，留空）；
     * 在线歌：NAS search 返回的 album/duration 透传。
     * via = "online" 时页面显示「（在线）」角标 —— 用户要求「播的是翻唱要能看到
     * 实际信息」，这张卡片就是答案：真实歌手 + 专辑 + 时长一目了然。</p>
     */
    private static void notePlayingFull(String title, String album, String duration, String via) {
        if (title != null && title.length() > 0) {
            curTitle = title;
        }
        curAlbum = (album == null) ? "" : album;
        curDuration = (duration == null) ? "" : duration;
        curVia = (via == null) ? "" : via;
        softPlaying = true;
        softFresh = true;
        savePlayState(true);
    }

    /**
     * v1.3.178：按**曲库下标**记「正在播放」—— 曲名/专辑/时长/来源全部从 tracks
     * 那条记录里取，任何推播路径（random / play_artist / play_index / 队列跳播）
     * 都能拿到正确信息，不再依赖调用方手填。
     *
     * <p>这是「播放窗口残留上一首在线歌信息」的正解：信息跟着曲目走，
     * 而不是跟着「上一次谁写过它」走。</p>
     */
    private static void notePlayingTrack(int idx) {
        try {
            if (idx >= 0 && idx < tracks.size()) {
                String[] e = tracks.get(idx);
                // ★ curTitle 必须是**纯歌名**：replay（「再放一次刚才那首」）要靠它在
                //   库里反查，拼上歌手就查不到了；歌手另有 curArtist 字段（总线也会给）。
                if (e[0].length() > 0) {
                    curTitle = e[0];
                }
                if (e.length > 1) {
                    curArtist = e[1];
                }
                notePlayingFull(null,
                        e.length > 4 ? e[4] : "",
                        e.length > 5 ? e[5] : "",
                        e.length > 7 ? e[7] : "");
                // v1.3.188：封面跟着曲目走（搜索结果带 img；池歌/本地没有 ⇒ 异步补一次
                // /api/pic，取不到就让它空着，页面回落成转碟）。
                String p = (e.length > 6 && e[6] != null) ? e[6] : "";
                if ("null".equals(p)) p = "";
                curPic = p;
                if (p.length() == 0) {
                    fetchPicAsync(e[0], e[1]);
                }
                return;
            }
        } catch (Throwable t) {
            Log.w(TAG, "notePlayingTrack: " + t);
        }
        curAlbum = "";
        curDuration = "";
        curVia = "";
        notePlaying("");
    }

    /**
     * v1.3.178：总线回推「当前曲目」时，用**库里那条记录**补上专辑/时长/来源；
     * 曲名与歌手仍以总线给的为准（player 报的才是真正播出去的那一条）。
     *
     * <p>为什么不能直接用 {@link #notePlaying(String)}：那条为了治「残留上一首专辑」
     * 会在换曲名时清空专辑/时长/来源。而总线事件是在起播约 1 秒后必到的 ——
     * 于是刚写好的实际信息被它抹掉，卡片上又只剩一个曲名。实测就是这个现象。</p>
     */
    private static void notePlayingBus(String title, String singer, int trackIdx) {
        if (trackIdx >= 0 && trackIdx < tracks.size()) {
            String[] e = tracks.get(trackIdx);
            curAlbum = e.length > 4 ? e[4] : "";
            curDuration = e.length > 5 ? e[5] : "";
            curVia = e.length > 7 ? e[7] : "";
            /* v1.3.189：封面也必须跟着这一首走。以前这块只改曲名/歌手/专辑，
               curPic 留着上一首的 URL ⇒ status 里「曲名已经是新的、封面还是旧的」，
               要等 fetchPicAsync（约 10s）或下一次 notePlayingTrack 才纠正。
               实测切歌 3s 后 curPic 仍是上一张（页面因此挂着旧封面）。 */
            String p = (e.length > 6 && e[6] != null) ? e[6] : "";
            if ("null".equals(p)) p = "";
            curPic = p;
        } else {
            curAlbum = "";
            curDuration = "";
            curVia = "";
            curPic = "";
        }
        /* v1.3.189：库里这首没存封面 ⇒ 异步补一次（跟 notePlayingTrack 一致） */
        if (curPic.length() == 0 && curTitle.length() > 0) {
            fetchPicAsync(curTitle, curArtist);
        }
        if (title != null && title.length() > 0) {
            curTitle = title;
        }
        if (singer != null) {
            curArtist = singer;
        }
        softPlaying = true;
        softFresh = true;
        savePlayState(true);
    }

    /** v1.3.136：记一次「停/暂停」（内存 + 落盘）。 */
    private static void noteStopped() {
        softPlaying = false;
        softFresh = false;     // v1.3.143
        savePlayState(false);
    }

    private static void savePlayState(boolean playing) {
        try {
            playStateTs = System.currentTimeMillis();
            SharedPreferences.Editor e = playerPref().edit();
            e.putBoolean(K_PLAYING, playing);
            e.putString(K_TITLE, curTitle);
            e.putString(K_ALBUM, curAlbum);
            e.putString(K_DURATION, curDuration);
            e.putString(K_VIA, curVia);
            e.putLong(K_TS, playStateTs);
            e.commit();     // 同步写：force-stop / 掉电前也要留下结论
        } catch (Throwable t) {
            Log.w(TAG, "savePlayState: " + t);
        }
    }

    /**
     * v1.3.136：启动时把「上次是不是还在播」读回来 —— 解决「小智启动之前就在放歌」
     * 时小智一问三不知的问题。之后由 warm-up 的 DLNA 查询校正（真状态优先）。
     */
    private static void restorePlayState() {
        try {
            SharedPreferences sp = playerPref();
            boolean p = sp.getBoolean(K_PLAYING, false);
            curTitle = sp.getString(K_TITLE, "");
            curAlbum = sp.getString(K_ALBUM, "");
            curDuration = sp.getString(K_DURATION, "");
            curVia = sp.getString(K_VIA, "");
            playStateTs = sp.getLong(K_TS, 0);
            if (p) {
                long age = System.currentTimeMillis() - playStateTs;
                if (age > 24L * 3600L * 1000L) {
                    // 超过一天就不敢认了（多半早播完了/被别处停了），按已停止处理，
                    // 免得让路逻辑拿着过期结论去「无中生有」地恢复。
                    SessionLog.i("music play-state: 上次记录太旧（"
                            + (age / 3600000L) + "h 前），按已停止处理");
                    savePlayState(false);
                } else {
                    softPlaying = true;
                    // v1.3.143：恢复值**不算新鲜** —— 只能给 status / 小智问答显示曲名，
                    // 不能拿去让路（歌可能早播完了；让路靠唤醒瞬间 isMusicActive() 判）。
                    softFresh = false;
                    SessionLog.i("music play-state restored: 上次记录还在播「" + curTitle
                            + "」（" + (age / 1000L)
                            + "s 前，重启后内存态已丢，先按在播处理"
                            + "；★ 恢复值不作让路判据，仅用于显示）");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "restorePlayState: " + t);
        }
    }

    /** v1.3.136：给 Web 控制台 / MCP status 用的「当前曲目名」（可能为空串）。 */
    public static String curTitle() {
        return curTitle == null ? "" : curTitle;
    }

    /** v1.3.136：DLNA 报告的真状态（"" = 尚未查询）。 */
    public static String dlnaState() {
        return dlnaState == null ? "" : dlnaState;
    }

    /**
     * v1.3.137：★「到底有没有音乐在播」的真判据 —— {@code AudioManager.isMusicActive()}。
     *
     * <p>它问的是 AudioFlinger 里有没有活跃的 STREAM_MUSIC 音轨，**覆盖所有播放路径**
     * （我们推的歌单、原厂起的播放、DLNA 推送的），跟「谁起的播」无关。
     *
     * <p>为什么不再信 DLNA：2026-10-13 实测 —— 音箱明明在播《The Tradition》
     * （本地记录 + 耳朵都确认），DLNA {@code GetTransportInfo} 仍然报
     * {@code NO_MEDIA_PRESENT}、{@code GetPositionInfo} 报「第 0 首 时长 00:00:00」。
     * ⇒ **DLNA 只是外部控制通道，它的状态机跟 player 自己的播放引擎不同步**，
     * 用它判「在不在播」必然恒阴性（v1.3.133~135 把它当"真状态兜底"是设计错误）。
     *
     * <p>⚠️ 使用时机很关键：**必须在小智自己的 TTS 开播之前**问。TTS 播的也是
     * STREAM_MUSIC，一旦开播这个函数就恒 true，会把自己当成音乐。
     * 所以由 {@code MusicResume.deferPause()} 在唤醒瞬间（TTS 还没起来时）采一次。
     */
    public static boolean isMusicActive() {
        try {
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            boolean a = am != null && am.isMusicActive();
            Log.i(TAG, "isMusicActive -> " + a);
            return a;
        } catch (Throwable t) {
            Log.w(TAG, "isMusicActive failed: " + t);
            return false;
        }
    }

    private static synchronized boolean msgReady() {
        if (msgTried) {
            return msgMgr != null;
        }
        msgTried = true;
        try {
            Class<?> mm = Class.forName(
                    "com.phicomm.speaker.device.utils.PhicommMessageManager");
            msgMgr = mm.getMethod("messageCenter", Context.class).invoke(null, app);
            Log.i(TAG, "msgcenter ok: " + msgMgr);
            SessionLog.i("msgcenter ready");
        } catch (Throwable t) {
            Log.w(TAG, "msgcenter unavailable: " + t);
            SessionLog.i("msgcenter FAIL: " + t);
            msgMgr = null;
        }
        return msgMgr != null;
    }

    /** 发一条 MSG_TYPE_PLAYER 消息。json 为 null 时 obj 传 null（原厂控制指令就是这么发的）。 */
    private static boolean msg(int subType, String json) {
        return msg(subType, json, -1);
    }

    /**
     * v1.3.119：带 arg2 的版本 —— setMode(7) 要把模式号放在 arg2 里。
     * 实测 player 默认停在 mPlayMode:1（随机），说「下一首」会从第 1 个台跳到
     * 第 8 个台，对「把电台排成一张列表挨个听」来说没有意义。
     */
    private static boolean msg(int subType, String json, int arg2) {
        if (!msgReady()) {
            return false;
        }
        try {
            Object p = null;
            if (json != null) {
                Class<?> pu = Class.forName("android.os.ParcelableUtil");
                p = pu.getMethod("obtain", String.class).invoke(null, json);
            }
            msgMgr.getClass()
                    .getMethod("sendMessage", int.class, int.class, int.class,
                            android.os.Parcelable.class)
                    .invoke(msgMgr, Integer.valueOf(4), Integer.valueOf(subType),
                            Integer.valueOf(arg2), p);
            Log.i(TAG, "msg sent subType=" + subType + " arg2=" + arg2
                    + (json == null ? "" : (" len=" + json.length())));
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "msg send subType=" + subType + ": " + t);
            return false;
        }
    }

    /**
     * v1.3.119：设播放模式（subType=7，模式号走 arg2）。
     *
     * <p>★ v1.3.172 更正 —— 以前注释写的「0=顺序 1=随机」是猜的，**是错的**。
     * 反编译原厂 player 坐实的真值（{@code util/Constant}）：</p>
     * <pre>
     *   PLAY_MODE_LIST_SHUFFLE = 1   列表随机
     *   PLAY_MODE_LIST_CYCLE   = 2   列表循环（= 我们说的「顺序播放」），也是 PLAY_MODE_DEFAULT
     *   PLAY_MODE_SINGLE_CYCLE = 3   单曲循环
     * </pre>
     * 而且 {@code AbsPlayer.setPlayMode()} 开头就 {@code if (mode != 1 && != 2 && != 3)
     * → 打 "setPlayMode error, mode = N" 并 return false} —— **0 根本不收**。
     * 这就是页面上「顺序播放」以前点了没反应的根因（我们一直发 0）。
     */
    private static boolean setPlayMode(int mode) {
        // 越界一律校正成默认的列表循环，别把 0 之类的值送上去白挨一句 error
        if (mode != 1 && mode != 3) {
            mode = 2;
        }
        lastMode = mode;
        boolean ok = msg(7, null, mode);
        SessionLog.i("set play mode " + mode + "（1=随机 2=顺序 3=单曲）: "
                + (ok ? "sent" : "msgcenter FAIL"));
        return ok;
    }

    /**
     * 只放一首（play_url / 电台用）。
     *
     * v1.3.91：整体结构从「裸数组」改成 player 真正认的 PlayRequestInfo：
     *   {"index":0,"itemList":[{"itemId":"...","title":"...","url":"...","artist":"..."}]}
     * 依据：player dex 里硬编码的样例串
     *   {"index":0,"itemList":[{"itemId":"100_kw_MUSIC_15198210","title":"爱要怎么说出口"}, ...]}
     * 以及 PlaySong.toString() 的字段拼接片段（itemId/title/url/artist/duration/lyric/imgUrl）。
     * v1.3.90 发裸数组时 player 报：
     *   parsePlayRequetJson parse json error: Expected BEGIN_OBJECT but was BEGIN_ARRAY
     */
    private static String oneJson(String title, String url) {
        return oneJson(title, url, "");
    }

    private static String oneJson(String title, String url, String artist) {
        try {
            JSONArray arr = new JSONArray();
            arr.put(song("local_1", title, url, artist));
            JSONObject root = new JSONObject();
            root.put("index", 0);
            root.put("itemList", arr);
            return root.toString();
        } catch (Throwable t) {
            Log.w(TAG, "oneJson: " + t);
            return "{\"index\":0,\"itemList\":[]}";
        }
    }

    /**
     * v1.3.93：补一脚「真正开始播」。
     *
     * 现象（用户原话）：「小智好像不会播放只会换歌，播放是我在魔改 web 里点了才播的」。
     * 取证：发完 subType=1 后 player 的状态机停在
     *   MEDIA_PREPARING -> BUFFERING_END -> HandlePlayerPrepared（PlayState 7）
     * 但 **没有 MEDIA_PLAYING**，AudioFlinger 显示 `4 Tracks of which 0 are active`，
     * 并反复 `BUFFER TIMEOUT: remove(4096) from active list`（buffer underrun）。
     * ⇒ 魔改 player 收到 playSpecifiedMusic 后只「换歌 + prepare」，**不会自动 start**，
     *   必须再发一条 subType=3 (play) 才真正出声。
     *
     * 延迟是必须的：太快 player 还没 prepare 完，play 会被丢。600ms 实测够。
     */
    private static void kickPlay(final String why) {
        kickPlay(why, 600);
    }

    /**
     * @param delayMs 换歌（next/prev）要比首次播歌等更久 —— player 要重新 prepare 下一首，
     *                600ms 不够（实测会被丢），用 1200ms。
     */
    private static void kickPlay(final String why, final long delayMs) {
        kickPlay(why, delayMs, 1);
    }

    /**
     * @param times 发几次 play。换歌场景用 2 次（1200ms + 再 1000ms）—— player 换歌
     *              prepare 耗时不定，单次可能落在它还没准备好时。play 本身幂等，
     *              已经在播时重复发无副作用。
     */
    private static void kickPlay(final String why, final long delayMs, final int times) {
        new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < times; i++) {
                    try {
                        Thread.sleep(i == 0 ? delayMs : 1000);
                    } catch (Throwable t) {
                        // ignore
                    }
                    boolean ok = msg(3, null);
                    if (ok) {
                        softPlaying = true;   // v1.3.132：补发的 play 也说明在播了
                    }
                    SessionLog.i("kick play (" + why + ") #" + (i + 1) + ": "
                            + (ok ? "sent" : "msgcenter FAIL"));
                }
            }
        }, "pb-kick").start();
    }

    /** 单个 PlaySong 条目。itemId 用 100_local_MUSIC_<n> 模仿原厂酷我 id 的形态。 */
    private static JSONObject song(String itemId, String title, String url, String artist)
            throws Exception {
        JSONObject o = new JSONObject();
        o.put("itemId", itemId);
        o.put("title", title);
        o.put("url", url);
        o.put("artist", artist);
        return o;
    }

    /**
     * v1.3.113：三条「拼歌单」路径统一成**先算索引序列、再统一推送** ——
     * 减半重试、上限裁剪、日志只写一份，不用在每个 op 里各写一遍。
     *
     * 全库洗牌后的索引序列（「随便放点音乐」用）。
     */
    private static List<Integer> shuffledIdx() {
        int n = tracks.size();
        int[] a = shuffled(n);
        List<Integer> out = new ArrayList<Integer>(n);
        for (int i = 0; i < n; i++) {
            out.add(Integer.valueOf(a[i]));
        }
        return out;
    }

    /**
     * 命中的那首排第一，后面跟洗过牌的其余曲目 —— player 拿到的是**完整播放列表**，
     * 播完自动续、next/prev 也才有意义（v1.3.92 起就是这个策略，只是上限改成全库）。
     */
    private static List<Integer> hitFirstIdx(int hit) {
        List<Integer> out = new ArrayList<Integer>();
        int n = tracks.size();
        if (n == 0) {
            return out;
        }
        if (hit >= 0 && hit < n) {
            out.add(Integer.valueOf(hit));
        }
        int[] a = shuffled(n);
        for (int j = 0; j < n; j++) {
            if (a[j] != hit) {
                out.add(Integer.valueOf(a[j]));
            }
        }
        return out;
    }

    /**
     * v1.3.179：命中那首排第一，后面只跟**在线段**（[from, n)）的洗牌结果。
     *
     * <p>用途：op=play_rand_at（在在线随机批里点一首）。合并后本地曲目和在线池
     * 挤在同一份 index.json 里，全推会把本地那 1188 首也塞进 player 的列表 ——
     * 用户只是点了一首在线歌，不该顺带整理整个曲库。</p>
     */
    private static List<Integer> hitFirstIdx(int hit, int from) {
        List<Integer> out = new ArrayList<Integer>();
        int n = tracks.size();
        if (n == 0) {
            return out;
        }
        int a0 = from > 0 ? from : 0;
        if (hit >= 0 && hit < n) {
            out.add(Integer.valueOf(hit));
        }
        int[] a = shuffled(n);
        for (int j = 0; j < n; j++) {
            if (a[j] != hit && a[j] >= a0) {
                out.add(Integer.valueOf(a[j]));
            }
        }
        return out;
    }

    /**
     * v1.3.114：全库洗牌后按需只取前 cnt 首（cnt &lt;= 0 = 整个曲库）。
     * 「随机放 5 首」走的就是这里 —— 列表真的只有 5 首，播完这 5 首就结束。
     */
    private static List<Integer> randomIdx(int cnt) {
        List<Integer> idxs = shuffledIdx();
        if (cnt > 0 && idxs.size() > cnt) {
            return new ArrayList<Integer>(idxs.subList(0, cnt));
        }
        return idxs;
    }

    /** 按给定索引序列拼 playSpecifiedMusic 的 JSON（limit 条截断，从第 0 首开始播）。 */
    private static String buildJson(List<Integer> idxs, int limit) {
        return buildJson(idxs, limit, 0);
    }

    /**
     * v1.3.163：带起始下标的版本 —— {@code startIndex} 直接写进 JSON 的 "index"，
     * player 收到后应从这一首开始播（队列内跳转，不用把目标挪到第一再重推）。
     *
     * <p>⚠️ 魔改 player 实测不认 index>0（仍从第 0 首开始），所以 {@link #playAt(int)}
     * 里做了兜底：跳完 1.2s 后用总线回推的曲名校验，不一致就把目标顶到第一重推一次。</p>
     */
    private static String buildJson(List<Integer> idxs, int limit, int startIndex) {
        JSONArray arr = new JSONArray();
        try {
            int n = tracks.size();
            for (int j = 0; j < idxs.size() && arr.length() < limit; j++) {
                int i = idxs.get(j).intValue();
                if (i < 0 || i >= n) {
                    continue;
                }
                String[] e = tracks.get(i);
                arr.put(song("100_local_MUSIC_" + i, e[0], urlOf(e),
                        (e.length > 1) ? e[1] : ""));
            }
            JSONObject root = new JSONObject();
            root.put("index", startIndex);
            root.put("itemList", arr);
            return root.toString();
        } catch (Throwable t) {
            Log.w(TAG, "buildJson: " + t);
        }
        return "{\"index\":0,\"itemList\":[]}";
    }

    /**
     * v1.3.119：电台也能拼成一张播放列表（「把所有电台加进播放列表」）。
     *
     * 电台按 radios.json 的顺序排（不洗牌 —— 顺序是可预期的，用户心里有数）；
     * start >= 0 时把那个台顶到第一（命中台优先，跟歌曲的 hitFirstIdx 一个思路）。
     */
    private static List<Integer> radioIdx(int start) {
        int n = radios.size();
        List<Integer> out = new ArrayList<Integer>(n);
        if (start >= 0 && start < n) {
            out.add(Integer.valueOf(start));
        }
        for (int i = 0; i < n; i++) {
            if (i != start) {
                out.add(Integer.valueOf(i));
            }
        }
        return out;
    }

    /** 电台版 buildJson（v1.3.119）：条目来自 radios 而不是 tracks。 */
    private static String buildRadioJson(List<Integer> idxs, int limit) {
        return buildRadioJson(idxs, limit, 0);
    }

    /** v1.3.163：电台版带起始下标。 */
    private static String buildRadioJson(List<Integer> idxs, int limit, int startIndex) {
        JSONArray arr = new JSONArray();
        try {
            int n = radios.size();
            for (int j = 0; j < idxs.size() && arr.length() < limit; j++) {
                int i = idxs.get(j).intValue();
                if (i < 0 || i >= n) {
                    continue;
                }
                String[] r = radios.get(i);
                arr.put(song("100_radio_" + i, r[0], r[1], "电台"));
            }
            JSONObject root = new JSONObject();
            root.put("index", startIndex);
            root.put("itemList", arr);
            return root.toString();
        } catch (Throwable t) {
            Log.w(TAG, "buildRadioJson: " + t);
        }
        return "{\"index\":0,\"itemList\":[]}";
    }

    /**
     * 把歌单推进 player（subType=1）。@return **实际推进去的条数**，0 = 失败。
     *
     * v1.3.113：全库上千首的 JSON 有上百 KB。Binder 事务缓冲是**每进程 1MB 共享**的
     * （不是一次事务独占），内存紧张/并发事务多时 writeValue 会失败。这里做
     * **减半重试**：第一次推不进去就砍一半再推，最多 4 次 —— 宁可少推几首，
     * 也不能一首都推不进去（v1.3.112 及以前推不动就直接返回 ERR）。
     *
     * v1.3.119：抽出 radio 参数，歌曲和电台共用这套减半重试。
     */
    private static int pushSongs(String why, List<Integer> idxs) {
        return pushPlaylist(why, idxs, false);
    }

    /** v1.3.119：把所有电台排进 player 自己的播放列表。 */
    private static int pushRadios(String why, int start) {
        return pushPlaylist(why, radioIdx(start), true);
    }

    /**
     * v1.3.136：从 DLNA {@code GetPositionInfo} 的 {@code TrackMetaData}
     * （DIDL-Lite 片段）里抠出 {@code <dc:title>}。
     */
    private static String didlTitle(String md) {
        if (md == null || md.length() == 0) {
            return "";
        }
        try {
            int a = md.indexOf("<dc:title");
            if (a < 0) {
                return "";
            }
            a = md.indexOf('>', a);
            int b = md.indexOf("</dc:title>", a < 0 ? 0 : a);
            if (a < 0 || b < 0) {
                return "";
            }
            String t = md.substring(a + 1, b).trim();
            if (t.startsWith("<![CDATA[") && t.endsWith("]]>")) {
                t = t.substring(9, t.length() - 3).trim();
            }
            t = t.replace("&amp;", "&").replace("&lt;", "<")
                    .replace("&gt;", ">").replace("&quot;", "\"")
                    .replace("&apos;", "'");
            return t;
        } catch (Throwable t) {
            return "";
        }
    }

    /** v1.3.136：播放列表第一首的曲名（电台取台名）—— 记为「当前曲目」。 */
    private static String firstTitleOf(List<Integer> idxs, int lim, boolean radio) {
        return nthTitleOf(idxs, lim, radio, 0);
    }

    /** v1.3.163：播放列表第 n 首（0-based）的曲名。 */
    private static String nthTitleOf(List<Integer> idxs, int lim, boolean radio, int n) {
        try {
            if (lim > 0 && !idxs.isEmpty() && n >= 0 && n < idxs.size() && n < lim) {
                int i = idxs.get(n).intValue();
                if (radio) {
                    if (i >= 0 && i < radios.size()) {
                        return radios.get(i)[0];
                    }
                } else if (i >= 0 && i < tracks.size()) {
                    return tracks.get(i)[0];
                }
            }
        } catch (Throwable t) {
            // ignore
        }
        return "";
    }

    private static int pushPlaylist(String why, List<Integer> idxs, boolean radio) {
        return pushPlaylist(why, idxs, radio, 0, 1);
    }

    /** v1.3.163：带起始下标（队列内跳转用）。 */
    private static int pushPlaylist(String why, List<Integer> idxs, boolean radio,
            int startIndex) {
        return pushPlaylist(why, idxs, radio, startIndex, 1);
    }

    /**
     * v1.3.166：subType 可选。
     *
     * <p>★ 原厂 {@code MsgHandler.handlePlayRequest()} 的开关（反编译实证）：
     * <pre>
     *   case 1:  case 15:                       // 两者共用 JSON 解析
     *       PlayRequestInfo pri = parse(json);
     *       boolean startSelf = true;
     *       if (method == 1) { startSelf = false; pri.setIndex(0); }   // ← 1 会把起始位写死清零
     *       mEasyPlayer.play(pri, startSelf);
     *   case 11: ... playIndex((Integer) obj)                          // 按位置播，列表不动
     * </pre>
     * 所以：**subType=1 传 index 永远是白搭**（v1.3.163 切歌失败的根因），
     * 而 **subType=15 保留 JSON 里的 index** ⇒ 列表原样重推 + index=N，
     * player 就播第 N 首，**队列顺序一点不变**（= 小飞版的行为）。</p>
     */
    private static int pushPlaylist(String why, List<Integer> idxs, boolean radio,
            int startIndex, int subType) {
        if (!msgReady()) {
            return 0;
        }
        int lim = Math.min(idxs.size(), maxPlaylist());
        if (lim <= 0) {
            return 0;
        }
        for (int attempt = 0; attempt < 4; attempt++) {
            String json = radio ? buildRadioJson(idxs, lim, startIndex)
                    : buildJson(idxs, lim, startIndex);
            if (msg(subType, json)) {
                // v1.3.163：把「player 手里的列表」记在自己这边 —— 8080 WS 没了以后，
                // web 的队列就靠它渲染（顺序、曲名、下标全是我们自己 push 时定好的）。
                synchronized (lastIdx) {
                    lastIdx.clear();
                    for (int j = 0; j < idxs.size() && j < lim; j++) {
                        lastIdx.add(idxs.get(j));
                    }
                }
                lastIsRadio = radio;
                curIdx = (startIndex >= 0 && startIndex < lim) ? startIndex : -1;
                SessionLog.i("push playlist (" + why + "): " + lim
                        + (radio ? " stations" : " tracks") + ", subType=" + subType
                        + ", json " + json.length() + "B"
                        + (attempt > 0 ? (" (retry#" + attempt + ")") : ""));
                // v1.3.136：记下当前曲目（列表第一首）—— 落盘后进程重启也报得出来
                // v1.3.163：startIndex>0 时当前曲目是那一首，不是第一首
                // v1.3.178 ★：改走 notePlayingTrack —— 专辑/时长/来源随曲目一起取，
                //   不再只记一个曲名（只记曲名 = 上一首在线歌的专辑一直挂在卡片上）。
                int at = (startIndex > 0 && startIndex < lim) ? startIndex : 0;
                if (radio || at >= idxs.size()) {
                    notePlaying(nthTitleOf(idxs, lim, radio, at));
                } else {
                    notePlayingTrack(idxs.get(at).intValue());
                }
                return lim;
            }
            if (lim <= 1) {
                break;
            }
            lim = (lim + 1) / 2;
            SessionLog.i("push playlist (" + why + ") FAILED -> halve to " + lim);
        }
        return 0;
    }

    /**
     * 推的歌越多，player 解析 JSON + 建列表越久，首次 kick play 的等待要相应加长，
     * 否则 play 落在它还没 prepare 完的时候会被丢（表现为「点了不出声」）。
     */
    private static long startDelay(int n) {
        return n > 300 ? 1500 : 600;
    }

    /**
     * v1.3.157：口语噪声词（整串删除）。只删**多字短语**，绝不删「听 / 放 / 来 / 点 /
     * 歌」这类单字 —— 它们大量出现在歌名里（听海、放生、来生缘、丁香花），删了会误伤。
     */
    private static final String[] NOISE_PHRASE = {
            "我想听", "我要听", "我听听", "来一首", "来首", "来一曲", "放一首", "放首", "放一曲",
            "给我放", "帮我放", "给我播", "帮我播", "请播放", "请给我放", "播放一首", "播放",
            "点播", "换首", "换一首", "切首", "一首", "这首歌", "那首歌", "这首歌叫什么",
            "请", "帮我", "给我", "呗", "吧", "啊", "呀",
    };

    /** v1.3.157：切出来之后整个丢弃的噪声 token */
    private static final String[] NOISE_TOKEN = { "歌", "歌曲", "音乐", "首", "个", "一下", "听", "放", "来", "点" };

    /**
     * v1.3.157：把「播放周杰伦的晴天」切成 {@code ["周杰伦","晴天"]}。
     *
     * 原实现是**整串子串匹配**：{@code artist.contains(key) || title.contains(key)}。
     * 于是「周杰伦的晴天」必然 0 命中 —— 库里 title=晴天、artist=周杰伦，没有任何一个
     * 字段包含「周杰伦的晴天」这个整串。而「歌手 + 歌名」恰恰是语音点歌最常见的说法，
     * 表现为「说全了反而找不到，只说歌名却能播」。
     *
     * 这里：① 剥口语噪声 → ② 按分隔符切词 → ③ 丢噪声 token。
     */
    private static List<String> splitKeys(String raw) {
        List<String> out = new ArrayList<String>();
        String s = String.valueOf(raw).trim().toLowerCase();
        for (int i = 0; i < NOISE_PHRASE.length; i++) {
            s = s.replace(NOISE_PHRASE[i], " ");
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == '，' || c == ',' || c == '、'
                    || c == '；' || c == ';' || c == '/' || c == '|' || c == '-' || c == '—'
                    || c == '的' || c == '（' || c == '(' || c == '）' || c == ')') {
                c = ' ';
            }
            sb.append(c);
        }
        String[] parts = sb.toString().split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            String t = parts[i].trim();
            if (t.length() == 0) {
                continue;
            }
            boolean noise = false;
            for (int j = 0; j < NOISE_TOKEN.length; j++) {
                if (t.equals(NOISE_TOKEN[j])) {
                    noise = true;
                    break;
                }
            }
            if (!noise) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * v1.3.157：单个关键词在一首歌上的匹配分，**越小越像**。
     * 0=歌名全等 1=歌手全等 2=歌名包含 3=歌手包含 -1=没命中。
     * 排序的意义：库里同名曲很多（原唱 / 翻唱 / live / DJ），原来取「库里第一条含该词的」，
     * 排在最前面的经常是翻唱版；按匹配度排之后，第一条才是最像用户说的那一首。
     */
    private static int keyScore(String[] t, String key, boolean artistOnly) {
        String ti = (t[0] == null) ? "" : t[0].toLowerCase();
        String ar = (t.length > 1 && t[1] != null) ? t[1].toLowerCase() : "";
        if (!artistOnly && ti.equals(key)) {
            return 0;
        }
        if (ar.equals(key)) {
            return 1;
        }
        if (!artistOnly && ti.indexOf(key) >= 0) {
            return 2;
        }
        if (ar.indexOf(key) >= 0) {
            return 3;
        }
        return -1;
    }

    /**
     * v1.3.157：把「还有哪些同名候选」拼成一句给模型看的话。
     *
     * 同名曲太多了（原唱 / 翻唱 / Live / DJ / 伴奏），播的只能是「最像的那一条」，
     * 但用户未必想要它。把带序号的候选回给模型，小智才有机会说
     * 「我还找到周杰伦版，要听吗」，而不是闷声播了翻唱还假装是用户点的。
     * 只有 1 条命中就返回空串 —— 别在正常情况下啰嗦。
     */
    private static String altHint(List<Integer> hits, int playing) {
        if (hits == null || hits.size() <= 1) {
            return "";
        }
        StringBuilder sb = new StringBuilder("（库里还有 " + (hits.size() - 1) + " 首同名的：");
        int shown = 0;
        for (int j = 0; j < hits.size() && shown < 3; j++) {
            int i = hits.get(j).intValue();
            if (i == playing) {
                continue;
            }
            String[] e = tracks.get(i);
            if (shown > 0) {
                sb.append("、");
            }
            sb.append(i + 1).append(".").append(e[0] == null ? "" : e[0]);
            if (e.length > 1 && e[1] != null && e[1].length() > 0) {
                sb.append("(").append(e[1]).append(")");
            }
            shown++;
        }
        if (shown == 0) {
            return "";
        }
        sb.append("；要换一首请说「播放第 N 首」）");
        return sb.toString();
    }

    /**
     * v1.3.177：本地这条「最接近的」到底够不够准？——**决定要不要改用在线结果**。
     *
     * <p>背景：以前 {@code hits.size() > 0} 就直接播池里最像的那条。而 matchTracks
     * 为了「不漏」会把 OR 的最好一档也留下来（哪怕只有一个词命中），于是用户语音说
     * 「播放稻香」时，池里一首只是歌名里含同一个词的《稻香 (伴奏)》或同名 Live 版
     * 就会被当成命中 ⇒ 播出来的是「库里最接近的」，而不是用户点的那首。</p>
     *
     * <p>现在的要求：**每个关键词都必须命中，且至少一个词是「全等」级**
     * （{@link #keyScore} 0=歌名全等 / 1=歌手全等）。只包含级、或部分词命中，
     * 都算「不够准」 ⇒ 交给 NAS 全源在线搜一遍，拿到了就播在线那条，
     * 拿不到再回落到这条池内结果（保证始终有声）。</p>
     */
    private static boolean localHitSolid(String k, int idx) {
        try {
            String key = String.valueOf(k).trim().toLowerCase();
            if (key.length() == 0) {
                return true;
            }
            if (idx < 0 || idx >= tracks.size()) {
                return false;
            }
            List<String> keys = splitKeys(key);
            if (keys.size() == 0) {
                return true;      // 全是噪声 ⇒ 与整串包含等价，认了
            }
            String[] t = tracks.get(idx);
            boolean exact = false;
            for (int i = 0; i < keys.size(); i++) {
                int sc = keyScore(t, keys.get(i), false);
                if (sc < 0) {
                    return false; // 有关键词没命中 ⇒ 就是「最接近」而已，不是它
                }
                if (sc <= 1) {
                    exact = true; // 歌名/歌手全等
                }
            }
            return exact;
        } catch (Throwable t) {
            return true;          // 判不了就按老行为播，别把用户的路堵死
        }
    }

    /**
     * v1.3.112：返回**所有**命中的曲目索引（不再是 v1.3.111 那样 break 取第一条）。
     *
     * 取第一条是「随机放某个歌手的歌」「库里第几首」做不了的根因 —— 上层拿不到候选集，
     * 只能播第一首然后被随机补足成全库混合。
     *
     * v1.3.157：改成**分词 AND 匹配 + 按匹配度排序**（见 {@link #splitKeys}）。
     * 三条保护，确保「变聪明」不会反过来变笨：
     *  · AND 全落空 ⇒ 回退 OR（至少命中一个词），按「命中词数多的在前」排；
     *  · 切不出关键词（用户说的全是噪声）⇒ 退化成原来的整串包含匹配；
     *  · 同分时保持**库内原序**（稳定），行为可预测。
     *
     * @param artistOnly true 只匹配歌手字段（play_artist 首选），false 歌名+歌手都匹配
     */
    private static List<Integer> matchTracks(String k, boolean artistOnly) {
        List<Integer> out = new ArrayList<Integer>();
        if (k == null) {
            return out;
        }
        String key = k.trim().toLowerCase();
        if (key.length() == 0) {
            return out;
        }
        List<String> keys = splitKeys(key);
        if (keys.size() == 0) {
            keys = null;   // 全是噪声 ⇒ 退回整串包含匹配
        }

        // keys == null（用户说的全是噪声）⇒ 走原来的整串包含匹配，直接返回
        if (keys == null) {
            for (int i = 0; i < tracks.size(); i++) {
                String[] t = tracks.get(i);
                String ti = (t[0] == null) ? "" : t[0].toLowerCase();
                String ar = (t.length > 1 && t[1] != null) ? t[1].toLowerCase() : "";
                if (ar.indexOf(key) >= 0 || (!artistOnly && ti.indexOf(key) >= 0)) {
                    out.add(Integer.valueOf(i));
                }
            }
            return out;
        }

        // 一次遍历：每首歌记下「命中了几个词」「匹配分之和」
        final List<Integer> idx = new ArrayList<Integer>();
        final List<Integer> hit = new ArrayList<Integer>();
        final List<Integer> sum = new ArrayList<Integer>();
        int best = 0;
        for (int i = 0; i < tracks.size(); i++) {
            String[] t = tracks.get(i);
            int h = 0, s = 0;
            for (int j = 0; j < keys.size(); j++) {
                int one = keyScore(t, keys.get(j), artistOnly);
                if (one >= 0) {
                    h++;
                    s += one;
                }
            }
            if (h == 0) {
                continue;
            }
            idx.add(Integer.valueOf(i));
            hit.add(Integer.valueOf(h));
            sum.add(Integer.valueOf(s));
            if (h > best) {
                best = h;
            }
        }
        if (idx.size() == 0) {
            return out;
        }

        // 只留「命中词数最多」的一档：AND 全命中时就是它们；否则自动退成 OR 的最好一档
        final int need = best;
        List<Integer> pos = new ArrayList<Integer>();
        for (int i = 0; i < idx.size(); i++) {
            if (hit.get(i).intValue() == need) {
                pos.add(Integer.valueOf(i));
            }
        }
        Collections.sort(pos, new Comparator<Integer>() {
            public int compare(Integer a, Integer b) {
                int sa = sum.get(a.intValue()).intValue();
                int sb = sum.get(b.intValue()).intValue();
                if (sa != sb) {
                    return sa < sb ? -1 : 1;
                }
                int ia = idx.get(a.intValue()).intValue();
                int ib = idx.get(b.intValue()).intValue();
                return ia < ib ? -1 : (ia == ib ? 0 : 1);   // 同分 ⇒ 库内原序，行为可预测
            }
        });
        for (int i = 0; i < pos.size(); i++) {
            out.add(idx.get(pos.get(i).intValue()));
        }
        return out;
    }

    /**
     * v1.3.112：歌手统计（按曲目数降序）。
     * 用户不会凭空知道库里有没有周杰伦 —— list 必须把可点的歌手报出来，
     * 否则「随机放某个歌手的歌」这个需求在模型侧无从下手。
     *
     * <p>v1.3.182：新增 {@code localOnly} —— 在线歌播过就会 append 进 tracks，
     * 「本地歌曲里有什么」必须只统计本地，否则用户关了本地曲库还能听到
     * 「本地一共有五百四十七首」。</p>
     */
    private static String artistSummary(int maxArtists, boolean localOnly) {
        LinkedHashMap<String, Integer> cnt = new LinkedHashMap<String, Integer>();
        for (String[] t : tracks) {
            if (localOnly && isOnlineTrack(t)) {
                continue;
            }
            String a = (t.length > 1 && t[1] != null) ? t[1].trim() : "";
            if (a.length() == 0) {
                continue;
            }
            Integer c = cnt.get(a);
            cnt.put(a, Integer.valueOf((c == null) ? 1 : (c.intValue() + 1)));
        }
        List<String[]> rows = new ArrayList<String[]>();
        for (Map.Entry<String, Integer> e : cnt.entrySet()) {
            rows.add(new String[]{e.getKey(), String.valueOf(e.getValue().intValue())});
        }
        Collections.sort(rows, new Comparator<String[]>() {
            public int compare(String[] a, String[] b) {
                return Integer.parseInt(b[1]) - Integer.parseInt(a[1]);
            }
        });
        StringBuilder sb = new StringBuilder();
        int m = Math.min(rows.size(), maxArtists);
        for (int i = 0; i < m; i++) {
            sb.append(rows.get(i)[0]).append("(").append(rows.get(i)[1]).append(")、");
        }
        if (rows.size() > m) {
            sb.append("等共 ").append(rows.size()).append(" 位歌手");
        }
        return sb.toString();
    }

    /** v1.3.182：这条是不是在线收录进来的（via 第 8 位非空 = online/rand）。 */
    private static boolean isOnlineTrack(String[] e) {
        return e != null && e.length > 7 && e[7] != null && e[7].length() > 0;
    }

    private static int[] shuffled(int n) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = i;
        }
        Random r = new Random(System.currentTimeMillis());
        for (int i = n - 1; i > 0; i--) {
            int j = r.nextInt(i + 1);
            int t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
        return a;
    }

    /** 在 XzBootstrap 装配完成后调用；失败不影响小智主链路。 */
    public static synchronized boolean init(Context ctx) {
        app = (ctx == null) ? null : ctx.getApplicationContext();
        try {
            File f = new File(app.getFilesDir(), "music_index");
            if (f.exists()) {
                String s = readFile(f).trim();
                if (s.length() > 0) {
                    setIndexSpec(s);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "music_index flag read: " + t);
        }
        Log.i(TAG, "player bridge init, index=" + indexUrl);
        SessionLog.i("player bridge ready, index=" + indexUrl);
        // v1.3.90 调试开关：files/dbg_play 里写一个曲名，进程启动后自动播一次。
        // 用途：不用喊小智也能端到端验证「索引 -> msgcenter/DLNA -> 出声」整条链。
        // v1.3.95：**读完立刻删**（一次性）。以前要手动 rm，忘了就会导致每次开机自动播。
        String dbg = "";
        try {
            File f = new File(app.getFilesDir(), "dbg_play");
            if (f.exists()) {
                dbg = readFile(f).trim();
                try {
                    f.delete();
                } catch (Throwable t2) {
                    Log.w(TAG, "dbg_play unlink: " + t2);
                }
                Log.w(TAG, "dbg_play consumed once: " + dbg);
            }
        } catch (Throwable t) {
            Log.w(TAG, "dbg_play read: " + t);
        }
        final String dbgTitle = dbg;

        new Thread(new Runnable() {
            public void run() {
                // v1.3.136：先把「上次是不是还在播」从磁盘读回来 —— 这一步不依赖 DLNA，
                // 越早越好。音乐活在 player 进程里，跟我们的死活无关：小智重启后
                // 内存态全丢，以前只能答「播放列表为空」。
                restorePlayState();
                loadIndex();
                // 预热原厂消息总线（主通道）
                msgReady();
                // 预热：先探到 DLNA 端口，首次 tools/call 就不用等发现
                if (discover()) {
                    SessionLog.i("dlna warm up ok: " + avtUrl);
                    // v1.3.133/136：softPlaying 是内存态，重启即丢 —— 而音乐在 player
                    // 独立进程里继续放。启动时查一次真状态补上，否则 TTS 让路判据全假。
                    // v1.3.136：改用「带结果」版 —— 没在播也要把 DLNA 原话打出来
                    // （取证用：DLNA 到底看不看得见 player 的播放）。
                    queryPlayingStateAsync(new StateCallback() {
                        public void onResult(boolean playing) {
                            SessionLog.i("music warm-up: DLNA=" + dlnaState
                                    + (playing
                                        ? " ⇒ 探测到播放中"
                                        : " ⇒ 没在播（保留本地记录："
                                            + (softPlaying ? "在播" : "未在播") + "）"));
                        }
                    });
                } else {
                    SessionLog.i("dlna warm up FAIL (will retry on call)");
                }
                if (dbgTitle.length() > 0) {
                    try {
                        Thread.sleep(3000);
                    } catch (Throwable t) {
                        // ignore
                    }
                    JSONObject a = new JSONObject();
                    try {
                        a.put("title", dbgTitle);
                    } catch (Throwable t) {
                        // ignore
                    }
                    String r = dispatch("play_song", a);
                    SessionLog.i("dbg_play '" + dbgTitle + "' -> " + r);
                }
            }
        }, "pb-index").start();
        return true;
    }

    public static boolean isReady() {
        return true;
    }

    // ---------------- v1.3.99：web 控制台接口 ----------------

    /** 运行时状态（供 /api/status 展示） */
    private static final int STREAM_MUSIC = 3;

    /**
     * v1.3.100：直接改系统 STREAM_MUSIC 音量（player 播的就是这条流）。
     * 用反射写，省掉对 android.media.AudioManager 的编译期依赖。
     *
     * @return null 表示成功，否则返回失败原因（调用方据此回退到 DLNA）。
     */
    private static String setSystemVolume(int lv0to100) {
        Context c = app;
        if (c == null) {
            return "app context is null";
        }
        try {
            Object am = c.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                return "AudioManager is null";
            }
            Class<?> cls = Class.forName("android.media.AudioManager");
            int max = ((Integer) cls.getMethod("getStreamMaxVolume", int.class)
                    .invoke(am, Integer.valueOf(STREAM_MUSIC))).intValue();
            if (max <= 0) {
                return "max volume=" + max;
            }
            int target = (int) (lv0to100 * max / 100.0f + 0.5f);
            cls.getMethod("setStreamVolume", int.class, int.class, int.class)
                    .invoke(am, Integer.valueOf(STREAM_MUSIC),
                            Integer.valueOf(target), Integer.valueOf(0));
            Log.i(TAG, "volume: set music stream " + target + "/" + max
                    + " (req " + lv0to100 + "%, readback " + readSystemVolume() + "%)");
            return null;
        } catch (Throwable t) {
            return String.valueOf(t);
        }
    }

    /** 读回系统音乐流音量（0~100）。失败返回 -1。 */
    private static int readSystemVolume() {
        Context c = app;
        if (c == null) {
            return -1;
        }
        try {
            Object am = c.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                return -1;
            }
            Class<?> cls = Class.forName("android.media.AudioManager");
            int max = ((Integer) cls.getMethod("getStreamMaxVolume", int.class)
                    .invoke(am, Integer.valueOf(STREAM_MUSIC))).intValue();
            int cur = ((Integer) cls.getMethod("getStreamVolume", int.class)
                    .invoke(am, Integer.valueOf(STREAM_MUSIC))).intValue();
            if (max <= 0) {
                return -1;
            }
            return (int) (cur * 100.0f / max + 0.5f);
        } catch (Throwable t) {
            return -1;
        }
    }

    // ---------------- v1.3.163：自有队列（8080 没了以后 web 的数据源） ----------------

    /** 当前歌手（总线回推才有；以前只能拿到曲名）。 */
    public static String curArtist() {
        return curArtist;
    }

    /** 当前曲目在自有队列中的位置（0-based，-1 = 未知）。 */
    public static int curIdx() {
        return curIdx;
    }

    /** 最近一次收到总线曲目事件的时间戳（0 = 从未收到；用来判断「通道活着没」）。 */
    public static long busSongTs() {
        return busSongTs;
    }

    /**
     * player 通过 USER_DATA 总线（what=33554432, arg1=1）回推的「当前曲目」。
     *
     * <p>val 形如 {@code [{"singer":"周杰伦","songName":"晴天",
     * "music_id":"100_local_MUSIC_1149","album":"",...}]}。
     * 切歌（我们发的 next、player 播完自动下一首、语音点歌）都会推一次，
     * 比 8080 的 get_info 轮询更实时，而且**原厂 player 也有**。</p>
     */
    public static void onBusSong(String singer, String title, String musicId) {
        if (title == null || title.length() == 0) {
            return;
        }
        curTitle = title;
        curArtist = (singer == null) ? "" : singer;
        busSongTs = System.currentTimeMillis();
        int k = indexOfMusicId(musicId);
        int ti = -1;
        if (k >= 0) {
            curIdx = k;
            // 队列位置 → 库内下标（itemId 里那份 n 就是 tracks 的下标）
            synchronized (lastIdx) {
                if (k < lastIdx.size()) {
                    ti = lastIdx.get(k).intValue();
                }
            }
        }
        // v1.3.178：走 notePlayingBus —— 专辑/时长/来源从库里那条补上，
        // 而不是被 notePlaying 的「换曲名就清空」给抹掉。
        notePlayingBus(title, singer, ti);
        Log.i(TAG, "bus song: " + title
                + (curArtist.length() > 0 ? (" - " + curArtist) : "")
                + " (queueIdx=" + curIdx + ", id=" + musicId + ")");
        SessionLog.i("now playing: " + title
                + (curArtist.length() > 0 ? (" - " + curArtist) : ""));
    }

    /**
     * 从 music_id（{@code 100_local_MUSIC_<n>} / {@code 100_radio_<n>}）反查它在
     * 自有队列里的位置。itemId 是 push 时我们自己拼的，n 就是库内下标。
     */
    private static int indexOfMusicId(String id) {
        if (id == null) {
            return -1;
        }
        int p = id.lastIndexOf('_');
        if (p < 0) {
            return -1;
        }
        int n;
        try {
            n = Integer.parseInt(id.substring(p + 1).trim());
        } catch (Throwable t) {
            return -1;
        }
        synchronized (lastIdx) {
            for (int i = 0; i < lastIdx.size(); i++) {
                if (lastIdx.get(i).intValue() == n) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * 自有队列（我们最后一次 push 进 player 的那张列表）。
     *
     * @return {@code {total, cur, radio, ts, list:[{i, title, artist}]}}，i 为 0-based 队列位置
     */
    public static JSONObject queueJson(int limit) {
        JSONObject o = new JSONObject();
        try {
            List<Integer> snap;
            synchronized (lastIdx) {
                snap = new ArrayList<Integer>(lastIdx);
            }
            boolean radio = lastIsRadio;
            o.put("total", snap.size());
            o.put("cur", curIdx);
            o.put("radio", radio);
            o.put("bus", busSongTs);
            JSONArray arr = new JSONArray();
            int lim = (limit <= 0) ? snap.size() : Math.min(snap.size(), limit);
            for (int j = 0; j < lim; j++) {
                int i = snap.get(j).intValue();
                String t = null;
                String a = "";
                if (radio) {
                    if (i >= 0 && i < radios.size()) {
                        t = radios.get(i)[0];
                        a = "电台";
                    }
                } else if (i >= 0 && i < tracks.size()) {
                    String[] e = tracks.get(i);
                    t = e[0];
                    a = (e.length > 1) ? e[1] : "";
                }
                if (t == null) {
                    continue;
                }
                JSONObject it = new JSONObject();
                it.put("i", j);
                it.put("title", t);
                it.put("artist", a);
                // v1.3.175：实际歌曲信息（数组中后三个位置）
                if (!radio && i >= 0 && i < tracks.size()) {
                    String[] e = tracks.get(i);
                    it.put("album", e.length > 4 ? e[4] : "");
                    it.put("duration", e.length > 5 ? e[5] : "");
                }
                arr.put(it);
            }
            o.put("list", arr);
        } catch (Throwable t) {
            Log.w(TAG, "queue json: " + t);
        }
        return o;
    }

    /**
     * 跳到自有队列第 i 首（0-based）—— 8080 WS 的 {@code {"type":"play","index":i}} 的替代品。
     *
     * <p>★ v1.3.164 实测修正：原厂 player（EchoService）**完全不理 JSON 的 "index"** ——
     * 收到 playSpecified（总线 subType=1）后一律 {@code setCurrentTrack: play position = 0}，
     * 也就是永远从 itemList[0] 开始播（设备日志连推三次表，三次都播第一首）。
     * 所以这里不再指望 index，而是和 play_index 一个路子：**把目标顶到列表第一，整表重推**
     * （其余保持相对顺序）。推完 player 自然从头播 = 目标那一首。</p>
     *
     * <p>★★ v1.3.166：扒了原厂 {@code MsgHandler.handlePlayRequest()} 才发现，锅不在
     * player 不认 index，而在 **subType=1 的代码里写死了 {@code pri.setIndex(0)}**。
     * 同一个 switch 里的 **subType=15 不清零** ⇒ 改发 15 + 原顺序列表 + index=i，
     * player 直接播第 i 首，**队列顺序完全不变**（小飞版就是这么做的）。
     * 兜底：1.8s 后总线回推的下标不对 ⇒ 退回 v1.3.164 的 rotate（已验证有效）。</p>
     */
    public static String playAt(int i) {
        List<Integer> snap;
        synchronized (lastIdx) {
            snap = new ArrayList<Integer>(lastIdx);
        }
        if (snap.isEmpty()) {
            return "ERR 队列是空的（先在音乐页选一首或点随机播放）";
        }
        if (i < 0 || i >= snap.size()) {
            return "ERR 序号超出范围，队列共 " + snap.size() + " 首";
        }
        final boolean radio = lastIsRadio;
        // v1.3.166：首选 subType=15（列表原样 + index=i ⇒ 顺序不变）
        int n = pushPlaylist("play_at15#" + i, snap, radio, i, 15);
        if (n <= 0) {
            return "ERR 推送失败（消息总线不可用，player 起来了吗？）";
        }
        pushed = true;
        String who = nthTitleOf(snap, n, radio, i);
        final int want = i;
        final List<Integer> snap2 = snap;
        final long ts0 = busSongTs;      // 发送前的回推时间戳
        // 兜底：1.8s 后总线回推的下标不是目标 ⇒ subType=15 这版 player 不认，退回 rotate。
        // ⚠ 没收到任何回推时**不折腾**（可能只是 player 这次没推），免得打断已经播对的曲子。
        new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(1800);
                } catch (Throwable t) {
                    // ignore
                }
                if (busSongTs == ts0 || curIdx == want) {
                    return;
                }
                SessionLog.i("play_at: subType=15 未生效 (cur=" + curIdx
                        + " want=" + want + ") -> 退回 rotate 重推");
                List<Integer> re = new ArrayList<Integer>(snap2.size());
                re.add(snap2.get(want));
                for (int j = 0; j < snap2.size(); j++) {
                    if (j != want) {
                        re.add(snap2.get(j));
                    }
                }
                int n2 = pushPlaylist("play_at_rotate#" + want, re, radio, 0, 1);
                if (n2 > 0) {
                    kickPlay("play_at_rotate", startDelay(n2), 2);
                }
            }
        }, "pb-playat").start();
        SessionLog.i("play_at #" + (i + 1) + " (subType=15, 顺序不变): " + who
                + " (list=" + n + ")");
        return "OK 播放队列第 " + (i + 1) + " 首：" + who;
    }

    public static JSONObject statusJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("ready", idxLoaded);
            o.put("tracks", tracks.size());
            o.put("radios", radios.size());
            o.put("indexUrl", indexUrl);
            // v1.3.102：多源。indexUrls = 配置的全部源；sources = 每个源的曲目/电台/错误
            JSONArray iu = new JSONArray();
            List<String> iuSnap;
            synchronized (indexUrls) {
                iuSnap = new ArrayList<String>(indexUrls);
            }
            for (int i = 0; i < iuSnap.size(); i++) {
                iu.put(iuSnap.get(i));
            }
            o.put("indexUrls", iu);
            // v1.3.117：控制台要按电台名出按钮（点了直接试播，用来验证到底出不出声）
            JSONArray rn = new JSONArray();
            for (int i = 0; i < radios.size(); i++) {
                JSONObject rj = new JSONObject();
                rj.put("name", radios.get(i)[0]);
                rn.put(rj);
            }
            o.put("radioList", rn);
            JSONArray ss = new JSONArray();
            for (int i = 0; i < srcStats.size(); i++) {
                String[] s = srcStats.get(i);
                JSONObject j = new JSONObject();
                j.put("url", s[0]);
                j.put("tracks", s[1]);
                j.put("radios", s[2]);
                j.put("err", s[3]);
                ss.put(j);
            }
            o.put("sources", ss);
            o.put("dlnaPort", dlnaPort);
            o.put("pushed", pushed);
            // v1.3.136：控制台/排错要看的「到底在不在播」—— 内存软状态 + DLNA 真状态 +
            // 当前曲目（重启后从磁盘恢复的）
            o.put("playing", softPlaying);
            o.put("dlnaState", dlnaState());
            o.put("curTitle", curTitle());
            o.put("playTs", playStateTs);
            // v1.3.163：自有队列摘要 + 总线曲名（8080 不可用时 web 就靠这些活）
            o.put("curArtist", curArtist());
            // v1.3.175：实际歌曲信息（专辑/时长/来源）——播翻唱/DJ 版时用户要看这些
            o.put("curAlbum", curAlbum);
            o.put("curDuration", curDuration);
            o.put("curVia", curVia);
            // v1.3.188：封面 URL（页面直接当 <img src>；空 = 回落成转碟）
            o.put("curPic", curPic);
            // v1.3.178：在线随机池规模（排障：0 = 还没拉过 / NAS 拉不动）
            o.put("randPool", randPool.size());
            o.put("onlineAppends", onlineAppends);
            o.put("curIdx", curIdx());
            o.put("busTs", busSongTs());
            o.put("qTotal", lastIdx.size());
            o.put("qRadio", lastIsRadio);
            // v1.3.172：播放模式回显（1=随机 2=顺序 3=单曲循环），页面 seg 据此高亮
            o.put("mode", lastMode);
            int v = readSystemVolume();
            o.put("volume", v >= 0 ? v : lastVolume);
        } catch (Throwable t) {
            Log.w(TAG, "status json: " + t);
        }
        return o;
    }

    /**
     * 改音乐索引地址并立即重拉（web 控制台「保存」用）。
     * 同时落 files/music_index，下次启动仍生效。
     *
     * v1.3.102：接受**多行**（每行一个 index.json 的 URL），也可以一行里用逗号/空格分隔。
     * 多份索引的曲目合并成一个库 —— 这就是「NAS 本地 + 公网源」同时可用的实现方式。
     */
    public static void setIndexUrl(String url) {
        if (url == null) {
            return;
        }
        // v1.3.116：清空 = 恢复默认地址，不再「什么都不做」（原来那样点了保存没反应，
        // 用户会以为没删掉）。默认地址还是会被加回去，但至少状态是明确一致的。
        if (url.trim().length() == 0) {
            url = DEFAULT_INDEX_URL;
        }
        setIndexSpec(url);
        try {
            File f = new File(app.getFilesDir(), "music_index");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f, false);
            fos.write(url.trim().getBytes("UTF-8"));
            fos.close();
        } catch (Throwable t) {
            Log.w(TAG, "music_index write: " + t);
        }
        reloadIndex();
    }

    /**
     * v1.3.102：解析索引地址文本（换行 / 逗号 / 空格都当分隔符，# 开头是注释）。
     * indexUrl 保留第一份，仅用于旧日志与状态回显兼容。
     */
    // v1.3.116：这里**不要**用 static synchronized —— 那把锁是 PlayerBridge.class，
    // 而 loadIndex()（static synchronized）会长时间占着它（拉索引、解析上千首）。
    // 一旦某个源卡住，控制台「保存音乐源」就会跟着排队等锁，表现就是「删不掉」。
    // 改成只在 indexUrls 上同步，和 loadIndex 彻底解耦。
    private static void setIndexSpec(String spec) {
        synchronized (indexUrls) {
        indexUrls.clear();
        String[] lines = spec.split("[\n\r,;]+");
        for (int i = 0; i < lines.length; i++) {
            String u = lines[i].trim();
            if (u.length() == 0 || u.startsWith("#")) {
                continue;
            }
            if (!u.startsWith("http")) {
                continue;
            }
            if (!indexUrls.contains(u)) {
                indexUrls.add(u);
            }
        }
        if (indexUrls.size() == 0) {
            indexUrls.add(DEFAULT_INDEX_URL);
        }
        indexUrl = indexUrls.get(0);
        }
    }

    /** v1.3.116：当前配了几个音乐源（控制台回显用）。 */
    public static int indexUrlCount() {
        synchronized (indexUrls) {
            return indexUrls.size();
        }
    }

    /** 后台重拉索引（loadIndex 是 synchronized，与MCP调用互斥） */
    public static void reloadIndex() {
        new Thread(new Runnable() {
            public void run() {
                loadIndex();
                SessionLog.i("web: reload music index -> " + indexUrl);
            }
        }, "pb-reload").start();
    }

    // ---------------- 索引 ----------------

    /**
     * v1.3.102：多源加载。逐个拉 indexUrls，曲目/电台**追加**进同一个库。
     *
     * 设计要点：
     *  · 单源失败不影响其它源（公网源挂了，NAS 本地照常能放）；
     *  · 每条 track 在进入库之前就把最终 URL 算好（绝对 url 优先，否则按该源 base 拼），
     *    所以库里不存在「属于哪个 base」的歧义；
     *  · srcStats 记录每个源的成败与条目数，web 控制台按源显示，排障不用翻日志。
     */
    private static synchronized void loadIndex() {
        List<String[]> nt = new ArrayList<String[]>();
        List<String[]> nr = new ArrayList<String[]>();
        // v1.3.179：本地段（onlineFrom 之前的那些），单独攒一份
        List<String[]> nl = new ArrayList<String[]>();
        List<String[]> stats = new ArrayList<String[]>();
        StringBuilder errs = new StringBuilder();
        boolean anyOk = false;

        if (indexUrls.size() == 0) {
            setIndexSpec(DEFAULT_INDEX_URL);
        }
        // v1.3.116：先快照，别在循环里一直抓着 indexUrls
        List<String> urls;
        synchronized (indexUrls) {
            urls = new ArrayList<String>(indexUrls);
        }
        for (int i = 0; i < urls.size(); i++) {
            String url = urls.get(i);
            try {
                // v1.3.116：4MB / 10s 双上限，绝不让它把 loadIndex 拖死
                String s = httpGet(url, 6000, 4 * 1024 * 1024, 10000);
                JSONObject o = new JSONObject(s);
                String srcBase = o.optString("base", "");
                int addT = 0;
                int addR = 0;
                JSONArray ta = o.optJSONArray("tracks");
                if (ta != null) {
                    // v1.3.179 ★：合并后的 index.json 一肩挑「本地 + 在线」，
                    //   用 onlineFrom 标出在线段从哪开始。本地那一段另存一份
                    //   （{@link #localTracks}），随机在线换批时它原样留着。
                    int cut = o.has("onlineFrom") ? o.optInt("onlineFrom", 0) : -1;
                    if (cut < 0) {
                        // 老服务端没这个字段：按 source 标记猜。
                        //   mini-lx = 整份都是在线池（可以整批换掉）；
                        //   其它（原 r1-music 等）= 整份都是本地库，保护起来别丢。
                        cut = "mini-lx".equals(o.optString("source", "")) ? 0 : ta.length();
                    }
                    for (int k = 0; k < ta.length(); k++) {
                        JSONObject t = ta.getJSONObject(k);
                        String file = t.optString("file", "");
                        // 绝对 url 字段（公网源用）优先，否则按本源 base 拼
                        String abs = t.optString("url", "");
                        if (abs == null || abs.length() == 0) {
                            abs = joinUrl(srcBase, file);
                        }
                        // v1.3.175：String[7] = [0]title [1]artist [2]file [3]url
                        // [4]album [5]duration [6]pic。后三个是「实际歌曲信息」：
                        // 池里带的 album 直接用；在线搜的歌由 playOnlineItem 填。
                        // v1.3.178：String[8] —— [7]via。在线搜/在线随机拉来的填 "online"
                        //   （见 appendOnlineTracks）。
                        // v1.3.183：★ 在线池段（k >= cut）标 "pool" —— 以前池歌
                        //   via=""，统计/搜索的「本地」口径把它们全算了进去：
                        //   用户关着本地曲库，小智还说「本地一共有五百四十七首」
                        //   （547 = 100 首池歌 + 447 首在线 append，真正的本地是 0）。
                        //   ★ 用 "pool" 而不是 "online"：显示端只认 online/rand，
                        //   pool 不出 pill（与改前观感一致）；而「本地」口径的判断
                        //   是「via 非空 = 非本地」，pool 一样被挡在外面。
                        //   播放不受 via 影响；localTracks 另存本地段，random_online 不变。
                        String via = (k >= cut) ? "pool" : "";
                        nt.add(new String[]{
                                t.optString("title", ""),
                                t.optString("artist", ""),
                                file,
                                abs,
                                t.optString("album", ""),
                                t.optString("duration", ""),
                                jstr(t, "pic"),
                                via});
                        if (k < cut) {
                            nl.add(nt.get(nt.size() - 1));
                        }
                        addT++;
                    }
                }
                JSONArray ra = o.optJSONArray("radios");
                if (ra != null) {
                    for (int k = 0; k < ra.length(); k++) {
                        JSONObject r = ra.getJSONObject(k);
                        nr.add(new String[]{r.optString("name", ""), r.optString("url", "")});
                        addR++;
                    }
                }
                stats.add(new String[]{url, String.valueOf(addT), String.valueOf(addR), ""});
                anyOk = true;
                Log.i(TAG, "index[" + i + "] ok: " + url + " -> " + addT + " tracks, "
                        + addR + " radios");
            } catch (Throwable t) {
                String e = String.valueOf(t);
                stats.add(new String[]{url, "0", "0", e});
                if (errs.length() > 0) {
                    errs.append(" | ");
                }
                errs.append(url).append(": ").append(e);
                Log.w(TAG, "index[" + i + "] FAIL " + url + ": " + t);
            }
        }

        tracks.clear();
        tracks.addAll(nt);
        synchronized (localTracks) {
            localTracks.clear();
            localTracks.addAll(nl);
        }
        onlineAppends = 0;      // v1.3.178：整表重建 ⇒ 在线 append 的账也归零
        radios.clear();
        radios.addAll(nr);
        srcStats.clear();
        srcStats.addAll(stats);
        idxLoaded = anyOk;
        idxErr = errs.toString();
        if (anyOk) {
            SessionLog.i("music index loaded: " + tracks.size() + " tracks, "
                    + radios.size() + " radios (from " + urls.size() + " source(s)"
                    + (idxErr.length() > 0 ? "; failed: " + idxErr : "") + ")");
        } else {
            SessionLog.i("music index FAIL (all sources): " + idxErr);
        }
    }

    private static synchronized String[] find(String q) {
        String k = (q == null ? "" : q.trim().toLowerCase());
        if (k.length() == 0) {
            return null;
        }
        for (String[] t : tracks) {
            if (t[0].toLowerCase().contains(k) || t[1].toLowerCase().contains(k)) {
                return t;
            }
        }
        for (String[] r : radios) {
            if (r[0].toLowerCase().contains(k)) {
                return r;
            }
        }
        return null;
    }

    /**
     * v1.3.174：库外在线点歌 —— 曲库 index.json 是启动时拉的快照，快照外的歌
     * （新歌 / 没进曲库池的歌）以前 miss 后只能 ERR。现在 miss 时问 NAS mini-lx
     * 在线搜一把：
     * <pre>
     *   GET {base}/api/search?q=..&amp;n=5&amp;source=all  → {"list":[{title,artist,source,rid}]}
     *   GET {base}/api/url?rid=..&amp;source=..&amp;q=128k     → {"url":"https://..."}
     * </pre>
     * base 从 {@link #indexUrl} 现场推导（全局 {@code base} 字段是死字段，没人赋值）。
     * 搜到的歌 append 进 {@link #tracks}（四元组第 [3] 位放绝对 URL，urlOf 认），
     * 再走和库里歌完全相同的 pushSongs 推播 ⇒ 队列可见、能切歌、reloadIndex 自动清。
     * <p>全程 try/catch + 返回 null 兜底：任何一步失败调用方照原路 ERR，
     * 零副作用（loadIndex 失败也不会把 tracks 弄脏——append 只发生在拿到 URL 之后）。</p>
     *
     * @return OK 话术；在线链路不可用返回 {@code null}（调用方维持原有 ERR）
     */
    /**
     * v1.3.175：NAS mini-lx 基址推导（{@code http://host:port}，无尾斜杠）。
     *
     * <p>来源：全局 {@link #base} 是死字段（没人赋值，实测恒空），
     * 真正可靠的是 {@link #indexUrl} —— 去掉末段 {@code /index.json}。
     * 在线搜索 / 取直链 / 曲库索引都打这一个基址。</p>
     *
     * @return 基址；indexUrl 也没拉到时返回 {@code null}
     */
    private static String nasBase() {
        String b = (base != null && base.length() > 0) ? base : "";
        if (b.length() == 0) {
            String iu = indexUrl;
            if (iu == null || iu.length() == 0) {
                return null;
            }
            int cut = iu.lastIndexOf('/');
            if (cut <= 7) {           // "http://x" 都没走到，没得拼
                return null;
            }
            b = iu.substring(0, cut);
        }
        return b;
    }

    /**
     * v1.3.175：在线搜歌（不打本地曲库）——打 NAS mini-lx {@code /api/search?source=all}。
     *
     * <p>给 web「在线搜歌」按钮用：池子里没有的歌（新歌/冷门歌）从这里出，
     * 每条带 {@code rid}/{@code source}，点播放时喂给 {@link #playOnlineItem}。</p>
     *
     * @return {@code {total, list:[{title,artist,source,rid}]}}；任何一步失败返回 {@code null}
     */
    public static JSONObject onlineSearchJson(String q, int lim) {
        try {
            if (q == null || q.trim().length() == 0) {
                return null;
            }
            String b = nasBase();
            if (b == null) {
                return null;
            }
            // v1.3.176：8s 对「歌手名」这类多源聚合不够（kw/wy/kg 都要跑一遍），
            //   结果就变成点了在线搜歌却返回空 ⇒ 提到 15s。网页侧反代给了 25s。
            String js = httpGet(b + "/api/search?q=" + URLEncoder.encode(q.trim(), "UTF-8")
                    + "&n=" + (lim > 0 ? lim : 10) + "&source=all", 15000);
            if (js == null || js.length() == 0) {
                return null;
            }
            JSONObject src = new JSONObject(js);
            JSONArray list = src.optJSONArray("list");
            if (list == null || list.length() == 0) {
                return null;
            }
            JSONObject o = new JSONObject();
            JSONArray out = new JSONArray();
            for (int i = 0; i < list.length(); i++) {
                JSONObject s = list.optJSONObject(i);
                if (s == null) {
                    continue;
                }
                String title = s.optString("title", "");
                String artist = s.optString("artist", "");
                String source = s.optString("source", "");
                String rid = s.optString("rid", "");
                if (source.length() == 0 || rid.length() == 0) {
                    continue;
                }
                JSONObject it = new JSONObject();
                it.put("title", title);
                it.put("artist", artist);
                it.put("source", source);
                it.put("rid", rid);
                // v1.3.175：实际歌曲信息透传（kw 源本来就有；没有的源留空）
                it.put("album", s.optString("album", ""));
                it.put("duration", s.optString("duration", ""));
                out.put(it);
                // v1.3.176：NAS 那边不认 n=（一次甩过来上百条），为了手机上能翻
                //   得动，这里自己截。total 依旧用 NAS 给的原始命中数。
                if (lim > 0 && out.length() >= lim) {
                    break;
                }
            }
            o.put("total", src.optInt("total", out.length()));
            o.put("list", out);
            return o;
        } catch (Throwable t) {
            SessionLog.i("online search 「" + q + "」 failed: " + t);
            return null;
        }
    }

    /**
     * v1.3.175：播放一条**指定的在线条目**（web 在线搜索结果点播放用）。
     *
     * <p>从 {@link #playOnlineSong(String)} 抽出「拿链 + append + 推播」部分，
     * 区别只是不再自己搜 —— 四件套（title/artist/source/rid）由界面给。
     * append 进 tracks ⇒ 队列可见、能上下曲；刷新页面重拉 index 时自动清掉。</p>
     *
     * <p>v1.3.175：album/duration 来自 NAS search 的实际元数据 —— 播的是翻唱/DJ 版时，
     * 「正在播放」卡片显示真实歌手 + 专辑 + 时长，用户要求的「显示实际歌曲信息」。</p>
     */
    public static String playOnlineItem(String title, String artist, String source, String rid,
                                        String album, String duration) {
        return playOnlineItem(title, artist, source, rid, album, duration, 15000);
    }

    /** v1.3.177：{@code urlMs} = 取链超时。语音链路（本地还有兜底）给短一点的预算。 */
    public static String playOnlineItem(String title, String artist, String source, String rid,
                                        String album, String duration, int urlMs) {
        try {
            if ((title == null || title.length() == 0) && (rid == null || rid.length() == 0)) {
                return null;
            }
            String b = nasBase();
            if (b == null || source == null || source.length() == 0 || rid == null || rid.length() == 0) {
                return null;
            }
            String uj = httpGet(b + "/api/url?rid=" + URLEncoder.encode(rid, "UTF-8")
                    + "&source=" + URLEncoder.encode(source, "UTF-8")
                    + "&q=128k&title=" + URLEncoder.encode(title == null ? "" : title, "UTF-8")
                    + "&artist=" + URLEncoder.encode(artist == null ? "" : artist, "UTF-8"), urlMs);
            String url = (uj == null || uj.length() == 0)
                    ? "" : new JSONObject(uj).optString("url", "");
            // v1.3.179 ★：单曲也别让用户干等——取链超时就改推**惰性地址**。
            //   取链是「五级兜底串起来」，各级超时 12s，上游抽风时能卡好几秒；
            //   而惰性地址现在只是登记，等 player 真开口要这首时 NAS 才取
            //   （那时只有它一首，慢也只慢它自己，不会拖住整批）。
            boolean lazy = false;
            if (url.length() == 0 && lazyOk(b)) {
                url = b + "/r/" + source + "/" + rid
                        + "?title=" + enc(title) + "&artist=" + enc(artist);
                lazy = true;
                SessionLog.i("play online item: 取链超时，改推惰性地址 " + url);
            }
            if (url.length() == 0) {
                return null;
            }
            String who = (artist != null && artist.length() > 0)
                    ? (title + " - " + artist) : title;
            // v1.3.175：String[7] —— [4]album [5]duration [6]pic，实际歌曲信息随曲目进队列
            // v1.3.178：String[8] —— [7]via="online"（来源跟着曲目走，切歌/重启都不会串）
            String[] item = new String[]{title == null ? "" : title,
                    artist == null ? "" : artist, "", url,
                    album == null ? "" : album, duration == null ? "" : duration, "", "online"};
            int idx;
            synchronized (tracks) {
                tracks.add(item);
                idx = tracks.size() - 1;
            }
            List<Integer> one = new ArrayList<Integer>();
            one.add(Integer.valueOf(idx));
            int n = pushSongs("play_online", one);
            SessionLog.i("play online item: 「" + who + "」 " + url + " push=" + n);
            // v1.3.179：惰性地址 = 「马上能开口，但何时出声看 NAS 那一下」
            //   ⇒ 话术要说清，别让用户以为已经响了。
            String tail = lazy ? "（在线，起播时缓冲一下）" : "（在线搜到的）";
            if (n > 0) {
                pushed = true;
                kickPlay("play_online", startDelay(n), 2);
                // v1.3.175：落实际歌曲信息（via=online ⇒ 页面显示「在线」角标）
                notePlayingFull(who, album, duration, "online");
                return "OK 正在播放 " + who + tail;
            }
            // 推不了列表（player 没起）⇒ DLNA 单条直落
            String r = setUri(url);
            if (r.startsWith("OK")) {
                notePlayingFull(who, album, duration, "online");
                return "OK 正在播放 " + who + tail;
            }
            return null;
        } catch (Throwable t) {
            SessionLog.i("play online item failed: " + t);
            return null;
        }
    }

    /**
     * v1.3.174：曲库快照 / 电台都没中 ⇒ 去 NAS mini-lx 在线搜一把（语音链路兜底）。
     *
     * <p>v1.3.175：base 推导抽去 {@link #nasBase()}；搜到第一条后直接调
     * {@link #playOnlineItem}，不再各写一遍。</p>
     */
    public static String playOnlineSong(String q) {
        // v1.3.179：取链预算 15s → 8s。取链是五级兜底串起来的，拿不到时
        //   15s 只是让用户多站 7 秒；8s 拿不到就交给惰性地址（见 playOnlineItem）。
        return playOnlineSong(q, 8000, 8000);
    }

    /**
     * v1.3.177：带预算版。{@code searchMs} = 搜歌超时，{@code urlMs} = 取链超时。
     *
     * <p>语音链路用时故意给小（4s + 8s）：这次是「本地已经有一条最接近的，
     * 只是不确定准不准」的情形，在线是加分项而不是必经之路 —— NAS 慢了就立刻
     * 回落本地，别让用户在音箱前干等十几秒。</p>
     */
    public static String playOnlineSong(String q, int searchMs, int urlMs) {
        try {
            if (q == null || q.trim().length() == 0) {
                return null;
            }
            String b = nasBase();
            if (b == null) {
                return null;
            }
            // ① 在线搜（source=all = mini-lx 全源聚合搜）
            String js = httpGet(b + "/api/search?q=" + URLEncoder.encode(q, "UTF-8")
                    + "&n=5&source=all", searchMs);
            if (js == null || js.length() == 0) {
                return null;
            }
            JSONArray list = new JSONObject(js).optJSONArray("list");
            if (list == null || list.length() == 0) {
                return null;
            }
            JSONObject s0 = list.getJSONObject(0);
            String title = s0.optString("title", q);
            String artist = s0.optString("artist", "");
            String source = s0.optString("source", "");
            String rid = s0.optString("rid", "");
            if (source.length() == 0 || rid.length() == 0) {
                return null;
            }
            // ② 拿链 + ③ 塞 tracks + 推播（与库内歌同一条路，队列可见、能上下曲）
            // v1.3.175：album/duration 一起带（实际歌曲信息）
            return playOnlineItem(title, artist, source, rid,
                    s0.optString("album", ""), s0.optString("duration", ""), urlMs);
        } catch (Throwable t) {
            SessionLog.i("play online 「" + q + "」 failed: " + t);
            return null;
        }
    }

    // ---------------------------------------------------------------------------
    // v1.3.178：在线随机池 + 在线批量取链
    //   —— 「随机播放在线音乐」和「歌手连播也要包含在线」这两件事的公共底座。
    // ---------------------------------------------------------------------------

    /** 最近一次从 NAS 拉的在线随机曲库（op=play_rand_at 按下标取用）。 */
    private static final List<String[]> randPool = new ArrayList<String[]>();

    /**
     * v1.3.179：**本地曲库段**的快照（一份曲目记录的引用，不复制内容）。
     *
     * <p>由来：mini-lx 和 r1-music 合并之后，一个 index.json 里同时装着
     * 本地曲目和在线随机池（本地在前，用 {@code onlineFrom} 标出分界）。
     * 而「随机播放在线音乐」的做法是<b>整批换掉曲库</b> —— 不把本地段单独留一份，
     * 一句话就会把本地那一千多首冲没了。</p>
     */
    private static final List<String[]> localTracks = new ArrayList<String[]>();

    /** v1.3.179：本地曲库段的快照（随机在线换批时用它打底）。 */
    public static List<String[]> localTracksSnapshot() {
        synchronized (localTracks) {
            return new ArrayList<String[]>(localTracks);
        }
    }

    /**
     * v1.3.179：只推**在线段**（[from, size)）—— 本地那几百上千首留在库里做存档，
     * 不跟着塞进 player 的列表（用户要的是「播放在线音乐」，不是「把全库洗一遍」）。
     */
    private static List<Integer> shuffledIdx(int from) {
        int n = tracks.size();
        int a = from > 0 ? from : 0;
        if (a >= n) {
            a = 0;
        }
        int[] arr = shuffled(n);
        List<Integer> out = new ArrayList<Integer>();
        for (int i = 0; i < n; i++) {
            if (arr[i] >= a) {
                out.add(Integer.valueOf(arr[i]));
            }
        }
        return out;
    }

    /**
     * 从 NAS mini-lx 拉一批**在线随机曲库**。
     *
     * <p>为什么这条路走得通：mini-lx 的 {@code /index.json?n=300&fresh=1} 在服务端
     * 重洗一批牌（{@code fresh=1}），每条的 url 是 {@code /s/<i>} 这种**惰性地址** ——
     * 真正播放那一刻才由 NAS 现场取直链并转发。所以：
     * <ul>
     *   <li>拉 300 首只要 1 次 HTTP（实测 0.45s / 42KB），**不需要为每一首单独取链**；</li>
     *   <li>几十小时就过期的网易/酷狗直链对 R1 永远是新鲜的；</li>
     *   <li>{@code /s/<i>} 用的是池内绝对下标，我们抽哪一批都不会错位。</li>
     * </ul>
     * 我们在这 300 首里再随机挑 {@code n} 首 ⇒ 每次都是不同的一批。</p>
     *
     * @param n 要几首（<=0 = 100）
     * @param fresh true = 让 NAS 重新洗牌
     * @return String[8] 列表（[7]="online"）；失败返回 null
     */
    private static List<String[]> fetchOnlinePool(int n, boolean fresh) {
        try {
            String b = nasBase();
            if (b == null) {
                return null;
            }
            int want = n > 0 ? n : 100;
            // 多拉一些再自己抽：mini-lx 的 n 上限 300，池容量 400
            int ask = Math.min(300, Math.max(want, 100));
            // v1.3.179 ★★：打 /online.json（**只吐在线池**那条路）。
            //   合并之后 /index.json 里混着本地曲目，再拿它当「在线随机」的源，
            //   本地那一千多首就会被当成在线歌塞回列表。
            JSONObject o = null;
            // ★ 这个 GET 必须自己吞异常：httpGet 见到非 200 是 **throw**（不是返回 null），
            //   老 mini-lx 根本没有 /online.json 这条路由 ⇒ 404 ⇒ 直接抛到外层 catch
            //   ⇒ 下面那句「回落 /index.json」永远走不到 ⇒ 在线随机恒等于「拉不到」。
            //   （v1.3.179 实测：旧 NAS 上 random_online 一直回落本地，就是栽在这）
            try {
                String s = httpGet(b + "/online.json?n=" + ask + (fresh ? "&fresh=1" : ""),
                        15000, 4 * 1024 * 1024, 20000);
                if (s != null && s.length() > 0) {
                    JSONObject t0 = new JSONObject(s);
                    // 必须是「有 tracks 数组」的应答才算数：
                    // 万一上游回了个 {"error":"not found"} 之类的非空 200，
                    // 只看非空就会把它当成有效响应，然后一首都拿不到。
                    if (t0.has("tracks")) {
                        o = t0;
                    }
                }
            } catch (Throwable ignored) {
            }
            if (o == null) {
                // 老 mini-lx 没有 /online.json（那时它本来就只吐在线池）⇒ 回落
                String s = httpGet(b + "/index.json?n=" + ask + (fresh ? "&fresh=1" : ""),
                        15000, 4 * 1024 * 1024, 20000);
                o = new JSONObject(s);
            }
            // Web 上把「在线音乐库」关掉了 ⇒ 这里就是空的，按「拉不到」处理
            //   （op=random_online 会因此回落本地曲库，与页面开关的口径一致）
            if (o.optBoolean("off", false)) {
                SessionLog.i("online pool: NAS 侧已关闭在线音乐库");
                return null;
            }
            JSONArray ta = o.optJSONArray("tracks");
            if (ta == null || ta.length() == 0) {
                return null;
            }
            String sb = o.optString("base", "");
            List<String[]> all = new ArrayList<String[]>();
            for (int k = 0; k < ta.length(); k++) {
                JSONObject t = ta.getJSONObject(k);
                String file = t.optString("file", "");
                String abs = t.optString("url", "");
                if (abs == null || abs.length() == 0) {
                    abs = joinUrl(sb, file);
                }
                if (abs.length() == 0) {
                    continue;
                }
                // [7]via = "rand"：在线随机批（与「在线搜到的」区分开，
                //   卡片上分别显示「在线随机」/「在线搜到的」，别让用户以为是搜索结果）
                all.add(new String[]{t.optString("title", ""), t.optString("artist", ""),
                        file, abs, t.optString("album", ""), t.optString("duration", ""),
                        jstr(t, "pic"), "rand"});
            }
            if (all.size() == 0) {
                return null;
            }
            if (all.size() <= want) {
                return all;
            }
            int[] a = shuffled(all.size());
            List<String[]> out = new ArrayList<String[]>(want);
            for (int i = 0; i < want; i++) {
                out.add(all.get(a[i]));
            }
            return out;
        } catch (Throwable t) {
            SessionLog.i("online pool fetch failed: " + t);
            return null;
        }
    }

    /**
     * v1.3.178：拉**新的一批**在线随机曲库并缓存（{@code fresh=1} = 每次都换一批）。
     *
     * @return 缓存内容；失败返回 null（调用方据此回落本地曲库）
     */
    public static List<String[]> refreshRandPool(int n) {
        List<String[]> p = fetchOnlinePool(n, true);
        if (p == null || p.size() == 0) {
            return null;
        }
        synchronized (randPool) {
            randPool.clear();
            randPool.addAll(p);
        }
        return p;
    }

    /** v1.3.178：当前缓存的在线随机曲库（快照；可能为空）。 */
    public static List<String[]> randPoolSnapshot() {
        synchronized (randPool) {
            return new ArrayList<String[]>(randPool);
        }
    }

    /**
     * v1.3.178：给 web 用的「在线随机一批」（{@code /api/search?random=1}）。
     *
     * <p>每条带 {@code ri} = 在这批里的下标 —— 页面点哪一条，就把这个 ri 原样回传
     * {@code op=play_rand_at}，服务端据此从同一批里取，不用重新拉一次。</p>
     */
    public static JSONObject randPoolJson(int n) {
        JSONObject o = new JSONObject();
        try {
            List<String[]> p = refreshRandPool(n);
            JSONArray arr = new JSONArray();
            if (p != null) {
                for (int i = 0; i < p.size(); i++) {
                    String[] e = p.get(i);
                    JSONObject it = new JSONObject();
                    it.put("ri", i);
                    it.put("title", e[0]);
                    it.put("artist", e[1]);
                    it.put("album", e.length > 4 ? e[4] : "");
                    it.put("duration", e.length > 5 ? e[5] : "");
                    arr.put(it);
                }
            }
            o.put("total", arr.length());
            o.put("list", arr);
            o.put("random", true);
        } catch (Throwable t) {
            SessionLog.i("randPoolJson: " + t);
        }
        return o;
    }

    /**
     * v1.3.178：把一批在线曲目 append 进 {@link #tracks}，返回它们在 tracks 里的下标。
     *
     * <p>为什么必须 append：{@code pushSongs} 只认 tracks 下标，播出去的每一条都得
     * 在库里有位置（队列可见、能上下曲）。代价是库会慢慢变大 —— 用
     * {@link #ONLINE_APPEND_CAP} 兜底：v1.3.186 起，装不下就**回收最早收录的在线
     * append**（只删 via=online/rand 的尾部条目，本地/池段下标不动），回收量按
     * 「本次缺口」算 ⇒ 一批能全装下，且额度永不卡死。</p>
     */
    private static List<Integer> appendOnlineTracks(List<String[]> items) {
        List<Integer> out = new ArrayList<Integer>();
        if (items == null || items.size() == 0) {
            return out;
        }
        synchronized (tracks) {
            // v1.3.186 ★：判据从「已超额」改成「本次装不下」。
            //   只判 onlineAppends > CAP 会留个半死的边界：额度恰好停在 400 时不回收，
            //   而下面的循环 400<=400 只加 1 条就到 401 ⇒ 用户点「周杰伦」只播 1 首，
            //   下一次再回收 200 ⇒ 199 首 / 1 首交替。按「本次能不能全装下」判就平稳了。
            int need = onlineAppends + items.size() - ONLINE_APPEND_CAP;
            if (need > 0) {
                // 一次多回收一点（至少半个额度）摊销成本，但必须覆盖本次缺口
                int freed = evictOldestOnline(Math.max(ONLINE_APPEND_CAP / 2, need));
                // 计数以库里真实条目为准 —— loadIndex 重建过 / 有些 append 不是
                // online/rand 时，counter 会和实际条目漂移，重数一遍最稳。
                onlineAppends = countOnlineAppends();
                SessionLog.i("online append 额度不够（在库 " + onlineAppends + " + 本次 "
                        + items.size() + " > " + ONLINE_APPEND_CAP
                        + "）→ 回收最早收录的 " + freed + " 条在线歌");
            }
            int reused = 0;
            int added = 0;
            for (int i = 0; i < items.size(); i++) {
                String[] it = items.get(i);
                if (it == null) {
                    continue;
                }
                // v1.3.187 ★ 入库去重：在线段里已经有「同名 + 同歌手」的就复用它的
                //   下标，不再重复入库。两个直接好处：
                //   ① 反复搜同一个歌手不再推高额度 —— 那才是额度被吃光的真正来源
                //      （昨天 247+154+… 一天就干掉 400，大部分是重复的同一批歌）；
                //   ② 跨源同曲（kw/kg 各来一遍）合并成一条，队列里不会出现同一首
                //      歌连播两遍。额度不消耗、tracks 不增长，语义完全不变。
                int ex = findSameOnline(it);
                if (ex >= 0) {
                    Integer k = Integer.valueOf(ex);
                    if (!out.contains(k)) {
                        out.add(k);
                    }
                    reused++;
                    continue;
                }
                if (onlineAppends >= ONLINE_APPEND_CAP) {
                    break;
                }
                tracks.add(it);
                out.add(Integer.valueOf(tracks.size() - 1));
                onlineAppends++;
                added++;
            }
            if (reused > 0) {
                SessionLog.i("online append 去重：复用库里已有 " + reused
                        + " 条，本次新入库 " + added + " 条");
            }
            if (out.size() == 0) {
                SessionLog.i("online append 装不进 0 条：在库 " + onlineAppends
                        + " 条，库里已无可回收的在线条目");
            }
        }
        return out;
    }

    /**
     * v1.3.186：删掉**最早收录**的在线 append 条目，腾额度。
     *
     * <p>只认 {@code via=online/rand}（真 append 的在线歌）：它们全部位于
     * 「本地段 + 池段」之后的**尾部、按收录顺序排列** ⇒ 从头扫等于删最老的，
     * 本地段与 pool 段的下标一个不动，调用方手里已算好的命中下标不受影响。
     * player 队列持条目值拷贝 ⇒ 回收不影响正在播；被回收的歌下次照样能搜到。</p>
     *
     * @return 实际删掉几条
     */
    private static int evictOldestOnline(int target) {
        int freed = 0;
        for (int i = 0; i < tracks.size() && freed < target; ) {
            String[] e = tracks.get(i);
            if (e != null && e.length > 7 && e[7] != null
                    && ("online".equals(e[7]) || "rand".equals(e[7]))) {
                tracks.remove(i);
                freed++;
            } else {
                i++;
            }
        }
        return freed;
    }

    /** v1.3.186：数一遍库里现存的在线 append 条数（让额度计数与真实条目一致）。 */
    private static int countOnlineAppends() {
        int n = 0;
        for (int i = 0; i < tracks.size(); i++) {
            String[] e = tracks.get(i);
            if (e != null && e.length > 7 && e[7] != null
                    && ("online".equals(e[7]) || "rand".equals(e[7]))) {
                n++;
            }
        }
        return n;
    }

    /**
     * v1.3.187：在**在线段**（via 非空：online/rand/pool）里找「同名 + 同歌手」的
     * 已有条目，返回其下标；没有返回 -1。
     *
     * <p>只在线段里找，不碰本地段：本地那首是本地文件，若把它当「在线搜到的」
     * 复用，via 语义、额度计数、以及 play_artist 的「本地 N 首 + 在线 M 首」文案
     * 都会串。</p>
     */
    private static int findSameOnline(String[] it) {
        if (it == null || it.length < 2) {
            return -1;
        }
        String t = songKey(it[0]);
        if (t.length() == 0) {
            return -1;
        }
        String a = songKey(it[1]);
        for (int i = 0; i < tracks.size(); i++) {
            String[] e = tracks.get(i);
            if (e == null || e.length < 8 || e[7] == null || e[7].length() == 0) {
                continue;
            }
            if (t.equals(songKey(e[0])) && a.equals(songKey(e[1]))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * v1.3.187：曲名/歌手归一 —— 去空白 + 转小写。英文歌名大小写与空格不统一
     * （"Shake It Off" / "shakeitoff" 同源），中文不受影响。
     */
    /**
     * v1.3.188：取 JSON 字符串，**把 JSON null 归一成空串**。
     *
     * <p>API22 的 {@code JSONObject.optString(k, def)} 不做 NULL 判断：值为
     * {@code null} 时返回**字面量 "null"**（不是 def）。线上就是这个坑 ——
     * NAS 的 {@code img:null} 进来后封面 URL 变成了字符串 "null"，页面
     * {@code <img src="null">} 直接请求本站 /null。凡是可能为空的字段都走这里。</p>
     */
    private static String jstr(JSONObject o, String k) {
        if (o == null) {
            return "";
        }
        String v = o.optString(k, "");
        return (v == null || "null".equals(v)) ? "" : v;
    }

    private static String songKey(String s) {
        if (s == null) {
            return "";
        }
        return s.replace(" ", "").replace("　", "").trim().toLowerCase();
    }

    // ---------------- v1.3.188：歌词 / 封面（落雪经 NAS mini-lx 中转） ----------------
    //
    // mini-lx 已经把这两件事做完了（还带 1 小时缓存）：
    //   GET /api/lyric?rid=<rid>&source=<kw|kg|wy|tx|mg> → {lines:[{time,text}], lrc}
    //   GET /api/pic?rid=<rid>                          → {url}
    // 设备只管「这首歌是哪个源的哪个 id」—— 惰性地址 rel="r/<source>/<rid>" 里就有，
    // 本地文件/电台没有 ⇒ 用 NAS /api/search 搜一首同名同歌手的顶上（跨源也认）。

    private static final int LRC_CACHE_MAX = 24;
    private static final HashMap<String, JSONObject> lrcCache = new HashMap<String, JSONObject>();
    private static final HashMap<String, String> picCache = new HashMap<String, String>();
    /** 曲名|歌手 → {source, rid}；长度为 0 表示「查过，没有」（别再白跑一次）。 */
    private static final HashMap<String, String[]> ridCache = new HashMap<String, String[]>();
    /** v1.3.190：曲名|歌手 → 候选 {source,rid} 列表（一首歌多个版本，逐个试到有词为止）。 */
    private static final HashMap<String, List<String[]>> ridListCache
            = new HashMap<String, List<String[]>>();

    /**
     * v1.3.188：取歌词（顺带把封面也带回来）。{@code title} 为空时取「当前曲目」。
     *
     * <p>页面拿到的就是 {@code {ok,title,artist,pic,lines:[{time,text}]}} ——
     * 逐行歌词带时间轴，前端按播放位置高亮当前行。</p>
     */
    public static JSONObject lyricJson(String title, String artist) {
        JSONObject o = new JSONObject();
        try {
            String t = (title == null || title.length() == 0) ? curTitle() : title;
            String a = (artist == null || artist.length() == 0) ? curArtist : artist;
            o.put("title", t);
            o.put("artist", a);
            /* v1.3.190 ★：同一首歌在落雪上往往有好几个 rid（原版 / Live / 伴奏 /
               颁奖典礼现场…），**有的带词有的不带**。以前只取第一个候选，撞上没词的
               就报「这首歌没有歌词」，还把那个坏 rid 缓存住了 —— 用户在外面明明
               搜得到词。现在拿候选列表逐个试，第一个有词的就采信。 */
            List<String[]> cands = ridCandidates(t, a);
            if (cands.isEmpty()) {
                o.put("ok", false);
                o.put("msg", "这首歌没有在线信息（本地文件 / 电台 / 没匹配到在线源），取不到歌词");
                return o;
            }
            String tried = "";
            for (int ci = 0; ci < cands.size(); ci++) {
                String[] rr = cands.get(ci);
                String key = rr[0] + ":" + rr[1];
                tried += (tried.length() > 0 ? "," : "") + key;
                JSONObject lj = lrcCache.get(key);
                if (lj == null) {
                    String b = nasBase();
                    if (b == null) {
                        if (ci == 0) {
                            o.put("ok", false);
                            o.put("msg", "音乐库地址不可用");
                            return o;
                        }
                        break;
                    }
                    String js = httpGet(b + "/api/lyric?rid=" + enc(rr[1])
                            + "&source=" + enc(rr[0]), 7000);
                    lj = new JSONObject(js);
                    if (lrcCache.size() > LRC_CACHE_MAX) {
                        lrcCache.clear();
                    }
                    lrcCache.put(key, lj);
                }
                JSONArray lines = lj.optJSONArray("lines");
                int n = (lines == null) ? 0 : lines.length();
                if (n > 0) {
                    o.put("ok", true);
                    o.put("lines", lines);
                    SessionLog.i("lyric 命中 " + key + "（第 " + (ci + 1) + "/"
                            + cands.size() + " 个候选：" + tried + "）");
                    String pic = picOf(key, rr[1]);
                    if (pic.length() > 0) {
                        o.put("pic", pic);
                        curPic = pic;
                    }
                    return o;
                }
            }
            o.put("ok", false);
            o.put("lines", new JSONArray());
            o.put("msg", "这首歌没有歌词（试过 " + tried + "）");
        } catch (Throwable e) {
            // ★ JSONObject.put 本身抛受检 JSONException ⇒ catch 块里再包一层，
            //   否则编译器报「未报告的异常」（catch 里没有第二道兜底）。
            try {
                o.put("ok", false);
                o.put("msg", "取歌词失败：" + e);
            } catch (Throwable ignored) {
            }
        }
        return o;
    }

    /**
     * v1.3.188：这首歌的 {@code {source, rid}}。
     *
     * <p>先扫库内条目看 {@code rel}（{@code r/<source>/<rid>}），命中零开销；
     * 扫不到（本地文件 / 电台 / DLNA 推流）才去 NAS 搜一首同名同歌手的顶上 ——
     * 跨源匹配由落雪负责，本地歌也能拿到歌词。</p>
     */
    private static String[] ridOf(String title, String artist) {
        String k = songKey(title) + "|" + songKey(artist);
        String[] c = ridCache.get(k);
        if (c != null) {
            return c.length == 0 ? null : c;
        }
        try {
            String kt = songKey(title);
            String ka = songKey(artist);
            synchronized (tracks) {
                for (int i = 0; i < tracks.size(); i++) {
                    String[] e = tracks.get(i);
                    if (e == null || e.length < 3 || e[2] == null) {
                        continue;
                    }
                    if (!kt.equals(songKey(e[0]))) {
                        continue;
                    }
                    if (ka.length() > 0 && !ka.equals(songKey(e[1]))) {
                        continue;
                    }
                    String[] rr = parseRel(e[2]);
                    if (rr != null) {
                        if (ridCache.size() > LRC_CACHE_MAX * 4) {
                            ridCache.clear();
                        }
                        ridCache.put(k, rr);
                        return rr;
                    }
                }
            }
            String b = nasBase();
            if (b != null) {
                String js = httpGet(b + "/api/search?q="
                        + enc((artist.length() > 0 ? (title + " " + artist) : title))
                        + "&n=1&source=all", 5000);
                JSONArray list = new JSONObject(js).optJSONArray("list");
                if (list != null && list.length() > 0) {
                    JSONObject s = list.optJSONObject(0);
                    String src = s == null ? "" : s.optString("source", "");
                    String rid = s == null ? "" : s.optString("rid", "");
                    if (src.length() > 0 && rid.length() > 0) {
                        String[] rr = new String[]{src, rid};
                        if (ridCache.size() > LRC_CACHE_MAX * 4) {
                            ridCache.clear();
                        }
                        ridCache.put(k, rr);
                        return rr;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 查不到就当没有，下一次换曲会重试
        }
        ridCache.put(k, new String[0]);
        return null;
    }

    /**
     * v1.3.190：这首歌的**全部候选** {@code {source, rid}}（去重、有序）。
     *
     * <p>为什么必须列一堆：落雪里一首歌常常有多个版本 —— 原版带词、Live/伴奏/
     * 现场版不带词（实测「谢谢你的爱 / 刘德华」：kw:13851692 有 34 行，
     * kw:1044733(Live)、kw:658124、kw:90320964(现场) 全是 0 行）。只取第一条就会
     * 随机撞空。候选来源：① 库内同曲名的所有条目（零开销）；② NAS
     * {@code /api/search} 前几条（标题/歌手对得上才收，避免混进别人的同名歌）。
     * 结果按「歌名|歌手」缓存，歌词本身再按 rid 缓存 ⇒ 重复取词几乎不打网络。</p>
     */
    private static List<String[]> ridCandidates(String title, String artist) {
        String k = songKey(title) + "|" + songKey(artist);
        List<String[]> c = ridListCache.get(k);
        if (c != null) {
            return c;
        }
        List<String[]> out = new ArrayList<String[]>();
        String kt = songKey(title);
        String ka = songKey(artist);
        try {
            synchronized (tracks) {
                for (int i = 0; i < tracks.size() && out.size() < 4; i++) {
                    String[] e = tracks.get(i);
                    if (e == null || e.length < 3 || e[2] == null) {
                        continue;
                    }
                    if (!kt.equals(songKey(e[0]))) {
                        continue;
                    }
                    if (ka.length() > 0 && !ka.equals(songKey(e[1]))) {
                        continue;
                    }
                    String[] rr = parseRel(e[2]);
                    if (rr != null && !hasRid(out, rr)) {
                        out.add(rr);
                    }
                }
            }
            String b = nasBase();
            if (b != null) {
                String js = httpGet(b + "/api/search?q="
                        + enc((artist.length() > 0 ? (title + " " + artist) : title))
                        + "&n=5&source=all", 6000);
                JSONArray list = new JSONObject(js).optJSONArray("list");
                List<String[]> loose = new ArrayList<String[]>();
                for (int i = 0; list != null && i < list.length(); i++) {
                    JSONObject s = list.optJSONObject(i);
                    if (s == null) {
                        continue;
                    }
                    String src = s.optString("source", "");
                    String rid = s.optString("rid", "");
                    if (src.length() == 0 || rid.length() == 0) {
                        continue;
                    }
                    String[] rr = new String[]{src, rid};
                    if (hasRid(out, rr) || hasRid(loose, rr)) {
                        continue;
                    }
                    /* 标题/歌手对得上才当候选（不然「谢霆锋的谢谢你的爱」会混进来）；
                       歌手空或标题差一点（Live 后缀）的放宽松队列兜底。 */
                    boolean okT = kt.equals(songKey(s.optString("title", "")));
                    boolean okA = ka.length() == 0 || ka.equals(songKey(s.optString("artist", "")));
                    if (okT && okA) {
                        if (out.size() < 6) {
                            out.add(rr);
                        }
                    } else if (okT || okA) {
                        loose.add(rr);
                    }
                }
                for (int i = 0; i < loose.size() && out.size() < 6; i++) {
                    out.add(loose.get(i));
                }
            }
        } catch (Throwable ignored) {
            // 候选拿不全没关系，有一个算一个
        }
        if (ridListCache.size() > LRC_CACHE_MAX * 4) {
            ridListCache.clear();
        }
        ridListCache.put(k, out);
        return out;
    }

    /** v1.3.190：候选列表里已经有这个 {source,rid} 了吗（避免重复试同一个）。 */
    private static boolean hasRid(List<String[]> l, String[] rr) {
        for (int i = 0; i < l.size(); i++) {
            String[] x = l.get(i);
            if (x != null && x.length > 1 && x[0].equals(rr[0]) && x[1].equals(rr[1])) {
                return true;
            }
        }
        return false;
    }

    /** v1.3.188：{@code r/<source>/<rid>} → {source, rid}；不是惰性地址返回 null。 */
    private static String[] parseRel(String rel) {
        if (rel == null || !rel.startsWith("r/")) {
            return null;
        }
        int a = rel.indexOf('/', 2);
        if (a < 0) {
            return null;
        }
        String src = rel.substring(2, a);
        String rid = rel.substring(a + 1);
        if (src.length() == 0 || rid.length() == 0) {
            return null;
        }
        return new String[]{src, rid};
    }

    /** v1.3.188：封面 URL（NAS {@code /api/pic}，服务端缓存 1 小时）。 */
    private static String picOf(String key, String rid) {
        String c = picCache.get(key);
        if (c != null) {
            return c;
        }
        String u = "";
        try {
            String b = nasBase();
            if (b != null) {
                String js = httpGet(b + "/api/pic?rid=" + enc(rid), 5000);
                u = jstr(new JSONObject(js), "url");   // JSON null ⇒ ""（见 jstr 注释）
            }
        } catch (Throwable ignored) {
            u = "";
        }
        if (picCache.size() > LRC_CACHE_MAX) {
            picCache.clear();
        }
        picCache.put(key, u);
        return u;
    }

    /** v1.3.188：起播时补一次封面（异步，拿不到就算了 —— 页面回落成转碟）。 */
    private static void fetchPicAsync(final String title, final String artist) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    String[] rr = ridOf(title, artist);
                    if (rr == null) {
                        return;
                    }
                    String p = picOf(rr[0] + ":" + rr[1], rr[1]);
                    // 取回来的可能已经不是当前这首了（用户切歌比网络快）⇒ 只在对得上时落
                    if (p.length() > 0 && songKey(title).equals(songKey(curTitle()))) {
                        curPic = p;
                    }
                } catch (Throwable ignored) {
                }
            }
        }, "pb-pic").start();
    }

    /**
     * v1.3.178：搜一个关键词的在线曲目，并**并行取链**成可直接播的曲目列表。
     *
     * <p>「随机播放周杰伦的歌」这类需求：本地池里往往只有寥寥几首，用户要的是
     * 本地 + 在线合起来的那一批。慢的不是搜索（1 次），是取链（每首 1 次）⇒
     * 这里用固定并发的小线程池一起取，把 12 首的耗时压到一次取链的量级。</p>
     *
     * @param q 关键词（歌手名 / 歌名）
     * @param max 最多要几条
     * @param searchMs 搜索超时
     * @param budgetMs 取链的**总预算**（到点就放弃还没回来的，拿多少算多少）
     * @return 可播曲目（String[8]）；任何一步失败返回 null
     */
    private static List<String[]> onlineTracksFor(String q, int max, int searchMs, long budgetMs) {
        try {
            String b = nasBase();
            if (b == null || q == null || q.trim().length() == 0) {
                return null;
            }
            int lim = max > 0 ? max : 10;
            String js = httpGet(b + "/api/search?q=" + URLEncoder.encode(q.trim(), "UTF-8")
                    + "&n=" + lim + "&source=all", searchMs);
            if (js == null || js.length() == 0) {
                return null;
            }
            JSONArray list = new JSONObject(js).optJSONArray("list");
            if (list == null || list.length() == 0) {
                return null;
            }
            List<JSONObject> cand = new ArrayList<JSONObject>();
            for (int i = 0; i < list.length() && cand.size() < lim; i++) {
                JSONObject s = list.optJSONObject(i);
                if (s == null) {
                    continue;
                }
                if (s.optString("source", "").length() == 0
                        || s.optString("rid", "").length() == 0) {
                    continue;
                }
                cand.add(s);
            }
            // v1.3.179 ★★：能惰就惰 —— 一条 /r/<source>/<rid> 就当一首推过去，
            // 把「取链」从「推列表之前」挪到「真正播放那一刻」（NAS 现场取 + 流式转发）。
            // 这是把逐条取链换掉的**唯一理由**：每首取链要走五级兜底（各级超时 12s），
            // 12 首就是几十秒，5s 预算一到只剩 0~4 首（实测孙燕姿 0 首、周杰伦 4 首）。
            if (lazyOk(b)) {
                return lazyTracks(b, cand);
            }
            // 老 mini-lx（没有 /r/ 路由）照原路逐条取链，功能不倒退。
            // ★★ 但这条路上每一条都要真打上游，必须收着：
            //   20 条并发砸下去 = 送上门被限流甚至封 IP（实测并发 12 时整批 0 成功）。
            //   被封之后连「起播时取链」都会一起哑火 ⇒ 这里宁可少给几首。
            //   NAS 侧还有一道取链节流闸（并发 2 + 250ms 间隔）兜底。
            int cap = Math.min(cand.size(), LEGACY_URL_MAX);
            return resolveOnlineUrls(b, cand.subList(0, cap), LEGACY_URL_CONC, 9000, budgetMs);
        } catch (Throwable t) {
            SessionLog.i("online tracks for 「" + q + "」 failed: " + t);
            return null;
        }
    }

    /** -1 = 还没探过；0 = 这台 mini-lx 没有 /r/；1 = 支持。 */
    private static volatile int lazySupported = -1;
    /** 上次探测的时刻 —— 「不支持」的结论只信 10 分钟（NAS 那边一升级就能自己接上）。 */
    private static volatile long lazyCheckedAt = 0;

    /**
     * v1.3.179：这台 NAS 上的 mini-lx 支不支持**惰性地址** {@code /r/<source>/<rid>}。
     *
     * <p>探针是 {@code GET /r/ping}（新路由，极轻）。只有新版才回
     * {@code {"ok":true,"lazy":true}}；老版没有这段路由，请求落空 ⇒ 判定不支持。
     * 支持的结论一直记住（不可能自己消失）；**不支持的结论只信 10 分钟** ——
     * 用户在 NAS 上重建了容器也不必重启音箱，到点自己探第二次就接上了。</p>
     */
    private static boolean lazyOk(String b) {
        long now = System.currentTimeMillis();
        if (lazySupported >= 0
                && (lazySupported == 1 || now - lazyCheckedAt < 600000)) {
            return lazySupported == 1;
        }
        boolean ok = false;
        try {
            String js = httpGet(b + "/r/ping", 2500);
            ok = js != null && js.indexOf("\"lazy\"") >= 0;
        } catch (Throwable t) {
            ok = false;
        }
        lazyCheckedAt = now;
        lazySupported = ok ? 1 : 0;
        SessionLog.i("mini-lx 惰性地址 /r/：" + (ok ? "支持（推列表不再逐条取链）"
                : "不支持（回落逐条取链）"));
        return ok;
    }

    /**
     * v1.3.179：把搜索结果**原样**变成可推的曲目，一条 HTTP 都不多发。
     *
     * <p>url 形如 {@code http://NAS:8100/r/kw/95769?title=我不难过&artist=孙燕姿}，
     * 与曲库池的 {@code /s/<i>} 完全同构：现在只是「登记」，等 player 真要放这一首、
     * 来 GET 这个地址时，NAS 才去取真链并流式转发。代价是「能不能放」要等它开口才知道，
     * 换来的是：推 12 首和推 1 首一样快，且永远不会因为上游抽风而整批哑火。</p>
     */
    private static List<String[]> lazyTracks(String b, List<JSONObject> items) {
        List<String[]> out = new ArrayList<String[]>();
        for (int i = 0; i < items.size(); i++) {
            JSONObject s = items.get(i);
            if (s == null) {
                continue;
            }
            String src = s.optString("source", "");
            String rid = s.optString("rid", "");
            if (src.length() == 0 || rid.length() == 0) {
                continue;
            }
            String title = s.optString("title", "");
            String artist = s.optString("artist", "");
            String rel = "r/" + src + "/" + rid;
            StringBuilder sb = new StringBuilder();
            sb.append(b).append('/').append(rel)
                    .append("?title=").append(enc(title))
                    .append("&artist=").append(enc(artist));
            out.add(new String[]{
                    title,
                    artist,
                    rel,
                    sb.toString(),
                    s.optString("album", ""),
                    s.optString("duration", ""),
                    // v1.3.188：[6] 封面 —— 搜索结果本来就带 img（kuwo/QQ CDN），
                    //   顺手带上，省掉一次 /api/pic（封面走浏览器直连 CDN，不占 NAS）。
                    jstr(s, "img"),
                    "online"});
        }
        return out;
    }

    /** URLEncoder 会抛受检异常，包一层让调用处能直接内联。 */
    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * v1.3.178：并行取直链。
     *
     * <p>背景：一次在线搜能回几十条，但每条都要 {@code /api/url} 拿直链（内部还要
     * 走 lxserver 中继 + 五级兜底）。串行做 12 首就是十几秒，语音链路等不起。
     * 这里开固定 {@code conc} 个线程分块取，全部 join 后再按原序挑出成功的 ——
     * 单条失败只丢那一首，不影响整批。</p>
     *
     * <p>★ 三个「别把上游惹毛」的设定（都是实测踩出来的）：
     * <ol>
     *   <li>{@code conc} 固定 4 —— 开到 12 时上游直接限流，半数请求 5s 超时，
     *       整批一首都拿不到（比并发 4 慢还更差）；</li>
     *   <li>线程**错峰启动**（每 60ms 一个）—— 把「同时 12 个请求」摊成细流；</li>
     *   <li>{@code budgetMs} 到点即走 —— 拿多少算多少，绝不为了等齐 12 首
     *       让用户在音箱前多站几秒（mini-lx 有直链缓存，下次再点基本秒回）。</li>
     * </ol></p>
     */
    private static List<String[]> resolveOnlineUrls(final String b, final List<JSONObject> items,
                                                    int conc, final int perMs, long budgetMs) {
        final int n = items.size();
        if (n == 0) {
            return new ArrayList<String[]>();
        }
        final String[] got = new String[n];
        int c = Math.min(conc > 0 ? conc : 6, n);
        int per = (n + c - 1) / c;
        List<Thread> ts = new ArrayList<Thread>();
        for (int w = 0; w < c; w++) {
            final int from = w * per;
            final int to = Math.min(n, from + per);
            if (from >= to) {
                break;
            }
            Thread t = new Thread(new Runnable() {
                public void run() {
                    for (int i = from; i < to; i++) {
                        try {
                            JSONObject s = items.get(i);
                            String uj = httpGet(b + "/api/url?rid="
                                    + URLEncoder.encode(s.optString("rid", ""), "UTF-8")
                                    + "&source="
                                    + URLEncoder.encode(s.optString("source", ""), "UTF-8")
                                    + "&q=128k&title="
                                    + URLEncoder.encode(s.optString("title", ""), "UTF-8")
                                    + "&artist="
                                    + URLEncoder.encode(s.optString("artist", ""), "UTF-8"),
                                    perMs);
                            if (uj == null || uj.length() == 0) {
                                continue;
                            }
                            String u = new JSONObject(uj).optString("url", "");
                            if (u.length() > 0) {
                                got[i] = u;
                            }
                        } catch (Throwable ignored) {
                            // 单条失败就跳过，不拖累整批
                        }
                    }
                }
            }, "pb-url" + w);
            ts.add(t);
            t.start();
            // 错峰：别在 0ms 内把 4 个请求同时砸出去（上游限流时整批超时）
            if (URL_STAGGER > 0 && w < c - 1) {
                try {
                    Thread.sleep(URL_STAGGER);
                } catch (Throwable ignored) {
                }
            }
        }
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < ts.size(); i++) {
            long left = budgetMs - (System.currentTimeMillis() - t0);
            if (left <= 0) {
                SessionLog.i("online url budget out (" + budgetMs + "ms): 放弃剩下的，"
                        + "先播已经取到链的");
                break;
            }
            try {
                ts.get(i).join(left);
            } catch (Throwable ignored) {
            }
        }
        List<String[]> out = new ArrayList<String[]>();
        for (int i = 0; i < n; i++) {
            if (got[i] == null || got[i].length() == 0) {
                continue;
            }
            JSONObject s = items.get(i);
            out.add(new String[]{s.optString("title", ""), s.optString("artist", ""), "",
                    got[i], s.optString("album", ""), s.optString("duration", ""), "", "online"});
        }
        return out;
    }

    /** v1.3.117：电台名匹配（双向 contains —— 用户可能说「先锋898电台」）。 */
    private static String[] matchRadio(String q) {
        String k = (q == null ? "" : q.trim().toLowerCase());
        if (k.length() == 0) {
            return null;
        }
        for (String[] r : radios) {
            String n = r[0].toLowerCase();
            if (n.contains(k) || k.contains(n)) {
                return r;
            }
        }
        return null;
    }

    private static String radioNames() {
        return radioNames(radios.size());
    }

    /**
     * v1.3.118：电台可能有几十个（用户一次编进来 47 个台），
     * 全列出来会把 list / 报错提示撑得又长又难读 —— 只列前 limit 个并给总数。
     */
    private static String radioNames(int limit) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(limit, radios.size());
        for (int i = 0; i < n; i++) {
            sb.append(radios.get(i)[0]).append("、");
        }
        if (radios.size() > n) {
            sb.append("…等共 ").append(radios.size()).append(" 个台");
        }
        return sb.toString();
    }

    /**
     * v1.3.117：play_url 的本体。
     *
     * 背景（用户原话：「网络电台好像播不了，小智说放起来了，但是没声」）：
     * 设备日志里模型实际传的是 {"url":"http://fm91.8"} 和 {"url":"先锋898"}
     * —— 它把**电台名**当成网址塞进了 play_url；而旧代码只要 msg(1,…) 把消息
     * 发出去就返回 true ⇒ 一律回「OK 已开始播放」。player 拿着根本不存在的地址
     * 什么都播不出来，用户端看到的就是「说放起来了但没声」。
     *
     * 三件事：① 不是网址的输入先去电台/曲库里认领；② 是网址的先探活，连不上
     * 就报错、不再谎报成功；③ 走网络流专用的长 kick。
     */
    private static String playUrl(String url, JSONObject args) {
        if (!isHttpUrl(url)) {
            if (!idxLoaded) {
                loadIndex();
            }
            if (isAllRadio(url)) {
                return playAllRadios("play_url->all_radio", -1);
            }
            String[] rr = matchRadio(url);
            if (rr != null) {
                return playRadio(rr, "play_url->radio", false);
            }
            if (idxLoaded) {
                List<Integer> hits = matchTracks(url, false);
                if (hits.size() > 0) {
                    int hit = hits.get(0).intValue();
                    String[] e = tracks.get(hit);
                    String who = e[1].length() > 0 ? (e[0] + " - " + e[1]) : e[0];
                    int n = pushSongs("play_url->song", hitFirstIdx(hit));
                    if (n > 0) {
                        pushed = true;
                        kickPlay("play_url->song", startDelay(n), 2);
                        return "OK 「" + url + "」不是网址，按歌名理解成《" + who
                                + "》，正在播放";
                    }
                }
            }
            return "ERR 「" + url + "」不是一个网址，电台和曲库里也没找到它。"
                    + "要播电台请说电台名（"
                    + (radios.size() > 0 ? radioNames() : "当前没配电台")
                    + "），不要自己拼网址";
        }
        String perr = probeUrl(url);
        if (perr != null) {
            return "ERR 这个地址播不了：" + perr + "（" + url + "）";
        }
        // v1.3.153：标题优先用调用方给的（搜索/曲库点播传的是真实歌名），
        // 没有才退回「网络音频」（MCP 或语音随手给一个裸网址的情形）。
        String nm = (args == null) ? "" : args.optString("title", "").trim();
        String ar = (args == null) ? "" : args.optString("artist", "").trim();
        if (nm.length() == 0) {
            nm = "网络音频";
        }
        if (msg(1, oneJson(nm, url, ar))) {
            pushed = true;
            kickPlay("play_url", 800, 5);
            SessionLog.i("play url (msgcenter): " + url);
            return "OK 已开始播放（网络流，可能要缓冲几秒才出声）";
        }
        try {
            return setUri(url);
        } catch (Throwable t) {
            return "ERR 播放通道不可用: " + t;
        }
    }

    /** v1.3.117：电台统一出口。主通道 = 原厂消息总线；forceDlna 时直接走 DLNA。 */
    private static String playRadio(String[] r, String why, boolean forceDlna) {
        String url = r[1];
        if (!forceDlna && msg(1, oneJson(r[0], url))) {
            pushed = true;
            // 直播流没有 Content-Length，player 要先填满缓冲才 prepared，比本地 mp3
            // 慢得多 —— 600ms×2 那套 kick 打不中（表现就是「说放起来了但没声」）。
            // play 幂等，这里拉长到 800ms 起、每秒补一脚、共 5 次（≈4.8s 窗口）。
            kickPlay(why, 800, 5);
            SessionLog.i("play radio (" + why + ") msgcenter: " + r[0] + " -> " + url);
            return "OK 正在播放电台 " + r[0] + "（网络流，可能要缓冲几秒才出声）";
        }
        try {
            String s = setUri(url);
            SessionLog.i("play radio (" + why + ") dlna: " + r[0] + " -> " + url);
            return s.startsWith("OK") ? ("OK 正在播放电台 " + r[0])
                    : (s + " (" + r[0] + ")");
        } catch (Throwable t) {
            return "ERR 电台 " + r[0] + " 播放通道不可用: " + t;
        }
    }

    /**
     * v1.3.119：一次把所有电台排进播放列表（用户原话「把所有广播电台添加到播放列表」，
     * 之前一个 msg(1,…) 只带一首 ⇒ 只能放一个台、也没法用「下一首」换台）。
     *
     * 走的是和歌曲同一条通道（playSpecifiedMusic + 整个 itemList），player 自己
     * 持有列表，next/prev 才有意义。差别只在于：
     *   - 条目来自 radios 而不是 tracks；
     *   - 直播流**没有结尾**，不会自动进下一首，换台只能手动 next/prev；
     *   - 首播缓冲比本地 mp3 慢 ⇒ 用网络流那套长 kick（800ms 起、共 5 次）。
     *
     * @param start 从哪个台开始（-1 = 列表第一个）
     */
    private static String playAllRadios(String why, int start) {
        if (radios.size() == 0) {
            return "ERR 电台列表是空的（NAS 的 radios.json 里加，或在音乐服务网页上添加）";
        }
        int n = pushRadios(why, start);
        if (n <= 0) {
            return "ERR 播放通道不可用（消息总线发送失败）";
        }
        pushed = true;
        // 顺序播放：47 个台排成一张表，next 就该是下一个台（默认随机会乱跳）
        setPlayMode(0);
        kickPlay(why, 800, 5);
        int first = (start >= 0 && start < radios.size()) ? start : 0;
        SessionLog.i("play all radios (" + why + "): " + n + " stations, first="
                + radios.get(first)[0] + ", start=" + start);
        return "OK 已把 " + n + " 个电台排进播放列表，正在播第一个「"
                + radios.get(first)[0]
                + "」。直播流要缓冲几秒才出声；说「下一首」「上一首」换台"
                + "（直播流没有结尾，不会自动跳下一个台）";
    }

    /**
     * v1.3.120：把「第 3 个电台 / 第三个 / 3 号台」解析成 **1-based 序号**（失败返回 -1）。
     *
     * 背景（用户原话）：「当我说要播放第几个电台的时候，只会把电台全部放进列表然后播放第一个」
     * —— 模型手里没有「第几个」这个参数可用，只能调 play_radio_all 且不传 name，
     * 于是永远从第一个开始。
     *
     * 判据收得很紧（必须整体就是序号，或「第 + 数 + 个/号/台 (+电台)」），
     * 否则「FM91.8」「动听101」这类**台名里带数字**的会被误判成序号。
     */
    private static int radioOrdinal(String q) {
        if (q == null) {
            return -1;
        }
        String s = q.trim().replaceAll("\\s+", "");
        if (s.length() == 0) {
            return -1;
        }
        if (s.matches("\\d{1,3}")) {
            int v = Integer.parseInt(s);
            return v >= 1 ? v : -1;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^第?([0-9]{1,3}|[一二三四五六七八九十]{1,3})[个号台首](电台|广播|台)?$")
                .matcher(s);
        if (m.matches()) {
            String n = m.group(1);
            int v = n.matches("\\d+") ? Integer.parseInt(n) : cn2num(n);
            return v >= 1 ? v : -1;
        }
        return -1;
    }

    /** 中文数字 → 阿拉伯数字（1~99，够电台列表用；解析不了返回 -1）。 */
    private static int cn2num(String s) {
        String d = "\u96f6\u4e00\u4e8c\u4e09\u56db\u4e94\u516d\u4e03\u516b\u4e5d";  // 零一二三四五六七八九
        if (s == null || s.length() == 0) {
            return -1;
        }
        if (s.length() == 1) {
            if (s.charAt(0) == '\u5341') {   // 十
                return 10;
            }
            int i = d.indexOf(s.charAt(0));
            return i > 0 ? i : -1;
        }
        int shi = s.indexOf('\u5341');       // 十
        if (shi < 0) {
            return -1;
        }
        int hi = 1;
        int lo = 0;
        if (shi > 0) {
            int i = d.indexOf(s.charAt(shi - 1));
            if (i <= 0) {
                return -1;
            }
            hi = i;
        }
        if (shi + 1 < s.length()) {
            int i = d.indexOf(s.charAt(shi + 1));
            if (i <= 0) {
                return -1;
            }
            lo = i;
        }
        return hi * 10 + lo;
    }

    /** v1.3.119：把「所有电台 / 全部电台 / 每个电台」这类泛词认出来。
     *
     * 必须同时含「电台/广播/台」和「所有/全部/每个」才认 —— 否则「上海音乐台」
     * 这种带「台」的真台名会被误判成「全部」。
     */
    private static boolean isAllRadio(String q) {
        if (q == null) {
            return false;
        }
        String s = q.trim();
        if (s.length() == 0) {
            return false;
        }
        boolean obj = s.contains("电台") || s.contains("广播") || s.contains("台");
        boolean all = s.contains("所有") || s.contains("全部") || s.contains("每个")
                || s.contains("全都") || s.contains("所有台");
        return obj && all;
    }

    /** radios 里这个条目的下标（matchRadio 返回的是 String[] 引用，按引用找）。 */
    private static int radioIndexOf(String[] rr) {
        for (int i = 0; i < radios.size(); i++) {
            if (radios.get(i) == rr) {
                return i;
            }
        }
        return -1;
    }

    /** 是不是一个至少看起来像网址的东西（http/https + 有 host）。 */
    private static boolean isHttpUrl(String s) {
        String l = s.toLowerCase();
        if (!l.startsWith("http://") && !l.startsWith("https://")) {
            return false;
        }
        try {
            String host = new URL(s).getHost();
            return host != null && host.length() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * v1.3.117：播之前先探一下地址在不在 —— 只取响应头，**绝不读 body**
     * （读 body 遇上直播流会一直有数据，read timeout 永远不会触发，就是
     * v1.3.116 那个「地址删不掉」的同款陷阱）。
     *
     * @return null = 可以用；否则是给人看的原因。
     */
    private static String probeUrl(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("HEAD");
            c.setConnectTimeout(3000);
            c.setReadTimeout(4000);
            c.setRequestProperty("Icy-MetaData", "0");
            int code = c.getResponseCode();
            if (code >= 400 || code == 405) {
                // 不少源不支持 HEAD，退 GET 拿头（不读 body）
                c.disconnect();
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestMethod("GET");
                c.setConnectTimeout(3000);
                c.setReadTimeout(4000);
                c.setRequestProperty("Icy-MetaData", "0");
                code = c.getResponseCode();
            }
            if (code >= 400) {
                return "服务器返回 HTTP " + code;
            }
            String ct = c.getHeaderField("Content-Type");
            if (ct != null && ct.toLowerCase().startsWith("text/html")) {
                return "返回的是网页（" + ct + "），不是音频";
            }
            return null;
        } catch (Throwable t) {
            String m = String.valueOf(t);
            if (m.contains("UnknownHost") || m.contains("Unable to resolve")) {
                return "域名解析失败（" + hostOf(url) + " 这个域名不存在或没网）";
            }
            if (m.contains("ECONNREFUSED") || m.contains("refused")) {
                return "连接被拒绝（" + hostOf(url) + " 端口不通）";
            }
            if (m.contains("timed out") || m.contains("timeout")
                    || m.contains("Timeout")) {
                return "连接超时（" + hostOf(url) + " 3 秒没响应）";
            }
            return m;
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (Throwable t) {
            return url;
        }
    }

    private static String urlOf(String[] e) {
        if (e == null) {
            return "";
        }
        if (e.length == 2) {
            return e[1];                 // 电台：本来就是绝对地址
        }
        if (e.length >= 4 && e[3] != null && e[3].length() > 0) {
            return e[3];                 // v1.3.102：入库时已算好的最终 URL
        }
        return joinUrl(base, e[2]);      // 兜底（老格式）
    }

    private static String joinUrl(String b, String file) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(b == null ? "" : b);
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '/') {
                sb.append('/');
            }
            String[] segs = file.split("/");
            for (int i = 0; i < segs.length; i++) {
                if (i > 0) {
                    sb.append('/');
                }
                sb.append(URLEncoder.encode(segs[i], "UTF-8").replace("+", "%20"));
            }
            return sb.toString();
        } catch (Throwable t) {
            return b + file;
        }
    }

    // ---------------- MCP 分发 ----------------

    /** MCP tools/call 入口。op 是去掉 "self.r1player." 前缀后的动作名。 */
    /**
     * v1.3.130：把唤醒期间被系统暂停的音乐接回去。
     *
     * 等价于原厂那条链的终点：
     * <pre>
     *   PhicommExternalMusicHandler.doResume()
     *     → CommonPlayer.resume()
     *       → PhicommMusicController.play()
     *         → MessageDispatchManager.sendMessage(4, 3, -1, null)
     *             其中 4 = MSG_TYPE_PLAYER、3 = play、obj = null
     * </pre>
     * 中间两跳只是转发，所以我们直接发那一条总线消息（就是本类的 {@code msg(3, null)}）。
     * 注意 json 传 null —— 原厂控制指令不挂 Parcelable。
     */
    public static boolean resumeMusic() {
        boolean ok = msg(3, null);
        if (ok) {
            notePlaying(null);      // v1.3.136：写盘（自动恢复也算「在播」）
        }
        Log.i(TAG, "resumeMusic (subType=3): " + (ok ? "sent" : "msgcenter not ready"));
        return ok;
    }

    /**
     * v1.3.132：暂停音乐（subType=2 = 原厂 {@code CommonPlayer.pause()}）。
     *
     * 用途 = TTS 与音乐互斥（Web「小智说话时暂停音乐」）：小智每轮开口前让路，
     * 说完由 {@link MusicResume} 接回。没在播时发这条是空转，无害。
     */
    public static boolean pauseMusic() {
        boolean ok = msg(2, null);
        if (ok) {
            noteStopped();          // v1.3.136：写盘（让路暂停也是真暂停）
        }
        Log.i(TAG, "pauseMusic (subType=2): " + (ok ? "sent" : "msgcenter not ready"));
        return ok;
    }

    /**
     * v1.3.132：粗粒度「音乐是不是在播」。
     *
     * <p>只统计**经过本类**的播放动作（dispatch 的 play 族 / resumeMusic / kickPlay）。
     * 原厂自己起的播放（DLNA 推送、小讯放歌）不在此列 —— 那类场景由
     * {@link MusicResume#isPending()}（原厂 doInterrupt 的 isPlaying() 结论）兜底判断。
     */
    /**
     * v1.3.133：异步查一次 DLNA 渲染器的播放状态（GetTransportInfo）。
     * 只在 {@code CurrentTransportState == PLAYING} 时回调（主线程）。
     *
     * <p>用途：{@link #softPlaying} 是进程内内存态，有两个盲区 ——
     * ① 进程重启即丢（音乐在 player 独立进程里继续放，我们不知道）；
     * ② 原厂/DLNA 自己起的播放没经过 {@link #dispatch}，没记账。
     * TTS 让路判据和启动 warm-up 都用它查一次真状态。
     */
    public static void queryPlayingAsync(final Runnable onPlaying) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    String r = avt("GetTransportInfo",
                            "<u:GetTransportInfo xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
                                    + "<InstanceID>0</InstanceID></u:GetTransportInfo>");
                    String st = tag(r, "CurrentTransportState");
                    dlnaState = st;     // v1.3.136：记下真状态供 status 回显
                    Log.i(TAG, "GetTransportInfo -> " + st);
                    if ("PLAYING".equalsIgnoreCase(st)) {
                        softPlaying = true;
                        new Handler(Looper.getMainLooper()).post(onPlaying);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "queryPlayingAsync failed: " + t);
                }
            }
        }, "pb-probe").start();
    }

    /**
     * v1.3.134：{@link #queryPlayingAsync(Runnable)} 的「带结果」版 ——
     * 无论播不播都回调（主线程）。用途：MusicResume 的让路兜底需要区分
     * 「没在播 ⇒ 撤账」和「在播 ⇒ 补让路」，只在 PLAYING 时回调的旧接口做不到。
     */
    public interface StateCallback {
        void onResult(boolean playing);
    }

    public static void queryPlayingStateAsync(final StateCallback cb) {
        new Thread(new Runnable() {
            public void run() {
                boolean playing = false;
                try {
                    String r = avt("GetTransportInfo",
                            "<u:GetTransportInfo xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
                                    + "<InstanceID>0</InstanceID></u:GetTransportInfo>");
                    String st = tag(r, "CurrentTransportState");
                    dlnaState = st;     // v1.3.136
                    Log.i(TAG, "GetTransportInfo -> " + st);
                    playing = "PLAYING".equalsIgnoreCase(st);
                    if (playing) {
                        softPlaying = true;
                    }
                    // v1.3.136：★ DLNA 说「没在播」时**不推翻**内存/磁盘里的在播结论 ——
                    // DLNA 只是外部控制通道，对 player 自己的播放引擎（原厂起播、
                    // 我们推的歌单）未必同步（这正是「小智启动前就在放歌却说播放列表为空」的
                    // 根因）。只有 PLAYING 这种**阳性**结论才用来纠偏。
                } catch (Throwable t) {
                    Log.w(TAG, "queryPlayingStateAsync failed: " + t);
                }
                final boolean p = playing;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    public void run() {
                        cb.onResult(p);
                    }
                });
            }
        }, "pb-probe2").start();
    }

    public static boolean isSoftPlaying() {
        return softPlaying;
    }

    /**
     * v1.3.143：{@code softPlaying} 里**本进程内真播过**的那部分（排除重启恢复的过期值）。
     * 让路判据请用这个；只想显示「当前曲目」用 {@link #isSoftPlaying()}。
     */
    public static boolean isSoftPlayingFresh() {
        return softFresh;
    }

    /**
     * v1.3.152：给 Web 控制台的**结构化**搜索（{@code GET /api/search?k=歌名/歌手}）。
     *
     * 与 {@code op=search} 的区别：那个返回一整段中文文本（给 MCP / 模型看的），
     * 前端没法拿它渲染可点击列表。这里返回
     * {@code {"total":N,"list":[{"index","title","artist","url"}…]}}：
     * · {@code index} 是 **1-based**（与 play_index 的编号一致）；
     * · {@code url} 是曲库里那条绝对地址，前端点一下走 {@code op=play_url} 即播。
     * 上限 60 条（多了前端也没法点）。
     */
    public static JSONObject searchJson(String q) {
        return searchJson(q, 60);
    }

    /**
     * 曲库搜索（web「搜索曲库」用）。
     *
     * <p>v1.3.168：<b>关键词留空 = 列全部曲库</b>（web 上「留空点搜索」就走这条），
     * {@code lim} 控制最多回几条（{@code <=0} 表示不限制）。关键词非空时默认上限 60
     * —— 那条路径是给语音点歌/精确查找用的，一次回 1188 条没意义。</p>
     *
     * <p>v1.3.181：<b>只搜本地</b> —— 在线歌播过就会 append 进 tracks，不过滤的话
     * 「搜索曲库」和「在线搜歌」两个按钮就分不出区别了。返回条目的 {@code index}
     * 仍是全库下标，点播不受过滤影响。</p>
     */
    public static JSONObject searchJson(String q, int lim) {
        JSONObject o = new JSONObject();
        try {
            if (!idxLoaded) {
                loadIndex();
            }
            if (!idxLoaded) {
                o.put("err", "音乐库不可用: " + idxErr);
                return o;
            }
            String key = (q == null) ? "" : q.trim();
            List<Integer> hits;
            if (key.length() == 0) {
                // 空关键词 ⇒ 全库（按下标原序）
                // ★ v1.3.181：只列**本地**。在线歌播过一次就会 append 进 tracks
                //   （上限 400 条），不过滤的话「搜索曲库」能搜出在线歌 ——
                //   页面上「搜索」和「在线搜歌」两个按钮就分不出区别了
                //   （用户原话：「这个搜索应该是搜出来本地音乐吧」）。
                hits = new ArrayList<Integer>(tracks.size());
                for (int i = 0; i < tracks.size(); i++) {
                    String[] e = tracks.get(i);
                    if (e.length > 7 && e[7] != null && e[7].length() > 0) {
                        continue;               // 在线 append 的条目不进「曲库」结果
                    }
                    hits.add(Integer.valueOf(i));
                }
            } else {
                hits = matchTracks(key, false);
                // ★ v1.3.181：同上 —— 关键词搜索也只出本地。
                //   index 仍是**全库下标**（点播 op=play_at 用它），过滤只影响显不显示，
                //   不影响点播命中。（matchTracks 本身不过滤：语音点歌还要靠它搜到
                //   在线 append 的歌 —— 那条链路「搜到就播」是合理行为。）
                List<Integer> local = new ArrayList<Integer>(hits.size());
                for (int i = 0; i < hits.size(); i++) {
                    int ix = hits.get(i).intValue();
                    String[] e = tracks.get(ix);
                    if (e.length > 7 && e[7] != null && e[7].length() > 0) {
                        continue;
                    }
                    local.add(Integer.valueOf(ix));
                }
                hits = local;
            }
            JSONArray arr = new JSONArray();
            int cap = (lim <= 0) ? hits.size() : Math.min(hits.size(), lim);
            for (int j = 0; j < cap; j++) {
                int i = hits.get(j).intValue();
                String[] e = tracks.get(i);
                JSONObject it = new JSONObject();
                it.put("index", i + 1);
                it.put("title", e[0]);
                it.put("artist", e.length > 1 ? e[1] : "");
                it.put("url", e.length > 3 ? e[3] : "");
                arr.put(it);
            }
            o.put("total", hits.size());
            o.put("list", arr);
        } catch (Throwable t) {
            // 注意：这里已经在 catch 块里，put 自己还会抛 JSONException，得再兜一层
            try {
                o.put("err", String.valueOf(t));
            } catch (Throwable ignored) {
            }
        }
        return o;
    }

    public static String dispatch(String op, JSONObject args) {
        try {
            // v1.3.130：先把「用户到底说了什么」告诉 MusicResume，它据此决定要不要
            // 在会话结束时把唤醒期间暂停的音乐接回去（见 MusicResume 类注释）。
            // v1.3.132：顺手维护 softPlaying 软状态（TTS 互斥的让路判据之一）。
            if ("pause".equals(op) || "stop".equals(op)) {
                MusicResume.noteUserPause();
                noteStopped();          // v1.3.136：落盘（重启后不该再以为在播）
            } else if ("play".equals(op) || "next".equals(op) || "prev".equals(op)
                    || "random".equals(op) || "play_song".equals(op)
                    || "play_artist".equals(op) || "play_index".equals(op)
                    || "play_at".equals(op) || "replay".equals(op)
                    || "play_radio".equals(op) || "play_radio_all".equals(op)
                    // v1.3.178：在线随机 / 在线随机池点播 —— 同样是「用户要放歌」
                    || "random_online".equals(op) || "play_rand_at".equals(op)
                    || "play_url".equals(op)) {
                MusicResume.noteUserPlay();
                // v1.3.136：落盘（曲名由 pushPlaylist 填，这里沿用旧值）
                notePlaying(null);
            }
            // v1.3.88：控制指令优先走原厂消息总线（player 自己的播放引擎，不受 DLNA
            // 状态机限制 —— DLNA 在 STATUS_BUSY 时会给 SetAVTransportURI 回 501）。
            if ("play".equals(op)) {
                // v1.3.94 兜底：模型经常把「播放音乐」理解成 play，但 player 里还没歌单时
                // subType=3 是空转。这时直接当 random 处理（先推 100 首再播）。
                if (!pushed) {
                    if (!idxLoaded) {
                        loadIndex();
                    }
                    int n = pushSongs("play->random", shuffledIdx());
                    if (n > 0) {
                        pushed = true;
                        kickPlay("play->random", startDelay(n), 2);
                        SessionLog.i("play with empty playlist -> random " + n + " tracks");
                        return "OK 开始播放（随机 " + n + " 首，库里共 " + tracks.size() + " 首）";
                    }
                }
                if (msg(3, null)) {
                    pushed = true;
                    return "OK 继续播放";
                }
                avt("Play", "<u:Play xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
                        + "<InstanceID>0</InstanceID><Speed>1</Speed></u:Play>");
                return "OK 继续播放";
            }
            if ("pause".equals(op)) {
                if (msg(2, null)) {
                    return "OK 已暂停";
                }
                avt("Pause", "<u:Pause xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
                        + "<InstanceID>0</InstanceID></u:Pause>");
                return "OK 已暂停";
            }
            if ("stop".equals(op)) {
                if (msg(4, null)) {
                    return "OK 已停止";
                }
                avt("Stop", "<u:Stop xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
                        + "<InstanceID>0</InstanceID></u:Stop>");
                return "OK 已停止";
            }
            if ("next".equals(op)) {
                if (msg(6, null)) {
                    // v1.3.94：魔改 player 切歌后同样只 prepare 不 start，补一脚 play
                    kickPlay("next", 1200, 2);
                    return "OK 下一曲";
                }
                avt("Next", "<u:Next xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
                        + "<InstanceID>0</InstanceID></u:Next>");
                return "OK 下一曲";
            }
            if ("prev".equals(op)) {
                if (msg(5, null)) {
                    kickPlay("prev", 1200, 2);
                    return "OK 上一曲";
                }
                avt("Previous", "<u:Previous xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
                        + "<InstanceID>0</InstanceID></u:Previous>");
                return "OK 上一曲";
            }
            if ("status".equals(op)) {
                // v1.3.136：status 是模型判断「到底有没有在播」的唯一依据 ——
                // 以前只回 DLNA 状态，而 DLNA 对 player 自己的播放引擎未必同步，
                // 于是「小智启动前就在放歌」时它只能答「播放列表为空」。
                // 现在两条都给：DLNA 真状态 + 本地记录（重启后仍存活）。
                String st = "";
                try {
                    String s = avt("GetTransportInfo",
                            "<u:GetTransportInfo xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
                                    + "<InstanceID>0</InstanceID></u:GetTransportInfo>");
                    st = tag(s, "CurrentTransportState");
                    dlnaState = st;
                } catch (Throwable t) {
                    st = "(查询失败)";
                }
                String pos = "";
                String meta = "";
                try {
                    String p = avt("GetPositionInfo",
                            "<u:GetPositionInfo xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
                                    + "<InstanceID>0</InstanceID></u:GetPositionInfo>");
                    pos = " 第" + tag(p, "Track") + "首 时长=" + tag(p, "TrackDuration")
                            + " 位置=" + tag(p, "RelTime");
                    meta = didlTitle(tag(p, "TrackMetaData"));
                    if (meta.length() > 0) {
                        curTitle = meta;      // DLNA 给的曲名更真，覆盖记录
                        savePlayState(softPlaying);
                    }
                } catch (Throwable t) {
                    // 忽略：状态为主
                }
                StringBuilder sb = new StringBuilder("OK DLNA状态=");
                sb.append(st).append(pos);
                if (meta.length() > 0) {
                    sb.append(" DLNA曲目=").append(meta);
                }
                sb.append(" | 本地记录=").append(softPlaying ? "在播" : "未在播");
                if (curTitle.length() > 0) {
                    sb.append("《").append(curTitle).append("》");
                }
                if (playStateTs > 0) {
                    sb.append("(").append(
                            (System.currentTimeMillis() - playStateTs) / 1000L).append("秒前)");
                }
                sb.append(" [dlna=").append(avtUrl).append("]");
                return sb.toString();
            }
            if ("volume".equals(op)) {
                // v1.3.100：参数兼容 level / value 两种写法（web 控制台曾用 value，
                // 与这里的 level 不匹配导致「点了没反应」，历史上坑过一次）。
                int lv = -1;
                if (args != null) {
                    lv = args.optInt("level", -1);
                    if (lv < 0) {
                        lv = args.optInt("value", -1);
                    }
                }
                if (lv < 0 || lv > 100) {
                    return "ERR level 需要 0~100";
                }
                // v1.3.100：优先直接改系统 STREAM_MUSIC 音量。player 播的就是这条流，
                // 一条 API 立刻生效，不依赖 DLNA 状态机（DLNA 忙时会给 501 且
                // 只影响它的音量，与系统流还不同源）。
                String err = setSystemVolume(lv);
                if (err == null) {
                    lastVolume = lv;
                    return "OK 音量设为 " + lv + "（系统音乐流）";
                }
                SessionLog.i("volume sys fail: " + err + " -> fallback dlna");
                rcs("SetVolume", "<u:SetVolume xmlns:u=\"urn:schemas-upnp-org:service:RenderingControl:1\">"
                        + "<InstanceID>0</InstanceID><Channel>Master</Channel>"
                        + "<DesiredVolume>" + lv + "</DesiredVolume></u:SetVolume>");
                lastVolume = lv;
                return "OK 音量设为 " + lv + "（DLNA 兜底）";
            }
            if ("random".equals(op)) {
                if (!idxLoaded) {
                    loadIndex();
                }
                if (!idxLoaded) {
                    return "ERR 音乐库不可用: " + idxErr;
                }
                // v1.3.114：支持 count（「随机放 5 首」）。之前 random 没有数量参数，
                // 模型想放 5 首只能调 random 拿全库，再用 4 次 next 去「凑数」——
                // 那样既不限制列表长度，又会把正在播的歌切掉。
                int cnt = args == null ? 0 : args.optInt("count", 0);
                int n = pushSongs("random", randomIdx(cnt));
                if (n > 0) {
                    pushed = true;
                    kickPlay("random", startDelay(n), 2);
                    return "OK 随机播放 " + n + " 首"
                            + (cnt > 0
                                    ? ("（从库里 " + tracks.size() + " 首中随机挑 " + n + " 首）")
                                    : ("（整个曲库共 " + tracks.size() + " 首）"));
                }
                return "ERR 随机播放失败，播放通道不可用";
            }
            // v1.3.178：「随机播放在线音乐」——**每次**都从 NAS 现拉一批新的随机曲目。
            //
            // 与 random 的分工：
            //   random         = 播**本地曲库快照**（启动时从 index.json 拉的那批，不换）
            //   random_online  = 让 NAS 重洗一批牌，再从中随机抽 N 首 ⇒ 每次都不一样
            //
            // 拉不到（NAS 不在 / 没配音乐源 / 上游全挂）时原样退回本地 random ——
            // 宁可播旧的，也不能让用户在音箱前等一场空。
            if ("random_online".equals(op)) {
                int cnt = args == null ? 0 : args.optInt("count", 0);
                if (cnt <= 0) {
                    // v1.3.180：默认条数也统一到「在线曲目上限」（以前写死 100）
                    cnt = onlineMax();
                }
                if (cnt > maxPlaylist()) {
                    cnt = maxPlaylist();
                }
                if (!idxLoaded) {
                    loadIndex();
                }
                List<String[]> pool = refreshRandPool(cnt);
                if (pool != null && pool.size() > 0) {
                    // v1.3.179 ★：本地曲库段先放回去，再接在线新批。
                    //   合并前它们是两行 music_index，整表替换伤不到本地那份；
                    //   现在挤在同一份 JSON 里，不先留着就永久没了。
                    List<String[]> keep = localTracksSnapshot();
                    synchronized (tracks) {
                        tracks.clear();
                        tracks.addAll(keep);
                        tracks.addAll(pool);
                        onlineAppends = 0;
                    }
                    // 只推在线段（keep.size() 之后）—— 用户要的是「播放在线音乐」
                    int n = pushSongs("random_online", shuffledIdx(keep.size()));
                    if (n > 0) {
                        pushed = true;
                        kickPlay("random_online", startDelay(n), 2);
                        return "OK 在线随机播放 " + n
                                + " 首（刚从 NAS 换的新一批；再说一次「随机播放在线音乐」"
                                + "又会换一批）";
                    }
                }
                if (!idxLoaded) {
                    return "ERR 音乐库不可用: " + idxErr;
                }
                int n2 = pushSongs("random_online->local", randomIdx(cnt));
                if (n2 > 0) {
                    pushed = true;
                    kickPlay("random_online->local", startDelay(n2), 2);
                    return "OK 在线曲库暂时拉不到，改播本地曲库 " + n2
                            + " 首（共 " + tracks.size() + " 首）";
                }
                return "ERR 随机播放失败，播放通道不可用";
            }
            // v1.3.178：在线随机池里点某一首 —— 播它，**同时把这批 100 首全排进播放列表**。
            //
            // 为什么能「一键整批进队列」：这批曲目的 url 是 mini-lx 的 /s/<i> 惰性地址，
            // 不需要为每一首单独取链（那是慢的地方），整批推一次就行。
            if ("play_rand_at".equals(op)) {
                int i = args == null ? -1 : args.optInt("i", -1);
                List<String[]> pool = randPoolSnapshot();
                if (i < 0 || i >= pool.size()) {
                    return "ERR 在线随机列表已失效，请重新点一次「在线搜歌」";
                }
                // v1.3.179：同样先留本地段（理由同 random_online）
                List<String[]> keep = localTracksSnapshot();
                synchronized (tracks) {
                    tracks.clear();
                    tracks.addAll(keep);
                    tracks.addAll(pool);
                    onlineAppends = 0;
                }
                int hit = keep.size() + i;
                int n = pushSongs("play_rand_at", hitFirstIdx(hit, keep.size()));
                if (n > 0) {
                    pushed = true;
                    kickPlay("play_rand_at", startDelay(n), 2);
                    String[] e = pool.get(i);
                    String who = (e[1].length() > 0) ? (e[0] + " - " + e[1]) : e[0];
                    return "OK 正在播放 " + who + "（在线随机）—— 这 " + n
                            + " 首已全部排进播放列表，播完自动续";
                }
                return "ERR 播放通道不可用（消息总线发送失败）";
            }
            if ("play_url".equals(op)) {
                String url = args == null ? "" : args.optString("url", "").trim();
                if (url.length() == 0) {
                    return "ERR 缺少 url";
                }
                return playUrl(url, args);
            }
            // v1.3.117：电台专用入口。之前只有 play_song 里顺带做电台匹配，
            // 模型看不到「有电台这回事」，就把电台名塞进 play_url（实测传过
            // 「先锋898」和编造的「http://fm91.8」）⇒ 播不出声还回 OK。
            if ("play_radio".equals(op)) {
                String q = args == null ? "" : args.optString("name", "").trim();
                if (q.length() == 0) {
                    q = args == null ? "" : args.optString("title", "").trim();
                }
                if (q.length() == 0) {
                    q = args == null ? "" : args.optString("q", "").trim();
                }
                if (!idxLoaded) {
                    loadIndex();
                }
                // v1.3.120：「第 N 个电台」—— 序号可以走 index 参数，也可以直接写进 name。
                // 命中序号就**只播那一个台**（用户要的是那一个，不是整张列表）。
                int ord = args == null ? 0 : args.optInt("index", 0);
                if (ord <= 0) {
                    ord = radioOrdinal(q);
                }
                if (ord > 0) {
                    if (ord > radios.size()) {
                        return "ERR 一共只有 " + radios.size() + " 个电台，没有第 "
                                + ord + " 个。"
                                + (radios.size() > 0 ? ("可用：" + radioNames(15)) : "");
                    }
                    return playRadio(radios.get(ord - 1), "play_radio#" + ord, false);
                }
                // v1.3.119：「所有电台 / 全部电台」这种泛词直接整表推送，
                // 不再落到 matchRadio 去匹配一个根本不存在的台名。
                if (isAllRadio(q)) {
                    return playAllRadios("play_radio->all", -1);
                }
                String[] rr = matchRadio(q);
                if (rr == null) {
                    return "ERR 没找到电台「" + q + "」，要说完整台名。"
                            + "想一次把所有电台排进列表请用 play_radio_all。"
                            + (radios.size() > 0
                                    ? ("可用电台：" + radioNames(15))
                                    : "（电台列表是空的，在 NAS 的 radios.json 里加）");
                }
                // 控制台调试用：?op=radio_dlna 强制走 DLNA 通道对比
                boolean viaDlna = args != null
                        && "dlna".equals(args.optString("via", ""));
                return playRadio(rr, "play_radio", viaDlna);
            }
            // v1.3.119：一次性把所有电台排进播放列表（可选 name = 从哪个台开始）。
            if ("play_radio_all".equals(op)) {
                if (!idxLoaded) {
                    loadIndex();
                }
                String q = args == null ? "" : args.optString("name", "").trim();
                if (q.length() == 0) {
                    q = args == null ? "" : args.optString("title", "").trim();
                }
                int start = -1;
                // v1.3.120：序号（index 参数，或 name 写成「第3个」）⇒ 从那个台开始
                int ord = args == null ? 0 : args.optInt("index", 0);
                if (ord <= 0) {
                    ord = radioOrdinal(q);
                }
                if (ord > 0) {
                    start = Math.min(ord, radios.size()) - 1;
                } else if (q.length() > 0 && !isAllRadio(q)) {
                    String[] rr = matchRadio(q);
                    if (rr != null) {
                        start = radioIndexOf(rr);
                    }
                }
                return playAllRadios("play_radio_all", start);
            }
            // v1.3.119：手动设播放模式（调试用，?op=mode&v=N）
            // v1.3.172：真值改成 1=随机 2=顺序 3=单曲循环（0 原厂不收，setPlayMode 内部会校正成 2）
            if ("set_mode".equals(op)) {
                int v = args == null ? 0 : args.optInt("mode", 0);
                boolean ok = setPlayMode(v);
                return ok ? ("OK 播放模式设为 " + lastMode + "（1=随机 2=顺序 3=单曲循环）")
                        : "ERR 设置失败（消息总线发送失败）";
            }

            // v1.3.172：重播「上次那一首」。
            //
            // 背景：player 只有「列表 + index」模型，列表是 player 进程的**内存态** ——
            // player 一重启（装机 / 崩溃 / 断电）列表就没了；而 curTitle 是我们**落盘**的
            // （K_TITLE，跨进程重启存活），于是控制台顶栏还挂着上次的曲名，点 ▶ 却毫无反应
            // （空列表没有可播对象）。以前只能「要么不显示，要么让它真能播」二选一，
            // 现在选后者：用落盘的曲名回库里反查，找到就整段推回 player 并开播。
            //
            // 找不到 / 压根没记录 ⇒ 退化成 random（保证「点了 ▶ 一定有声音」）。
            if ("replay".equals(op)) {
                String q = (curTitle == null) ? "" : curTitle.trim();
                if (!idxLoaded) {
                    loadIndex();
                }
                if (!idxLoaded) {
                    return "ERR 音乐库不可用: " + idxErr;
                }
                if (q.length() > 0) {
                    List<Integer> hits = matchTracks(q, false);
                    // 曲名单独配不上（同名太多 / 带了后缀）⇒ 加上歌手再试一次
                    if (hits.size() == 0 && curArtist != null && curArtist.length() > 0) {
                        hits = matchTracks(curArtist + " " + q, false);
                    }
                    if (hits.size() > 0) {
                        List<Integer> pick = new ArrayList<Integer>(hits);
                        int cap = maxPlaylist();
                        if (pick.size() > cap) {
                            pick = new ArrayList<Integer>(pick.subList(0, cap));
                        }
                        int n = pushSongs("replay", pick);
                        if (n > 0) {
                            pushed = true;
                            kickPlay("replay", startDelay(n), 2);
                            SessionLog.i("replay 「" + q + "」: 命中 " + hits.size()
                                    + " 首，推 " + n + " 首");
                            return "OK 重新播放「" + q + "」（库里命中 " + hits.size()
                                    + " 首，已排入 " + n + " 首）";
                        }
                    }
                    SessionLog.i("replay: 库里没有「" + q + "」，退化为随机");
                }
                int n2 = pushSongs("replay->random", shuffledIdx());
                if (n2 > 0) {
                    pushed = true;
                    kickPlay("replay->random", startDelay(n2), 2);
                    return q.length() > 0
                            ? ("OK 库里找不到「" + q + "」，改为随机播放 " + n2 + " 首")
                            : ("OK 没有上次播放记录，随机播放 " + n2 + " 首");
                }
                return "ERR 播放通道不可用（消息总线发送失败）";
            }
            if ("play_song".equals(op)) {
                String q = args == null ? "" : args.optString("title", "");
                if (q.length() == 0) {
                    q = args == null ? "" : args.optString("name", "");
                }
                if (!idxLoaded) {
                    loadIndex();
                }
                if (!idxLoaded) {
                    return "ERR 音乐库不可用: " + idxErr;
                }
                // v1.3.112：拿全部命中，播第一条；若还有其它命中，把「可以连播」这个
                // 信息回给模型，让它有机会提示用户（v1.3.111 只播第一条且不说还有多少）。
                List<Integer> hits = matchTracks(q, false);
                if (hits.size() > 0) {
                    int hit = hits.get(0).intValue();
                    // v1.3.177：本地只捞到「最接近」的一条（包含级 / 部分词命中）
                    // ⇒ 先去 NAS 全源在线搜。语音点名要的歌几乎一定在在线结果里排第一，
                    //   这是「在线优先」：拿到了就用在线那条，拿不到才回落池内这条
                    //   （还有本地兜底，所以这里给较短预算，别让语音干等）。
                    if (!localHitSolid(q, hit)) {
                        String on = playOnlineSong(q, 4000, 8000);
                        if (on != null) {
                            return on;
                        }
                    }
                    String[] e = tracks.get(hit);
                    String url = urlOf(e);
                    String who = e[1].length() > 0 ? (e[0] + " - " + e[1]) : e[0];
                    // 主通道：把整个歌单（命中项排第一）推给 player 自己的播放引擎
                    int n = pushSongs("play_song", hitFirstIdx(hit));
                    // v1.3.157：同名曲很多（原唱/翻唱/Live/DJ），播的是「最像的那一条」，
                    // 但用户未必想要它 —— 把带序号的候选回给模型，小智才有机会说
                    // 「我还找到周杰伦版，要听吗」，而不是闷声播了翻唱。
                    String alt = altHint(hits, hit);
                    if (n > 0) {
                        SessionLog.i("play song (msgcenter): " + who + " -> " + url
                                + " (hits=" + hits.size() + ", list=" + n + ")");
                        pushed = true;
                        kickPlay("play_song", startDelay(n), 2);
                        // v1.3.178：专辑/时长/来源交给 pushPlaylist→notePlayingTrack 从曲目里取，
                        // 这里不再手填（手填会漏掉在线条目的 via="online"）。
                        notePlayingTrack(hit);
                        return "OK 正在播放 " + who + alt;
                    }
                    SessionLog.i("play song (dlna fallback): " + who + " -> " + url);
                    String r = setUri(url);
                    return r.startsWith("OK") ? ("OK 正在播放 " + who + alt) : (r + alt + " (" + who + ")");
                }
                // v1.3.120：序号（模型可能把「第 N 个电台」派给 play_song）
                int ord2 = args == null ? 0 : args.optInt("index", 0);
                if (ord2 <= 0) {
                    ord2 = radioOrdinal(q);
                }
                if (ord2 > 0 && ord2 <= radios.size()) {
                    return playRadio(radios.get(ord2 - 1), "play_song#" + ord2, false);
                }
                // v1.3.119：曲库里没有、又是「所有电台」这类泛词 ⇒ 整表推送
                if (isAllRadio(q)) {
                    return playAllRadios("play_song->all_radio", -1);
                }
                // 电台（v1.3.117：统一走 playRadio，与 play_radio 共用同一套逻辑）
                String[] rr = matchRadio(q);
                if (rr == null) {
                    // v1.3.174：曲库快照 / 电台都没中 ⇒ 去 NAS mini-lx 在线搜一把。
                    // index.json 是启动时拉的快照，快照外的歌（新歌/没进曲库池的歌）
                    // 以前到这里只能 ERR；在线搜到就按「库里刚多了一首」推播。
                    // playOnlineSong 全程自兜底，任何一步失败返回 null ⇒ 照旧 ERR。
                    String on = playOnlineSong(q);
                    if (on != null) {
                        return on;
                    }
                    return "ERR 没找到「" + q + "」，库里共 " + tracks.size() + " 首"
                            + (radios.size() > 0 ? ("；电台：" + radioNames(15)) : "");
                }
                return playRadio(rr, "play_song->radio", false);
            }
            // v1.3.112：随机播放某个歌手/关键词的多首（「随便放点周杰伦」「来几首邓丽君」）。
            // 这是之前完全没有的能力 —— play_song 只播第一条，random 是全库随机，
            // 中间「按条件筛选后随机」这一档是空的。
            if ("play_artist".equals(op)) {
                String q = args == null ? "" : args.optString("artist", "");
                if (q.length() == 0) {
                    q = args == null ? "" : args.optString("name", "");
                }
                if (q.length() == 0) {
                    q = args == null ? "" : args.optString("title", "");
                }
                // v1.3.115：**不填 count = 该歌手/关键词的全部歌曲**（v1.3.112~114 默认 20）。
                // 用户说「播放周杰伦的歌」时想要的就是周杰伦所有的歌，没有理由只给 20 首；
                // 想限量再显式传 count。
                int cnt = args == null ? 0 : args.optInt("count", 0);
                if (cnt > maxPlaylist()) {
                    cnt = maxPlaylist();
                }
                if (!idxLoaded) {
                    loadIndex();
                }
                if (!idxLoaded) {
                    return "ERR 音乐库不可用: " + idxErr;
                }
                // v1.3.114：没给歌手时不要报错 —— 模型经常把「随机放 5 首」派给
                // play_artist 却填不出 artist。这时退化成 random(count)。
                if (q.length() == 0) {
                    int n2 = pushSongs("play_artist->random", randomIdx(cnt));
                    if (n2 > 0) {
                        pushed = true;
                        kickPlay("play_artist->random", startDelay(n2), 2);
                        return "OK 随机播放 " + n2 + " 首（没指定歌手，从库里 "
                                + tracks.size() + " 首中随机挑）";
                    }
                    return "ERR 播放通道不可用（消息总线发送失败）";
                }
                // 歌手优先；歌手里没有再回退到歌名匹配（用户说的可能不是人名）
                List<Integer> hits = matchTracks(q, true);
                if (hits.size() == 0) {
                    hits = matchTracks(q, false);
                }
                // ★ v1.3.184：matchTracks 在「本地 + 在线收录」**合并列表**里搜，
                //   命中数里混着在线条目（池歌/在线搜过的都算），可下面的文案写的
                //   是「本地 X 首」—— 用户关着本地曲库，点周杰伦却听到
                //   「本地 2 首」（其实是池里那 2 首周杰伦）。过滤到真本地；
                //   一条不剩就走下面的在线分支，话术自然就对了。
                for (int i = hits.size() - 1; i >= 0; i--) {
                    int ix = hits.get(i).intValue();
                    if (ix < 0 || ix >= tracks.size() || isOnlineTrack(tracks.get(ix))) {
                        hits.remove(i);
                    }
                }
                // v1.3.178 ★：本地 + 在线一起找。
                //
                // 以前 play_artist 是**纯本地**：库里只有 3 首周杰伦，用户说「随机播放
                // 周杰伦的歌」就只能在这 3 首里打转，而池子外的几十首周杰伦一概看不到。
                // 现在：本地命中照旧算数，再在线搜一批补进来（并行取链，最长 ~5s），
                // 洗牌后一起推 —— 用户点的是"这个歌手的歌"，不是"池里那几首"。
                // 预算：搜 4s + 取链 5s（到点就放弃没回来的，拿多少算多少）。
                // 在线是「加分项」不是必经之路 —— NAS 慢也不能让用户在音箱前干等。
                // v1.3.180：条数统一由「在线曲目上限」管（files/online_max，默认 300）
                List<String[]> on = onlineTracksFor(q, onlineMax(), 4000, 5000);
                if (hits.size() == 0) {
                    // 本地一条都没有：以前直接 ERR，池外歌手彻底哑火。
                    // 现在在线有就在线播，在线也没有才报 ERR。
                    if (on != null && on.size() > 0) {
                        List<Integer> oi = appendOnlineTracks(on);
                        if (oi.size() > 0) {
                            List<Integer> only = new ArrayList<Integer>(oi);
                            Collections.shuffle(only);
                            if (cnt > 0 && only.size() > cnt) {
                                only = new ArrayList<Integer>(only.subList(0, cnt));
                            }
                            int n = pushSongs("play_artist->online", only);
                            if (n > 0) {
                                pushed = true;
                                kickPlay("play_artist->online", startDelay(n), 2);
                                return "OK 本地曲库里没有「" + q
                                        + "」，改为在线播放 " + n + " 首（在线搜到的）";
                            }
                        }
                    }
                    // ★ v1.3.184：报本地数（tracks.size() 是合并容器，在线收录全算
                    //   进去 —— 关着本地曲库时这里会谎报「本地共五百多首」）
                    int locN2 = 0;
                    for (int i = 0; i < tracks.size(); i++) {
                        if (!isOnlineTrack(tracks.get(i))) {
                            locN2++;
                        }
                    }
                    return "ERR 本地和在线都没找到「" + q + "」相关的歌（本地共 "
                            + locN2 + " 首）。用 list 看看库里有哪些歌手";
                }
                List<Integer> pick = new ArrayList<Integer>(hits);
                int onCnt = 0;
                if (on != null && on.size() > 0) {
                    List<Integer> oi = appendOnlineTracks(on);
                    onCnt = oi.size();
                    pick.addAll(oi);
                }
                Collections.shuffle(pick);
                if (cnt > 0 && pick.size() > cnt) {
                    pick = new ArrayList<Integer>(pick.subList(0, cnt));
                }
                // 洗过牌之后第一首可能是冷门翻唱 —— 把最像的那条（本地最佳命中）顶到第一
                int best = hits.get(0).intValue();
                pick.remove(Integer.valueOf(best));
                pick.add(0, Integer.valueOf(best));
                int n = pushSongs("play_artist", pick);
                if (n > 0) {
                    SessionLog.i("play artist '" + q + "': pick " + n
                            + " of " + hits.size() + " local + " + onCnt + " online");
                    pushed = true;
                    kickPlay("play_artist", startDelay(n), 2);
                    return "OK 随机播放「" + q + "」的 " + n + " 首"
                            + "（本地 " + hits.size() + " 首"
                            + (onCnt > 0 ? (" + 在线 " + onCnt + " 首") : "")
                            + (cnt > 0 && (hits.size() + onCnt) > n
                                    ? ("，按你要的只取了 " + n + " 首") : "")
                            + "）";
                }
                return "ERR 播放通道不可用（消息总线发送失败）";
            }

            // v1.3.112：按序号播放（「放第 8 首」）。序号与 search/list 输出的编号一致（1 起）。
            if ("play_index".equals(op)) {
                if (!idxLoaded) {
                    loadIndex();
                }
                if (!idxLoaded) {
                    return "ERR 音乐库不可用: " + idxErr;
                }
                int n = args == null ? 0 : args.optInt("index", 0);
                if (n <= 0) {
                    n = args == null ? 0 : args.optInt("n", 0);
                }
                if (n <= 0) {
                    return "ERR 需要 index 参数（第几首，从 1 开始）";
                }
                if (n > tracks.size()) {
                    return "ERR 序号 " + n + " 超出范围，库里只有 " + tracks.size() + " 首";
                }
                int hit = n - 1;
                String[] e = tracks.get(hit);
                String url = urlOf(e);
                String who = e[1].length() > 0 ? (e[0] + " - " + e[1]) : e[0];
                int cnt = pushSongs("play_index", hitFirstIdx(hit));
                if (cnt > 0) {
                    SessionLog.i("play index #" + n + " (msgcenter): " + who + " -> " + url
                            + " (list=" + cnt + ")");
                    pushed = true;
                    kickPlay("play_index", startDelay(cnt), 2);
                    return "OK 正在播放第 " + n + " 首：" + who;
                }
                SessionLog.i("play index #" + n + " (dlna fallback): " + who + " -> " + url);
                String r = setUri(url);
                return r.startsWith("OK") ? ("OK 正在播放第 " + n + " 首：" + who) : (r + " (" + who + ")");
            }

            // v1.3.163：跳到自有队列第 N 首（1-based）—— 8080 的 {"type":"play","index":N}
            // 的等价物。队列是我们自己 push 的那张列表，不依赖 player 开 WS。
            if ("play_at".equals(op)) {
                int n = args == null ? 0 : args.optInt("index", 0);
                if (n <= 0) {
                    n = args == null ? 0 : args.optInt("i", 0);
                }
                if (n <= 0) {
                    return "ERR 需要 index 参数（队列第几首，从 1 开始）";
                }
                return playAt(n - 1);
            }
            // v1.3.163：只返回自有队列（不播）。web 在 8080 不可用时用它渲染列表。
            if ("queue".equals(op)) {
                int lim = args == null ? 0 : args.optInt("limit", 0);
                return "OK " + queueJson(lim).toString();
            }

            // v1.3.112：只查不播，返回带序号的候选列表。模型要「放第几首」必须先有这个。
            if ("search".equals(op)) {
                if (!idxLoaded) {
                    loadIndex();
                }
                if (!idxLoaded) {
                    return "ERR 音乐库不可用: " + idxErr;
                }
                String q = args == null ? "" : args.optString("keyword", "");
                if (q.length() == 0) {
                    q = args == null ? "" : args.optString("title", "");
                }
                if (q.length() == 0) {
                    q = args == null ? "" : args.optString("artist", "");
                }
                // ★ v1.3.182：search 的工具描述写的就是「只覆盖本地曲库池」，
                //   但 matchTracks 是在合并列表里搜 —— 在线听过的歌全混进来了。
                //   过滤掉（下标仍是全库下标，play_index 不受影响）。
                List<Integer> raw = matchTracks(q, false);
                List<Integer> hits = new ArrayList<Integer>(raw.size());
                for (int i = 0; i < raw.size(); i++) {
                    int ix = raw.get(i).intValue();
                    if (ix >= 0 && ix < tracks.size() && !isOnlineTrack(tracks.get(ix))) {
                        hits.add(Integer.valueOf(ix));
                    }
                }
                if (hits.size() == 0) {
                    int locN = 0;
                    for (int i = 0; i < tracks.size(); i++) {
                        if (!isOnlineTrack(tracks.get(i))) {
                            locN++;
                        }
                    }
                    return "ERR 没找到和「" + q + "」相关的本地歌，本地曲库共 " + locN
                            + " 首。用 list 看看有哪些歌手";
                }
                StringBuilder sb = new StringBuilder("OK 找到 " + hits.size() + " 首：");
                int lim = Math.min(hits.size(), 40);
                for (int j = 0; j < lim; j++) {
                    int i = hits.get(j).intValue();
                    String[] e = tracks.get(i);
                    sb.append(i + 1).append(".").append(e[0]);
                    if (e[1].length() > 0) {
                        sb.append("(").append(e[1]).append(")");
                    }
                    sb.append("、");
                }
                if (hits.size() > lim) {
                    sb.append("…仅列前 ").append(lim).append(" 首");
                }
                sb.append("。说「播放第 N 首」用 play_index，说「随机播放 XX」用 play_artist");
                return sb.toString();
            }

            if ("list".equals(op)) {
                if (!idxLoaded) {
                    loadIndex();
                }
                if (!idxLoaded) {
                    return "ERR 音乐库不可用: " + idxErr;
                }
                // v1.3.112：1185 首的库列 60 首歌名没意义（顺序固定、永远同一批、
                // 还挤爆上下文）。改成「歌手统计 + 少量带序号样例」，这才是对
                // 「随机放某歌手的歌」真正有用的信息。
                // ★ v1.3.182：tracks 是「本地 + 在线收录」的合并容器 —— 在线歌播过
                //   就会 append 进来（上限 400）。用户问「本地歌曲里有什么/几首」，
                //   模型照着「共 N 首」回答就成了「本地一共有五百四十七首」
                //   （用户原话：「这应该是把之前播过的在线歌都当成本地了」）。
                //   ⇒ 本地/在线分开报，歌手与样例只取本地。
                int locN = 0;
                int onlN = 0;
                for (int i = 0; i < tracks.size(); i++) {
                    if (isOnlineTrack(tracks.get(i))) {
                        onlN++;
                    } else {
                        locN++;
                    }
                }
                StringBuilder sb = new StringBuilder("OK 本地共 " + locN + " 首。");
                if (locN > 0) {
                    sb.append("歌手：").append(artistSummary(30, true)).append("。");
                    // 样例先攒后拼 —— 表头里的数量必须是真实显示数（本地段里夹着
                    // 在线条目时，扫描全库也未必凑得满 20）
                    StringBuilder sample = new StringBuilder();
                    int shown = 0;
                    for (int i = 0; i < tracks.size() && shown < 20; i++) {
                        if (isOnlineTrack(tracks.get(i))) {
                            continue;
                        }
                        shown++;
                        sample.append(i + 1).append(".").append(tracks.get(i)[0]);
                        if (tracks.get(i)[1].length() > 0) {
                            sample.append("(").append(tracks.get(i)[1]).append(")");
                        }
                        sample.append("、");
                    }
                    sb.append("样例 ").append(shown).append(" 首（带序号）：").append(sample)
                            .append("…（要找某歌手/歌名用 search，随机连播某歌手用 play_artist）");
                }
                if (onlN > 0) {
                    // 在线收录的要说清来源身份，别让模型把它们当成本地的转述
                    sb.append("。另收录了 ").append(onlN)
                            .append(" 首在线曲库的歌（不属于本地，点歌/随机时能播到）");
                }
                if (radios.size() > 0) {
                    // v1.3.120：带序号 —— 用户说「第 N 个电台」时模型（和它自己）
                    // 要能对上号，否则只能猜名字。
                    sb.append("。电台（前 ")
                            .append(Math.min(12, radios.size()))
                            .append(" 个，带序号）：");
                    int rn = Math.min(12, radios.size());
                    for (int i = 0; i < rn; i++) {
                        sb.append(i + 1).append(".").append(radios.get(i)[0]).append("、");
                    }
                    if (radios.size() > rn) {
                        sb.append("…等共 ").append(radios.size())
                                .append(" 个台（说「第 N 个电台」可按序号播）");
                    }
                }
                return sb.toString();
            }
            return "ERR unknown op: " + op;
        } catch (Throwable t) {
            Log.w(TAG, "dispatch " + op + " failed: " + t);
            return "ERR " + op + ": " + t;
        }
    }

    private static String setUri(String url) throws Exception {
        // DLNA 渲染器在 STATUS_BUSY（已有媒体在播）时会对 SetAVTransportURI 回
        // 501 Action Failed，先 Stop 一次能显著提高成功率；Stop 本身失败可忽略。
        try {
            avt("Stop", "<u:Stop xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
                    + "<InstanceID>0</InstanceID></u:Stop>");
        } catch (Throwable t) {
            Log.w(TAG, "dlna pre-stop ignored: " + t);
        }
        // CurrentURIMetaData 留空实测可用（2026-10-09）。
        avt("SetAVTransportURI",
                "<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
                        + "<InstanceID>0</InstanceID><CurrentURI>" + xmlEsc(url)
                        + "</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>"
                        + "</u:SetAVTransportURI>");
        avt("Play", "<u:Play xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
                + "<InstanceID>0</InstanceID><Speed>1</Speed></u:Play>");
        return "OK 已开始播放";
    }

    // ---------------- SOAP ----------------

    private static final String SVC_AVT = "urn:schemas-upnp-org:service:AVTransport:1";
    private static final String SVC_RCS = "urn:schemas-upnp-org:service:RenderingControl:1";

    private static String avt(String action, String body) throws Exception {
        return call(true, SVC_AVT, action, body);
    }

    private static String rcs(String action, String body) throws Exception {
        return call(false, SVC_RCS, action, body);
    }

    /**
     * 发一次 SOAP。端口失效时（player 重启过）自动重新发现再试一次。
     */
    private static synchronized String call(boolean isAvt, String svc, String action, String body)
            throws Exception {
        if (avtUrl == null && !discover()) {
            throw new Exception("DLNA renderer not found on localhost");
        }
        String url = isAvt ? avtUrl : rcsUrl;
        try {
            return soap(url, svc, action, body);
        } catch (Throwable t) {
            Log.w(TAG, "dlna " + action + " failed (" + t + "), rediscovering");
            if (discover()) {
                return soap(isAvt ? avtUrl : rcsUrl, svc, action, body);
            }
            throw (t instanceof Exception) ? (Exception) t : new Exception(String.valueOf(t));
        }
    }

    // ---------------- DLNA 动态发现 ----------------

    /**
     * 在本机 LISTEN 端口里找 UPnP 设备描述，解析出 AVTransport / RenderingControl 的 controlURL。
     * 依据：/proc/net/tcp 的 st=0A（LISTEN）。
     */
    private static synchronized boolean discover() {
        List<Integer> ports = listenPorts();
        for (int i = 0; i < ports.size(); i++) {
            int p = ports.get(i);
            if (p <= 1024 || p == 5555 || p > 65535) {
                continue;
            }
            try {
                String xml = httpGet("http://127.0.0.1:" + p + "/", 1200);
                if (xml == null || xml.length() == 0 || xml.indexOf("AVTransport") < 0) {
                    continue;
                }
                String a = ctrlOf(xml, "AVTransport");
                if (a == null) {
                    continue;
                }
                String r = ctrlOf(xml, "RenderingControl");
                avtUrl = "http://127.0.0.1:" + p + a;
                rcsUrl = (r == null) ? null : ("http://127.0.0.1:" + p + r);
                dlnaPort = p;   // v1.3.99：暴露给 web 控制台
                Log.i(TAG, "dlna discovered port=" + p + " avt=" + a);
                SessionLog.i("dlna discovered: 127.0.0.1:" + p);
                return true;
            } catch (Throwable t) {
                // 不是 DLNA 端口，继续
            }
        }
        return false;
    }

    /** 从设备描述 XML 里取指定服务的 controlURL。 */
    private static String ctrlOf(String xml, String service) {
        int i = 0;
        while (true) {
            int s = xml.indexOf("<service>", i);
            if (s < 0) {
                return null;
            }
            int e = xml.indexOf("</service>", s);
            if (e < 0) {
                return null;
            }
            String blk = xml.substring(s, e);
            if (blk.indexOf("service:" + service + ":") >= 0) {
                int a = blk.indexOf("<controlURL>");
                if (a < 0) {
                    return null;
                }
                int b = blk.indexOf("</controlURL>", a);
                if (b < 0) {
                    return null;
                }
                return blk.substring(a + 12, b);
            }
            i = e + 10;
        }
    }

    /** 读 /proc/net/tcp，返回本机处于 LISTEN 的端口列表。 */
    private static List<Integer> listenPorts() {
        List<Integer> out = new ArrayList<Integer>();
        try {
            FileInputStream in = new FileInputStream("/proc/net/tcp");
            String all = readAll(in);
            String[] lines = all.split("\n");
            for (int i = 1; i < lines.length; i++) {
                String[] f = lines[i].trim().split("\\s+");
                if (f.length < 4) {
                    continue;
                }
                if (!"0A".equalsIgnoreCase(f[3])) {
                    continue;
                }
                int c = f[1].indexOf(':');
                if (c < 0) {
                    continue;
                }
                try {
                    out.add(Integer.parseInt(f[1].substring(c + 1), 16));
                } catch (Throwable t) {
                    // ignore
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "/proc/net/tcp unreadable: " + t);
        }
        return out;
    }

    private static String soap(String url, String service, String action, String body)
            throws Exception {
        String env = "<?xml version=\"1.0\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body>" + body + "</s:Body></s:Envelope>";
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            c.setRequestProperty("SOAPACTION", "\"" + service + "#" + action + "\"");
            c.setDoOutput(true);
            c.setConnectTimeout(3000);
            c.setReadTimeout(6000);
            OutputStream os = c.getOutputStream();
            os.write(env.getBytes("UTF-8"));
            os.close();
            int code = c.getResponseCode();
            InputStream is = (code >= 400) ? c.getErrorStream() : c.getInputStream();
            String r = (is == null) ? "" : readAll(is);
            if (code >= 400 || r.contains("<s:Fault") || r.contains("errorCode")) {
                throw new Exception("HTTP " + code + " " + r);
            }
            Log.i(TAG, "dlna " + action + " ok");
            return r;
        } finally {
            c.disconnect();
        }
    }

    private static String tag(String xml, String name) {
        int i = xml.indexOf("<" + name + ">");
        if (i < 0) {
            return "";
        }
        int j = xml.indexOf("</" + name + ">", i);
        if (j < 0) {
            return "";
        }
        return xml.substring(i + name.length() + 2, j);
    }

    private static String xmlEsc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---------------- IO ----------------

    private static String httpGet(String url, int timeoutMs) throws Exception {
        return httpGet(url, timeoutMs, 8 * 1024 * 1024, 20000);
    }

    /** v1.3.116：可指定字节上限与总耗时上限（拉 index.json 用更紧的档位）。 */
    private static String httpGet(String url, int timeoutMs, int maxBytes,
                                  long deadlineMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("GET");
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            int code = c.getResponseCode();
            if (code != 200) {
                throw new Exception("HTTP " + code + " for " + url);
            }
            String ct = c.getHeaderField("Content-Type");
            if (ct != null) {
                String l = ct.toLowerCase();
                // 明确的音频/视频流直接拒掉，给用户一句能看懂的话
                if (l.startsWith("audio/") || l.startsWith("video/")
                        || l.contains("mpegurl")) {
                    throw new Exception("这个地址返回的是 " + ct
                            + "（音视频流），不是 index.json。"
                            + "直播流请填进 NAS 的 radios.json，不要放在这里");
                }
            }
            return readAll(c.getInputStream(), maxBytes, deadlineMs);
        } finally {
            c.disconnect();
        }
    }

    /**
     * v1.3.116：带上限地读完一个流。
     *
     * 原来是无上限的 while(read>0) —— 遇到**永不结束的直播流**（用户误把
     * qingting 的 64k.mp3 当 index.json 填进音乐源）就会一直读下去：
     * setReadTimeout 只在「单次 read 阻塞超时」时触发，直播流一直在送数据所以
     * 永不超时 ⇒ loadIndex() 卡死并一直占着 PlayerBridge 类锁 ⇒
     * setIndexSpec()（保存音乐源走的路）跟着排队 ⇒ 控制台「保存」永远无响应，
     * 表现就是「加进去的地址删不掉」。
     *
     * 现在：字节超过 maxBytes、或总耗时超过 deadlineMs，直接抛异常收手。
     */
    private static String readAll(InputStream is, int maxBytes, long deadlineMs)
            throws Exception {
        long t0 = System.currentTimeMillis();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) {
            bo.write(buf, 0, n);
            if (bo.size() > maxBytes) {
                is.close();
                throw new Exception("响应超过 " + (maxBytes / 1024) + "KB，"
                        + "这不像一个 index.json（是不是填成了音频流地址？）");
            }
            if (System.currentTimeMillis() - t0 > deadlineMs) {
                is.close();
                throw new Exception("读取超过 " + deadlineMs + "ms 仍没结束，"
                        + "这不像一个 index.json（是不是填成了直播流地址？）");
            }
        }
        is.close();
        return new String(bo.toByteArray(), "UTF-8");
    }

    private static String readAll(InputStream is) throws Exception {
        return readAll(is, 8 * 1024 * 1024, 20000);
    }

    private static String readFile(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            return readAll(in);
        } finally {
            in.close();
        }
    }
}
