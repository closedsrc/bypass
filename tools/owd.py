#!/usr/bin/env python3
"""Drive Bypass on the Otherworld Drive (MuMu) device over adb.

Wraps the adb calls the audit and soak runs need: find a node by its visible
text or content description, tap it, read the tunnel and the core's own log.
"""
import re
import subprocess
import time

ADB = r"C:\Android\platform-tools\adb.exe"
# MuMu VM 1 ("Android Device-1") — the instance that has Otherworld Drive installed.
# MuMu VM 0 is 16384/5555; do not mix them up.
DEV = "127.0.0.1:5557"
PKG = "com.vpn.simple"

APK_DEBUG = r"E:\vpn\android\app\build\outputs\apk\debug\app-debug.apk"


def sh(*a, t=120):
    return subprocess.run(
        [ADB, "-s", DEV, *a], capture_output=True, text=True, errors="ignore", timeout=t
    ).stdout


def nodes():
    sh("shell", "uiautomator", "dump", "/sdcard/_owd.xml")
    x = sh("exec-out", "cat", "/sdcard/_owd.xml")
    out = []
    for m in re.finditer(r"<node[^>]*>", x):
        n = m.group(0)

        def g(k):
            mm = re.search(k + r'="([^"]*)"', n)
            return mm.group(1) if mm else ""

        bm = re.search(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", g("bounds"))
        out.append({
            "bounds": tuple(map(int, bm.groups())) if bm else None,
            "text": g("text"),
            "desc": g("content-desc"),
            "class": g("class"),
            "checked": g("checked") == "true",
            "clickable": g("clickable") == "true",
        })
    return out


def find(text=None, desc=None, contains=False):
    for n in nodes():
        if text is not None:
            v = n["text"]
            if (text in v) if contains else (v == text):
                return n
        if desc is not None:
            v = n["desc"]
            if (desc in v) if contains else (v == desc):
                return n
    return None


def center(n):
    x0, y0, x1, y1 = n["bounds"]
    return (x0 + x1) // 2, (y0 + y1) // 2


def tap(target):
    c = target if isinstance(target, tuple) else center(target)
    sh("shell", "input", "tap", str(c[0]), str(c[1]))


def tap_text(text, contains=True):
    n = find(text=text, contains=contains)
    if n:
        tap(n)
        return True
    return False


def wait_text(text, timeout=30, contains=True):
    end = time.time() + timeout
    while time.time() < end:
        if find(text=text, contains=contains):
            return True
        time.sleep(1)
    return False


def texts():
    return [n["text"] for n in nodes() if n["text"]]


def tun():
    return "tun0" in sh("shell", "ip", "-o", "addr", "show")


def alive():
    return PKG in sh("shell", "ps", "-A")


def switch():
    return find(desc="Bypass connection", contains=True)


def connect():
    tap(switch())


def ping():
    return "0% packet loss" in sh("shell", "ping", "-c", "2", "-W", "6", "example.com")


def logcat():
    return sh("logcat", "-d", t=180)


def clear_log():
    sh("logcat", "-c")


def install():
    return sh("install", "-r", "-t", APK_DEBUG, t=300).strip()


def launch():
    sh("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")


def prefs(name="bypass.exclusions"):
    p = sh("shell", "run-as", PKG, "cat", f"/data/data/{PKG}/shared_prefs/{name}.xml")
    return re.findall(r"<string>([^<]*)</string>", p)


def config():
    return sh("shell", "run-as", PKG, "cat", f"/data/data/{PKG}/files/mihomo/config.yaml")


def profile():
    return sh("shell", "run-as", PKG, "cat", f"/data/data/{PKG}/files/profile.yaml")


def consent():
    """Accept Android's VPN consent and the notification prompt if either is up."""
    out = []
    for label in ("OK", "ALLOW"):
        n = find(text=label)
        if n and "vpndialogs" in n["class"] or (n and label == "ALLOW"):
            tap(n)
            out.append(label)
            time.sleep(2)
    return out
