package core

import (
	"encoding/json"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"sync"
)

// Exclusions are the apps the user has asked Bypass to leave alone. While the tunnel is
// up, everything else goes through the profile but an excluded app keeps using the
// network exactly as it would with the switch off. On Android that is done by keeping
// the app out of the VPN interface; on Windows it becomes a PROCESS-NAME rule at the
// head of the profile's rules.
type Exclusions struct {
	mu    sync.RWMutex
	items map[string]string // normalised name -> name as the user typed it
}

// NewExclusions builds a set from the given app names. Blanks are dropped.
func NewExclusions(names ...string) *Exclusions {
	e := &Exclusions{items: map[string]string{}}
	e.Add(names...)
	return e
}

// NormalizeApp folds the spellings that mean the same app together: surrounding
// whitespace and quotes go, case is folded, and a Windows ".exe" suffix is not part of
// the app's identity, so "Spotify" and "Spotify.exe" cannot both be listed.
func NormalizeApp(name string) string {
	n := strings.TrimSpace(name)
	n = strings.Trim(n, "\"'")
	n = strings.ToLower(n)
	n = strings.TrimSuffix(n, ".exe")
	return strings.TrimSpace(n)
}

// Add puts names in the set. It reports whether anything actually changed, so a caller
// can skip a reconnect when the user re-picks the same apps.
func (e *Exclusions) Add(names ...string) bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	changed := false
	for _, name := range names {
		key := NormalizeApp(name)
		if key == "" {
			continue
		}
		if _, ok := e.items[key]; ok {
			continue
		}
		e.items[key] = strings.TrimSpace(name)
		changed = true
	}
	return changed
}

// Remove takes a name out of the set and reports whether it was there.
func (e *Exclusions) Remove(name string) bool {
	key := NormalizeApp(name)
	if key == "" {
		return false
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	if _, ok := e.items[key]; !ok {
		return false
	}
	delete(e.items, key)
	return true
}

// Contains reports whether an app is excluded.
func (e *Exclusions) Contains(name string) bool {
	key := NormalizeApp(name)
	if key == "" {
		return false
	}
	e.mu.RLock()
	defer e.mu.RUnlock()
	_, ok := e.items[key]
	return ok
}

// List returns the excluded apps, sorted for display.
func (e *Exclusions) List() []string {
	e.mu.RLock()
	defer e.mu.RUnlock()
	out := make([]string, 0, len(e.items))
	for _, display := range e.items {
		out = append(out, display)
	}
	sort.Slice(out, func(i, j int) bool {
		return strings.ToLower(out[i]) < strings.ToLower(out[j])
	})
	return out
}

// Set replaces the whole set with names and reports whether the result differs from what
// was there before.
func (e *Exclusions) Set(names []string) bool {
	next := map[string]string{}
	for _, name := range names {
		key := NormalizeApp(name)
		if key == "" {
			continue
		}
		if _, dup := next[key]; dup {
			continue
		}
		next[key] = strings.TrimSpace(name)
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	if sameKeys(e.items, next) {
		return false
	}
	e.items = next
	return true
}

func sameKeys(a, b map[string]string) bool {
	if len(a) != len(b) {
		return false
	}
	for k := range a {
		if _, ok := b[k]; !ok {
			return false
		}
	}
	return true
}

// --- persistence ------------------------------------------------------------

// ExclusionsPath is where the desktop clients keep the list between runs.
func ExclusionsPath() (string, error) {
	dir, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, "Bypass", "exclusions.json"), nil
}

// LoadExclusions reads the saved list. A missing file is an error; callers treat a
// failure as "nothing excluded yet".
func LoadExclusions() (*Exclusions, error) {
	path, err := ExclusionsPath()
	if err != nil {
		return nil, err
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var names []string
	if err := json.Unmarshal(data, &names); err != nil {
		return nil, err
	}
	return NewExclusions(names...), nil
}

// Save writes the list back to the user's config directory.
func (e *Exclusions) Save() error {
	path, err := ExclusionsPath()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	data, err := json.MarshalIndent(e.List(), "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, data, 0o600)
}

// --- known apps -------------------------------------------------------------

// maxKnownApps bounds how many process names are remembered, so the file cannot grow
// without limit on a long-lived machine.
const maxKnownApps = 500

// KnownAppsPath is where the desktop client remembers the apps it has seen. Windows has
// no way to enumerate process names that are not currently running, so remembering them
// is what keeps the picker useful for an app the user has not launched today.
func KnownAppsPath() (string, error) {
	dir, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, "Bypass", "known-apps.json"), nil
}

func LoadKnownApps() ([]string, error) {
	path, err := KnownAppsPath()
	if err != nil {
		return nil, err
	}
	return loadKnownAppsFrom(path)
}

// SaveKnownApps merges names into the remembered set and writes it back.
func SaveKnownApps(names []string) error {
	path, err := KnownAppsPath()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	return saveKnownAppsTo(path, names)
}

func loadKnownAppsFrom(path string) ([]string, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var names []string
	if err := json.Unmarshal(data, &names); err != nil {
		return nil, err
	}
	return names, nil
}

func saveKnownAppsTo(path string, names []string) error {
	byKey := map[string]string{}
	for _, n := range append(loadOrEmpty(path), names...) {
		n = strings.TrimSpace(n)
		if n == "" {
			continue
		}
		byKey[strings.ToLower(n)] = n
	}
	out := make([]string, 0, len(byKey))
	for _, n := range byKey {
		out = append(out, n)
	}
	sort.Slice(out, func(i, j int) bool { return strings.ToLower(out[i]) < strings.ToLower(out[j]) })
	if len(out) > maxKnownApps {
		out = out[:maxKnownApps]
	}
	data, err := json.MarshalIndent(out, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, data, 0o600)
}

func loadOrEmpty(path string) []string {
	names, err := loadKnownAppsFrom(path)
	if err != nil {
		return nil
	}
	return names
}

// --- profile rewrite --------------------------------------------------------

// ExclusionRules returns the process names to match for a list of apps. On Windows
// mihomo sees processes with their ".exe" suffix, so a bare name is given both spellings
// rather than silently never matching.
func ExclusionRules(names []string, goos string) []string {
	out := []string{}
	seen := map[string]struct{}{}
	add := func(name string) {
		key := strings.ToLower(name)
		if _, dup := seen[key]; dup {
			return
		}
		seen[key] = struct{}{}
		out = append(out, name)
	}
	for _, raw := range names {
		name := strings.Trim(strings.TrimSpace(raw), "\"'")
		if name == "" {
			continue
		}
		add(name)
		if goos == "windows" && !strings.HasSuffix(strings.ToLower(name), ".exe") {
			add(name + ".exe")
		}
	}
	return out
}

// ApplyExclusions puts a DIRECT rule for every excluded app ahead of every other rule in
// the profile, so those apps are matched before anything can send them into the tunnel.
// It uses ApplyExclusionsFor with the platform this binary was built for.
func ApplyExclusions(profile string, names []string) string {
	return ApplyExclusionsFor(profile, names, runtime.GOOS)
}

// ApplyExclusionsFor is ApplyExclusions with the target platform made explicit, which is
// what the tests drive.
func ApplyExclusionsFor(profile string, names []string, goos string) string {
	rules := ExclusionRules(names, goos)
	if len(rules) == 0 {
		return profile
	}
	lines := strings.Split(profile, "\n")
	block, ok := blockRange(lines, "rules")
	if !ok {
		out := append([]string{}, lines...)
		out = append(out, "rules:")
		for _, r := range rules {
			out = append(out, "  - "+fmtExclusionRule(r))
		}
		return strings.Join(out, "\n")
	}
	pad := "  "
	if entries, _ := entriesIn(lines, "rules"); len(entries) > 0 {
		if m := listEntryRe.FindStringSubmatch(lines[entries[0].first]); m != nil && m[1] != "" {
			pad = m[1]
		}
	}
	existing := map[string]struct{}{}
	for i := block.first + 1; i <= block.last; i++ {
		existing[strings.ToLower(strings.TrimSpace(lines[i]))] = struct{}{}
	}
	added := make([]string, 0, len(rules))
	for _, r := range rules {
		line := pad + "- " + fmtExclusionRule(r)
		key := strings.ToLower(strings.TrimSpace(line))
		if _, dup := existing[key]; dup {
			continue
		}
		existing[key] = struct{}{}
		added = append(added, line)
	}
	if len(added) == 0 {
		return profile
	}
	insertAt(&lines, block.first+1, added)
	return strings.Join(lines, "\n")
}

func fmtExclusionRule(name string) string {
	return "PROCESS-NAME," + name + ",DIRECT"
}
