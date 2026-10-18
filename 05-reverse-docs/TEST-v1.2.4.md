# TEST-v1.2.4 —— 会话期开麦 实机验收清单

> 背景：2026-10-03 实测确认 4 麦 HAL 独占。v1.2.3 常开 AudioRecord 把原厂
> 云知声 KWS 挤瞎（「小讯小讯」无反应）；force-stop 我们后原厂 1s 内复活。
> v1.2.4 = 麦是会话期资源：IDLE 归原厂，唤醒后才开麦，会话结束归还。

## 构建与安装（WSL）

```bash
cd /workspace/r1-client && patch -p1 --dry-run < /mnt/c/Users/shaoh/WorkBuddy/R1/r1-client-v1.2.3-to-v1.2.4.patch
cd /workspace/r1-client && patch -p1 < /mnt/c/Users/shaoh/WorkBuddy/R1/r1-client-v1.2.3-to-v1.2.4.patch && bash build.sh
```

> 若 /workspace/r1-client 还是干净的 v1.2.2 树（没打过 v1.2.3 补丁），
> 先按 SETUP-WSL.md 重来：解压 zip → keystore → v1.2.3 补丁 → 本补丁。

安装（Windows 侧 Git Bash / 或 WSL 用 Windows adb 路径）：

```bash
A="/mnt/c/Users/shaoh/WorkBuddy/R1/一键安装Unisound_v1.6.13/adb.exe"
"$A" -s 192.168.2.5:5555 push /workspace/r1-client/r1-client-v1.2.4.apk /data/local/tmp/r1.apk
"$A" -s 192.168.2.5:5555 shell ls -l /data/local/tmp/r1.apk   # ★ 必须与本地大小一致（WiFi push 会静默截断）
"$A" -s 192.168.2.5:5555 shell /system/bin/pm install -r /data/local/tmp/r1.apk
"$A" -s 192.168.2.5:5555 shell rm /data/local/tmp/r1.apk
"$A" -s 192.168.2.5:5555 shell am startservice -n cn.workbuddy.r1client/.R1ClientService
```

## 验收项

### 1) IDLE 期不占麦（本补丁的核心验收）

启动服务 30s 后（等装配完成）：

```bash
"$A" -s 192.168.2.5:5555 logcat -v time -d | grep -i r1client | tail -15
"$A" -s 192.168.2.5:5555 shell dumpsys media.audio_flinger | grep -A 25 "Input thread"
```

期望：
- 日志出现 `R1ClientService ready (mic=ondemand tts=? kws=off bus=true opus=true)`
- 心跳：`alive Ns (tts=? mic=false sess=IDLE bus=true)` ← **mic=false 是新常态**
- 喊「小讯小讯」→ 原厂应答「唉」（KWS 活着，与 r1client 共存）

### 2) 唤醒 → 开麦 → 会话

喊「小讯小讯」后立刻抓：

```bash
"$A" -s 192.168.2.5:5555 logcat -v time -t 300 | grep -iE "r1client" | tail -40
```

期望时序：
1. `*** BUS WAKEUP` / `wakeup src=bus:小讯小讯 state=IDLE`
2. `session start -> CONNECTING`
3. `mic ACQUIRED (attempt=N)` ← N 是竞争指标：原厂 609 放手快则 N=1~4
4. `ws open` → `handshake done, session_id=...` → `LISTENING`
5. `flush pending N frames`（开麦~握手间说的话）
6. 说话 → `STT: ...` → TTS 从喇叭出声

### 3) 会话结束 → 还麦 → KWS 复活（第二轮验收）

对话结束后等 ~10-25s（TTS-end+10s 空闲断连，或唤醒后不说话 15s 看门狗）：

```bash
"$A" -s 192.168.2.5:5555 logcat -v time -t 300 | grep -iE "r1client" | tail -20
```

期望：
- `idle timeout reached, closing ws` 或 `no TTS within 15s ... closing session`
- `ws closed: ...` → `mic RELEASED -> unisound KWS back on duty` → `back to IDLE`
- **再喊「小讯小讯」→ 仍有「唉」** ← 麦还干净了，KWS 复活（可循环验收）

## 观察点 / 已知限制

| 项 | 说明 |
|---|---|
| `mic ACQUIRED (attempt=N)` | N 反映与原厂 ASR 的竞争时长。若长期 N>10（>3s），说明原厂放手慢，唤醒后首字可能被吃 —— 贴日志再调 |
| 原厂 609 | 原厂云端 ASR 已死，每次唤醒它会闪灯/可能放错误提示音，属原厂行为，与我们无关 |
| no-tts 看门狗 15s | 唤醒后不说话，15s 收会话还麦。若 NAS 服务器 LLM+TTS 首包 >15s 会误杀，可调 `NO_TTS_TIMEOUT_MS` |
| player=null 降级 | TTS 初始化仍带看门狗；降级时 ASR 链路照常（只是没声音） |
| pending 15s 上限 | 唤醒后 15s 内没完成握手，更早的语音丢弃（MAX_PENDING_FRAMES=250） |
