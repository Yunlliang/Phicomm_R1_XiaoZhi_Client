# jp2 v1.3.68：寄生层 keep 规则
# 说明：okhttp/okio/orgjson 由原厂 dex 提供（--lib 引入，不打包），
#       kotlin-stdlib 与 sherpa 必须打包（原厂 dex 里没有），靠 R8 按可达性裁剪。
-keep class cn.workbuddy.r1client.** { *; }
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembers class * {
    native <methods>;
}
# KWS/音频回调都是反射或 JNI 触发，避免被内联掉
-keep,allowoptimization class cn.workbuddy.r1client.KwsEngine$* { *; }
-dontobfuscate
-ignorewarnings
