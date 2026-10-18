.class public Lcn/workbuddy/r1client/BootReceiver;
.super Landroid/content/BroadcastReceiver;

# 纯占位：开机广播收到即返回，不再拉起任何东西。
# 真正在设备里干活的是 com.phicomm.speaker.device（jp2）里的寄生层，
# 这个包存在的唯一意义是给 libr1opus.so 提供一个 SELinux 允许 exec 的落点。

.method public constructor <init>()V
    .locals 0
    invoke-direct {p0}, Landroid/content/BroadcastReceiver;-><init>()V
    return-void
.end method

.method public onReceive(Landroid/content/Context;Landroid/content/Intent;)V
    .locals 0
    return-void
.end method
