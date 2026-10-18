# v1.2.3 实机验证清单

产物：`/workspace/r1-client/r1-client-v1.2.3.apk`（打补丁 + `bash build.sh` 后生成）

---

## 0. 这版改了什么（相对 v1.2.2）

| # | 改动 | 治什么 |
|---|---|---|
| 1 | `onCreate`（main 线程）只做 `startForeground`，装配全部挪到 `R1Pipeline` 工作线程 | **ANR 根治**：main 5s 内必返回 |
| 2 | `TtsPlayer`/`AudioCapture` 不再调 `getMinBufferSize()`（binder 调用，HAL 死锁时永不返回），改固定 4096（R1 实机健康时的真实值） | 拔掉卡死点 |
| 3 | TTS/mic 初始化包 **2.5s 看门狗**，超时降级（服务活着但少个组件） | HAL 死锁不再拖死服务 |
| 4 | 降级后按 5s/15s/60s/300s 退避重试，TTS 成功后运行期 `setPlayer()` 补挂 | 自愈 |
| 5 | `WAKEUP_MODE = MODE_BUS_ONLY`，**KWS 停用** | 避开 OrtApi ABI 错配的必崩 SIGSEGV |
| 6 | 心跳日志每 10s 打活体状态 | 验证"活多久"有了硬指标 |
| 7 | HAL 超时时自动跑 audio probe（getMinBufferSize 探针），打出数值=HAL 恢复/再超时=仍死锁 | 卡住变"有证据的卡住" |
| 8 | `AudioCapture.start()` 加 synchronized、`running` 置位挪到 `startRecording()` 之后 | 重试期间不产生双读线程 |

---

## 1. 在 WSL 里打补丁 + 构建

```bash
# 补丁文件先传到 WSL（或从 Windows 侧复制）：
#   /mnt/c/Users/shaoh/WorkBuddy/R1/r1-client-v1.2.2-to-v1.2.3.patch
cd /workspace/r1-client
patch -p1 --dry-run < /mnt/c/Users/shaoh/WorkBuddy/R1/r1-client-v1.2.2-to-v1.2.3.patch   # 先干跑
patch -p1 < /mnt/c/Users/shaoh/WorkBuddy/R1/r1-client-v1.2.2-to-v1.2.3.patch
bash build.sh   # 产物：/workspace/r1-client/r1-client-v1.2.3.apk
```

> 补丁改了 6 个文件：`AndroidManifest.xml`(版本 7/1.2.3)、`build.sh`(产物名)、
> `R1ClientService.java`(核心重构)、`SessionController.java`、`TtsPlayer.java`、`AudioCapture.java`

---

## 2. 安装并抓日志

```bash
adb -s 192.168.2.5:5555 uninstall cn.workbuddy.r1client
adb -s 192.168.2.5:5555 install /workspace/r1-client/r1-client-v1.2.3.apk
adb -s 192.168.2.5:5555 logcat -c
adb -s 192.168.2.5:5555 shell am startservice -n cn.workbuddy.r1client/.R1ClientService
adb -s 192.168.2.5:5555 logcat -v time R1CLIENT:I *:S
```

**不要动 `com.phicomm.speaker.device`** —— 总线唤醒靠它。

---

## 3. 期望日志（按顺序）

### 3.1 关键时序：main 线程秒回

```
R1CLIENT: =========== R1ClientService onCreate (mode=1) ===========
R1CLIENT: onCreate: foreground up, spawning pipeline thread      ← main 到此为止
R1CLIENT: onStartCommand: first start
R1CLIENT: opus enc=true dec=true                                 ← 以下都在工作线程
R1CLIENT: receiver iface resolved: android.os.MessageDispatchManager$MessageReceiver
R1CLIENT: WakeupBridge register=true
R1CLIENT: TtsPlayer started, minBuf=4096(fixed) buf=16384
R1CLIENT: AudioCapture started: 16000Hz/60ms, minBuf=4096(fixed)
R1CLIENT: =========== R1ClientService ready (mic=true tts=true kws=off bus=true opus=true) ===========
R1CLIENT: alive 10s (tts=true mic=true bus=true)                 ← 心跳，每 10s 一发
R1CLIENT: alive 20s (tts=true mic=true bus=true)
```

**`alive 10s/20s/30s...` 连续出现 = 服务活了**（v1.2.2 连 10s 都撑不到）。

### 3.2 如果 HAL 死锁（预期中的降级，不是失败）

```
R1CLIENT: tts-init TIMEOUT after 2500ms (audio HAL wedged) -> DEGRADED: no TTS yet
R1CLIENT: probe[tts-timeout]: getMinBufferSize(out)=XXXX   ← 打出数值=HAL 其实恢复了
R1CLIENT: mic-init TIMEOUT after 2500ms (audio HAL wedged) -> DEGRADED: mic may self-recover
R1CLIENT: =========== R1ClientService ready (mic=false tts=false ...) ===========
R1CLIENT: audio retry #1 scheduled in 5000ms
R1CLIENT: retry: re-trying TTS init
R1CLIENT: TtsPlayer started, ...
R1CLIENT: audio retry: FULLY RECOVERED ...
```

**只要心跳还在跳，降级不算失败** —— 这是这版的核心设计。

---

## 4. 语音测试

喊 **「小讯小讯」**（KWS 已停用，「小智小智」这版不响应）：

```
R1CLIENT: *** BUS WAKEUP: 小讯小讯
R1CLIENT: wakeup src=bus:小讯小讯 state=IDLE
R1CLIENT: session start -> connecting (preroll=true)
R1CLIENT: ws connected
R1CLIENT: handshake done -> listening
R1CLIENT: flush preroll N frames (Nms)
```

说完命令（如「今天天气怎么样」）：

```
R1CLIENT: STT: 今天天气怎么样
R1CLIENT: TTS start
R1CLIENT: TTS stop
R1CLIENT: back to IDLE
```

音箱出声 = 全链路打通。

---

## 5. 验收标准

| # | 检查项 | 通过标准 |
|---|---|---|
| 1 | `onCreate` 返回 | `foreground up` 与 `opus enc=` 几乎同时出现（main 不等装配） |
| 2 | 服务存活 | `alive 60s` 出现 = **比 v1.2.2 寿命长 3 倍**；`alive 300s` = 完全达标 |
| 3 | pid 稳定 | `ps | grep r1client` 反复查 pid 不变 |
| 4 | ANR | `/data/anr/traces.txt` 不再新增（`ls -la /data/anr/` 时间戳不动） |
| 5 | 总线唤醒 | `*** BUS WAKEUP` 出现 |
| 6 | 降级恢复（若发生） | `FULLY RECOVERED` 出现 |
| 7 | ❌ 不要求 | `kws init=true` —— 这版主动关闭，`kws=off` 才是预期 |

---

## 6. 如果还是 ANR（概率很低）

1. 看 `probe[...]` 有没有打出数值
   - 没有 → HAL 彻底死锁，重启 mediaserver 再试：`adb shell killall mediaserver`（自动重启）
   - 有 → 抓 `/data/anr/traces.txt` 看 main 现在停在哪一行，贴出来
2. 终极方案（v1.2.4 预案）：音频挪独立 `:audio` 进程，主服务只做总线 + WS

## 7. 下一步（v1.2.4 方向）

- **修 KWS**：sherpa-jni 与 ORT 必须成套（同一构建产物），详见 `DIAG-v1.2.2.md` §2.7
- KWS 挪 `:kws` 独立进程做崩溃隔离
- 修好后再把 `WAKEUP_MODE` 切回 `MODE_HYBRID`
