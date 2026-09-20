package core

import (
	"strings"
	"testing"
)

func TestPrepareRuntimeInjectsTunAndController(t *testing.T) {
	in := "proxies:\n  - name: Tokyo\n    type: ss\n    server: b.example.com\n"
	out := PrepareRuntime(in, RuntimeTun{ControllerAddr: "127.0.0.1:39090"})
	for _, want := range []string{
		"external-controller: 127.0.0.1:39090",
		"tun:",
		"  enable: true",
		"  stack: system",
		"  device: Wintun",
		"  auto-route: true",
		"  - any:53",
	} {
		if !strings.Contains(out, want) {
			t.Fatalf("missing %q in:\n%s", want, out)
		}
	}
	if !strings.Contains(out, "name: Tokyo") {
		t.Fatal("user proxy lost")
	}
}

func TestPrepareRuntimeReplacesExistingTun(t *testing.T) {
	in := "tun:\n  enable: false\n  stack: gvisor\nproxies:\n  - name: Tokyo\n    type: ss\n    server: b.example.com\n"
	out := PrepareRuntime(in, RuntimeTun{ControllerAddr: "127.0.0.1:39090"})
	if strings.Contains(out, "stack: gvisor") {
		t.Fatal("old tun block survived")
	}
	if n := strings.Count(out, "tun:"); n != 1 {
		t.Fatalf("tun: appears %d times", n)
	}
	if !strings.Contains(out, "name: Tokyo") {
		t.Fatal("proxy after tun block lost")
	}
}

func TestPrepareRuntimeNoDuplicateController(t *testing.T) {
	in := "external-controller: ':9090'\nproxies:\n  - name: Tokyo\n    type: ss\n    server: b.example.com\n"
	out := PrepareRuntime(in, RuntimeTun{ControllerAddr: "127.0.0.1:39090"})
	if strings.Contains(out, "'9090'") || strings.Contains(out, ":9090") {
		t.Fatal("old controller survived")
	}
	if n := strings.Count(out, "external-controller:"); n != 1 {
		t.Fatalf("external-controller appears %d times", n)
	}
}
