package cn.workbuddy.r1web;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 设备状态采集（CPU / 内存 / 温度 / 存储 / 进程）—— 设置页「设备状态」一栏的数据源。
 *
 * <p>取数方式完全照抄小飞魔改 player 的「设备信息」页：那个页面是给 8080 的 WS 发
 * {@code {type:"shell"}}（{@code cat /sys/class/hwmon/hwmon0/device/temp0_input}、
 * {@code /proc/stat}、{@code scaling_cur_freq}）让设备执行 shell 再把输出回传。
 * 我们不需要绕这一圈 —— player 注入层本来就能 {@code Runtime.exec}，
 * 直接读 procfs / sysfs，更快也更可靠（小智停用时照样能看）。</p>
 *
 * <p>★ R1 实测的节点（rk3229 / API22）：</p>
 * <pre>
 *   温度   /sys/class/hwmon/hwmon0/device/temp0_input = 85（rockchip-tsadc，单位 ℃）
 *          —— /sys/class/thermal/thermal_zone* **不存在**，别去那儿找
 *   CPU    /proc/stat（cpu 行 + cpu0..3）+ /sys/devices/system/cpu/cpuN/cpufreq/scaling_cur_freq
 *   内存   /proc/meminfo（MemTotal / MemAvailable；老内核没 MemAvailable 就退回 Free+Buffers+Cached）
 *   存储   StatFs("/data")（988MB，设备最紧张的那块）
 * </pre>
 *
 * <p>所有取值都吞异常：控制台不该因为读不到一个 sysfs 节点就 500。</p>
 */
public final class Sys {

    /* ---------- 进程白名单 / 清理名单 ---------- */

    /**
     * 绝对不碰的进程 / 包（子串匹配 NAME）。动到其中一个就可能整机重启、播放中断，
     * 或者 adb 断线救不回来。这一层优先于 {@link #USELESS} —— 两个名单都命中时不禁。
     */
    private static final String[] KEEP = {
            "system_server", "zygote",
            "com.phicomm.speaker.player",      // 本进程 + :NetPlayer/:DlnaPlayer/:AirPlayer
            "com.phicomm.speaker.device",      // 小智 + :remote
            "cn.workbuddy.r1client",
            /* 用户 2026-10-17 明确要求：launcher(31MB) / systemtool(27MB) 加白名单，
               别当垃圾清 —— 原厂常驻，省几十 MB 不值当，风险却在自己这边。 */
            "com.phicomm.speaker.launcher",
            "com.phicomm.speaker.systemtool",
            "com.phicomm.speaker.netctl",      // 管配网/联网，停了可能把 WiFi 带走
            /* ★ 血的教训：早期按 uid>=10000 放行，把 com.android.defcontainer
               （包安装容器，uid 10006）当垃圾 hide 了 —— 那玩意儿一停，
               以后 adb install / pm install 全得靠运气。整片 com.android.* 里
               真正要保的就是它和下面这几个，其余的按 USELESS 名单走。 */
            "com.android.defcontainer",
            "com.android.systemui",
            "com.android.bluetooth",
            "com.android.shell",
            "com.android.settings",
            "com.android.packageinstaller",
            "com.android.providers.media",     // MediaStore：曲库扫描依赖它
            "com.android.providers.settings",
            "com.android.systemupdate",
            "com.google.",
    };

    /**
     * 无用服务清单 —— <b>照抄小飞魔改 player 的 useless_services_detection</b>
     * （r1_control.min.js 里的那串常量），一个不多加。
     *
     * <p>用户 2026-10-17 的要求就是「看小飞版禁哪些，不要多禁了」：这份名单是
     * 小飞作者长期实测筛出来的（都是音箱上用不到的：电话/短信、下载、VPN、
     * 定位、产测、OTA、bugreport、ijetty 之类），照抄最稳。早期那种
     * 「uid>=10000 通杀第三方」的写法已经废掉 —— 它把 defcontainer 也禁了。</p>
     *
     * <p>命中规则跟小飞一致：<b>名单 ∩ {@code pm list packages}</b>（只禁已安装的），
     * 再减去 {@link #KEEP}。没在跑的也禁 —— 反正它们随时会被拉起来占内存。</p>
     */
    private static final String[] USELESS = {
            "com.phicomm.speaker.airskill",
            "com.android.providers.telephony",
            "com.phicomm.speaker.productiontest",
            "com.android.externalstorage",
            "com.android.providers.downloads",
            "com.android.certinstaller",
            "com.phicomm.speaker.bugreport",
            "com.android.inputdevices",
            "com.android.server.telecom",
            "com.android.keychain",
            "com.phicomm.speaker.otaservice",
            "com.android.proxyhandler",
            "com.phicomm.speaker.ijetty",
            "com.android.vpndialogs",
            "com.android.location.fused",
            "com.phicomm.speaker.exceptionreporter",
    };

    /* ---------- 对外 ---------- */

    /**
     * 采一轮设备状态。
     *
     * @param cpuNow true = 现场采样 CPU（会睡 250ms 取两次 /proc/stat 求差）；
     *               false = 复用上次采样结果（页面轮询时用，避免拖慢响应）
     */
    public static JSONObject sample(boolean cpuNow) {
        JSONObject o = new JSONObject();
        try {
            o.put("cpuPct", cpuNow ? cpuPct() : lastPct);
            o.put("cores", Runtime.getRuntime().availableProcessors());
            JSONArray mhz = new JSONArray();
            for (int i = 0; i < 8; i++) {
                int f = coreMhz(i);
                if (f <= 0) {
                    break;
                }
                mhz.put(f);
            }
            o.put("coreMhz", mhz);

            long[] mem = memKb();
            o.put("memTotalKb", mem[0]);
            o.put("memAvailKb", mem[1]);
            o.put("memUsedPct", mem[0] > 0
                    ? Math.round((mem[0] - mem[1]) * 100.0 / mem[0]) : 0);

            o.put("tempC", tempC());
            o.put("tempLabel", readOne("/sys/class/hwmon/hwmon0/device/temp0_label"));

            long[] d = diskMb("/data");
            o.put("diskTotalMb", d[0]);
            o.put("diskAvailMb", d[1]);
            o.put("diskUsedPct", d[0] > 0
                    ? Math.round((d[0] - d[1]) * 100.0 / d[0]) : 0);

            List<Proc> ps = ps();
            o.put("procCount", ps.size());
            JSONArray top = new JSONArray();
            for (int i = 0; i < ps.size() && i < 6; i++) {
                top.put(ps.get(i).json());
            }
            o.put("top", top);
            List<Proc> junk = junk(ps);
            JSONArray cl = new JSONArray();
            int kb = 0;
            for (Proc p : junk) {
                cl.put(p.json());
                kb += p.rss;
            }
            o.put("cleanable", cl);
            o.put("cleanableKb", kb);
            o.put("pkgCount", installed().size());
        } catch (Throwable t) {
            Boot.log("sys sample: " + t);
        }
        return o;
    }

    /**
     * 一键清理 —— 两件事：
     * <pre>
     *   ① 禁用无用服务：{@link #USELESS} ∩ 已安装的包，走 pm hide（小飞版同款）
     *   ② 清缓存：pm trim-caches，把 /data 里各包的缓存腾出来（存储空间那栏立刻见效）
     * </pre>
     *
     * <p>命令拼接方式也照小飞：{@code pm hide a && pm hide b && ...} 一次发出去
     * （逐个 exec 要 fork 十几次，这台机器上一个 am/pm 就是几百毫秒）。</p>
     *
     * <p>执行完重新查一遍 {@code pm list packages} 逐个复查：还在列表里就说明没禁成，
     * 把 pm 的原始输出贴出来，绝不假装成功。</p>
     */
    public static String cleanup() {
        try {
            List<Proc> cand = junk(ps());
            StringBuilder sb = new StringBuilder();
            if (!cand.isEmpty()) {
                StringBuilder cmd = new StringBuilder();
                for (int i = 0; i < cand.size(); i++) {
                    if (i > 0) {
                        cmd.append(" ; ");        // 用 ; 不用 && ：一个禁不掉不该连坐后面
                    }
                    cmd.append("/system/bin/pm hide ").append(cand.get(i).name);
                }
                String r = DevCtl.exec(cmd.toString());
                try {
                    Thread.sleep(1500);
                } catch (Throwable t) {
                    // ignore
                }
                instTs = 0;                     // 丢掉 pm list packages 缓存，重新查
                List<String> left = installed();
                int ok = 0;
                long freed = 0;
                for (int i = 0; i < cand.size(); i++) {
                    Proc q = cand.get(i);
                    if (left.indexOf(q.name) < 0) {
                        ok++;
                        freed += q.rss;
                        addHidden(q.name);
                        sb.append("已禁用 ").append(q.name)
                                .append(q.rss > 0 ? "（在跑，" + (q.rss / 1024) + "MB）" : "（未在跑）")
                                .append("\n");
                    } else {
                        sb.append("禁不掉 ").append(q.name).append("：")
                                .append(r.length() > 80 ? r.substring(0, 80) : r).append("\n");
                    }
                }
                if (ok > 0) {
                    sb.insert(0, "禁用 " + ok + " 个无用服务"
                            + (freed > 0 ? "，进程释放约 " + (freed / 1024) + "MB" : "") + "\n");
                }
            }

            long before = diskMb("/data")[1];
            DevCtl.exec("pm trim-caches 2000000000");
            long after = diskMb("/data")[1];

            String head = cand.isEmpty()
                    ? "没有可禁用的无用服务（清单里那几个都没装，或者已经禁过了）"
                    : "清理完成";
            if (after > before) {
                head += "；缓存清出 " + (after - before) + "MB";
            } else if (after < before) {
                head += "（/data 可用反而少了 " + (before - after) + "MB，多半是别的进程在写）";
            } else {
                head += "；缓存已经很干净，没清出东西";
            }
            Boot.log("cleanup: " + head);
            return head + (sb.length() > 0 ? "\n" + sb.toString().trim() : "");
        } catch (Throwable t) {
            return "清理失败: " + t;
        }
    }

    /**
     * 清理的反向操作：{@code pm unhide}。
     *
     * <p>★ 不依赖 {@code r1_hidden.txt} 记录 —— 直接把 {@link #USELESS} 全名单
     * unhide 一遍（加上记录里可能有的额外包名）。记录文件丢了也能救回来，
     * 反正 unhide 一个本来就启用着的包没有任何副作用。</p>
     */
    public static String restore() {
        List<String> pkgs = new ArrayList<String>();
        for (int i = 0; i < USELESS.length; i++) {
            pkgs.add(USELESS[i]);
        }
        List<String> h = hidden();
        for (int i = 0; i < h.size(); i++) {
            if (pkgs.indexOf(h.get(i)) < 0) {
                pkgs.add(h.get(i));
            }
        }
        StringBuilder cmd = new StringBuilder();
        for (int i = 0; i < pkgs.size(); i++) {
            if (i > 0) {
                cmd.append(" ; ");        // 用 ; ：未安装的包会报错，不能让它连坐
            }
            cmd.append("/system/bin/pm unhide ").append(pkgs.get(i));
        }
        DevCtl.exec(cmd.toString());
        instTs = 0;
        clearHidden();
        Boot.log("restore: " + pkgs.size() + " pkgs");
        return "已恢复（pm unhide）：清单里 " + USELESS.length + " 个包全部解除隐藏\n"
                + "进程会由系统自己拉起来，过几秒再看设备状态";
    }

    /* ---- hide 记录：写 player 自己的 files 目录，重启后还在 ---- */

    private static synchronized List<String> hidden() {
        List<String> r = new ArrayList<String>();
        try {
            String s = readFile(new java.io.File(WebSrv.app.getFilesDir(), "r1_hidden.txt"));
            if (s != null) {
                String[] a = s.split("[\\r\\n]+");
                for (int i = 0; i < a.length; i++) {
                    if (a[i].trim().length() > 0) {
                        r.add(a[i].trim());
                    }
                }
            }
        } catch (Throwable t) {
            // ignore
        }
        return r;
    }

    private static synchronized void addHidden(String pkg) {
        try {
            List<String> l = hidden();
            if (l.indexOf(pkg) < 0) {
                l.add(pkg);
            }
            StringBuilder sb = new StringBuilder();
            for (String s : l) {
                sb.append(s).append("\n");
            }
            java.io.File f = new java.io.File(WebSrv.app.getFilesDir(), "r1_hidden.txt");
            java.io.FileOutputStream o = new java.io.FileOutputStream(f);
            o.write(sb.toString().getBytes("UTF-8"));
            o.close();
        } catch (Throwable t) {
            Boot.log("addHidden: " + t);
        }
    }

    private static void clearHidden() {
        try {
            java.io.File f = new java.io.File(WebSrv.app.getFilesDir(), "r1_hidden.txt");
            if (f.exists()) {
                f.delete();
            }
        } catch (Throwable t) {
            // ignore
        }
    }

    private static String readFile(java.io.File f) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(f), 512);
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = br.readLine()) != null) {
                sb.append(l).append("\n");
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            close(br);
        }
    }

    /** 进程名 → 包名（去掉 ":remote" 之类的后缀）。 */
    private static String pkgOf(String name) {
        int i = name == null ? -1 : name.indexOf(':');
        return i > 0 ? name.substring(0, i) : (name == null ? "" : name);
    }

    /* ---------------- CPU ---------------- */

    private static long[] lastTicks = null;
    private static volatile int lastPct = -1;

    /** 读 /proc/stat 的 cpu 行：user nice system idle iowait irq softirq steal。 */
    private static long[] ticks() {
        String s = firstLineStartsWith("/proc/stat", "cpu ");
        if (s == null) {
            return null;
        }
        String[] f = s.split("\\s+");
        long[] v = new long[8];
        for (int i = 0; i < v.length && i < f.length - 1; i++) {
            try {
                v[i] = Long.parseLong(f[i + 1]);
            } catch (Throwable t) {
                v[i] = 0;
            }
        }
        return v;
    }

    private static long sum(long[] a) {
        long s = 0;
        for (long x : a) {
            s += x;
        }
        return s;
    }

    /**
     * CPU 总占用（%）。
     *
     * <p>单次读 /proc/stat 只能拿到开机以来的累计值 ⇒ 必须取两次求差。
     * 第一次调用会现场睡 250ms 采两拍；之后 {@code sample(false)} 直接用
     * 「上次调用到现在」的差值（页面本来就几秒轮询一次，这个值反而更准）。</p>
     */
    private static int cpuPct() {
        try {
            long[] a = ticks();
            if (a == null) {
                return lastPct;
            }
            if (lastTicks != null) {
                long dt = sum(a) - sum(lastTicks);
                long di = a[3] - lastTicks[3];
                if (dt > 0) {
                    int pct = (int) Math.round(100.0 * (dt - di) / dt);
                    lastPct = pct < 0 ? 0 : (pct > 100 ? 100 : pct);
                    lastTicks = a;
                    return lastPct;
                }
            }
            lastTicks = a;
            // 首次：补一拍
            Thread.sleep(250);
            long[] b = ticks();
            if (b == null) {
                return lastPct;
            }
            long dt = sum(b) - sum(a);
            long di = b[3] - a[3];
            if (dt > 0) {
                int pct = (int) Math.round(100.0 * (dt - di) / dt);
                lastPct = pct < 0 ? 0 : (pct > 100 ? 100 : pct);
            }
            lastTicks = b;
        } catch (Throwable t) {
            Boot.log("cpu: " + t);
        }
        return lastPct;
    }

    /** 第 i 核当前频率（MHz）。读不到（离线核 / 没 cpufreq 节点）返回 0。 */
    private static int coreMhz(int i) {
        String s = readOne("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq");
        if (s == null) {
            s = readOne("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_cur_freq");
        }
        if (s == null) {
            return 0;
        }
        try {
            return (int) (Long.parseLong(s.trim()) / 1000L);   // kHz → MHz
        } catch (Throwable t) {
            return 0;
        }
    }

    /* ---------------- 内存 ---------------- */

    /** [MemTotal, 可用]（kB）。可用的定义优先 MemAvailable，退回 Free+Buffers+Cached。 */
    private static long[] memKb() {
        long total = 0, free = 0, avail = -1, buffers = 0, cached = 0;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader("/proc/meminfo"), 4096);
            String l;
            while ((l = br.readLine()) != null) {
                if (l.startsWith("MemTotal:")) {
                    total = num(l);
                } else if (l.startsWith("MemAvailable:")) {
                    avail = num(l);
                } else if (l.startsWith("MemFree:")) {
                    free = num(l);
                } else if (l.startsWith("Buffers:")) {
                    buffers = num(l);
                } else if (l.startsWith("Cached:")) {
                    cached = num(l);
                }
            }
        } catch (Throwable t) {
            Boot.log("mem: " + t);
        } finally {
            close(br);
        }
        if (avail < 0) {
            avail = free + buffers + cached;
        }
        return new long[]{total, avail};
    }

    private static long num(String l) {
        String[] f = l.split("\\s+");
        try {
            return Long.parseLong(f[1]);
        } catch (Throwable t) {
            return 0;
        }
    }

    /* ---------------- 温度 ---------------- */

    /**
     * CPU 温度（℃）。
     *
     * <p>候选节点按本机的实际情况排：rk3229 只有 rockchip-tsadc 这一个 hwmon，
     * /sys/class/thermal 根本不存在。值域判单位：>1000 当毫度（/1000）。</p>
     */
    private static int tempC() {
        String[] paths = {
                "/sys/class/hwmon/hwmon0/device/temp0_input",
                "/sys/class/thermal/thermal_zone0/temp",
                "/sys/devices/virtual/thermal/thermal_zone0/temp",
        };
        for (String p : paths) {
            String s = readOne(p);
            if (s == null) {
                continue;
            }
            try {
                long v = Long.parseLong(s.trim());
                if (v > 1000) {
                    v = v / 1000;      // 毫度
                }
                return (int) v;
            } catch (Throwable t) {
                // 换下一个节点
            }
        }
        return -1;
    }

    /* ---------------- 存储 ---------------- */

    /** [总量, 可用]（MB）。 */
    private static long[] diskMb(String path) {
        try {
            android.os.StatFs sf = new android.os.StatFs(path);
            long bs = sf.getBlockSizeLong();
            long total = sf.getBlockCountLong() * bs / (1024 * 1024);
            long avail = sf.getAvailableBlocksLong() * bs / (1024 * 1024);
            return new long[]{total, avail};
        } catch (Throwable t) {
            return new long[]{0, 0};
        }
    }

    /* ---------------- 进程 ---------------- */

    static final class Proc {
        String user;
        int pid;
        int ppid;
        int rss;            // kB
        String name;

        JSONObject json() {
            JSONObject o = new JSONObject();
            try {
                o.put("user", user);
                o.put("pid", pid);
                o.put("rss", rss);
                o.put("name", name);
            } catch (Throwable t) {
                // ignore
            }
            return o;
        }
    }

    /**
     * 进程表（按 RSS 降序）。
     *
     * <p>★ 为什么不跑 {@code ps}：第一版就是 exec("ps") 再按 9 段切，
     * 实测在 player 进程里拿回来的东西解析不出一行（procCount 恒为 0）——
     * fork 出来的 sh 在这台 512MB 的机器上本来就不可靠，还得赌输出没被 stderr 插队。
     * 直接遍历 /proc 更稳：cmdline 给进程名、status 给 VmRSS/Uid、stat 给 ppid，
     * 全是 0444 的只读文本，不需要任何权限，也不用 fork。</p>
     */
    static List<Proc> ps() {
        List<Proc> list = new ArrayList<Proc>();
        try {
            String[] fs = new java.io.File("/proc").list();
            if (fs == null) {
                return list;
            }
            for (int k = 0; k < fs.length; k++) {
                if (!isNum(fs[k])) {
                    continue;
                }
                int pid = Integer.parseInt(fs[k]);
                String cmd = cmdlineOf(pid);
                if (cmd == null || cmd.length() == 0) {
                    continue;                     // 内核线程：cmdline 是空的
                }
                if (cmd.indexOf('/') == 0 || cmd.indexOf('[') == 0) {
                    continue;                     // native 服务不进列表
                }
                Proc p = new Proc();
                p.pid = pid;
                p.name = cmd;
                p.ppid = ppidOf(pid);
                p.rss = rssOf(pid);
                p.user = uidOf(pid);
                list.add(p);
            }
        } catch (Throwable t) {
            Boot.log("ps: " + t);
        }
        Collections.sort(list, new Comparator<Proc>() {
            public int compare(Proc a, Proc b) {
                return b.rss - a.rss;
            }
        });
        return list;
    }

    /**
     * 进程名里有没有以 suffix 结尾的（用来判断 :DlnaPlayer / :AirPlayer 这类
     * 独立进程服务在不在跑）。
     */
    static boolean hasProc(String suffix) {
        return pidsOf(suffix).size() > 0;
    }

    /** 进程名以 suffix 结尾的那些 pid（":DlnaPlayer" / ":AirPlayer" …）。 */
    static List<Integer> pidsOf(String suffix) {
        List<Integer> r = new ArrayList<Integer>();
        List<Proc> ps = ps();
        for (Proc p : ps) {
            if (p.name != null && p.name.endsWith(suffix)) {
                r.add(Integer.valueOf(p.pid));
            }
        }
        return r;
    }

    /** /proc/PID/cmdline：以 \0 分段，第一段就是进程名（app 进程 = 包名）。 */
    private static String cmdlineOf(int pid) {
        java.io.InputStream in = null;
        try {
            in = new java.io.FileInputStream("/proc/" + pid + "/cmdline");
            byte[] b = new byte[256];
            int n = in.read(b);
            if (n <= 0) {
                return null;
            }
            int end = 0;
            while (end < n && b[end] != 0) {
                end++;
            }
            return new String(b, 0, end, "UTF-8").trim();
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable t) {
                // ignore
            }
        }
    }

    /** /proc/PID/status 里抠一行（VmRSS / Uid）。 */
    private static String statusLine(int pid, String key) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader("/proc/" + pid + "/status"), 1024);
            String l;
            while ((l = br.readLine()) != null) {
                if (l.startsWith(key)) {
                    return l;
                }
            }
        } catch (Throwable t) {
            // 进程可能刚好没了
        } finally {
            close(br);
        }
        return null;
    }

    /** 常驻内存（kB）。 */
    private static int rssOf(int pid) {
        String l = statusLine(pid, "VmRSS:");
        if (l == null) {
            return 0;
        }
        String[] f = l.split("\\s+");
        try {
            return Integer.parseInt(f[1]);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** uid（字符串，1000=system、10008 这类是 app）。 */
    private static String uidOf(int pid) {
        String l = statusLine(pid, "Uid:");
        if (l == null) {
            return "";
        }
        String[] f = l.split("\\s+");
        return f.length > 1 ? f[1] : "";
    }

    /**
     * ppid —— /proc/PID/stat 的第 4 字段。
     * comm 字段带括号且可能含空格，所以先跳到最后一个 ')' 再切。
     */
    private static int ppidOf(int pid) {
        String s = readOne("/proc/" + pid + "/stat");
        if (s == null) {
            return 0;
        }
        int e = s.lastIndexOf(')');
        if (e < 0 || e + 2 >= s.length()) {
            return 0;
        }
        String[] f = s.substring(e + 2).split("\\s+");
        try {
            return Integer.parseInt(f[1]);      // [0]=state，[1]=ppid
        } catch (Throwable t) {
            return 0;
        }
    }

    private static boolean isNum(String s) {
        if (s == null || s.length() == 0) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 可禁用候选 —— {@link #USELESS} ∩ 已安装 ∩ 不在 {@link #KEEP} 里（跟小飞一致）。
     *
     * <p>返回的 {@link Proc} 是「包」维度的伪条目：{@code name}=包名、
     * {@code pid}=它某个在跑的进程（没在跑就是 0）、{@code rss}=这个包所有进程
     * 的常驻内存合计（没在跑就是 0 —— 这种禁用不立刻省内存，但不会再被拉起来）。</p>
     */
    private static List<Proc> junk(List<Proc> ps) {
        List<Proc> r = new ArrayList<Proc>();
        List<String> inst = installed();
        for (int i = 0; i < USELESS.length; i++) {
            String pkg = USELESS[i];
            if (hit(pkg, KEEP) || inst.indexOf(pkg) < 0) {
                continue;
            }
            Proc q = new Proc();
            q.name = pkg;
            q.user = "";
            for (int j = 0; j < ps.size(); j++) {
                Proc p = ps.get(j);
                if (pkg.equals(pkgOf(p.name))) {
                    q.rss += p.rss;
                    if (q.pid == 0) {
                        q.pid = p.pid;
                    }
                }
            }
            r.add(q);
        }
        return r;
    }

    /* pm list packages 很重（要 fork 一次），而页面每 5 秒刷一次 —— 缓存 60 秒 */
    private static List<String> instCache = null;
    private static long instTs = 0;

    private static synchronized List<String> installed() {
        long now = System.currentTimeMillis();
        if (instCache != null && now - instTs < 60000) {
            return instCache;
        }
        List<String> r = new ArrayList<String>();
        String out = DevCtl.exec("/system/bin/pm list packages");
        if (out != null) {
            String[] t = out.split("\\s+");
            for (int i = 0; i < t.length; i++) {
                int k = t[i].indexOf("package:");
                if (k >= 0) {
                    String p = t[i].substring(k + 8).trim();
                    if (p.length() > 0) {
                        r.add(p);
                    }
                }
            }
        }
        instCache = r;
        instTs = now;
        return r;
    }

    private static boolean hit(String name, String[] pats) {
        for (String k : pats) {
            if (name != null && name.indexOf(k) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** pid 还在不在（kill 之后复查，别把「命令成功」当成「进程没了」）。 */
    /* ---------------- 小工具 ---------------- */

    /** 读一个单行 sysfs/procfs 节点，读不到返回 null。 */
    private static String readOne(String path) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(path), 256);
            String s = br.readLine();
            return s == null ? null : s.trim();
        } catch (Throwable t) {
            return null;
        } finally {
            close(br);
        }
    }

    /** 找到以 prefix 开头的那一行（/proc/stat 的 "cpu " 总行）。 */
    private static String firstLineStartsWith(String path, String prefix) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(path), 4096);
            String l;
            while ((l = br.readLine()) != null) {
                if (l.startsWith(prefix)) {
                    return l;
                }
            }
        } catch (Throwable t) {
            // ignore
        } finally {
            close(br);
        }
        return null;
    }

    private static void close(BufferedReader br) {
        try {
            if (br != null) {
                br.close();
            }
        } catch (Throwable t) {
            // ignore
        }
    }

    private Sys() {
    }
}
