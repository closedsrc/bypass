#!/usr/bin/env python3
"""Playtest Bypass the way a person uses it.

For every action it:
  * finds the switch in the accessibility tree and taps its real centre
  * records logcat from just before the tap to just after it settles
  * reports the observed tunnel / VPN-network / UI-label state
  * fails loudly on crashes, ANRs, or state that does not match reality

Run: python E:/vpn/playtest.py [cycles]
"""
import re
import subprocess
import sys
import time

ADB = r"C:\Android\platform-tools\adb.exe"
DEV = "127.0.0.1:16384"
PKG = "com.vpn.simple"


def sh(*args, timeout=60):
    return subprocess.run(
        [ADB, "-s", DEV, *args], capture_output=True, text=True,
        errors="ignore", timeout=timeout,
    ).stdout


def sh_bin(*args, timeout=60):
    return subprocess.run(
        [ADB, "-s", DEV, *args], capture_output=True, timeout=timeout,
    ).stdout


def ui_nodes():
    sh("shell", "uiautomator", "dump", "/sdcard/_pt.xml")
    xml = sh("exec-out", "cat", "/sdcard/_pt.xml")
    return xml


def switch_center(xml):
    """Real centre of the VPN switch, straight from the accessibility tree.

    The description changes with state ("VPN switch" vs "Connected. Tap to
    disconnect."), so match the clickable custom View instead of the label.
    """
    best = None
    for m in re.finditer(r"<node[^>]*>", xml):
        n = m.group(0)
        if 'class="android.view.View"' not in n or 'clickable="true"' not in n:
            continue
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if not b:
            continue
        x0, y0, x1, y1 = map(int, b.groups())
        area = (x1 - x0) * (y1 - y0)
        if best is None or area > best[0]:
            best = (area, (x0 + x1) // 2, (y0 + y1) // 2)
    return (best[1], best[2]) if best else None


def label(xml):
    texts = [t for t in re.findall(r'text="([^"]*)"', xml) if t]
    return next((t for t in texts if t != "BYPASS"), "?")


def tun_ifaces():
    out = sh("shell", "ip", "-o", "addr", "show")
    return sorted(set(re.findall(r"\btun\d+\b", out)))


def vpn_agents():
    return sh("shell", "dumpsys", "connectivity").count("ni{VPN CONNECTED")


def read_state():
    xml = ui_nodes()
    return {
        "tun": tun_ifaces(),
        "agents": vpn_agents(),
        "label": label(xml),
        "center": switch_center(xml),
    }


def app_alive():
    return PKG in sh("shell", "ps", "-A")


def new_problems(tag):
    """Crashes / ANRs / exceptions logged for our package."""
    log = sh("logcat", "-d", "-t", "400")
    bad = []
    for line in log.splitlines():
        low = line.lower()
        if PKG in line and any(
            k in low for k in ("fatal", "exception", "anr", "sigsegv", "sigabrt")
        ):
            bad.append(line.strip()[:160])
        if "AndroidRuntime" in line and "FATAL" in line:
            bad.append(line.strip()[:160])
    if bad:
        print(f"    !! log problems during {tag}:")
        for b in dict.fromkeys(bad):
            print("       ", b)
    return bad


def settle(prev, timeout=25.0):
    """Wait for tun/label to stop changing."""
    deadline = time.time() + timeout
    last = None
    stable = 0
    while time.time() < deadline:
        time.sleep(1.5)
        cur = read_state()
        if cur["tun"] == prev["tun"] and cur["label"] == prev["label"]:
            stable += 1
            if stable >= 2:
                return cur
        else:
            stable = 0
        prev, last = cur, cur
    return last or prev


def show(tag, s):
    tun = ",".join(s["tun"]) if s["tun"] else "none"
    print(f"  {tag:<24} tun={tun:<10} agents={s['agents']}  UI='{s['label']}'")


def accept_consent_if_shown():
    xml = sh("exec-out", "cat", "/sdcard/_pt.xml")
    if "Connection request" in xml:
        m = re.search(r'text="OK"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
        if m:
            x0, y0, x1, y1 = map(int, m.groups())
            print(f"    consent dialog -> tap OK at ({(x0+x1)//2},{(y0+y1)//2})")
            sh("shell", "input", "tap", str((x0 + x1) // 2), str((y0 + y1) // 2))
            return True
    return False


def main():
    cycles = int(sys.argv[1]) if len(sys.argv) > 1 else 3

    print("Launching app fresh (as a user opening it)...")
    sh("shell", "am", "force-stop", PKG)
    time.sleep(1)
    launch = sh("shell", "am", "start", "-W", "-n", f"{PKG}/.MainActivity")
    if "Error" in launch or "does not exist" in launch:
        print("LAUNCH FAILED:", launch.strip())
        sys.exit(1)
    time.sleep(3)
    sh("logcat", "-c")

    state = read_state()
    show("opened", state)
    problems = []

    if state["center"] is None:
        print("FATAL: could not find the VPN switch on screen")
        sys.exit(1)

    for i in range(1, cycles + 1):
        print(f"\n--- user tap {i} ---")
        before = read_state()
        cx, cy = before["center"]

        # A user taps wherever the switch is at that moment.
        sh("shell", "input", "tap", str(cx), str(cy))
        time.sleep(2)
        if accept_consent_if_shown():
            time.sleep(2)

        after = settle(before)
        show(f"after tap {i}", after)

        if not app_alive():
            problems.append(f"tap {i}: app process is gone")
            print("    !! app process died")
            break

        problems += new_problems(f"tap {i}")

        connecting = before["tun"] == []
        ok_tun = (after["tun"] != []) if connecting else (after["tun"] == [])
        want = "Connected" if connecting else "Disconnected"
        ok_label = after["label"] == want
        if not ok_tun or not ok_label:
            problems.append(
                f"tap {i}: wanted {want} + tun {'up' if connecting else 'down'}, "
                f"got UI='{after['label']}' tun={after['tun'] or 'none'}"
            )

    print("\n--- final state ---")
    show("end", read_state())

    print()
    if problems:
        print("PLAYTEST FAILED:")
        for p in dict.fromkeys(problems):
            print("  -", p)
        sys.exit(1)
    print(f"PLAYTEST PASSED: {cycles} taps, UI and real tunnel state agreed each time.")


if __name__ == "__main__":
    main()
