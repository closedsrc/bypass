package core

import (
	"fmt"
	"path/filepath"
	"strings"
	"testing"
)

func TestNormalizeAppFoldsSpellings(t *testing.T) {
	cases := map[string]string{
		"  Spotify  ": "spotify",
		"Spotify.exe": "spotify",
		"SPOTIFY.EXE": "spotify",
		`"chrome"`:    "chrome",
		"com.vpn.app": "com.vpn.app",
		"   ":         "",
	}
	for in, want := range cases {
		if got := NormalizeApp(in); got != want {
			t.Errorf("NormalizeApp(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestExclusionsSetBehaviour(t *testing.T) {
	e := NewExclusions("Spotify.exe", "spotify")
	if got := e.List(); len(got) != 1 {
		t.Fatalf("the .exe spelling should not double up: %v", got)
	}
	if !e.Contains("SPOTIFY") {
		t.Error("spotify should be excluded")
	}
	if e.Add("spotify") {
		t.Error("adding an app twice is not a change")
	}
	if !e.Add("Discord") {
		t.Error("adding a new app is a change")
	}
	if !e.Remove("spotify.exe") {
		t.Error("removing by the .exe spelling should work")
	}
	if e.Contains("spotify") {
		t.Error("spotify should be gone")
	}
	if e.Set([]string{"Discord"}) {
		t.Error("setting the same single app is not a change")
	}
	if !e.Set([]string{"Discord", "Slack"}) {
		t.Error("setting a wider list is a change")
	}
	if got := e.List(); len(got) != 2 || got[0] != "Discord" || got[1] != "Slack" {
		t.Errorf("list should be sorted and complete: %v", got)
	}
	if len(NewExclusions(" ", "").List()) != 0 {
		t.Error("blank names should not be kept")
	}
}

func TestExclusionRulesWindowsSuffix(t *testing.T) {
	got := ExclusionRules([]string{"spotify"}, "windows")
	want := []string{"spotify", "spotify.exe"}
	if len(got) != 2 || got[0] != want[0] || got[1] != want[1] {
		t.Fatalf("windows should get both spellings: %v", got)
	}
	got = ExclusionRules([]string{"spotify.exe"}, "windows")
	if len(got) != 1 || got[0] != "spotify.exe" {
		t.Fatalf("an explicit .exe should not be duplicated: %v", got)
	}
	got = ExclusionRules([]string{"spotify"}, "linux")
	if len(got) != 1 || got[0] != "spotify" {
		t.Fatalf("only windows gets an .exe spelling added: %v", got)
	}
}

const profileWithRules = `proxies:
  - name: node-1
    type: socks5
rules:
  - GEOIP,CN,DIRECT
  - MATCH,SimpleVPN
`

func TestApplyExclusionsForPutsAppsFirst(t *testing.T) {
	got := ApplyExclusionsFor(profileWithRules, []string{"Spotify"}, "windows")
	lines := strings.Split(got, "\n")

	first := indexOf(lines, "- PROCESS-NAME,Spotify,DIRECT")
	second := indexOf(lines, "- PROCESS-NAME,Spotify.exe,DIRECT")
	match := indexOf(lines, "- MATCH,SimpleVPN")
	if first < 0 || second < 0 {
		t.Fatalf("both direct rules should be present:\n%s", got)
	}
	if first > match || second > match {
		t.Errorf("excluded apps must be matched before the catch-all:\n%s", got)
	}
	if indexOf(lines, "- GEOIP,CN,DIRECT") < second {
		t.Errorf("excluded apps must come before the profile's own rules:\n%s", got)
	}
}

func TestApplyExclusionsForIsIdempotent(t *testing.T) {
	once := ApplyExclusionsFor(profileWithRules, []string{"spotify"}, "windows")
	twice := ApplyExclusionsFor(once, []string{"spotify.exe"}, "windows")
	if count := strings.Count(twice, "PROCESS-NAME"); count != 2 {
		t.Errorf("applying twice should not stack rules, got %d:\n%s", count, twice)
	}
}

func TestApplyExclusionsForWithoutRulesBlock(t *testing.T) {
	got := ApplyExclusionsFor("proxies:\n  - name: n1\n    type: socks5\n", []string{"slack"}, "linux")
	if !strings.Contains(got, "rules:") || !strings.Contains(got, "- PROCESS-NAME,slack,DIRECT") {
		t.Errorf("a missing rules block should be created:\n%s", got)
	}
}

func TestApplyExclusionsForNoApps(t *testing.T) {
	if got := ApplyExclusionsFor(profileWithRules, nil, "windows"); got != profileWithRules {
		t.Errorf("with nothing excluded the profile must be untouched:\n%s", got)
	}
	if got := ApplyExclusionsFor(profileWithRules, []string{"  "}, "windows"); got != profileWithRules {
		t.Error("blank names must be ignored")
	}
}

func TestApplyExclusionsForKeepsRuleIndent(t *testing.T) {
	profile := "rules:\n    - MATCH,SimpleVPN\n"
	got := ApplyExclusionsFor(profile, []string{"zoom"}, "linux")
	if !strings.Contains(got, "    - PROCESS-NAME,zoom,DIRECT") {
		t.Errorf("injected rules should follow the profile's indentation:\n%s", got)
	}
}

func TestKnownAppsMergeSortAndBound(t *testing.T) {
	path := filepath.Join(t.TempDir(), "known-apps.json")
	if _, err := loadKnownAppsFrom(path); err == nil {
		t.Fatal("a missing file should be an error, not an empty list")
	}
	if err := saveKnownAppsTo(path, []string{"b.exe", "a.exe"}); err != nil {
		t.Fatal(err)
	}
	got, err := loadKnownAppsFrom(path)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 2 || got[0] != "a.exe" || got[1] != "b.exe" {
		t.Fatalf("remembered apps should be sorted: %v", got)
	}
	if err := saveKnownAppsTo(path, []string{"c.exe"}); err != nil {
		t.Fatal(err)
	}
	if got, _ = loadKnownAppsFrom(path); len(got) != 3 {
		t.Fatalf("saving again should merge, not replace: %v", got)
	}
	many := make([]string, maxKnownApps+25)
	for i := range many {
		many[i] = fmt.Sprintf("app%04d.exe", i)
	}
	if err := saveKnownAppsTo(path, many); err != nil {
		t.Fatal(err)
	}
	if got, _ = loadKnownAppsFrom(path); len(got) != maxKnownApps {
		t.Fatalf("the remembered list should stay bounded at %d, got %d", maxKnownApps, len(got))
	}
}

func indexOf(lines []string, want string) int {
	for i, l := range lines {
		if strings.TrimSpace(l) == want {
			return i
		}
	}
	return -1
}
