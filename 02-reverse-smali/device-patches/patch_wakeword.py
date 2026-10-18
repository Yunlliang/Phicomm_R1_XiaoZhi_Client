#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
jp2 v1.3.71：把原厂（云知声 USC）的默认唤醒词从「小讯小讯」改成「小智小智」。

为什么不在 resources.arsc 里改？
    Janus(CVE-2017-13156) 顶替的前提是「APK 的 v1(JAR) 签名仍然有效」，
    前插 dex 不进签名校验范围。resources.arsc 是签名条目，改一个字节
    MANIFEST.MF 摘要就失配 -> 装上就 INSTALL_FAILED / 无法覆盖 system app。
    我们没有原厂私钥，不能重签，所以只能在自己回编的 dex（smali）层短路。

短路点（UserPerferenceUtil）：
    getDefaultWakeupWord(Context)  -> 原读 R.array.default_wakeup_words
                                      （实测 = ["小讯小讯","交接手续"]）
    getMainWakeupWord(Context)     -> 原 = SP set + getDefaultWakeupWord()
    两者都写成只返回 ["小智小智"]，覆盖全部调用者：
        NativeANTEngine.smali:904/1406/1412/1439（setWakeupWord0 词源）
        NativeANTEngine$1.smali:68
        DefaultANTEMainVocabProvider.smali:72
        NLUDispatcher.smali:582/736（ASR 文本剥离唤醒词）
"""
import io
import os
import re
import sys

TARGET = r'C:\Users\shaoh\WorkBuddy\R1\janus_pandora2\uni_smali\com\unisound\vui\util\UserPerferenceUtil.smali'
WAKE = r'\u5c0f\u667a\u5c0f\u667a'          # 小智小智

SIG = '()Ljava/util/List;'

NEW_BODY = '''.method public static {name}(Landroid/content/Context;)Ljava/util/List;
    .registers 4
    .param p0, "context"    # Landroid/content/Context;
    .annotation system Ldalvik/annotation/Signature;
        value = {
            "(",
            "Landroid/content/Context;",
            ")",
            "Ljava/util/List",
            "<",
            "Ljava/lang/String;",
            ">;"
        }
    .end annotation

    .prologue
    # jp2 v1.3.71: factory wakeup word hard-wired to "小智小智".
    #   was: R.array.default_wakeup_words (小讯小讯/交接手续) / SP main_wakeup_word.
    #   cannot patch resources.arsc: it is inside the v1 signature, and Janus
    #   replacement depends on that signature staying valid.
    new-instance v1, Ljava/util/ArrayList;

    invoke-direct {{v1}}, Ljava/util/ArrayList;-><init>()V

    const-string v0, "{wake}"

    invoke-interface {{v1, v0}}, Ljava/util/List;->add(Ljava/lang/Object;)Z

    return-object v1
.end method
'''


def replace_method(text, name):
    start = text.find('.method public static %s(Landroid/content/Context;)Ljava/util/List;' % name)
    assert start >= 0, 'method not found: ' + name
    end = text.find('\n.end method', start)
    assert end > 0, 'end not found: ' + name
    end += len('\n.end method')
    new = NEW_BODY.replace('{name}', name).replace('{wake}', WAKE) \
                 .replace('{{', '{').replace('}}', '}')
    return text[:start] + new + text[end:], text[start:end]


def main():
    with io.open(TARGET, 'r', encoding='utf-8') as f:
        text = f.read()

    for name in ('getDefaultWakeupWord', 'getMainWakeupWord'):
        text, old = replace_method(text, name)
        print('patched %-22s (%d -> %d chars)' % (name, len(old), len(text[:0]) + 0))
        print('   old first line:', old.splitlines()[0])

    with io.open(TARGET, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)
    print('written:', TARGET)

    # 自检
    chk = io.open(TARGET, 'r', encoding='utf-8').read()
    assert chk.count(WAKE) == 2, 'wake word count = %d' % chk.count(WAKE)
    print('SELF-CHECK OK: const-string 小智小智 x2')


if __name__ == '__main__':
    main()
