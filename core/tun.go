package core

import "strings"

// RuntimeTun configures the mihomo core for real system-wide routing on Windows. The
// filtered profile keeps the user's own routing; this adds the pieces the tunnel needs:
// a Wintun adapter with automatic routing and DNS hijack, plus the localhost control
// endpoint the shell polls to know the core is genuinely up.
type RuntimeTun struct {
	ControllerAddr string // e.g. 127.0.0.1:39090
	Device         string // Wintun
}

// managedTopKeys are the top-level keys this layer owns and rewrites.
var managedTopKeys = []string{"tun", "external-controller", "external-controller-tls", "external-ui", "find-process-mode"}

// PrepareRuntime returns the profile to hand mihomo: the user's filtered profile with
// the tun and control keys normalised so the tunnel actually forms and can be observed.
func PrepareRuntime(filtered string, cfg RuntimeTun) string {
	dev := cfg.Device
	if dev == "" {
		dev = "Wintun"
	}
	lines := strings.Split(filtered, "\n")
	for _, key := range managedTopKeys {
		lines = removeTopBlock(lines, key)
	}
	if cfg.ControllerAddr != "" {
		lines = append(lines, "external-controller: "+cfg.ControllerAddr)
	}
	// Process lookup is what makes per-app exclusions possible: without it mihomo cannot
	// name the app behind a connection and PROCESS-NAME rules never match.
	lines = append(lines, "find-process-mode: strict")
	lines = append(lines,
		"tun:",
		"  enable: true",
		"  stack: system",
		"  device: "+dev,
		"  auto-route: true",
		"  auto-detect-interface: true",
		"  dns-hijack:",
		"    - any:53",
	)
	return strings.Join(lines, "\n")
}

// removeTopBlock drops a whole top-level block (its key line and every more-indented
// line beneath it) if the key is present at column zero.
func removeTopBlock(lines []string, key string) []string {
	block, ok := blockRange(lines, key)
	if !ok {
		return lines
	}
	out := append([]string{}, lines[:block.first]...)
	out = append(out, lines[block.last+1:]...)
	return out
}
