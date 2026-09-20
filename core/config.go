package core

import (
	"fmt"
	"regexp"
	"strings"

	"gopkg.in/yaml.v3"
)

type Proxy struct {
	Name string
	Type string
	Raw  map[string]any
}

type Config struct {
	Proxies []Proxy
}

var usaPattern = regexp.MustCompile(`(?i)(^|[^a-z])(us|usa|u\.s\.|united states|america)([^a-z]|$)`)

func Parse(data []byte) (Config, error) {
	var doc struct {
		Proxies []map[string]any `yaml:"proxies"`
	}
	if err := yaml.Unmarshal(data, &doc); err != nil {
		return Config{}, fmt.Errorf("parse clash config: %w", err)
	}
	out := Config{Proxies: make([]Proxy, 0, len(doc.Proxies))}
	seen := make(map[string]struct{}, len(doc.Proxies))
	for _, raw := range doc.Proxies {
		name, _ := raw["name"].(string)
		typ, _ := raw["type"].(string)
		name = strings.TrimSpace(name)
		if name == "" || typ == "" {
			continue
		}
		key := strings.ToLower(name)
		if _, ok := seen[key]; ok {
			continue
		}
		seen[key] = struct{}{}
		out.Proxies = append(out.Proxies, Proxy{Name: name, Type: typ, Raw: raw})
	}
	if len(out.Proxies) == 0 {
		return Config{}, fmt.Errorf("config contains no usable proxies")
	}
	return out, nil
}

func (c Config) Eligible() []Proxy {
	out := make([]Proxy, 0, len(c.Proxies))
	for _, p := range c.Proxies {
		if !usaPattern.MatchString(p.Name) {
			out = append(out, p)
		}
	}
	return out
}

func Choose(eligible []Proxy, failed map[string]bool, cursor *int) (Proxy, error) {
	if len(eligible) == 0 {
		return Proxy{}, fmt.Errorf("no eligible proxies")
	}
	for i := 0; i < len(eligible); i++ {
		idx := (*cursor + i) % len(eligible)
		if !failed[eligible[idx].Name] {
			*cursor = (idx + 1) % len(eligible)
			return eligible[idx], nil
		}
	}
	return Proxy{}, fmt.Errorf("all eligible proxies failed")
}
