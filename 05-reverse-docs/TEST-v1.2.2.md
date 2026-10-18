# v1.2.2 实机验证清单

产物：`/workspace/r1-client/r1-client-v1.2.2.apk`（19.77 MB，已签名）

---

## 0. 前置：先卸掉旧客户端

旧版（v1.0.1 ~ v1.2.1）签名一致但代码差异大，直接覆盖安装可能残留 `files/kws` 里的旧模型。
**推荐先卸载**：

```
adb -s 192.168.2.5:5555 uninstall cn.workbuddy.r1client
```

> 如果提示 `Failure [DELETE_FAILED_...]`，说明有服务在跑，先停再卸：
> `adb -s 192.168.2.5:5555 shell am force-stop cn.workbuddy.r1client`
>
> 注意：**不要**动 `com.phicomm.speaker.device`（小讯本体）——
> 它是唤醒事件的来源，卸载/禁用它 = 自断粮道。

---

## 1. 安装

```
adb -s 192.168.2.5:5555 install -r /workspace/r1-client/r1-client-v1.2.2.apk
```

期望输出：`Success`

---

## 2. 清日志缓冲区（关键，避免看到旧进程的残留日志）

```
adb -s 192.168.2.5:5555 logcat -c
```

---

## 3. 启动并抓日志

开一个窗口持续抓（**带过滤，只留客户端相关**）：

```
adb -s 192.168.2.5:5555 logcat -v time R1CLIENT:I sherpa-onnx:* linker:* DEBUG:* AndroidRuntime:* libc:* *:S
```

**另开一个窗口**启动服务（R1 无桌面图标，用 `am` 拉起）：

```
adb -s 192.168.2.5:5555 shell am startservice -n cn.workbuddy.r1client/.R1ClientService
```

> 若 `startservice` 报 `does not exist`，改用：
> `adb -s 192.168.2.5:5555 shell am start-foreground-service -n cn.workbuddy.r1client/.R1ClientService`

---

## 4. 期望看到的日志（按顺序）

### 4.1 服务装配成功

```
R1CLIENT: =========== R1ClientService onCreate (mode=3) ===========
R1CLIENT: receiver iface resolved: android.os.MessageDispatchManager$MessageReceiver
R1CLIENT: wakeup bridge REGISTERED on type=33554432
R1CLIENT: WakeupBridge register=true
R1CLIENT: opus enc=true dec=true
R1CLIENT: AudioCapture started: 16000Hz/60ms, minBuf=...
R1CLIENT: mic start=true
R1CLIENT: =========== R1ClientService ready (mic=true kws=pending bus=true opus=true) ===========
```

**这一段出现 = 总线唤醒链路已经通了**（即使后面 KWS 崩了也不影响）。

### 4.2 KWS 初始化（本次修复的核心验证点）

```
R1CLIENT: assets/kws = [decoder-epoch-12-avg-2-chunk-16-left-64.onnx encoder-... joiner-... keywords.txt tokens.txt]
R1CLIENT: kws init=true (NNNN ms)
R1CLIENT: setKws: enabled
R1CLIENT: kws wired to session: true
```

**关键看 `kws init=true`。**

❌ 如果变成 `kws init=false` + 堆栈里出现：
- `UnsatisfiedLinkError: ... DT_HASH` → ORT 补丁没生效（不该发生）
- `UnsatisfiedLinkError: ... __register_atfork` → 混进了新版 ORT（不该发生）
- `SIGSEGV fault addr 0xc ... newFromAsset` → assets 路径又错了

### 4.3 OTA 激活

```
R1CLIENT: OTA req body: {"version":1,"uuid":"98bb9936cd6b",...,"mac_address":"98:BB:99:36:CD:6B"}
R1CLIENT: OTA resp code=200
R1CLIENT: 需要激活：请在 https://xiaozhi.me 输入验证码 NNNNNN
```

拿到验证码后**去 https://xiaozhi.me 输入**，然后：

```
R1CLIENT: got token from OTA: eyJhbGciOi...
```

> v1.2.1 那次的验证码是 `355692`，如果服务器还记得设备，这次可能直接返回 token 不再要验证码。

---

## 5. 语音测试

### 5.1 「小讯小讯」（总线唤醒，走原厂四麦引擎）

喊一句「小讯小讯」，期望：

```
R1CLIENT: [wakeup-bus] notifyMsg what=33554432 arg1=3 arg2=-1 val={"data":{"wakeupWord":"小讯小讯","wakeupType":1},"from":"unisound","key":"wakeup",...}
R1CLIENT: *** BUS WAKEUP: 小讯小讯
R1CLIENT: wakeup src=bus:小讯小讯 state=IDLE
R1CLIENT: session start -> connecting (preroll=true)
R1CLIENT: ws connected
R1CLIENT: handshake done -> listening
R1CLIENT: flush preroll 8 frames (480ms)
```

### 5.2 「小智小智」（自建 KWS）

喊「小智小智」，期望：

```
R1CLIENT: *** KWS HIT: 小智小智
R1CLIENT: wakeup src=kws:小智小智 state=IDLE
R1CLIENT: session start -> connecting (preroll=true)
```

### 5.3 完整对话

说完唤醒词后**直接说命令**（如「今天天气怎么样」），期望：

```
R1CLIENT: STT: 今天天气怎么样
R1CLIENT: TTS start
R1CLIENT: TTS stop
R1CLIENT: back to IDLE
```

**听到音箱出声 = 全链路打通**（唤醒 → 上行 → ASR → LLM → TTS → 播放）。

---

## 6. 已知风险与预期行为

| 现象 | 说明 | 是否要处理 |
|---|---|---|
| 唤醒后音箱**亮一圈灯** | 原厂 Pandora 收到唤醒事件后自己也在响应 | 正常，忍着；或试 `KILL_UNISOUND_AFTER_WAKEUP=true`（有风险） |
| 原厂 App **报错/提示连不上服务器** | 它去连已死的 `aios-home.hivoice.cn` | 正常副作用 |
| 喊「小讯小讯」时 KWS **也可能误命中** | 两条路径同时触发 | 已被 800ms 去抖挡住 |
| KWS 崩了服务却没死 | v1.2.x 的异步隔离 | 退化为纯总线唤醒，可接受 |
| KWS 崩了**服务也死了** | 异步线程的 SIGSEGV 仍会带走整个进程 | 需要改独立进程（见下） |

> ⚠️ **诚实说明**：`startKwsAsync()` 把 native 初始化放到独立线程，只是让崩溃**晚发生、日志更干净**，
> **并不能隔离进程级崩溃**。native SIGSEGV 会干掉整个进程，`catch(Throwable)` 拦不住。
> 若实测仍崩，下一步是把 KWS 放到独立进程（`android:process=":kws"`）+ Binder IPC，或者干脆退回 `MODE_BUS_ONLY`。

---

## 7. 如果 KWS 起不来，快速降级

把 `R1ClientService.java` 里的开关改成只用总线唤醒：

```java
private static final int WAKEUP_MODE = MODE_BUS_ONLY;
```

然后重新构建：

```
cd /workspace/r1-client && bash build.sh
```

**这一档只依赖「小讯小讯」，完全不碰 sherpa/ORT**，稳定性最高。

---

## 8. 抓崩溃现场（如果需要）

完整带 native 栈的日志：

```
adb -s 192.168.2.5:5555 logcat -b crash -d
```

配合 tombstone（需要 root 或 `run-as`）：

```
adb -s 192.168.2.5:5555 shell ls -la /data/tombstones/
```
