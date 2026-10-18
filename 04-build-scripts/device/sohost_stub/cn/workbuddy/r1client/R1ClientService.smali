.class public Lcn/workbuddy/r1client/R1ClientService;
.super Landroid/app/Service;

# 纯占位：禁止被绑定（onBind 返回 null），即使被 start 也立刻自我停止。

.method public constructor <init>()V
    .locals 0
    invoke-direct {p0}, Landroid/app/Service;-><init>()V
    return-void
.end method

.method public onBind(Landroid/content/Intent;)Landroid/os/IBinder;
    .locals 1
    const/4 v0, 0x0
    return-object v0
.end method

.method public onCreate()V
    .locals 0
    invoke-super {p0}, Landroid/app/Service;->onCreate()V
    invoke-virtual {p0}, Landroid/app/Service;->stopSelf()V
    return-void
.end method

.method public onStartCommand(Landroid/content/Intent;II)I
    .locals 1
    const/4 v0, 0x2
    return v0
.end method
