package core

import (
	"errors"
	"fmt"
	"regexp"
	"sort"
	"strings"
)

// groupName is the load-balancing group injected into every filtered profile.
const groupName = "SimpleVPN"

var (
	usaRe      = regexp.MustCompile(`(?i)(^|[^a-z])(us|usa|u\.s\.|united states|america)([^a-z]|$)`)
	nameLineRe = regexp.MustCompile(`^\s*-?\s*name:\s*"?([^"\n]+?)"?\s*$`)
	listEntryRe = regexp.MustCompile(`^(\s*)-\s`)
	topKeyRe   = regexp.MustCompile(`^([A-Za-z0-9_-]+):`)
	ruleBodyRe = regexp.MustCompile(`^\s*-\s*(.+?)\s*$`)
	inlineProxiesRe   = regexp.MustCompile(`^\s*proxies:\s*\[(.*)\]\s*$`)
	blockProxiesRe    = regexp.MustCompile(`^\s*proxies:\s*$`)
	writeInlineProxiesRe = regexp.MustCompile(`^\s*proxies:\s*\[.*\]\s*$`)
)

// FilterResult is the rewritten profile plus the node names that survived and were dropped.
type FilterResult struct {
	YAML    string
	Kept    []string
	Removed []string
}

// IsUsaNode reports whether a node name denotes a USA exit.
func IsUsaNode(name string) bool { return usaRe.MatchString(name) }

type entry struct{ first, last int }

// ApplyFilter rewrites a Clash profile so no traffic can leave through a USA node,
// mirroring android/.../ProfileFilter.kt. It works on lines rather than a YAML model so
// comments, key order and formatting come through untouched.
func ApplyFilter(profile string) (FilterResult, error) {
	lines := strings.Split(profile, "\n")

	// 1. which top-level proxies may stay
	proxyEntries, ok := entriesIn(lines, "proxies")
	if !ok {
		return FilterResult{}, errors.New("This profile has no proxies section")
	}
	kept := []string{}
	removedSet := map[string]struct{}{}
	removed := []string{}
	for _, e := range proxyEntries {
		name, found := nameOf(lines, e)
		if !found {
			continue
		}
		if IsUsaNode(name) {
			if _, dup := removedSet[name]; !dup {
				removedSet[name] = struct{}{}
				removed = append(removed, name)
			}
		} else {
			kept = append(kept, name)
		}
	}
	if len(kept) == 0 {
		return FilterResult{}, errors.New("Every proxy in this profile is a USA node")
	}

	// 2. drop the USA entries
	var usEntries []entry
	for _, e := range proxyEntries {
		if name, found := nameOf(lines, e); found && IsUsaNode(name) {
			usEntries = append(usEntries, e)
		}
	}
	deleteEntries(&lines, usEntries)

	// 3. and every reference to them from a group
	rewriteGroups(&lines, func(refs []string) []string {
		return filterNotIn(refs, removedSet)
	})

	// 4. a group left with nothing cannot be used, so it goes, along with what referred to it
	droppedSet := map[string]struct{}{}
	dropped := []string{}
	if groups, ok := entriesIn(lines, "proxy-groups"); ok {
		for _, e := range groups {
			name, found := nameOf(lines, e)
			if !found {
				continue
			}
			if len(listRefs(lines, e)) == 0 && !hasDynamicMembers(lines, e) {
				if _, dup := droppedSet[name]; !dup {
					droppedSet[name] = struct{}{}
					dropped = append(dropped, name)
				}
			}
		}
	}
	if len(dropped) > 0 {
		var dropEntries []entry
		if groups, ok := entriesIn(lines, "proxy-groups"); ok {
			for _, e := range groups {
				if name, found := nameOf(lines, e); found {
					if _, gone := droppedSet[name]; gone {
						dropEntries = append(dropEntries, e)
					}
				}
			}
		}
		deleteEntries(&lines, dropEntries)
		rewriteGroups(&lines, func(refs []string) []string {
			return filterNotIn(refs, droppedSet)
		})
	}

	// 5. our own group, built from what survived
	addGroup(&lines, kept)

	// 6. no rule may still name something that is gone, and the catch-all ends up on our group
	gone := map[string]struct{}{}
	for k := range removedSet {
		gone[k] = struct{}{}
	}
	for k := range droppedSet {
		gone[k] = struct{}{}
	}
	retargetRules(&lines, gone)
	ensureCatchAll(&lines)

	// 7. with no resolver, nothing inside the tunnel can be reached
	if !hasTopKey(lines, "dns") {
		lines = append(lines,
			"dns:",
			"  enable: true",
			"  enhanced-mode: fake-ip",
			"  fake-ip-range: 198.18.0.1/16",
			"  nameserver:",
			"    - tcp://1.1.1.1",
			"    - tcp://8.8.8.8",
		)
	}

	return FilterResult{YAML: strings.Join(lines, "\n"), Kept: kept, Removed: removed}, nil
}

// --- list surgery -----------------------------------------------------------

// entriesIn returns the index ranges of the direct `- ` entries of a top-level block.
// The bool reports whether the block exists at all.
func entriesIn(lines []string, key string) ([]entry, bool) {
	block, ok := blockRange(lines, key)
	if !ok {
		return nil, false
	}
	starts := []int{}
	indents := map[int]int{}
	for i := block.first + 1; i <= block.last; i++ {
		m := listEntryRe.FindStringSubmatch(lines[i])
		if m == nil {
			continue
		}
		starts = append(starts, i)
		indents[i] = len(m[1])
	}
	if len(starts) == 0 {
		return []entry{}, true
	}
	minIndent := 1 << 30
	for _, v := range indents {
		if v < minIndent {
			minIndent = v
		}
	}
	top := []int{}
	for _, s := range starts {
		if indents[s] == minIndent {
			top = append(top, s)
		}
	}
	out := make([]entry, len(top))
	for i, start := range top {
		last := block.last
		if i+1 < len(top) {
			last = top[i+1] - 1
		}
		out[i] = entry{start, last}
	}
	return out, true
}

func blockRange(lines []string, key string) (entry, bool) {
	type kv struct {
		line int
		key  string
	}
	var keys []kv
	for i, line := range lines {
		if len(line) > 0 && !isSpace(line[0]) && !strings.HasPrefix(line, "-") {
			if m := topKeyRe.FindStringSubmatch(line); m != nil {
				keys = append(keys, kv{i, m[1]})
			}
		}
	}
	at := -1
	for i, k := range keys {
		if k.key == key {
			at = i
			break
		}
	}
	if at < 0 {
		return entry{}, false
	}
	end := len(lines) - 1
	if at+1 < len(keys) {
		end = keys[at+1].line - 1
	}
	return entry{keys[at].line, end}, true
}

func nameOf(lines []string, e entry) (string, bool) {
	for i := e.first; i <= e.last; i++ {
		if m := nameLineRe.FindStringSubmatch(lines[i]); m != nil {
			return strings.TrimSpace(m[1]), true
		}
	}
	return "", false
}

// hasDynamicMembers is true for groups fed by providers rather than an inline list.
func hasDynamicMembers(lines []string, e entry) bool {
	for i := e.first; i <= e.last; i++ {
		t := strings.TrimLeft(lines[i], " \t")
		if strings.HasPrefix(t, "use:") || strings.HasPrefix(t, "include-all") || strings.HasPrefix(t, "include-all-proxies") {
			return true
		}
	}
	return false
}

// listRefs returns the proxy names a group lists, inline or as a block list.
func listRefs(lines []string, e entry) []string {
	for i := e.first; i <= e.last; i++ {
		line := lines[i]
		if m := inlineProxiesRe.FindStringSubmatch(line); m != nil {
			refs := []string{}
			for _, ref := range strings.Split(m[1], ",") {
				ref = strings.TrimSpace(ref)
				ref = strings.Trim(ref, "\"'")
				if ref != "" {
					refs = append(refs, ref)
				}
			}
			return refs
		}
		if !blockProxiesRe.MatchString(line) {
			continue
		}
		base := firstNonSpace(line)
		refs := []string{}
		j := i + 1
		for j <= e.last {
			item := lines[j]
			if strings.TrimSpace(item) == "" {
				j++
				continue
			}
			if firstNonSpace(item) <= base {
				break
			}
			marker := listEntryRe.FindStringIndex(item)
			if marker == nil {
				break
			}
			ref := strings.TrimSpace(item[marker[1]:])
			ref = strings.Trim(ref, "\"'")
			refs = append(refs, ref)
			j++
		}
		return refs
	}
	return []string{}
}

func writeListRefs(lines *[]string, e entry, refs []string) {
	for i := e.first; i <= e.last; i++ {
		line := (*lines)[i]
		if writeInlineProxiesRe.MatchString(line) {
			quoted := make([]string, len(refs))
			for k, r := range refs {
				quoted[k] = fmt.Sprintf("%q", r)
			}
			(*lines)[i] = line[:strings.Index(line, "proxies:")] + "proxies: ["+strings.Join(quoted, ", ")+"]"
			return
		}
		if !blockProxiesRe.MatchString(line) {
			continue
		}
		keyIndent := firstNonSpace(line)
		stale := []int{}
		j := i + 1
		for j <= e.last {
			item := (*lines)[j]
			if strings.TrimSpace(item) == "" {
				j++
				continue
			}
			if firstNonSpace(item) <= keyIndent {
				break
			}
			if listEntryRe.MatchString(item) {
				stale = append(stale, j)
			}
			j++
		}
		for k := len(stale) - 1; k >= 0; k-- {
			removeAt(lines, stale[k])
		}
		added := make([]string, len(refs))
		for k, r := range refs {
			added[k] = strings.Repeat(" ", keyIndent+2) + fmt.Sprintf("- %q", r)
		}
		insertAt(lines, i+1, added)
		return
	}
}

func deleteEntries(lines *[]string, entries []entry) {
	sorted := make([]entry, len(entries))
	copy(sorted, entries)
	sort.SliceStable(sorted, func(i, j int) bool { return sorted[i].first > sorted[j].first })
	for _, e := range sorted {
		for i := e.last; i >= e.first; i-- {
			removeAt(lines, i)
		}
	}
}

// rewriteGroups applies fix to the first group that needs it, then restarts so index
// ranges are recomputed after each edit.
func rewriteGroups(lines *[]string, fix func([]string) []string) {
	for guard := 0; guard < 1000; guard++ {
		groups, _ := entriesIn(*lines, "proxy-groups")
		target := entry{}
		found := false
		for _, candidate := range groups {
			before := listRefs(*lines, candidate)
			if len(fix(before)) != len(before) {
				target = candidate
				found = true
				break
			}
		}
		if !found {
			return
		}
		writeListRefs(lines, target, fix(listRefs(*lines, target)))
	}
}

func addGroup(lines *[]string, nodes []string) {
	block, ok := blockRange(*lines, "proxy-groups")
	if !ok {
		*lines = append(*lines, "proxy-groups:")
		*lines = append(*lines, groupEntry("  ", nodes)...)
		return
	}
	entryPad := "  "
	if entries, _ := entriesIn(*lines, "proxy-groups"); len(entries) > 0 {
		if m := listEntryRe.FindStringSubmatch((*lines)[entries[0].first]); m != nil && m[1] != "" {
			entryPad = m[1]
		}
	}
	at := block.last + 1
	for at-1 > block.first && strings.TrimSpace((*lines)[at-1]) == "" {
		at--
	}
	insertAt(lines, at, groupEntry(entryPad, nodes))
}

func groupEntry(pad string, nodes []string) []string {
	inner := pad + "  "
	out := []string{
		pad + "- name: " + groupName,
		inner + "type: load-balance",
		inner + "strategy: round-robin",
		inner + "url: http://www.gstatic.com/generate_204",
		inner + "interval: 300",
		inner + "proxies:",
	}
	for _, node := range nodes {
		out = append(out, inner+"  - "+fmt.Sprintf("%q", node))
	}
	return out
}

// retargetRules sends any rule whose target is gone to our group. Rule shape is
// TYPE,PAYLOAD,TARGET except the catch-all, which is MATCH,TARGET.
func retargetRules(lines *[]string, gone map[string]struct{}) {
	block, ok := blockRange(*lines, "rules")
	if !ok {
		return
	}
	for i := block.first + 1; i <= block.last; i++ {
		m := ruleBodyRe.FindStringSubmatch((*lines)[i])
		if m == nil {
			continue
		}
		body := m[1]
		parts := splitRuleParts(body)
		targetAt := -1
		if len(parts) >= 2 && strings.EqualFold(parts[0], "MATCH") {
			targetAt = 1
		} else if len(parts) >= 3 {
			targetAt = 2
		}
		if targetAt < 0 {
			continue
		}
		if _, isGone := gone[parts[targetAt]]; !isGone {
			continue
		}
		parts[targetAt] = groupName
		(*lines)[i] = replaceBody(*lines, i, body, parts)
	}
}

func ensureCatchAll(lines *[]string) {
	block, ok := blockRange(*lines, "rules")
	if !ok {
		*lines = append(*lines, "rules:")
		*lines = append(*lines, "  - MATCH,"+groupName)
		return
	}
	for i := block.first + 1; i <= block.last; i++ {
		m := ruleBodyRe.FindStringSubmatch((*lines)[i])
		if m == nil {
			continue
		}
		body := m[1]
		parts := splitRuleParts(body)
		if len(parts) >= 2 && strings.EqualFold(parts[0], "MATCH") {
			parts[1] = groupName
			(*lines)[i] = replaceBody(*lines, i, body, parts)
			return
		}
	}
	pad := "  "
	if entries, _ := entriesIn(*lines, "rules"); len(entries) > 0 {
		if mm := listEntryRe.FindStringSubmatch((*lines)[entries[0].first]); mm != nil && mm[1] != "" {
			pad = mm[1]
		}
	}
	at := block.last + 1
	for at-1 > block.first && strings.TrimSpace((*lines)[at-1]) == "" {
		at--
	}
	insertAt(lines, at, []string{pad + "- MATCH," + groupName})
}

func splitRuleParts(body string) []string {
	raw := strings.Split(body, ",")
	parts := make([]string, len(raw))
	for i, p := range raw {
		p = strings.TrimSpace(p)
		parts[i] = strings.Trim(p, "\"'")
	}
	return parts
}

// replaceBody rebuilds a rule line, keeping the original prefix before the rule body.
func replaceBody(lines []string, i int, body string, parts []string) string {
	line := lines[i]
	idx := strings.Index(line, body)
	if idx < 0 {
		return strings.Join(parts, ",")
	}
	return line[:idx] + strings.Join(parts, ",")
}

// --- small helpers ----------------------------------------------------------

func isSpace(b byte) bool { return b == ' ' || b == '\t' }

func firstNonSpace(s string) int {
	for i := 0; i < len(s); i++ {
		if !isSpace(s[i]) {
			return i
		}
	}
	return len(s)
}

func filterNotIn(refs []string, set map[string]struct{}) []string {
	out := make([]string, 0, len(refs))
	for _, r := range refs {
		if _, gone := set[r]; !gone {
			out = append(out, r)
		}
	}
	return out
}

func hasTopKey(lines []string, key string) bool {
	prefix := key + ":"
	for _, line := range lines {
		if strings.HasPrefix(line, prefix) {
			return true
		}
	}
	return false
}

func removeAt(lines *[]string, i int) {
	*lines = append((*lines)[:i], (*lines)[i+1:]...)
}

func insertAt(lines *[]string, i int, added []string) {
	combined := append(append([]string{}, (*lines)[:i]...), added...)
	*lines = append(combined, (*lines)[i:]...)
}
