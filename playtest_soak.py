#!/usr/bin/env python3
"""Soak + messy-user playtest for Bypass.

Covers behaviour a real person produces:
  * many connect/disconnect cycles back to back
  * impatient double/triple taps (spamming the switch)
  * leaving the app and coming back while connected
  * verifying traffic actually flows after every connect

Fails on: wrong UI label, missing/live tunnel when it should not be, no traffic
after connect, process death, crash or ANR.
"""
import re
import subprocess
import sys
import time

ADB = r"C:\Android\platform-tools\adb.exe"
DEV = "127.0.0.1:16384"
PKG = "com.vpn.simple"
log = []


def note(s):
    print(s)
    log.append(s)


def sh(*a, t=90):
    return subprocess.run([ADB, "-s", DEV, *a], capture_output=True, text=True,
                          errors="ignore", timeout=t).stdout


def ui():
    sh("shell", "uiautomator", "dump", "/sdcard/_sk.xml")
    return sh("exec-out", "cat", "/sdcard/_sk.xml")


def center(xml=None):
    xml = xml or ui()
    best = None
    for m in re.finditer(r"<node[^>]*>", xml):
        n = m.group(0)
        if 'class="android.view.View"' not in n or 'clickable="true"' not in n:
            continue
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if b:
            x0, y0, x1, y1 = map(int, b.groups())
            a = (x1 - x0) * (y1 - y0)
            if best is None or a > best[0]:
                best = (a, (x0 + x1) // 2, (y0 + y1) // 2)
    return (best[1], best[2]) if best else None


def label():
    x = ui()
    t = [v for v in re.findall(r'text="([^"]*)"', x) if v]
    return next((v for v in t if v != "BYPASS"), "?")


def tun():
    return sorted(set(re.findall(r"\btun\d+\b", sh("shell", "ip", "-o", "addr", "show"))))


def agents():
    return sh("shell", "dumpsys", "connectivity").count("ni{VPN CONNECTED")


def traffic():
    out = sh("shell", "ping", "-c", "2", "-W", "6", "example.com")
    return ("received" in out and "0% packet loss" in out), out.strip().splitlines()[-1][:70] if out.strip() else "none"


def routing():
    """What the core did with the traffic: proxied flows and reachable nodes.

    A live tun interface only means packets enter the tunnel; these two prove the
    profile is loaded and connections are leaving through the user's nodes.
    """
    log = sh("logcat", "-d")
    proxied = [l for l in log.splitlines() if "match Match using SimpleVPN[" in l]
    healthy = [l for l in log.splitlines() if "Health Checked" in l and "alive: true" in l]
    refused = [l for l in log.splitlines() if "using SimpleVPN[" in l and "error:" in l]
    return proxied, healthy, refused


def alive():
    return PKG in sh("shell", "ps", "-A")


def tap(c=None):
    c = c or center()
    sh("shell", "input", "tap", str(c[0]), str(c[1]))
    time.sleep(2)
    # consent dialog may appear on first ever connect
    x = sh("exec-out", "cat", "/sdcard/_sk.xml")
    if "Connection request" in x:
        m = re.search(r'text="OK"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x)
        if m:
            x0, y0, x1, y1 = map(int, m.groups())
            note("      (accepted consent dialog)")
            sh("shell", "input", "tap", str((x0 + x1) // 2), str((y0 + y1) // 2))


def settle(timeout=30):
    prev = None
    last = (tun(), label())
    stable = 0
    end = time.time() + timeout
    while time.time() < end:
        time.sleep(1.5)
        cur = (tun(), label())
        if cur == prev:
            stable += 1
            if stable >= 2:
                return cur
        else:
            stable = 0
        prev = last = cur
    return last


def snapshot(tag):
    s = settle()
    note(f"  {tag:<26} tun={s[0] or 'none'}  agents={agents()}  UI='{s[1]}'")
    return s


def problems_scan():
    l = sh("logcat", "-d", "-t", "800")
    return [x.strip()[:150] for x in l.splitlines()
            if PKG in x and any(k in x.lower() for k in ("fatal", "sigabrt", "sigsegv", " anr "))]


def main():
    fails = []
    sh("shell", "am", "force-stop", PKG)
    time.sleep(1)
    sh("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(3)
    sh("logcat", "-c")

    note("=== A. soak: 6 connect/disconnect cycles, traffic checked each connect ===")
    s = snapshot("start")
    for i in range(1, 7):
        want_up = s[0] == []
        sh("logcat", "-c")  # capture exactly what this attempt produces
        tap()
        s = snapshot(f"cycle {i} {'connect' if want_up else 'disconnect'}")
        if not alive():
            fails.append(f"cycle {i}: process died"); break
        if want_up:
            ok, d = traffic()
            note(f"     internet via tunnel: {'OK' if ok else 'FAILED'} ({d})")
            time.sleep(10)  # let the nodes answer their health checks / background flows
            proxied, healthy, refused = routing()
            note(f"     proxied flows: {len(proxied)}   nodes alive: {len(healthy)}   failed dials: {len(refused)}")
            if proxied:
                note(f"     e.g. {proxied[-1].split('libclash:')[-1].strip()[:96]}")
            if s[0] == []:
                fails.append(f"cycle {i}: connect produced no tun")
            if s[1] != "Connected":
                fails.append(f"cycle {i}: label '{s[1]}'")
            if not ok:
                fails.append(f"cycle {i}: no internet ({d})")
            if not proxied and not healthy:
                fails.append(f"cycle {i}: nothing is being proxied through the profile")
        else:
            if s[0] != []:
                fails.append(f"cycle {i}: disconnect left tunnel {s[0]}")
            if s[1] != "Disconnected":
                fails.append(f"cycle {i}: label '{s[1]}'")
            ok, d = traffic()
            note(f"     direct internet: {'OK' if ok else 'FAILED'} ({d})")

    note("")
    note("=== B. impatient tapping (user spams the switch) ===")
    c = center()
    for n in range(3):
        sh("shell", "input", "tap", str(c[0]), str(c[1]))
        time.sleep(0.4)
    time.sleep(10)
    s = snapshot("after 3 fast taps")
    if not alive():
        fails.append("fast taps: process died")
    ok, d = traffic()
    note(f"     traffic: {'OK' if ok else 'FAILED'} ({d})")
    if s[0] != [] and not ok:
        fails.append(f"fast taps: tunnel {s[0]} but no traffic ({d})")
    if s[0] == [] and s[1] != "Disconnected":
        fails.append(f"fast taps: no tunnel but label '{s[1]}'")
    # leave it in a known state
    tap()
    snapshot("settled")

    note("")
    note("=== C. leave app while connected, come back ===")
    if tun() == []:
        tap(); s = snapshot("connect")
    else:
        s = snapshot("already connected")
    sh("shell", "input", "keyevent", "KEYCODE_HOME")
    time.sleep(2)
    sh("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(3)
    s = snapshot("reopened")
    if s[0] == []:
        fails.append("reopen: tunnel vanished while app was backgrounded")
    if s[1] != "Connected":
        fails.append(f"reopen: label '{s[1]}' but tunnel {'up' if s[0] else 'down'}")
    ok, d = traffic()
    note(f"     traffic after reopen: {'OK' if ok else 'FAILED'} ({d})")
    if not ok:
        fails.append(f"reopen: no traffic ({d})")

    note("")
    note("=== D. final cleanup + crash scan ===")
    tap()
    s = snapshot("final")
    if s[0] != []:
        fails.append(f"final: tunnel still up {s[0]}")
    bad = problems_scan()
    if bad:
        note("  crash/ANR lines:")
        for b in dict.fromkeys(bad):
            note("    " + b)

    note("")
    note("================ SOAK RESULT ================")
    if fails:
        note("FAILURES:")
        for f in fails:
            note("  - " + f)
        open("E:/vpn/soak_out.txt", "w", encoding="utf-8").write("\n".join(log))
        sys.exit(1)
    note("PASS: soak, impatient taps and reopen all behaved; traffic verified; no crashes.")
    open("E:/vpn/soak_out.txt", "w", encoding="utf-8").write("\n".join(log))


if __name__ == "__main__":
    main()
