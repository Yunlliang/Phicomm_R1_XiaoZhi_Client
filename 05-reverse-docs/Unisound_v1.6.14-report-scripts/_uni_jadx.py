# -*- coding: utf-8 -*-
"""jadx 深挖 Unisound v1.6.14 载荷：xiaofei / NanoHTTPD / dnsjava。"""
import json
import subprocess

JAVA = r'C:\Users\shaoh\WorkBuddy\R1\janus\dl\jdk-17.0.20.1+1\bin\java.exe'
JAR = r'C:\Users\shaoh\.workbuddy\binaries\jadx-mcp\jadx-headless-mcp-0.7.1-all.jar'
APK = r'C:\Users\shaoh\WorkBuddy\R1\一键安装Unisound_v1.6.14\apps\Unisound_v1.6.14.apk'

proc = subprocess.Popen([JAVA, '-Xmx4096m', '-jar', JAR],
                        stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                        stderr=subprocess.DEVNULL, text=True, encoding='utf-8', bufsize=1)
_id = [0]


def call(method, params=None, wait=True):
    if method != 'notifications/initialized':
        _id[0] += 1
    msg = {'jsonrpc': '2.0', 'method': method}
    if method != 'notifications/initialized':
        msg['id'] = _id[0]
    if params is not None:
        msg['params'] = params
    proc.stdin.write(json.dumps(msg) + '\n')
    proc.stdin.flush()
    if not wait:
        return None
    rid = _id[0]
    while True:
        line = proc.stdout.readline()
        if not line:
            return None
        line = line.strip()
        if not line:
            continue
        try:
            obj = json.loads(line)
        except ValueError:
            continue
        if obj.get('id') == rid:
            return obj


def raw(obj, n=2500):
    try:
        return obj['result']['content'][0]['text'][:n]
    except Exception:
        return json.dumps(obj.get('error', obj), ensure_ascii=False)[:400]


def clean(src, n=2200):
    out = []
    for line in src.split('\n'):
        s = line.strip()
        if s.startswith('import ') or s.startswith('/*') or s.startswith('*') or not s:
            continue
        out.append(line)
    return '\n'.join(out)[:n]


call('initialize', {'protocolVersion': '2024-11-05', 'capabilities': {},
                    'clientInfo': {'name': 'p', 'version': '1'}})
call('notifications/initialized', wait=False)
print('== load ==')
print(raw(call('tools/call', {'name': 'load_apk', 'arguments': {'path': APK}}), 400))

for kw in ['xiaofei', 'elonen', 'jnamed']:
    print('\n== 搜索类: %s ==' % kw)
    print(raw(call('tools/call', {'name': 'search_classes_by_keyword',
                                  'arguments': {'keyword': kw}}), 1500))

for cls in ['com.xiaofei.StackTraceUtil', 'com.xiaofei.server']:
    print('\n== 源码: %s ==' % cls)
    print(clean(raw(call('tools/call', {'name': 'get_class_source',
                                        'arguments': {'class_name': cls}}), 6000)))

print('\n== NanoHTTPD 入口源码 ==')
print(clean(raw(call('tools/call', {'name': 'get_class_source',
                                    'arguments': {'class_name': 'fi.iki.elonen.NanoHTTPD'}}), 6000), 1800))

try:
    proc.stdin.close()
except Exception:
    pass
