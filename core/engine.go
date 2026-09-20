package core

import (
	"bytes"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// Engine tuning: the localhost REST endpoint mihomo exposes (so we can confirm the core is
// genuinely running before reporting "connected"), and how long to wait for it to come up.
const (
	ControllerAddr  = "127.0.0.1:39090"
	startupDeadline = 30 * time.Second
)

// MihomoRuntime drives a bundled mihomo process in TUN mode over Wintun. Start does not
// return until the control endpoint answers, so a nil error means the tunnel is live; a
// failure carries mihomo's own output.
type MihomoRuntime struct {
	binary string
	dir    string

	mu     sync.Mutex
	cmd    *exec.Cmd
	stderr bytes.Buffer
	done   chan struct{}
}

// NewMihomoRuntime resolves the engine next to the app (shipped layout) or in the
// development engines/win directory, and prepares a private working directory.
func NewMihomoRuntime() (*MihomoRuntime, error) {
	dir, err := os.MkdirTemp("", "bypass-vpn-")
	if err != nil {
		return nil, err
	}
	return &MihomoRuntime{binary: findEngine(), dir: dir}, nil
}

func findEngine() string {
	exe, _ := os.Executable()
	exeDir := filepath.Dir(exe)
	for _, p := range []string{
		filepath.Join(exeDir, "mihomo.exe"),
		filepath.Join("engines", "win", "mihomo-windows-amd64.exe"),
	} {
		if _, err := os.Stat(p); err == nil {
			return p
		}
	}
	return ""
}

func (r *MihomoRuntime) Start(config []byte) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.cmd != nil {
		return fmt.Errorf("the tunnel is already running")
	}
	if r.binary == "" {
		return fmt.Errorf("VPN engine is not installed. Put mihomo.exe next to the app")
	}
	if !r.wintunPresent() {
		return fmt.Errorf("Wintun driver missing. Place wintun.dll next to mihomo.exe (https://www.wintun.net)")
	}

	runtimeCfg := PrepareRuntime(string(config), RuntimeTun{ControllerAddr: ControllerAddr})
	if err := os.WriteFile(filepath.Join(r.dir, "config.yaml"), []byte(runtimeCfg), 0600); err != nil {
		return err
	}

	r.stderr.Reset()
	r.done = make(chan struct{})
	cmd := exec.Command(r.binary, "-d", r.dir)
	cmd.Dir = r.dir
	cmd.Stderr = &r.stderr
	cmd.Stdout = io.Discard
	if err := cmd.Start(); err != nil {
		return fmt.Errorf("start engine: %w", err)
	}
	r.cmd = cmd
	go func() {
		_ = cmd.Wait()
		close(r.done)
	}()

	if err := r.waitReady(); err != nil {
		r.stopLocked()
		return err
	}
	return nil
}

func (r *MihomoRuntime) wintunPresent() bool {
	for _, p := range []string{
		filepath.Join(r.dir, "wintun.dll"),
		filepath.Join(filepath.Dir(r.binary), "wintun.dll"),
	} {
		if _, err := os.Stat(p); err == nil {
			return true
		}
	}
	return false
}

func (r *MihomoRuntime) waitReady() error {
	client := &http.Client{Timeout: 2 * time.Second}
	deadline := time.Now().Add(startupDeadline)
	for {
		select {
		case <-r.done:
			return fmt.Errorf("the engine stopped before connecting: %s", r.tail())
		default:
		}
		resp, err := client.Get("http://" + ControllerAddr + "/version")
		if err == nil {
			resp.Body.Close()
			if resp.StatusCode == http.StatusOK {
				return nil
			}
		}
		if time.Now().After(deadline) {
			return fmt.Errorf("the engine did not come up in time: %s", r.tail())
		}
		time.Sleep(500 * time.Millisecond)
	}
}

func (r *MihomoRuntime) Stop() error {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.stopLocked()
}

func (r *MihomoRuntime) stopLocked() error {
	if r.cmd == nil || r.cmd.Process == nil {
		return nil
	}
	err := r.cmd.Process.Kill()
	if r.done != nil {
		select {
		case <-r.done:
		case <-time.After(3 * time.Second):
		}
	}
	r.cmd = nil
	return err
}

func (r *MihomoRuntime) tail() string {
	s := strings.TrimSpace(r.stderr.String())
	if len(s) > 600 {
		s = s[len(s)-600:]
	}
	if s == "" {
		return "no output from the engine"
	}
	return s
}
