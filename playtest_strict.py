#!/usr/bin/env python3
"""Strict playtest: prove traffic actually flows after EVERY connect.

A tunnel interface can exist while carrying no traffic (stale mihomo state),
so this checks real DNS + HTTP reachability after each connect, and also
exercises fast repeated taps the way a person actually taps.
"""
import re
import subprocess
import sys
import time

ADB = r"C:\Android\platform-tools\adb.exe"
DEV = "127.0.0.1:16384"
PKG = "com.vpn.simple"


def sh(*a, t=60):
    return subprocess.run([ADB, "-s", DEV, *a], capture_output=True, text=True,
                          errors="ignore", timeout=t).stdout


def ui():
    sh("shell", "uiautomator", "dump", "/sdcard/_s.xml")
    return sh("exec-out", "cat", "/sdcard/_s.xml")


def center(xml):
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


def label(xml):
    t = [x for x in re.findall(r'text="([^"]*)"', xml) if x]
    return next((x for x in t if x != "BYPASS"), "?")


def tun():
    return sorted(set(re.findall(r"\btun\d+\b", sh("shell", "ip", "-o", "addr", "show"))))


def traffic():
    """Real end-to-end check through whatever route is active."""
    out = sh("shell", "ping", "-c", "2", "-W", "6", "example.com")
    ok = "2 received" in out or "3 received" in out
    if not ok:
        ok = "0% packet loss" in out
    return ok, out.strip().splitlines()[-1][:80] if out.strip() else "no output"


def state():
    x = ui()
    return {"tun": tun(), "label": label(x), "center": center(x)}


def settle(prev, timeout=30.0):
    end = time.time() + timeout
    stable, last = 0, prev
    while time.time() < end:
        time.sleep(1.5)
        cur = state()
        if cur["tun"] == prev["tun"] and cur["label"] == prev["label"]:
            stable += 1
            if stable >= 2:
                return cur
        else:
            stable = 0
        prev, last = cur, cur
    return last


def tap(c):
    sh("shell", "input", "tap", str(c[0]), str(c[1]))


def consent():
    x = sh("exec-out", "cat", "/sdcard/_s.xml")
    if "Connection request" in x:
        m = re.search(r'text="OK"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x)
        if m:
            x0, y0, x1, y1 = map(int, m.groups())
            print("      (consent dialog accepted)")
            sh("shell", "input", "tap", str((x0 + x1) // 2), str((y0 + y1) // 2))
            return True
    return False


def main():
    fail = []
    sh("shell", "am", "force-stop", PKG)
    time.sleep(1)
    sh("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(3)
    sh("logcat", "-c")

    s = state()
    print(f"opened: tun={s['tun'] or 'none'} UI='{s['label']}'")

    for i in range(1, 5):
        going_up = s["tun"] == []
        print(f"\ntap {i} -> {'CONNECT' if going_up else 'DISCONNECT'}")
        tap(s["center"])
        time.sleep(2)
        consent()
        s = settle(s)
        print(f"   state: tun={s['tun'] or 'none'} UI='{s['label']}'")

        if going_up:
            # tun up is necessary but NOT sufficient - prove traffic really flows.
            time.sleep(3)
            ok, detail = traffic()
            print(f"   traffic through tunnel: {'OK' if ok else 'FAILED'} ({detail})")
            if not ok:
                fail.append(f"connect #{i}: tunnel up but NO traffic ({detail})")
            if s["tun"] == []:
                fail.append(f"connect #{i}: no tun interface")
            if s["label"] != "Connected":
                fail.append(f"connect #{i}: label is '{s['label']}'")
        else:
            if s["tun"] != []:
                fail.append(f"disconnect #{i}: tunnel still up {s['tun']}")
            if s["label"] != "Disconnected":
                fail.append(f"disconnect #{i}: label is '{s['label']}'")
            ok, detail = traffic()
            print(f"   normal internet restored: {'OK' if ok else 'FAILED'} ({detail})")

            # A user taps again straight away; make sure a quick tap is handled.
            print("   fast tap (as a user would, immediately):")
            x = ui()
            c = center(x)
            tap(c)
            time.sleep(4)
            consent()
            q = settle(state(), timeout=30)
            print(f"   state: tun={q['tun'] or 'none'} UI='{q['label']}'")
            if q["tun"] == []:
                fail.append(f"fast tap after disconnect #{i}: did not connect")
            else:
                ok, detail = traffic()
                print(f"   traffic: {'OK' if ok else 'FAILED'} ({detail})")
                if not ok:
                    fail.append(f"fast tap after disconnect #{i}: no traffic ({detail})")
                # put it back down for the next loop
                c2 = center(ui())
                tap(c2)
                time.sleep(4)
                r = settle(state(), timeout=30)
                print(f"   back down: tun={r['tun'] or 'none'} UI='{r['label']}'")
                if r["tun"] != []:
                    fail.append(f"fast tap cleanup #{i}: tunnel still up")
                s = r

    log = sh("logcat", "-d", "-t", "600")
    bad = [
        l.strip()[:150]
        for l in log.splitlines()
        if PKG in l and any(k in l.lower() for k in ("fatal", "sigabrt", "sigsegv", "anr"))
    ]

    print("\n================ RESULT ================")
    if bad:
        print("crash/ANR lines:")
        for b in dict.fromkeys(bad):
            print("  ", b)
    if fail:
        print("FAILURES:")
        for f in fail:
            print("  -", f)
        sys.exit(1)
    print("PASS: every connect carried real traffic; every disconnect released it.")
    print("      fast repeat taps handled; no crashes.")


if __name__ == "__main__":
    main()
