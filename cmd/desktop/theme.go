//go:build windows

package main

import (
	"image/color"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/theme"
)

// violetTheme keeps Fyne's dark palette and swaps the accent to the app's lavender, so the
// switch and buttons match the Android build's violet identity.
type violetTheme struct{ fyne.Theme }

func newVioletTheme() fyne.Theme { return &violetTheme{theme.DefaultTheme()} }

func (t *violetTheme) Color(name fyne.ThemeColorName, _ fyne.ThemeVariant) color.Color {
	if name == theme.ColorNamePrimary {
		return color.NRGBA{R: 124, G: 92, B: 240, A: 255}
	}
	return t.Theme.Color(name, theme.VariantDark)
}
