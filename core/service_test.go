package core

import (
	"strings"
	"testing"
)

// recordingRuntime stands in for the engine so the tests can read exactly which profile
// was handed to it, and count restarts.
type recordingRuntime struct {
	profiles []string
	stops    int
}

func (r *recordingRuntime) Start(config []byte) error {
	r.profiles = append(r.profiles, string(config))
	return nil
}

func (r *recordingRuntime) Stop() error {
	r.stops++
	return nil
}

const testProfile = `proxies:
  - name: Tokyo
    type: ss
    server: a.example.com
rules:
  - MATCH,DIRECT
`

func newTestService(rt *recordingRuntime) *Service {
	svc := NewService(rt)
	// Keep the tests off the user's config directory.
	svc.excl = NewExclusions()
	svc.persist = func(*Exclusions) error { return nil }
	return svc
}

func TestConnectSendsExcludedAppsToDirect(t *testing.T) {
	rt := &recordingRuntime{}
	svc := newTestService(rt)
	svc.excl.Add("spotify.exe")
	if err := svc.Import([]byte(testProfile)); err != nil {
		t.Fatal(err)
	}
	if err := svc.Connect(); err != nil {
		t.Fatal(err)
	}
	if len(rt.profiles) != 1 {
		t.Fatalf("expected one start, got %d", len(rt.profiles))
	}
	if !strings.Contains(rt.profiles[0], "- PROCESS-NAME,spotify.exe,DIRECT") {
		t.Errorf("the excluded app should be sent DIRECT:\n%s", rt.profiles[0])
	}
	if got := svc.Snapshot()["excluded"]; got != 1 {
		t.Errorf("snapshot should report 1 excluded app, got %v", got)
	}
}

func TestSetExclusionsRestartsTheLiveTunnel(t *testing.T) {
	rt := &recordingRuntime{}
	svc := newTestService(rt)
	if err := svc.Import([]byte(testProfile)); err != nil {
		t.Fatal(err)
	}
	if err := svc.Connect(); err != nil {
		t.Fatal(err)
	}
	if err := svc.SetExclusions([]string{"discord.exe"}); err != nil {
		t.Fatal(err)
	}
	if len(rt.profiles) != 2 || rt.stops != 1 {
		t.Fatalf("changing the list while connected should restart once: %d starts, %d stops", len(rt.profiles), rt.stops)
	}
	if !strings.Contains(rt.profiles[1], "- PROCESS-NAME,discord.exe,DIRECT") {
		t.Errorf("the restarted tunnel should carry the new rule:\n%s", rt.profiles[1])
	}
	if err := svc.SetExclusions([]string{"discord.exe"}); err != nil {
		t.Fatal(err)
	}
	if len(rt.profiles) != 2 {
		t.Error("re-picking the same apps should not restart the tunnel")
	}
}

func TestSetExclusionsWhileIdleDoesNotTouchTheEngine(t *testing.T) {
	rt := &recordingRuntime{}
	svc := newTestService(rt)
	if err := svc.SetExclusions([]string{"slack.exe"}); err != nil {
		t.Fatal(err)
	}
	if len(rt.profiles) != 0 || rt.stops != 0 {
		t.Errorf("with nothing connected there is nothing to restart: %d starts, %d stops", len(rt.profiles), rt.stops)
	}
	if !svc.Exclusions().Contains("SLACK.EXE") {
		t.Error("the app should still be remembered for the next connect")
	}
}
