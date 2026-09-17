package core

import "testing"

const sample = `proxies:
  - name: "Tokyo relay"
    type: socks5
    server: tokyo.example
    port: 443
  - name: "New York USA"
    type: http
    server: us.example
    port: 443
  - name: "DE 01"
    type: vmess
    server: de.example
    port: 443
  - name: "Tokyo relay"
    type: socks5
    server: duplicate.example
    port: 443
`

func TestParseAndExcludeUSA(t *testing.T) {
	cfg, err := Parse([]byte(sample))
	if err != nil {
		t.Fatal(err)
	}
	eligible := cfg.Eligible()
	if len(cfg.Proxies) != 3 || len(eligible) != 2 {
		t.Fatalf("got %d total, %d eligible", len(cfg.Proxies), len(eligible))
	}
	for _, p := range eligible {
		if p.Name == "New York USA" {
			t.Fatal("USA node was not excluded")
		}
	}
}

func TestAllUSAIsBlocked(t *testing.T) {
	cfg, err := Parse([]byte("proxies:\n  - name: USA West\n    type: http\n    server: us.example\n    port: 443\n"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := cfg.FilteredYAML(); err == nil {
		t.Fatal("expected empty eligible pool error")
	}
}

func TestChooseRoundRobinWithFailures(t *testing.T) {
	cfg, err := Parse([]byte(sample))
	if err != nil {
		t.Fatal(err)
	}
	eligible := cfg.Eligible()
	cursor := 0
	p, err := Choose(eligible, map[string]bool{}, &cursor)
	if err != nil || p.Name != "Tokyo relay" {
		t.Fatalf("first choice: %v %v", p.Name, err)
	}
	p, err = Choose(eligible, map[string]bool{"DE 01": true}, &cursor)
	if err != nil || p.Name != "Tokyo relay" {
		t.Fatalf("fallback choice: %v %v", p.Name, err)
	}
}
