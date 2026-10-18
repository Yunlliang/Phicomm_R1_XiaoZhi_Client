# R1 小智客户端 —— WSL 构建环境搭建指南

从零到出 APK。目标环境：WSL2 Ubuntu + JDK + Android SDK（aapt2/r8/android.jar）。

**不需要**：Android Studio、Gradle、NDK（v1.2.3 不改 so，`libr1opus.so` 是现成的）。

> **本版已修正 4 个会让照抄脚本直接跑挂的坑**，都已在 Windows 侧实测验证过：
> ① `sudo mkdir /workspace` 后非 root 用户 `unzip` 权限不足 →
> ② `libr1opus.so` / `libzip` 有 32 位 zip 兼容问题 →
> ③ 补丁必须打在**干净 v1.2.2** 上（zip 里带的必须是 v1.2.2，不是 v1.2.3）→
> ④ `build.sh` 里 keystore 默认路径已改为 `$PROJ/debug.keystore`。
>
> **按本文档 §9 一条龙照抄即可**，不要跳步。

---

## 0. 总览：要准备什么

| 组件 | 用途 | 大小 | 来源 |
|---|---|---|---|
| JDK 8+ | `javac` 编译、`jarsigner` 签名 | ~200MB | apt |
| `aapt2` | 打包资源 + manifest → base.apk | ~2MB | SDK build-tools |
| `r8.jar` | Java 字节码 → dex | ~20MB | SDK build-tools |
| `android.jar` | 编译期 Android API 存根 | ~40MB | SDK platforms |
| `org.json` | 仅编译期依赖 | ~70KB | 必下（见 §4） |
| `libs/*.jar` | okhttp/okio/kotlin/sherpa | 2.5MB | **源码包里已带** |
| `jniLibs/*.so` | 三个 native 库 | 14MB | **源码包里已带** |
| debug.keystore | 签名 | 2KB | 自己生成（见 §5） |

> **重要**：完整包 `r1-client-src-v1.2.2.zip`（9.0MB，24 个文件）里
> **已经带了 `libs/` 和 `jniLibs/`**，只有 `org.json`、aapt2、r8.jar、android.jar 要自己搞。
>
> 位置：`C:\Users\shaoh\WorkBuddy\R1\r1-client-src-v1.2.2.zip`
> （WSL 路径：`/mnt/c/Users/shaoh/WorkBuddy/R1/r1-client-src-v1.2.2.zip`）
>
> 包内是**干净的 v1.2.2 源码树**（`versionCode=6`），
> 必须配合 §6 的补丁才能变成 v1.2.3。直接编它只能得到 v1.2.2。

---

## 1. 基础工具

```bash
sudo apt update
sudo apt install -y openjdk-17-jdk-headless unzip zip curl wget
java -version && javac -version
```

> JDK 8 / 11 / 17 都可以。源码按 `-source 8 -target 8` 编译，
> 高版本 JDK 会警告 "source 8 is obsolete"（`-Xlint:-options` 已压掉，不影响）。

---

## 2. Android SDK 组件（**推荐：离线包，见 §2B**）

> ⚠️ **2026-10-03 实测：`sdkmanager` 这条路在受限网络下不可靠**
> （Maven Central 被墙、Google 文件名会变），且 Ubuntu 26.04 上 apt 也频繁 404。
> **优先走 §2B 的离线包**（已下好放在 `R1/sdkdl/`），省掉所有网络问题。

### 2A. sdkmanager 路线（网络通畅时）

```bash
mkdir -p ~/android-sdk/cmdline-tools && cd ~/android-sdk/cmdline-tools
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q commandlinetools-linux-*_latest.zip
mv cmdline-tools latest 2>/dev/null || true
cd ~/android-sdk

export ANDROID_SDK_ROOT=$HOME/android-sdk
yes | $ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null
$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager \
  "platforms;android-22" "build-tools;34.0.0"
```

### 2B. 离线包路线（**推荐**）

直接用 `C:\Users\shaoh\WorkBuddy\R1\sdkdl\stage\` 里已下好的四个文件：

```bash
sudo mkdir -p /opt/android-sdk/bin /tmp/dl && \
sudo cp /mnt/c/Users/shaoh/WorkBuddy/R1/sdkdl/stage/android.jar    /opt/android-sdk/android.jar && \
sudo cp /mnt/c/Users/shaoh/WorkBuddy/R1/sdkdl/stage/bin/aapt2      /opt/android-sdk/bin/aapt2 && \
sudo cp /mnt/c/Users/shaoh/WorkBuddy/R1/sdkdl/stage/r8.jar         /opt/android-sdk/r8.jar && \
sudo cp /mnt/c/Users/shaoh/WorkBuddy/R1/sdkdl/stage/orgjson.jar    /tmp/dl/orgjson.jar && \
sudo chmod +x /opt/android-sdk/bin/aapt2
```

**四个文件全都齐了**（见 `sdkdl/README-OFFLINE.md`），走这条就不用再管 §3、§4。

### 2C. 若必须自己从 Google 下（查真实文件名，别猜）

```bash
# 先下官方仓库索引，用它查文件名（文件名会变，硬编码必 404）
curl -fL -o repo.xml https://dl.google.com/android/repository/repository2-3.xml
grep -oE 'android-2[0-9]_r[0-9]+\.zip' repo.xml | sort -u        # → android-22_r02.zip
grep -oE 'build-tools_r3[0-9][^"<]*linux\.zip' repo.xml | sort -u  # → build-tools_r34-linux.zip

cd /tmp/sdkdl
curl -fL -o android-22.zip https://dl.google.com/android/repository/android-22_r02.zip
curl -fL -o bt-r34.zip    https://dl.google.com/android/repository/build-tools_r34-linux.zip
unzip -q android-22.zip && unzip -q bt-r34.zip
# 解出 android-5.1.1/ 和 android-14/ 两个目录
```

> 🔴 **两个反直觉点**（踩过）：
> 1. platform 的包名是 **`android-22_r02.zip`**（不是 `platform-22_r02.zip`），
>    解出来目录叫 **`android-5.1.1/`**。
> 2. **build-tools r34 里没有 `r8.jar`，只有 `lib/d8.jar`**。但 `d8.jar`
>    内部同时含 `com.android.tools.r8.D8` 和 `R8` 主类 →
>    **拷成 `r8.jar` 就能用，`build.sh` 不用改**。

---

## 3. 把 SDK 摆成 build.sh 期望的布局

`build.sh` 默认 `SDK=/opt/android-sdk`，并假设目录是扁的：

```
/opt/android-sdk/
├── android.jar          ← platforms/android-22/android.jar
├── r8.jar               ← build-tools/*/lib/d8.jar（注意：r34 起叫 d8.jar）
└── bin/aapt2            ← build-tools/*/aapt2
```

> **走 §2B 离线包的话，这步已经做完了。** 下面只在你自己从 zip 摆的时候需要。

### 做法 A：软链到 /opt（推荐，零改配置）

```bash
export ANDROID_SDK_ROOT=$HOME/android-sdk
BT=$ANDROID_SDK_ROOT/build-tools/34.0.0

sudo mkdir -p /opt/android-sdk/bin
sudo ln -sf $BT/aapt2                      /opt/android-sdk/bin/aapt2
sudo ln -sf $BT/lib/d8.jar                 /opt/android-sdk/r8.jar     # ← 注意是 d8.jar
sudo ln -sf $ANDROID_SDK_ROOT/platforms/android-22/android.jar /opt/android-sdk/android.jar

# 验证
/opt/android-sdk/bin/aapt2 version
ls -l /opt/android-sdk/r8.jar /opt/android-sdk/android.jar
```

### 做法 B：改 build.sh 顶部变量

v1.2.3 的 build.sh 已支持环境变量覆盖：

```bash
PROJ= SDK= OUT= ORGJSON= KEYSTORE= VER= JAVA=
```

例如不想动 `/opt`：

```bash
SDK=$HOME/android-sdk      # 但你要自己把 android.jar / r8.jar / bin/aapt2 摆成扁布局
```

> **注意**：做法 B 里 `SDK` 下的布局要求和做法 A 完全一样（扁的），
> 不是指 `$HOME/android-sdk` 原生的 `platforms/` `build-tools/` 嵌套布局。
> 嫌麻烦就用做法 A。

---

## 4. org.json（**离线包里已带，可跳过**）

> 走 §2B 的话，`/tmp/dl/orgjson.jar` 已经拷好了，**这节跳过**。

`build.sh` 里 `ORGJSON=/tmp/dl/orgjson.jar`。**Android 运行时自带 org.json，
但 `javac` 编译时需要一份存根**，否则 `import org.json.*` 编译失败。

```bash
mkdir -p /tmp/dl && cd /tmp/dl
# ⚠️ Maven Central (repo1.maven.org) 在部分网络下不可达（实测 000）
# 用阿里云镜像：
curl -fL -o orgjson.jar https://maven.aliyun.com/repository/public/org/json/json/20231013/json-20231013.jar
ls -l orgjson.jar
```

> 用哪个版本的 json jar 都行 —— 它**只参与编译**，
> build.sh 里带了 `--lib $ORGJSON`（d8 的库参数），**不会打进 dex**。
> 另：`android.jar` 里其实已含 `org.json.*`，理论上这步可省（但保留更保险）。

---

## 5. debug.keystore

`build.sh` 默认 `KEYSTORE=$PROJ/debug.keystore`（v1.2.3 新默认值）。
自己生成一个，**别名必须是 `androiddebugkey`、密码 `android`**（build.sh 硬编码）：

```bash
cd /workspace/r1-client      # ← 必须等 §6 把源码放好之后再跑
keytool -genkeypair -v \
  -keystore debug.keystore \
  -alias androiddebugkey \
  -storepass android -keypass android \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US"
```

> ⚠️ **这个目录必须先存在**（§6 的 unzip 已经建好了）。
> 所以顺序是：§6 解压源码 → 再回来生成 keystore。
> 如果你一定要先做 §5，那就先把 `sudo mkdir -p /workspace/r1-client && sudo chown $USER /workspace/r1-client`。

> ⚠️ **签名必须和 v1.2.2 一致才能覆盖安装**。
> 如果这个 keystore 是新建的（不是当初签 v1.2.2 那把），
> 装 v1.2.3 前**先卸载旧版**：
> `adb -s 192.168.2.5:5555 uninstall cn.workbuddy.r1client`

---

## 6. 放源码 + 打补丁

**坑点**：`/workspace` 需要 sudo 建，但后面的 `unzip`/`patch`/`build.sh` 都要以
**当前用户**身份跑。所以建完目录必须 `chown`，否则会 permission denied。

```bash
# 建目录，并交给当前用户（关键！）
sudo mkdir -p /workspace
sudo chown -R $USER:$USER /workspace
cd /workspace

# 解压（zip 里有 libs/ jniLibs/，解出来是干净 v1.2.2 树，versionCode=6）
unzip -q /mnt/c/Users/shaoh/WorkBuddy/R1/r1-client-src-v1.2.2.zip

# 确认解压正常
ls r1-client/          # 应看到 src/ libs/ jniLibs/ build.sh AndroidManifest.xml
grep versionName r1-client/AndroidManifest.xml   # 必须是 1.2.2，不是 1.2.3

# 打 v1.2.3 补丁：先 dry-run，通过再实套
patch -p1 -d /workspace/r1-client --dry-run \
      < /mnt/c/Users/shaoh/WorkBuddy/R1/r1-client-v1.2.2-to-v1.2.3.patch

patch -p1 -d /workspace/r1-client \
      < /mnt/c/Users/shaoh/WorkBuddy/R1/r1-client-v1.2.2-to-v1.2.3.patch
```

期望 dry-run 输出是 6 行 `checking file ...`，**没有 `Reversed`、没有 `FAILED`**：

```
checking file AndroidManifest.xml
checking file build.sh
checking file src/cn/workbuddy/r1client/R1ClientService.java
checking file src/cn/workbuddy/r1client/SessionController.java
checking file src/cn/workbuddy/r1client/TtsPlayer.java
checking file src/cn/workbuddy/r1client/AudioCapture.java
```

打完补丁后确认版本变成 1.2.3：

```bash
grep versionName /workspace/r1-client/AndroidManifest.xml   # 应为 1.2.3
grep 'PROJ=' /workspace/r1-client/build.sh                  # 应为 ${PROJ:-/workspace/r1-client}
```

**确认三个必需目录都在**（缺哪个都会构建失败）：

```bash
cd /workspace/r1-client
ls libs/                     # kotlin-stdlib.jar okhttp.jar okio.jar sherpa-onnx-classes.jar
ls jniLibs/armeabi-v7a/      # libonnxruntime.so libsherpa-onnx-jni.so libr1opus.so
```

> `assets/` **不需要** —— v1.2.3 停用了 KWS（`MODE_BUS_ONLY`），不再加载模型。
> build.sh 检测到没有会自动建个空目录。

---

## 7. 构建

回到 §5 生成 keystore（如果还没生成），然后：

```bash
cd /workspace/r1-client
bash build.sh
```

期望输出：

```
note: /workspace/r1-client/assets 不存在，创建空目录（v1.2.3 不需要 KWS 模型）
=== [1/6] javac ===
javac OK, class count: NNN
=== [2/6] classes.jar ===
=== [3/6] d8 ===
=== [4/6] aapt2 link ===
=== [5/6] 合入 dex + so ===
=== [6/6] 签名 ===

=== 完成 ===
-rw-r--r-- 1 root root ~19000000 ... r1-client-v1.2.3.apk
--- 内容 ---
    ... classes.dex
    ... lib/armeabi-v7a/libr1opus.so
    ... lib/armeabi-v7a/libonnxruntime.so
    ... lib/armeabi-v7a/libsherpa-onnx-jni.so
    ... AndroidManifest.xml
```

产物：`/workspace/r1-client/r1-client-v1.2.3.apk`（约 19MB）

---

## 8. 构建前自检清单

| 症状 | 原因 | 修 |
|---|---|---|
| `javac: command not found` | 没装 JDK | §1；装完 `hash -r` 刷新缓存 |
| apt 报 `404 Not Found ... libxpm4` | Ubuntu 26.04 新 LTS 镜像同步不全 | `sudo apt clean && sudo apt update --fix-missing && sudo apt install -y --fix-missing openjdk-17-jdk-headless` |
| `package android.content does not exist` 一大片 | `android.jar` 缺失/空 | §2B 拷 android.jar |
| `package org.json does not exist` | 漏了 orgjson | §2B / §4 |
| `aapt2: No such file or directory` | SDK 布局不对 | §2B / §3 |
| `Unable to access jarfile .../r8.jar` | r8.jar 缺失 | §2B；注意 r34 里是 `lib/d8.jar` |
| `curl: (22) 404` 下 SDK | **文件名猜错** | §2C 下 `repository2-3.xml` 查真名 |
| `curl: (7)/(28)` 连不上 maven/repo1 | Maven Central 被墙 | 换 `maven.aliyun.com` |
| `Permission denied`（unzip/patch 时） | `/workspace` 属主是 root | `sudo chown -R $USER:$USER /workspace` |
| `Reversed (or previously applied) patch detected` | 源码不是干净 v1.2.2 | 重新 `unzip`；确认 `versionName` 是 1.2.2 |
| `keytool error: ... alias <androiddebugkey> does not exist` | keystore 别名/密码不对 | §5 |
| `!!! 签名失败：APK 内没有 META-INF/*.RSA` | keystore 路径错 | `KEYSTORE=` 指对 |
| `zip: command not found` | 没装 zip | §1 |
| 装 APK 报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 签名与 v1.2.2 不同 | 先 `uninstall` |
| `find: '/workspace/r1-client/src': No such file or directory` | `PROJ` 指错 / 源码没解压到那 | 检查 `ls /workspace/r1-client/src` |

---

## 9. 一条龙（照抄即可）

> 网络受限时，用 §2B 的离线包替代 §2/§3/§4 三步。

```bash
# 1) 工具
sudo apt update && sudo apt install -y openjdk-17-jdk-headless unzip zip curl wget
#    ↑ 若报 libxpm4 404：sudo apt clean && sudo apt update --fix-missing && sudo apt install -y --fix-missing openjdk-17-jdk-headless
hash -r && which javac || echo "!!! JDK 没装好"
# 2) SDK（离线包，推荐）
sudo mkdir -p /opt/android-sdk/bin /tmp/dl && \
sudo cp /mnt/c/Users/shaoh/WorkBuddy/R1/sdkdl/stage/android.jar /opt/android-sdk/android.jar && \
sudo cp /mnt/c/Users/shaoh/WorkBuddy/R1/sdkdl/stage/bin/aapt2   /opt/android-sdk/bin/aapt2 && \
sudo cp /mnt/c/Users/shaoh/WorkBuddy/R1/sdkdl/stage/r8.jar      /opt/android-sdk/r8.jar && \
sudo cp /mnt/c/Users/shaoh/WorkBuddy/R1/sdkdl/stage/orgjson.jar /tmp/dl/orgjson.jar && \
sudo chmod +x /opt/android-sdk/bin/aapt2
/opt/android-sdk/bin/aapt2 version
# 3) 源码（注意 chown）
sudo mkdir -p /workspace && sudo chown -R $USER:$USER /workspace && cd /workspace
unzip -q /mnt/c/Users/shaoh/WorkBuddy/R1/r1-client-src-v1.2.2.zip
cd r1-client
grep versionName AndroidManifest.xml     # 必须是 1.2.2
# 4) keystore
keytool -genkeypair -keystore debug.keystore -alias androiddebugkey \
  -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US"
# 5) 补丁
patch -p1 < /mnt/c/Users/shaoh/WorkBuddy/R1/r1-client-v1.2.2-to-v1.2.3.patch
# 6) 构建
bash build.sh
```

---

## 附 A：装到音箱

```bash
# 用 Windows 侧的 adb（WSL 里没有 adb 时）
ADB="/mnt/c/Users/shaoh/WorkBuddy/ridibooks/【adb和fastboot工具】platform-tools 28.0.0 (June 2018)/adb.exe"

"$ADB" connect 192.168.2.5:5555
# 签名若与 v1.2.2 不同，先卸
"$ADB" -s 192.168.2.5:5555 uninstall cn.workbuddy.r1client
"$ADB" -s 192.168.2.5:5555 install -r /workspace/r1-client/r1-client-v1.2.3.apk
```

> 更快：在 WSL 里直接 `sudo apt install -y android-tools-adb`，
> 然后 `adb connect 192.168.2.5:5555`（WSL2 网络模式与 Windows 同网段，通常能通）。

抓日志验证存活（详见 `TEST-v1.2.3.md`）：

```bash
"$ADB" -s 192.168.2.5:5555 logcat -c
"$ADB" -s 192.168.2.5:5555 shell am startservice -n cn.workbuddy.r1client/.R1ClientService
"$ADB" -s 192.168.2.5:5555 logcat -v time R1CLIENT:I *:S
```

**验收核心：`alive 10s / 20s / 30s ...` 心跳连续出现 = 服务活了。**

---

## 附 B：可选 —— 重编 libr1opus.so（只有改 opus 封装时才需要）

v1.2.3 **不需要**这一步（`libr1opus.so` 是现成的）。若要重编：

```bash
# NDK r21e
cd /tmp && wget -q https://dl.google.com/android/repository/android-ndk-r21e-linux-x86_64.zip
unzip -q android-ndk-r21e-linux-x86_64.zip -d /opt && sudo mv /opt/android-ndk-r21e /opt/ndk
NDK=/opt/ndk

# ⚠️ 不能用 Ubuntu 的 libopus-dev（那是 x86_64 的，链接到 armv7 会失败）
# 必须自己交叉编译 opus 静态库，这里给出最简路径：
cd /tmp
wget -q https://downloads.xiph.org/releases/opus/opus-1.3.1.tar.gz
tar xzf opus-1.3.1.tar.gz && cd opus-1.3.1
CC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/armv7a-linux-androideabi21-clang \
./configure --host=armv7a-linux-androideabi --disable-shared --enable-static \
            --disable-doc --disable-extra-programs
make -j$(nproc)

# 编 libr1opus.so
$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/armv7a-linux-androideabi21-clang \
  -shared -O2 -fPIC \
  -o /workspace/r1-client/jniLibs/armeabi-v7a/libr1opus.so \
  /workspace/r1-client/jni/r1opus.c \
  -I. -Iinclude -L.libs -lopus
```

---

## 附 C：WSL 与 Windows 的路径对照

| Windows | WSL |
|---|---|
| `C:\Users\shaoh\WorkBuddy\R1` | `/mnt/c/Users/shaoh/WorkBuddy/R1` |
| 源码包 | `/mnt/c/Users/shaoh/WorkBuddy/R1/r1-client-src-v1.2.2.zip` |
| 补丁 | `/mnt/c/Users/shaoh/WorkBuddy/R1/r1-client-v1.2.2-to-v1.2.3.patch` |
| 构建产物（在 WSL 里） | `/workspace/r1-client/r1-client-v1.2.3.apk` |
| Windows adb | `/mnt/c/Users/shaoh/WorkBuddy/ridibooks/【adb和fastboot工具】platform-tools 28.0.0 (June 2018)/adb.exe` |
