package cn.workbuddy.r1client;

import android.util.Log;

import java.io.File;

/**
 * Opus 编解码 JNI 封装。
 *
 * 上行：16kHz / mono / 60ms 帧 → CBR 24kbps（与官方客户端一致）
 * 下行：服务器下发裸 opus 包，按 16kHz mono 解码
 *
 * 说明：native 层需链接 libopus，so 放 jniLibs/armeabi-v7a/libopus.so。
 */
public final class OpusCodec {

    private static final String TAG = "R1CLIENT";

    /** 由 XzBootstrap 通过 PackageManager 解析后注入（r1client 的 nativeLibraryDir）。 */
    public static volatile String overrideLibPath;

    static {
        // v1.3.85：加载顺序 = 自持落点（files/、/data/local/tmp、/data/local、cache）
        //            → r1client 借壳 → 最后 loadLibrary。
        //
        // 目的：做成**单包**（卸载 cn.workbuddy.r1client 也能跑）。卡点是 SELinux ——
        // platform_app 域对 app_data_file（files/）通常没有 execute 权限，System.load
        // 会被拒。这里逐个落点试，哪个能 dlopen 就用哪个，并把每个结果写进
        // SessionLog（logcat 会被冲掉，rc.log 是持久的，事后可查）。
        //
        // 注意：不能依赖 overrideLibPath —— 外部对它赋值会先触发本类初始化，
        // 静态块跑完时它仍是 null（实测踩坑）。故直接枚举硬编码路径。
        boolean ok = false;
        java.util.ArrayList<String> cand = new java.util.ArrayList<String>();
        String pkg = "com.phicomm.speaker.device";
        cand.add("/data/data/" + pkg + "/files/libr1opus.so");
        cand.add("/data/local/tmp/libr1opus.so");
        cand.add("/data/local/libr1opus.so");
        cand.add("/data/data/" + pkg + "/cache/libr1opus.so");
        for (int i = 1; i <= 4; i++) {
            cand.add("/data/app/cn.workbuddy.r1client-" + i + "/lib/arm/libr1opus.so");
            cand.add("/data/app/cn.workbuddy.r1client-" + i + "/lib/armeabi-v7a/libr1opus.so");
        }
        if (overrideLibPath != null) {
            cand.add(overrideLibPath);
        }
        for (String p : cand) {
            try {
                File f = new File(p);
                if (!f.exists()) {
                    continue;
                }
                System.load(p);
                ok = true;
                Log.i(TAG, "loaded r1opus from " + p);
                try {
                    SessionLog.i("so load OK: " + p);
                } catch (Throwable ignored) {
                }
                break;
            } catch (Throwable t2) {
                Log.w(TAG, "load " + p + " failed: " + t2);
                try {
                    SessionLog.i("so load FAIL " + p + ": " + t2);
                } catch (Throwable ignored) {
                }
            }
        }
        if (!ok) {
            try {
                System.loadLibrary("r1opus");
                ok = true;
                try {
                    SessionLog.i("so load OK: loadLibrary(r1opus)");
                } catch (Throwable ignored) {
                }
            } catch (Throwable t3) {
                Log.e(TAG, "load r1opus failed (all paths): " + t3);
                try {
                    SessionLog.i("so load FAILED (all paths): " + t3);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ---- 上行编码器 ----
    private long encPtr;

    public synchronized boolean openEncoder() {
        if (encPtr != 0) {
            return true;
        }
        encPtr = nativeEncoderCreate(AudioCapture.SAMPLE_RATE, 24000, 60);
        Log.i(TAG, "opus encoder = " + encPtr);
        return encPtr != 0;
    }

    /**
     * 编码一帧 60ms PCM（1920 字节）→ opus 包。
     * @return opus 字节数组，失败返回 null
     */
    public synchronized byte[] encode(byte[] pcm, int len) {
        if (encPtr == 0) {
            return null;
        }
        return nativeEncode(encPtr, pcm, len);
    }

    public synchronized void closeEncoder() {
        if (encPtr != 0) {
            nativeEncoderDestroy(encPtr);
            encPtr = 0;
        }
    }

    // ---- 下行解码器 ----
    private long decPtr;

    public synchronized boolean openDecoder() {
        if (decPtr != 0) {
            return true;
        }
        decPtr = nativeDecoderCreate(AudioCapture.SAMPLE_RATE, 1);
        Log.i(TAG, "opus decoder = " + decPtr);
        return decPtr != 0;
    }

    /**
     * 解码一个 opus 包 → PCM（16bit LE）。
     */
    public synchronized byte[] decode(byte[] opus, int len) {
        if (decPtr == 0) {
            return null;
        }
        return nativeDecode(decPtr, opus, len);
    }

    public synchronized void closeDecoder() {
        if (decPtr != 0) {
            nativeDecoderDestroy(decPtr);
            decPtr = 0;
        }
    }

    // ---- native ----
    private static native long nativeEncoderCreate(int sampleRate, int bitrate, int frameMs);

    private static native byte[] nativeEncode(long ptr, byte[] pcm, int len);

    private static native void nativeEncoderDestroy(long ptr);

    private static native long nativeDecoderCreate(int sampleRate, int channels);

    private static native byte[] nativeDecode(long ptr, byte[] opus, int len);

    private static native void nativeDecoderDestroy(long ptr);
}
