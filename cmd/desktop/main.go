//go:build windows

package main

import (
	"embed"
	"fmt"
	"image/color"
	"os"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/app"
	"fyne.io/fyne/v2/canvas"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/dialog"
	"fyne.io/fyne/v2/widget"

	"github.com/energye/systray"

	"simplevpn/core"
)

//go:embed icon.png
var iconFS embed.FS

var orbIdle = color.NRGBA{R: 138, G: 124, B: 240, A: 255}
var orbLive = color.NRGBA{R: 80, G: 200, B: 120, A: 255}

var copyFor = map[string][2]string{
	"idle":         {"Ready to connect", "Your filtered proxy route is standing by."},
	"needs-config": {"Add your route", "Import a Clash configuration to begin."},
	"checking":     {"Starting tunnel", "Bringing up the Wintun route."},
	"connected":    {"You are connected", "Traffic is leaving through your filtered route."},
	"blocked":      {"No eligible route", "Your configuration only contains USA nodes."},
	"error":        {"Could not connect", "See the details below, then try again."},
}

type ui struct {
	svc    *core.Service
	app    fyne.App
	win    fyne.Window
	orb    *canvas.Circle
	status *widget.Label
	detail *widget.Label
	toggle *widget.Button
	apps   *widget.Button
	busy   bool
}

func main() {
	rt, err := core.NewMihomoRuntime()
	if err != nil {
		fatal(err)
	}
	svc := core.NewService(rt)

	a := app.NewWithID("com.vpn.bypass")
	a.Settings().SetTheme(newVioletTheme())
	w := a.NewWindow("Bypass")
	w.Resize(fyne.NewSize(360, 470))

	u := &ui{svc: svc, app: a, win: w}
	u.build()
	w.SetCloseIntercept(func() { w.Hide() }) // minimize to tray rather than quit

	var endTray func()
	quit := func() {
		if endTray != nil {
			endTray()
		}
		a.Quit()
	}
	start, end := systray.RunWithExternalLoop(func() {
		u.tray(func() { w.Show(); w.RequestFocus() }, u.onToggle, quit)
	}, func() {})
	endTray = end
	start()

	w.ShowAndRun()
	end()
}

func (u *ui) build() {
	brand := widget.NewLabelWithStyle("Bypass", fyne.TextAlignCenter, fyne.TextStyle{Bold: true})

	u.orb = canvas.NewCircle(orbIdle)
	sizer := canvas.NewRectangle(color.Transparent)
	sizer.SetMinSize(fyne.NewSize(150, 150))
	orb := container.NewStack(sizer, u.orb)

	u.status = widget.NewLabelWithStyle("Add your route", fyne.TextAlignCenter, fyne.TextStyle{Bold: true})
	u.detail = widget.NewLabel("Import a Clash configuration to begin.")
	u.detail.Wrapping = fyne.TextWrapWord

	u.toggle = widget.NewButton("Connect", u.onToggle)
	u.toggle.Importance = widget.HighImportance
	importBtn := widget.NewButton("Import profile…", u.onImport)
	u.apps = widget.NewButton("Excluded apps", u.showExclusions)

	u.win.SetContent(container.NewVBox(
		container.NewPadded(brand),
		container.NewCenter(orb),
		container.NewPadded(u.status),
		container.NewPadded(u.detail),
		container.NewPadded(u.toggle),
		container.NewPadded(importBtn),
		container.NewPadded(u.apps),
	))
	u.refresh()
}

func (u *ui) onImport() {
	dialog.ShowFileOpen(func(rc fyne.URIReadCloser, err error) {
		if err != nil || rc == nil {
			return
		}
		path := rc.URI().Path()
		rc.Close()
		data, err := os.ReadFile(path)
		if err != nil {
			dialog.ShowError(err, u.win)
			return
		}
		if err := u.svc.Import(data); err != nil {
			dialog.ShowError(err, u.win)
			return
		}
		fyne.Do(u.refresh)
	}, u.win)
}

// onToggle runs on the UI thread. The engine call blocks (it waits for the tunnel to form)
// so it happens on a worker; the interface stays responsive and settles on the result.
func (u *ui) onToggle() {
	if u.busy {
		return
	}
	wasConnected := u.svc.Snapshot()["state"] == core.Connected
	u.busy = true
	u.refresh()
	go func() {
		if wasConnected {
			_ = u.svc.Disconnect()
		} else {
			_ = u.svc.Connect()
		}
		u.busy = false
		fyne.Do(u.refresh)
	}()
}

func (u *ui) refresh() {
	snap := u.svc.Snapshot()
	state, _ := snap["state"].(core.State)
	texts := copyFor[string(state)]
	u.status.SetText(texts[0])
	detail := texts[1]
	if state == core.ErrorState {
		if msg, ok := snap["message"].(string); ok && msg != "" {
			detail = msg
		}
	}
	u.detail.SetText(detail)

	connected := state == core.Connected
	if u.busy {
		u.toggle.Disable()
		u.toggle.SetText("Working…")
	} else {
		u.toggle.Enable()
		if connected {
			u.toggle.SetText("Disconnect")
		} else {
			u.toggle.SetText("Connect")
		}
	}
	u.orb.FillColor = orbIdle
	if connected {
		u.orb.FillColor = orbLive
	}
	u.orb.Refresh()

	excluded, _ := snap["excluded"].(int)
	u.apps.SetText(fmt.Sprintf("Excluded apps (%d)", excluded))
}

func (u *ui) tray(show, toggle, quit func()) {
	systray.SetIcon(mustIcon())
	systray.SetTitle("Bypass")
	systray.SetTooltip("Bypass — no USA exit nodes")

	mOpen := systray.AddMenuItem("Open", "Show the window")
	mOpen.Click(func() { fyne.Do(show) })
	systray.AddSeparator()
	mToggle := systray.AddMenuItem("Connect / Disconnect", "")
	mToggle.Click(func() { fyne.Do(toggle) })
	systray.AddSeparator()
	mApps := systray.AddMenuItem("Excluded apps…", "Choose apps that ignore the tunnel")
	mApps.Click(func() { fyne.Do(u.showExclusions) })
	systray.AddSeparator()
	mQuit := systray.AddMenuItem("Quit", "")
	mQuit.Click(quit)
}

func mustIcon() []byte {
	b, _ := iconFS.ReadFile("icon.png")
	return b
}

func fatal(err error) {
	fmt.Fprintln(os.Stderr, "bypass:", err)
	os.Exit(1)
}
