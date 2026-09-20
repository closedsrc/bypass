//go:build windows

package main

import (
	"os/exec"
	"sort"
	"strings"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/dialog"
	"fyne.io/fyne/v2/widget"

	"simplevpn/core"
)

// runningProcesses lists the process names currently on the machine. Windows has no
// "give me the installed apps" API this client can call without extra dependencies, but
// the processes that are running are exactly the ones a user is likely to want to leave
// alone, and they are the names mihomo will match on.
func runningProcesses() []string {
	out, err := exec.Command("tasklist", "/FO", "CSV", "/NH").Output()
	if err != nil {
		return nil
	}
	return processNames(string(out))
}

func processNames(list string) []string {
	seen := map[string]struct{}{}
	names := []string{}
	for _, line := range strings.Split(list, "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		// tasklist's CSV form is "name","pid","session","mem"...; the name cannot contain
		// a comma, so the first field is enough.
		fields := strings.Split(line, ",")
		if len(fields) == 0 {
			continue
		}
		name := strings.TrimSpace(strings.Trim(fields[0], "\""))
		if name == "" {
			continue
		}
		key := strings.ToLower(name)
		if _, dup := seen[key]; dup {
			continue
		}
		seen[key] = struct{}{}
		names = append(names, name)
	}
	sort.Slice(names, func(i, j int) bool { return strings.ToLower(names[i]) < strings.ToLower(names[j]) })
	return names
}

func rememberedApps() []string {
	names, err := core.LoadKnownApps()
	if err != nil {
		return nil
	}
	return names
}

// showExclusions opens the split-tunnelling window: every app the user ticks keeps using
// the network directly while the tunnel carries everything else.
func (u *ui) showExclusions() {
	saved := u.svc.Exclusions()

	// Candidates are the running processes plus anything already excluded but not
	// currently running, so an existing choice never silently disappears.
	names := map[string]string{} // normalised -> display name
	picked := map[string]bool{}
	for _, n := range saved.List() {
		key := core.NormalizeApp(n)
		names[key] = n
		picked[key] = true
	}
	// Running processes plus everything seen on earlier visits: an app is worth offering
	// even when it is not open right now.
	running := runningProcesses()
	for _, p := range append(rememberedApps(), running...) {
		key := core.NormalizeApp(p)
		if _, ok := names[key]; !ok {
			names[key] = p
		}
	}
	if len(running) > 0 {
		_ = core.SaveKnownApps(running)
	}
	candidates := make([]string, 0, len(names))
	for _, display := range names {
		candidates = append(candidates, display)
	}
	sort.Slice(candidates, func(i, j int) bool {
		return strings.ToLower(candidates[i]) < strings.ToLower(candidates[j])
	})

	list := container.NewVBox()
	build := func(query string) {
		list.Objects = nil
		q := strings.ToLower(strings.TrimSpace(query))
		for _, display := range candidates {
			if q != "" && !strings.Contains(strings.ToLower(display), q) {
				continue
			}
			app := display
			check := widget.NewCheck(app, func(on bool) { picked[core.NormalizeApp(app)] = on })
			check.SetChecked(picked[core.NormalizeApp(app)])
			list.Add(check)
		}
		list.Refresh()
	}
	build("")

	search := widget.NewEntry()
	search.SetPlaceHolder("Filter apps…")
	search.OnChanged = build

	entry := widget.NewEntry()
	entry.SetPlaceHolder("Or type a process name, e.g. Spotify.exe")
	add := widget.NewButton("Add", func() {
		typed := strings.TrimSpace(entry.Text)
		if typed == "" {
			return
		}
		key := core.NormalizeApp(typed)
		names[key] = typed
		picked[key] = true
		candidates = append(candidates, typed)
		sort.Slice(candidates, func(i, j int) bool {
			return strings.ToLower(candidates[i]) < strings.ToLower(candidates[j])
		})
		entry.SetText("")
		build(search.Text)
	})
	entry.OnSubmitted = func(string) { add.OnTapped() }

	note := widget.NewLabel("")
	note.Wrapping = fyne.TextWrapWord

	win := u.app.NewWindow("Excluded apps")
	win.Resize(fyne.NewSize(420, 520))
	win.SetContent(container.NewBorder(
		container.NewVBox(
			widget.NewLabelWithStyle("Excluded apps", fyne.TextAlignLeading, fyne.TextStyle{Bold: true}),
			widget.NewLabel("Ticked apps ignore the tunnel and use your normal connection."),
			container.NewBorder(nil, nil, nil, add, entry),
			search,
		),
		container.NewPadded(note),
		nil,
		nil,
		container.NewVScroll(list),
	))

	win.SetOnClosed(func() {
		chosen := []string{}
		for _, display := range candidates {
			if picked[core.NormalizeApp(display)] {
				chosen = append(chosen, display)
			}
		}
		// Applying can rebuild the tunnel, which takes a moment, so it runs off the UI
		// thread; the main window settles on the result.
		go func() {
			err := u.svc.SetExclusions(chosen)
			fyne.Do(func() {
				u.refresh()
				if err != nil {
					dialog.ShowError(err, u.win)
				}
			})
		}()
	})

	win.Show()
}
