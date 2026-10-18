.class public Lcn/workbuddy/r1client/MainActivity;
.super Landroid/app/Activity;

# 纯占位：万一有人在抽屉里点到这个图标，也只是打开一个空白页，不做任何事。

.method public constructor <init>()V
    .locals 0
    invoke-direct {p0}, Landroid/app/Activity;-><init>()V
    return-void
.end method

.method protected onCreate(Landroid/os/Bundle;)V
    .locals 0
    invoke-super {p0}, Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V
    invoke-virtual {p0}, Landroid/app/Activity;->finish()V
    return-void
.end method
