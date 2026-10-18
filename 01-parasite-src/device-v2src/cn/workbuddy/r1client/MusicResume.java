package cn.workbuddy.r1client;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * v1.3.130：唤醒期间被「系统中断」暂停的音乐，在**小智说完话之后**自动恢复播放。
 *
 * <h3>为什么会有这个类</h3>
 * 原厂 USC 的唤醒流程里有一条音乐让路逻辑（反编译取证，2026-10-12）：
 * <pre>
 *   PhicommExternalMusicHandler.doInterrupt(ctx, "doActiveInterrupt")
 *     ├─ type == "doFinishAllInterrupt" → CommonPlayer.stop()  + shouldResume=false
 *     └─ 其它（唤醒/ASR）              → CommonPlayer.pause() + shouldResume=true
 *   ...
 *   会话结束 → SimpleSessionManager.fireResume() → manageState("doResume")
 *            → PhicommExternalMusicHandler.doResume(ctx)
 *              └─ if (mIsExternMode && shouldResume) → CommonPlayer.resume()
 * </pre>
 * 也就是说：**暂停是原厂干的，恢复本来也是原厂干的**（`shouldResume` 就是它的欠账标记）。
 *
 * <p>但 jp2 顶替 device 包之后，小智的 TTS 不走 USC 的 TTS 出口（v1.3.70 起被短路），
 * USC 的会话生命周期因此不按原厂流程收尾 ⇒ {@code fireResume} 永不触发 ⇒ 那笔
 * "shouldResume" 的欠账永远还不掉，音乐就停在暂停的位置。
 *
 * <h3>做法</h3>
 * <ol>
 *   <li><b>镜像原厂的欠账标记</b>：在两个 music handler 的
 *       doInterrupt(pause) / doInterrupt(stop) / doResume(resume) 三处各插一个静态调用，
 *       把原厂自己的判断结果转告本类。我们**不猜电平、不查 DLNA**，只信原厂。
 *       ★ <b>v1.3.135 起只有 {@link #markStopped()} / {@link #markResumed()} 由 smali 调用</b>；
 *       {@link #markPaused(String)} **不再从 smali 调**（见 v1.3.135 一节），
 *       只在 {@link #onTtsAudioStart()} 内部记账，避免「账记了、音乐没停」。
 *       ★ v1.3.134 起：两个 handler 都要钩 ——
 *       {@code DefaultMusicHandler}（小智语音起播的音乐，doInterrupt 里带 isPlaying() 判定）
 *       和 {@code PhicommExternalMusicHandler}（MCP/Web/DLNA 起播的 external 模式音乐，
 *       **用户的音乐走的是这条**，v1.3.133 只钩前一条，被完全绕过）。</li>
 *   <li><b>补上恢复动作</b>：小智说完话（TTS stop）后 {@value #RESUME_DELAY_MS}ms 内
 *       没有新一轮 TTS / 没有新的 STT，就发一条 player 总线 play（subType=3），
 *       等价于原厂 {@code CommonPlayer.resume()} → {@code PhicommMusicController.play()}。</li>
 *   <li><b>用户显式指令优先</b>：服务端若调过 {@code self.r1player.pause / stop}
 *       （= 用户自己说了「暂停 / 停止」），置 {@link #noteUserPause()} ⇒ 本轮永不自动恢复；
 *       之后用户说「继续播放」或要放新歌，{@link #noteUserPlay()} 把它清掉。</li>
 * </ol>
 *
 * <p>开关：{@code files/no_music_resume} 存在 → 整个功能关闭（默认开）。
 *
 * <h3>v1.3.132：TTS 与音乐互斥（第二功能，默认关）</h3>
 * 唤醒让路只覆盖「喊唤醒词那一刻」。**连续对话**里后续轮次不再经过唤醒
 * （15s 内直接接着说 / 小智主动开口），音乐就没人管了，会和小智的声音叠在一起。
 * 因此新增「小智说话时暂停音乐、说完继续」：Web 勾选 / {@code files/music_pause_tts}
 * 打开后，每一轮 TTS 开始都让路（{@link #markPaused(String)}），说完照旧由
 * {@link #onTurnEnded(String)} 接回。
 *
 * <p>只在「音乐本来就在播」时才让路 —— 判据是原厂钩子的 {@code pending}
 * （= 原厂 {@code isPlaying()} 的结论）或 {@link PlayerBridge#isSoftPlaying()}
 * （= 用户确实让我们放过歌）。音乐没在播时 pause 是空转，更糟糕的是恢复会
 * 「无中生有」把上一曲拽回来，所以必须先判断再动手。
 *
 * <h3>v1.3.138：开关语义统一 —— 「小智说话时暂停音乐」管所有轮次</h3>
 * 用户实测（2026-10-13）：「唤醒后第一次对话不管 web 上的说话暂停开关有没有打开
 * 音乐都会暂停，之后正常」。根因不是时序，是<b>门禁位置</b>：v1.3.134~137 的
 * {@link #onTtsAudioStart()} 先把唤醒让路分支处理完再 {@code return}，
 * {@code !ttsPause} 的检查排在它后面 ⇒ 唤醒轮永远绕开开关。
 * 修法：{@link #deferPause(boolean)} 与 {@link #onTtsAudioStart()} 两处都把
 * {@code ttsPause} 放在最前面当唯一门禁。
 *
 * <h3>v1.3.134：让路时机 = 「小智第一帧音频真正开播的那一刻」</h3>
 * v1.3.133 试过「唤醒后延后 1.5s 再暂停」，**没起效**，两个原因（2026-10-13 日志实证）：
 * <ol>
 *   <li>用户的音乐走 {@code PhicommExternalMusicHandler}（external 模式），
 *       v1.3.133 的 deferPause 钩子只插在 {@code DefaultMusicHandler} ⇒ 被完全绕过
 *       （日志有 {@code markPaused(wakeup)}、无 {@code deferPause}，音乐仍是唤醒即停）。</li>
 *   <li>实测唤醒→小智开口是 <b>3.5~8.8s</b>（唤醒→STT 2.8~4.9s + STT→TTS 首帧
 *       0.75~3.9s），1.5s 定时器永远抢在开口之前 ⇒ 空隙根本没缩短；
 *       退到 {@code onTtsStart}（tts start 事件）暂停也一样——事件比首帧音频还早
 *       0.75~3.9s。</li>
 * </ol>
 * 现在：唤醒时**只挂起**（{@link #deferPause(boolean)}，音乐继续播），
 * 暂停动作统一挪到 {@link #onTtsAudioStart()} —— 由 {@code TtsPlayer} 播放循环
 * 在第一帧音频开播前一刻调用（就是打点 "tts-first-frame-&gt;play-start" 的地方）。
 * 空隙 ≈ 0。唤醒后啥也没说（没有 TTS 音频）⇒ {@link #onTurnEnded(String)} 撤掉挂起
 * ⇒ 音乐压根没停过，也不会补恢复。
 *
 * <h3>v1.3.135：挂起分支必须「直接返回」，不能顺手记账</h3>
 * v1.3.134 装机实测（2026-10-13 20:58）打脸：
 * <pre>
 *   20:58:40.237 music deferPause (唤醒让路挂起：音乐继续播… trusted=true)
 *   20:58:40.238 music markPaused(wakeup) → pause + 待恢复     ← 只隔 1ms！
 * </pre>
 * 原因：v1.3.130 留下的 {@code markPaused()} 钩子紧跟在挂起分支后面
 * （{@code DefaultMusicHandler.smali} / {@code PhicommExternalMusicHandler.smali} 各一处），
 * v1.3.134 只把原厂 pause 换成 deferPause，**没摘掉这个旧钩子** ⇒ 音乐其实没被暂停，
 * 却记了一笔恢复账 ⇒ 小智说完话会把上一曲凭空拽回来；且 {@code deferPending} 与
 * {@code pending} 两套语义打架。
 * <p>修法：两个挂起分支在 {@code deferPause} 之后直接 {@code return}（smali: goto），
 * 记账全部交给 {@link #onTtsAudioStart()}。同时去掉 external 分支的
 * {@code isSoftPlaying} 门禁（软状态进程重启即丢，会漏让路），改为无条件挂起 +
 * 开播时查真状态（阴性撤账，不会无中生有）。
 *
 * <h3>v1.3.137：DLNA 判「在不在播」是错的，改用 AudioManager.isMusicActive()</h3>
 * 2026-10-13 实测（放歌中查 {@code ?op=status}）：
 * <pre>
 *   本地记录=在播《The Tradition》(8秒前)     ← 耳朵也确认在响
 *   DLNA状态=NO_MEDIA_PRESENT 第0首 时长=00:00:00 位置=00:00:00
 * </pre>
 * ⇒ <b>DLNA 只是外部控制通道，状态机跟 player 自己的播放引擎根本不同步</b>，
 * 用它判在播必然恒阴性（v1.3.133~135 拿它当"真状态兜底"是设计错误，会让让路
 * 一路撤账）。改用 {@link PlayerBridge#isMusicActive()}：
 * <ul>
 *   <li>★ 采样点必须是**唤醒那一刻**（{@link #deferPause(boolean)}）—— 那时 TTS 还没
 *       开播，AudioManager 看到的 STREAM_MUSIC 只可能是音乐；等到首帧开播再问，
 *       TTS 自己就是一条 STREAM_MUSIC，一问必然 true（把自己当音乐）。</li>
 *   <li>让路判据升级为：软状态 ∨ 原厂 trusted ∨ 唤醒瞬间 {@code isMusicActive}。</li>
 * </ul>
 * 另：软状态本身也已持久化（v1.3.136），进程重启不再丢。
 */
public final class MusicResume {

    private static final String TAG = "R1CLIENT/Music";

    /**
     * 「小智说完话」到恢复播放的等待时长。
     * 服务端的多轮 TTS 句间会有停顿，等一会儿再动手，避免刚恢复就被下一句打断。
     */
    private static final long RESUME_DELAY_MS = 2500L;

    /** DLNA 兜底查询的最大等待（ms）：超过就当作「没在播」撤账，绝不留悬账 */
    private static final long PROBE_DEADLINE_MS = 3000L;

    /** 总开关（files/no_music_resume） */
    private static volatile boolean enabled = true;
    /**
     * v1.3.132：「小智说话时暂停音乐，说完继续」—— TTS 与音乐互斥（默认关）。
     * Web 勾选 / {@code files/music_pause_tts} 打开。
     *
     * <p>★ v1.3.138：这是<b>让路的唯一门禁</b>，唤醒轮和连续对话轮都归它管。
     * 之前（v1.3.134~137）{@link #onTtsAudioStart()} 把唤醒让路分支写在
     * {@code !ttsPause} 检查<b>之前</b>就 return 了 ⇒ 开关关着时「唤醒后第一轮」
     * 照样暂停，与 Web 上「小智说话时暂停音乐」的文案不符（用户实测反馈）。
     * 现在关掉 = 小智说话时音乐一路播到底（TTS 与音乐叠加），一轮都不让。
     */
    private static volatile boolean ttsPause = false;
    /** 原厂欠着一笔"恢复播放"（doInterrupt → pause + shouldResume=true） */
    private static volatile boolean pending = false;
    /** 用户本轮明确说过「暂停 / 停止」⇒ 永不自动恢复 */
    private static volatile boolean userPaused = false;
    /** 本轮已执行过恢复，防重复 */
    private static volatile boolean fired = false;
    /** v1.3.133/134：唤醒让路已挂起（音乐还在播），等小智开口才真停 */
    private static volatile boolean deferPending = false;
    /**
     * v1.3.137：唤醒瞬间采到的「有音乐在播」真状态（{@link PlayerBridge#isMusicActive()}）。
     *
     * <p>★ 必须**在唤醒那一刻**采：那时小智的 TTS 还没开播，AudioManager 看到的
     * STREAM_MUSIC 只可能是音乐。等到首帧开播再问就晚了 —— TTS 播的也是这条流，
     * 一问必然 true（把自己当成音乐）。
     */
    private static volatile boolean deferMusicActive = false;

    /**
     * v1.3.144：唤醒应答音（那声「叮」）的开播时刻（{@code 0} = 本轮没有/已过去）。
     *
     * <p><b>为什么要单独认它</b>：应答音是 {@code TtsPlayer.push(本地 PCM)}，走的是
     * 和服务器 TTS 完全相同的播放循环 ⇒ 同样会触发 {@link #onTtsAudioStart()}。
     * 不认出来的话，唤醒后音乐在「叮」响之前就停了（实测 22:32:38.721→.742，隔 21ms），
     * 叮完 2.5s 又接回、等用户说完话再停一次 —— 那正是用户抱怨的「间隙」：
     * 音乐闪断一下又回来，来回折腾。</p>
     */
    private static volatile long wakeCueTs = 0L;

    /** v1.3.144：本轮服务器是否真的下发过 TTS（{@code onTtsStart}）。应答音不算。 */
    private static volatile boolean serverTtsSeen = false;

    /**
     * v1.3.146：会话是否还活着（{@code SessionController} 状态 != IDLE）。
     *
     * <p><b>为什么需要它</b>：IDLE 期 TtsPlayer 也会被用 —— 60s 自动断开那一下的
     * 「断连提示音」就是 {@code playLocalCue()} 推的本地 PCM，走的是同一条播放循环，
     * 于是也触发 {@link #onTtsAudioStart()}。而那时会话已经收尾（{@code enterIdle()}
     * 里 {@code onTurnEnded("session-end")} 已执行过），后面再没有任何事件会来收尾
     * ⇒ 音乐被暂停后<b>永远没人接回</b>，还要留一笔悬账污染下一轮。实测：</p>
     * <pre>
     * 22:51:54.275 ws closed: no-tts-timeout
     * 22:51:54.278 music onTurnEnded(session-end) skip: pending=false fired=true
     * 22:51:54.312 music markPaused(tts) → pause sent + 待恢复   ← 断连提示音干的
     * </pre>
     * 由 {@code SessionController.setState()} 维护：非 IDLE ⇒ true，回 IDLE ⇒ false。
     */
    private static volatile boolean sessionActive = false;

    /** v1.3.146：由 {@code SessionController.setState()} 调用（state != IDLE 即 true）。 */
    public static void setSessionActive(boolean a) {
        sessionActive = a;
    }

    /**
     * v1.3.144：当前开播的这一帧是不是「唤醒应答音」。
     *
     * <p>主判据 = 服务器还没下发过 TTS（{@code !serverTtsSeen}）—— 应答音一定早于
     * 服务器任何一句话；时间窗 1500ms 只是兜底，防止标记卡死导致永远不让路。</p>
     */
    private static boolean inWakeCue() {
        return wakeCueTs > 0L && !serverTtsSeen
                && android.os.SystemClock.elapsedRealtime() - wakeCueTs < 1500L;
    }

    /** v1.3.144：应答音起播 —— 由 {@code SessionController.playWakeAck()} 调用。 */
    public static void noteWakeCue() {
        wakeCueTs = android.os.SystemClock.elapsedRealtime();
    }

    /** v1.3.144：服务器下发 TTS 了 —— 之后开播的帧都是「小智真的在说话」。 */
    public static void noteServerTts() {
        serverTtsSeen = true;
    }
    /**
     * v1.3.134：本次挂起是否带着原厂的 {@code isPlaying()==true} 亲口结论
     * （DefaultMusicHandler.doInterrupt 的判定）。带着 ⇒ 开播时可直接停，
     * 不必等 DLNA 兜底查询（原厂语音起的播放不走 PlayerBridge，软状态是假的）。
     */
    private static volatile boolean yieldTrusted = false;

    private static Handler h;

    private MusicResume() {
    }

    private static Handler handler() {
        if (h == null) {
            synchronized (MusicResume.class) {
                if (h == null) {
                    h = new Handler(Looper.getMainLooper());
                }
            }
        }
        return h;
    }

    private static final Runnable RESUME = new Runnable() {
        @Override
        public void run() {
            if (!enabled || !pending || userPaused || fired) {
                SessionLog.i("music auto-resume skip: enabled=" + enabled + " pending=" + pending
                        + " userPaused=" + userPaused + " fired=" + fired);
                return;
            }
            fired = true;
            pending = false;
            boolean ok = PlayerBridge.resumeMusic();
            Log.i(TAG, "auto-resume -> " + (ok ? "sent" : "FAIL"));
            SessionLog.i("music auto-resume (小智说完话，接回唤醒时被暂停的播放): "
                    + (ok ? "sent" : "msgcenter FAIL"));
        }
    };

    static void setEnabled(boolean on) {
        enabled = on;
        if (!on) {
            pending = false;
            cancel();
            cancelDefer();
        }
    }

    static boolean isEnabled() {
        return enabled;
    }

    /** v1.3.132：TTS 与音乐互斥开关（Web「小智说话时暂停音乐」）。 */
    static void setTtsPause(boolean on) {
        ttsPause = on;
    }

    static boolean isTtsPause() {
        return ttsPause;
    }

    /**
     * v1.3.132：原厂是否欠着一笔「恢复播放」。
     * 这是「音乐本来就在播」的最可靠判据 —— pending 只由原厂钩子
     * （doInterrupt 里 isPlaying() 为真才 pause）或 TTS 互斥让路置 true。
     */
    public static boolean isPending() {
        return pending;
    }

    // ==================== smali 钩子（两个 music handler） ====================

    /**
     * v1.3.134：**唤醒让路改走这里**（两个 handler 的 pause 分支都把原厂
     * {@code pause()} 换成了调本方法）。
     *
     * <p>原厂 {@code DefaultMusicHandler.doInterrupt()} 的 pause 分支：
     * {@code if (playController.isPlaying()) playController.pause();} —— 带 isPlaying 判定，
     * 用 {@code deferPause(true)} 把这个结论带过来。
     * {@code PhicommExternalMusicHandler.doInterrupt()} 的 pause 分支无条件
     * {@code mCommonPlayer.pause()}，smali 侧用 {@code PlayerBridge.isSoftPlaying()}
     * 守门（没在播就不挂起），调无参版本 = {@code deferPause(false)}。
     *
     * <p>语义：**只挂起，不暂停** —— 音乐继续播，等小智第一帧音频开播
     * （{@link #onTtsAudioStart()}）才真停。唤醒后啥也没说 ⇒ 会话结束撤掉挂起，
     * 音乐压根没停过。
     *
     * <p>⚠️ 总开关（{@code files/no_music_resume}）关着时必须退化成原厂行为立即暂停 ——
     * 因为原厂那句 pause 已经被我们替换掉了，这里不补就没人停了。
     */
    public static void deferPause() {
        deferPause(false);
    }

    /**
     * v1.3.143：★ 唤醒那一刻**由我们主动**挂起让路 —— 不再指望原厂 handler。
     *
     * <p><b>为什么要加它</b>（用户报「说话时暂停音乐有时不灵」的真因）：
     * {@code deferPause} 唯一的调用点是两个原厂 music handler 的
     * {@code doInterrupt(pause)}，而实测**那段分支在放歌唤醒时根本不走** ——
     * 22:05~22:16 整段日志里 {@code deferPause} 一行成功记录都没有，只有开关关着的
     * skip；让路实际全靠 {@link #onTtsAudioStart()} 里连续轮分支的
     * {@code isSoftPlaying()} 兜底。软状态一丢（进程重启、非本进程起的播放）就整个不灵。</p>
     *
     * <p>由 {@code SessionController.handleWakeup()} 在所有唤醒路径上调用
     * （{@code onWakeup} / {@code debugWakeup} 都汇到那里），此时 TTS 尚未起播，
     * 是 {@code isMusicActive()} 唯一干净的采样点。</p>
     */
    public static void onWakeup() {
        if (!enabled) {
            return;
        }
        if (!ttsPause) {
            cancelDefer();
            return;
        }
        if (userPaused) {
            return;
        }
        // v1.3.145：这里**不能**因为「已经挂着」就 return —— 上一轮唤醒后用户没说话时
        // 挂起会一直留着（会话结束才撤），若此时跳过采样，本轮就会拿上一轮的旧结论
        // 去判断，上一轮要是 musicActive=false 就整轮不灵。唤醒是最新鲜的采样点，
        // 一律重采。（原厂 handler 随后调 deferPause(trusted) 时只合并 trusted，
        // 不会覆盖这里，见 deferPause 里的 deferPending 分支。）
        wakeCueTs = 0L;          // v1.3.144：新一轮，清掉上一轮的应答音/服务器 TTS 标记
        serverTtsSeen = false;
        boolean active = PlayerBridge.isMusicActive();
        deferMusicActive = active;
        pending = true;
        fired = false;
        deferPending = true;
        Log.i(TAG, "onWakeup: yield armed musicActive=" + active
                + " soft=" + PlayerBridge.isSoftPlaying()
                + " fresh=" + PlayerBridge.isSoftPlayingFresh());
        SessionLog.i("music wakeup-yield armed: 唤醒瞬间真状态 musicActive=" + active
                + "（软状态 soft=" + PlayerBridge.isSoftPlaying()
                + "/fresh=" + PlayerBridge.isSoftPlayingFresh()
                + "）⇒ 音乐继续播，等小智开口第一帧才停");
    }

    /**
     * @param trusted 原厂 doInterrupt 里 {@code isPlaying()} 亲口返回 true
     *                （DefaultMusicHandler 路径）。external 模式（Phicomm 路径）
     *                传 false，由开播时的软状态 / DLNA 兜底判断。
     */
    public static void deferPause(boolean trusted) {
        if (!enabled) {
            boolean ok = PlayerBridge.pauseMusic();
            SessionLog.i("music deferPause: 总开关关 ⇒ 立即暂停（原厂行为）: "
                    + (ok ? "sent" : "msgcenter FAIL"));
            return;
        }
        if (userPaused) {
            SessionLog.i("music deferPause skip: 用户显式暂停过，不挂起让路");
            return;
        }
        // v1.3.138：★ 让路唯一门禁 —— 开关关着时唤醒也不让路，音乐一路播
        // （TTS 与音乐叠加）。原厂那句 pause 已被我们顶掉，这里直接 return
        // 就等于「谁也不许停」。
        if (!ttsPause) {
            cancelDefer();
            SessionLog.i("music deferPause skip: 说话暂停开关关着 ⇒ 唤醒不让路"
                    + "（音乐继续播，小智声音叠在上面）");
            return;
        }
        // v1.3.143：已经由 onWakeup() 挂起了（原厂 handler 是**之后**才走的）⇒
        // 只把原厂 isPlaying() 的亲口结论并进来，不要重采/重置（那会丢掉更早、更干净的采样）。
        if (deferPending) {
            yieldTrusted = yieldTrusted || trusted;
            Log.i(TAG, "deferPause: 已有挂起，合并 trusted=" + trusted
                    + "（保留唤醒瞬间 musicActive=" + deferMusicActive + "）");
            return;
        }
        // v1.3.137：★ 唤醒这一刻（TTS 还没起）采一次真状态。这是最干净的取样点 ——
        // 之后 TTS 一开播，AudioManager 就会把小智自己当成音乐，再也问不出来。
        deferMusicActive = PlayerBridge.isMusicActive();
        pending = true;
        fired = false;
        deferPending = true;
        yieldTrusted = trusted;
        Log.i(TAG, "deferPause: 音乐继续播，小智开口才暂停 (trusted=" + trusted
                + " musicActive=" + deferMusicActive + ")");
        SessionLog.i("music deferPause (唤醒让路挂起：音乐继续播，小智开口即暂停；trusted="
                + trusted + " 唤醒瞬间真状态musicActive=" + deferMusicActive + ")");
    }

    /**
     * v1.3.134：**小智第一帧音频开播**（{@code TtsPlayer} 播放循环起点调用）⇒
     * 让路动作在这一刻执行，空隙 ≈ 0。
     *
     * <p>三种来路：</p>
     * <p>★ v1.3.138 前置门禁：{@code ttsPause} 关着 ⇒ <b>一律不让路</b>（含唤醒轮），
     * 音乐一路播到底、TTS 声音叠在上面。</p>
     * <ul>
     *   <li><b>唤醒让路挂着</b>（{@code deferPending}，唤醒那一轮）⇒ 立即暂停，
     *       欠账标记 "wakeup"。判据 = 软状态在播，或原厂 isPlaying 亲口说过（trusted），
     *       或唤醒瞬间 {@code isMusicActive()} 为真；三者都假 ⇒ DLNA 兜底查询，
     *       查到才停、查不到撤账。</li>
     *   <li><b>连续对话轮 + TTS 互斥开</b> ⇒ 同样让路，欠账标记 "tts"。</li>
     * </ul>
     */
    public static void onTtsAudioStart() {
        if (!enabled) {
            return;
        }
        if (userPaused) {
            cancelDefer();
            SessionLog.i("music onTtsAudioStart skip: 用户显式暂停过，不让路");
            return;
        }
        // v1.3.144：★ 唤醒应答音（那声「叮」）走的是同一个 TtsPlayer 播放循环，
        // 但它**不是**小智在说话。让路必须留到服务器真正回答的那一帧 ——
        // 否则音乐在叮响前就停、叮完 2.5s 又接回、等用户说完再停一次，来回闪断。
        // （★ 必须排在 sessionActive 门禁之前：唤醒时 setState(CONNECTING) 晚于
        //   playWakeAck，此刻 sessionActive 还没翻，先判会话会把「叮」误记成断连 cue，
        //  日志文案失真、后续排查被带偏。）
        if (inWakeCue()) {
            SessionLog.i("music onTtsAudioStart: 唤醒应答音（叮）不算开口 ⇒ "
                    + "保持挂起，音乐继续播");
            return;
        }
        // v1.3.146：★ 会话已结束 ⇒ 此刻开播的一定是本地提示音（60s 自动断开的
        // 断连 cue），不是小智在说话。让路只在会话里才有意义 —— 这里若让路，
        // 暂停之后再没有 onTurnEnded 来收尾，音乐就停死在半路（实测 22:51:54）。
        if (!sessionActive) {
            SessionLog.i("music onTtsAudioStart skip: 会话已结束（此刻是断连提示音，"
                    + "不是小智说话）⇒ 不让路，音乐继续播");
            return;
        }
        boolean wakeupYield = deferPending;
        // v1.3.137：唤醒瞬间采的真状态（TTS 还没起来时的结论，可信）
        boolean musicActive = deferMusicActive;
        cancelDefer();
        // v1.3.138：★ 门禁提前到唤醒轮之前 —— 开关关着时唤醒轮同样不让路
        // （v1.3.134~137 放在 wakeupYield 分支之后 ⇒ 唤醒后第一轮永远会停，
        //  与 Web「小智说话时暂停音乐」的文案不符）。
        if (!ttsPause) {
            if (wakeupYield) {
                SessionLog.i("music onTtsAudioStart: 说话暂停开关关着 ⇒ 唤醒轮也不让路");
            }
            return;
        }
        // v1.3.143：判据改用 fresh（本进程内真播过），**不认**重启恢复的过期 softPlaying ——
        // 那条会「没在播也暂停、说完再把上一曲拽回来」。真状态看唤醒瞬间采的 musicActive。
        boolean fresh = PlayerBridge.isSoftPlayingFresh();
        if (wakeupYield) {
            if (fresh || yieldTrusted || musicActive) {
                boolean ok = PlayerBridge.pauseMusic();
                markPaused("wakeup", ok);
            } else {
                SessionLog.i("music onTtsAudioStart: 三项判据全假（fresh=" + fresh
                        + " trusted=" + yieldTrusted + " musicActive=" + musicActive
                        + "）⇒ 认为没在播，不让路");
                probeYield("wakeup");
            }
            return;
        }
        if (fresh || pending) {
            boolean ok = PlayerBridge.pauseMusic();
            markPaused("tts", ok);
        } else {
            SessionLog.i("music onTtsAudioStart: 连续轮判据全假（fresh=" + fresh
                    + " pending=" + pending + "）⇒ 不让路");
            probeYield("tts");
        }
    }

    /**
     * v1.3.134：让路的 DLNA 兜底 —— 内存判据（软状态 / trusted）说没在播时调用。
     *
     * <p>为什么需要它：{@code softPlaying} 是进程内内存态，① 进程重启即丢
     * （音乐在 player 独立进程里继续放，我们不知道）；② 原厂/DLNA 自己起的播放
     * 没经过 {@link PlayerBridge#dispatch}，没记账。这两种情况下小智说话时
     * 音乐会一直响（实测 19:39 起一路 {@code tts-pause skip}）。
     *
     * <p>做法：异步查一次 DLNA 真状态（GetTransportInfo，本机回环 &lt;100ms）：
     * PLAYING 才补让路；没在播 / 查太慢 ⇒ **撤账**（{@code pending=false}）——
     * 从没暂停过就不该恢复，否则会给「无中生有」的恢复留门。
     */
    private static void probeYield(final String why) {
        if (!enabled || userPaused) {
            return;
        }
        final long t0 = android.os.SystemClock.elapsedRealtime();
        SessionLog.i("music yield-probe(" + why + "): 内存判据没在播，异步查 DLNA 真状态");
        PlayerBridge.queryPlayingStateAsync(new PlayerBridge.StateCallback() {
            @Override
            public void onResult(boolean playing) {
                if (!enabled || userPaused) {
                    SessionLog.i("music yield-probe(" + why + ") skip: enabled=" + enabled
                            + " userPaused=" + userPaused);
                    return;
                }
                if (android.os.SystemClock.elapsedRealtime() - t0 > PROBE_DEADLINE_MS) {
                    pending = false;
                    SessionLog.i("music yield-probe(" + why + ") skip: DLNA 返回太慢，撤销让路标记");
                    return;
                }
                if (playing) {
                    boolean ok = PlayerBridge.pauseMusic();
                    Log.i(TAG, "yield-probe(" + why + ") -> " + (ok ? "sent" : "FAIL"));
                    markPaused(why);
                } else {
                    pending = false;
                    SessionLog.i("music yield-probe(" + why + ") -> DLNA 说没在播，"
                            + "撤销让路标记（不暂停也不恢复）"
                            + "；注意 DLNA 对 player 自己的播放恒报 NO_MEDIA_PRESENT，"
                            + "阴性**不能**证明没在播");
                }
            }
        });
    }

    /** v1.3.134：撤掉挂起的唤醒让路（会话结束 / 用户自己操作了播放 / 已执行）。 */
    static void cancelDefer() {
        deferPending = false;
        yieldTrusted = false;
        deferMusicActive = false;   // v1.3.137
    }

    /**
     * 原厂 {@code doInterrupt()} 的 pause 分支：{@code CommonPlayer.pause(); shouldResume = true;}
     * —— 唤醒让路，欠一笔恢复。
     */
    public static void markPaused() {
        markPaused("wakeup");
    }

    /**
     * v1.3.132：带来源的版本。
     *
     * @param why "wakeup" = 唤醒让路（开播时执行，见 {@link #onTtsAudioStart()}）；
     *            "tts"    = 本轮 TTS 开始时的互斥让路（Web 开关，见 {@link #setTtsPause(boolean)}）
     */
    public static void markPaused(String why) {
        markPaused(why, true);
    }

    /**
     * v1.3.143：带「pause 到底发出去没有」的版本 —— msgcenter 没就绪时
     * {@code pauseMusic()} 会返回 false，那种情况下音乐其实没停，日志里要看得出来，
     * 否则排障时会把「没发成功」误读成「小智没让路」。
     */
    public static void markPaused(String why, boolean sent) {
        if (!enabled) {
            return;
        }
        pending = true;
        fired = false;
        // v1.3.131：同时落 r1client.log —— 钩子在 USC 的 handler 里触发，
        // 那是**唤醒瞬间**，抓 logcat 常常来不及；写文件才查得到。
        Log.i(TAG, "markPaused(" + why + "): 音乐让路，小智说完话由我们接回"
                + (sent ? "" : "  ★ pause 未发出(msgcenter 未就绪)"));
        SessionLog.i("music markPaused(" + why + ") → pause "
                + (sent ? "sent" : "★FAIL(msgcenter 未就绪，音乐其实没停)") + " + 待恢复");
    }

    /**
     * 原厂 {@code doInterrupt()} 的 stop 分支（type = doFinishAllInterrupt）：
     * {@code CommonPlayer.stop(); shouldResume = false;} —— 明确不恢复。
     */
    public static void markStopped() {
        pending = false;
        cancel();
        cancelDefer();
        Log.i(TAG, "markStopped: 音乐被停止，不恢复");
        SessionLog.i("music markStopped (doFinishAllInterrupt → stop, 不恢复)");
    }

    /**
     * 原厂 {@code doResume()} 自己把音乐接回去了 —— 我们不用管。
     * （jp2 下这条路走不到，留作保险与取证。）
     */
    public static void markResumed() {
        pending = false;
        fired = false;
        cancel();
        cancelDefer();
        Log.i(TAG, "markResumed: 原厂已自行恢复");
        SessionLog.i("music markResumed (原厂 doResume → startPlay, 我们不用管)");
    }

    // ==================== 会话侧 ====================

    /** 用户在对话里要了「暂停 / 停止」（服务端 MCP self.r1player.pause / stop）。 */
    public static void noteUserPause() {
        userPaused = true;
        pending = false;
        cancel();
        cancelDefer();
        Log.i(TAG, "noteUserPause: 用户显式暂停/停止 ⇒ 不自动恢复");
        SessionLog.i("music noteUserPause (用户说了暂停/停止 ⇒ 本轮不自动恢复)");
    }

    /** 用户在对话里要放新内容（play / play_song / random / 电台 / 切歌…）。 */
    public static void noteUserPlay() {
        userPaused = false;
        pending = false;
        fired = false;
        cancel();
        cancelDefer();
        SessionLog.i("music noteUserPlay (用户要放新内容 ⇒ 清掉暂停标记)");
    }

    /** 新一轮对话开始（唤醒 / 新 TTS / 收到 STT）⇒ 撤掉已经排上队的恢复。 */
    public static void cancel() {
        Handler hh = h;
        if (hh != null) {
            hh.removeCallbacks(RESUME);
        }
    }

    /**
     * 小智说完话（TTS 播完）。{@value #RESUME_DELAY_MS}ms 内没有新一轮对话就把音乐接回去。
     *
     * @param why 取证标签（"tts-stop" / "session-end"）
     */
    public static void onTurnEnded(String why) {
        // v1.3.144：应答音播完也会走到这里（tts-stop），但那是「叮」的收尾、不是
        // 小智说完话 ⇒ 挂起必须**保留**（等服务器真正回答那一帧才让路），
        // 也不能恢复（压根没停过）。否则挂起被撤，后面服务器 TTS 来时只剩软状态
        // 一条判据，软状态一丢就又「不灵」了。
        if (deferPending && !serverTtsSeen) {
            if ("session-end".equals(why)) {
                // v1.3.145：会话都结束了小智也没开口 ⇒ 音乐从头到尾没停过，撤账。
                // （不清的话挂起会留到下一轮，onWakeup 虽已改成重采，但 pending 悬着
                //   会让下一轮的「从没暂停过」判定失准。）
                cancelDefer();
                pending = false;
                SessionLog.i("music onTurnEnded(session-end): 唤醒后小智一直没说话 ⇒ "
                        + "撤掉挂起，音乐全程没停，无需恢复");
                return;
            }
            SessionLog.i("music onTurnEnded(" + why + "): 唤醒应答音收尾，挂起保留"
                    + "（等小智真正回答那一刻才让路），不恢复");
            return;
        }
        // v1.3.133/134：一轮结束 ⇒ 唤醒那次挂起的让路作废（音乐压根不用停）。
        // 例如唤醒后啥也没说、会话超时收尾，音乐会一直播下去。
        // 从没暂停过就不该恢复 —— 否则会给一直在播的音乐补一条空转的 play。
        boolean neverPaused = deferPending;
        cancelDefer();
        if (neverPaused) {
            pending = false;
            SessionLog.i("music onTurnEnded(" + why + ") skip: 唤醒后音乐从未暂停"
                    + "（让路挂起已撤销），无需恢复");
            return;
        }
        if (!enabled || !pending || userPaused || fired) {
            // v1.3.131：把"为什么没排队"也落文件 —— 否则又是一个查不到原因的静默分支。
            SessionLog.i("music onTurnEnded(" + why + ") skip: enabled=" + enabled
                    + " pending=" + pending + " userPaused=" + userPaused + " fired=" + fired);
            return;
        }
        Handler hh = handler();
        hh.removeCallbacks(RESUME);
        hh.postDelayed(RESUME, RESUME_DELAY_MS);
        Log.i(TAG, "onTurnEnded(" + why + ") -> resume in " + RESUME_DELAY_MS + "ms");
        SessionLog.i("music onTurnEnded(" + why + ") -> resume in " + RESUME_DELAY_MS + "ms");
    }
}
