#!/usr/bin/env python3
"""Import a profile into the app on the emulator and connect, the way a user would.

Pushes the file to Download, opens the app, taps the switch, walks the system file
picker and accepts the VPN consent dialog if it shows up.
"""
import re
import subprocess
import sys
import time

ADB = r"C:\Android\platform-tools\adb.exe"
DEV = "127.0.0.1:16384"
PKG = "com.vpn.simple"


def sh(*a, t=120):
    return subprocess.run([ADB, "-s", DEV, *a], capture_output=True, text=True,
                          errors="ignore", timeout=t).stdout


def ui():
    sh("shell", "uiautomator", "dump", "/sdcard/_pick.xml")
    return sh("exec-out", "cat", "/sdcard/_pick.xml")


def nodes(xml):
    for m in re.finditer(r"<node[^>]*>", xml):
        n = m.group(0)
        text = re.search(r'text="([^"]*)"', n)
        bound = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if not text or not bound or not text.group(1):
            continue
        x0, y0, x1, y1 = map(int, bound.groups())
        yield text.group(1), ((x0 + x1) // 2, (y0 + y1) // 2)


def tap(label, xml=None, contains=True):
    for text, centre in nodes(xml or ui()):
        hit = label in text if contains else text == label
        if hit:
            sh("shell", "input", "tap", str(centre[0]), str(centre[1]))
            return centre
    return None


def switch_centre(xml):
    best = None
    for m in re.finditer(r"<node[^>]*>", xml):
        n = m.group(0)
        if 'class="android.view.View"' not in n or 'clickable="true"' not in n:
            continue
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if b:
            x0, y0, x1, y1 = map(int, b.groups())
            area = (x1 - x0) * (y1 - y0)
            if best is None or area > best[0]:
                best = (area, (x0 + x1) // 2, (y0 + y1) // 2)
    return (best[1], best[2]) if best else None


def status():
    texts = [t for t, _ in nodes(ui())]
    return next((t for t in texts if t in ("Connected", "Disconnected", "Connecting...", "Disconnecting...")), "?")


def main():
    source = sys.argv[1]
    sh("shell", "pm", "clear", PKG)
    sh("push", source, "/sdcard/Download/bypass.yaml")
    sh("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(4)

    centre = switch_centre(ui())
    if not centre:
        print("FAIL: no switch found"); sys.exit(1)
    sh("shell", "input", "tap", str(centre[0]), str(centre[1]))
    time.sleep(5)

    xml = ui()
    if not tap("bypass.yaml", xml):
        print("FAIL: file not in picker:", [t for t, _ in nodes(xml)][:10]); sys.exit(1)
    time.sleep(6)

    xml = ui()
    for label in ("OK", "OKAY", "Allow", "ALLOW"):
        if tap(label, xml, contains=False):
            print("accepted the VPN consent dialog")
            break
    time.sleep(8)

    state = status()
    tun = sorted(set(re.findall(r"\btun\d+\b", sh("shell", "ip", "-o", "addr", "show"))))
    agents = sh("shell", "dumpsys", "connectivity").count("ni{VPN CONNECTED")
    print(f"imported: state={state} tun={tun or 'none'} agents={agents}")
    sys.exit(0 if state == "Connected" else 1)


if __name__ == "__main__":
    main()
