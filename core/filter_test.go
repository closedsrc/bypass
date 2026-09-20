package core

import (
	"strings"
	"testing"
)

const filterProfile = `proxies:
  - name: "US Premium 1"
    type: ss
    server: us1.example.com
    port: 443
  - name: "Singapore 2"
    type: ss
    server: sg.example.com
    port: 443
  - name: "Japan 3"
    type: vmess
    server: jp.example.com
    port: 443
  - name: "United States Backup"
    type: trojan
    server: us2.example.com
    port: 443
  - name: "America Direct"
    type: ss
    server: us3.example.com
    port: 443

proxy-groups:
  - name: VoltClash
    type: select
    proxies:
      - All
      - BDCLOUD
      - US Premium 1
  - name: All
    type: load-balance
    strategy: round-robin
    proxies:
      - US Premium 1
      - Singapore 2
      - Japan 3
  - name: BDCLOUD
    type: select
    proxies: ["US Premium 1", "Singapore 2"]

rules:
  - DOMAIN-SUFFIX,example.com,DIRECT
  - DOMAIN-SUFFIX,keepme.test,Singapore 2
  - GEOIP,CN,DIRECT,no-resolve
  - MATCH,VoltClash`

func mustApply(t *testing.T, in string) string {
	t.Helper()
	res, err := ApplyFilter(in)
	if err != nil {
		t.Fatalf("ApplyFilter: %v", err)
	}
	return res.YAML
}

func TestDropsUSAndReferences(t *testing.T) {
	res, err := ApplyFilter(filterProfile)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Join(res.Kept, ",") != "Singapore 2,Japan 3" {
		t.Fatalf("kept = %v", res.Kept)
	}
	if strings.Join(res.Removed, ",") != "US Premium 1,United States Backup,America Direct" {
		t.Fatalf("removed = %v", res.Removed)
	}
	for _, s := range []string{"us1.example.com", "us2.example.com", "us3.example.com", "US Premium 1", "United States Backup", "America Direct", "United States"} {
		if strings.Contains(res.YAML, s) {
			t.Fatalf("no USA artefact may survive, found %q", s)
		}
	}
}

func TestGroupsLoseOnlyGoneRefs(t *testing.T) {
	out := mustApply(t, filterProfile)
	for _, want := range []string{
		`- name: VoltClash`,
		`      - "All"`,
		`      - "BDCLOUD"`,
		`proxies: ["Singapore 2"]`,
		`      - "Singapore 2"`,
		`      - "Japan 3"`,
	} {
		if !strings.Contains(out, want) {
			t.Fatalf("missing %q in:\n%s", want, out)
		}
	}
}

func TestCatchAllPointsAtGeneratedGroup(t *testing.T) {
	out := mustApply(t, filterProfile)
	if !strings.Contains(out, "- MATCH,SimpleVPN") {
		t.Fatal("catch-all not repointed")
	}
	if strings.Contains(out, "MATCH,VoltClash") {
		t.Fatal("old catch-all target survived")
	}
}

func TestLoadBalanceGroupOrder(t *testing.T) {
	out := mustApply(t, filterProfile)
	group := out[strings.Index(out, "- name: SimpleVPN"):]
	if !strings.Contains(group, "type: load-balance") {
		t.Fatal("no load-balance type")
	}
	if strings.Index(group, "Singapore 2") > strings.Index(group, "Japan 3") {
		t.Fatal("nodes not in surviving order")
	}
}

func TestUserRulesKeepOrderAndTargets(t *testing.T) {
	out := mustApply(t, filterProfile)
	for _, want := range []string{
		"- DOMAIN-SUFFIX,example.com,DIRECT",
		"- DOMAIN-SUFFIX,keepme.test,Singapore 2",
		"- GEOIP,CN,DIRECT,no-resolve",
	} {
		if !strings.Contains(out, want) {
			t.Fatalf("rule lost: %q", want)
		}
	}
}

func TestTopLevelKeysAppearOnce(t *testing.T) {
	out := mustApply(t, filterProfile)
	for _, key := range []string{"proxies:", "proxy-groups:", "rules:"} {
		n := 0
		for _, line := range strings.Split(out, "\n") {
			if line == key {
				n++
			}
		}
		if n != 1 {
			t.Fatalf("%s appears %d times", key, n)
		}
	}
}

func TestEmptiedGroupDroppedAndRepointed(t *testing.T) {
	in := `proxies:
  - name: "US Only 1"
    type: ss
    server: a.example.com
  - name: "Singapore 2"
    type: ss
    server: b.example.com

proxy-groups:
  - name: US Only
    type: select
    proxies:
      - US Only 1
  - name: Mixed
    type: select
    proxies:
      - US Only
      - Singapore 2

rules:
  - DOMAIN-SUFFIX,gone.test,US Only
  - MATCH,Mixed`
	out := mustApply(t, in)
	if strings.Contains(out, "- name: US Only") {
		t.Fatal("emptied group should be gone")
	}
	if strings.Contains(out, "US Only 1") {
		t.Fatal("US node survived")
	}
	if !strings.Contains(out, "- DOMAIN-SUFFIX,gone.test,SimpleVPN") {
		t.Fatal("rule to emptied group not repointed")
	}
	if !strings.Contains(out, "- MATCH,SimpleVPN") {
		t.Fatal("catch-all not repointed")
	}
}

func TestRuleNamingRemovedNodeRepointed(t *testing.T) {
	in := `proxies:
  - name: "US West"
    type: ss
    server: a.example.com
  - name: "Tokyo"
    type: ss
    server: b.example.com

rules:
  - DOMAIN-SUFFIX,node.test,US West
  - MATCH,Tokyo`
	out := mustApply(t, in)
	if !strings.Contains(out, "- DOMAIN-SUFFIX,node.test,SimpleVPN") {
		t.Fatal("node rule not repointed")
	}
	if !strings.Contains(out, "- MATCH,SimpleVPN") {
		t.Fatal("catch-all not repointed")
	}
}

func TestRulesAndDNSAddedWhenAbsent(t *testing.T) {
	in := `proxies:
  - name: "Tokyo"
    type: ss
    server: b.example.com`
	out := mustApply(t, in)
	for _, want := range []string{"proxy-groups:", "rules:", "- MATCH,SimpleVPN", "fake-ip"} {
		if !strings.Contains(out, want) {
			t.Fatalf("missing %q", want)
		}
	}
}

func TestExistingDNSPreserved(t *testing.T) {
	in := `proxies:
  - name: "Tokyo"
    type: ss
    server: b.example.com

dns:
  enable: true
  nameserver:
    - 9.9.9.9

rules:
  - MATCH,DIRECT`
	out := mustApply(t, in)
	n := 0
	for _, line := range strings.Split(out, "\n") {
		if line == "dns:" {
			n++
		}
	}
	if n != 1 {
		t.Fatalf("dns: appears %d times", n)
	}
	if !strings.Contains(out, "- 9.9.9.9") {
		t.Fatal("existing nameserver lost")
	}
	if strings.Contains(out, "fake-ip") {
		t.Fatal("fake-ip injected despite existing dns")
	}
}

func TestProviderBackedGroupKept(t *testing.T) {
	in := `proxies:
  - name: "US One"
    type: ss
    server: a.example.com
  - name: "Osaka"
    type: ss
    server: b.example.com

proxy-groups:
  - name: FromProvider
    type: url-test
    use:
      - some-provider

rules:
  - MATCH,FromProvider`
	out := mustApply(t, in)
	if !strings.Contains(out, "- name: FromProvider") {
		t.Fatal("provider group dropped")
	}
	if !strings.Contains(out, "some-provider") {
		t.Fatal("provider ref lost")
	}
}

func TestOnlyWholeWordsCountAsUSA(t *testing.T) {
	for _, name := range []string{"US Premium 1", "USA-2", "United States Backup", "U.S. West", "America Direct", "us node"} {
		if !IsUsaNode(name) {
			t.Fatalf("%q should be USA", name)
		}
	}
	for _, name := range []string{"Singapore 2", "Just Fast", "Russia 1", "Australia", "Tokyo", "Japan 3"} {
		if IsUsaNode(name) {
			t.Fatalf("%q should NOT be USA", name)
		}
	}
}

func TestAllUSRefused(t *testing.T) {
	in := `proxies:
  - name: "US One"
    type: ss
    server: a.example.com
  - name: "America Two"
    type: ss
    server: b.example.com`
	_, err := ApplyFilter(in)
	if err == nil || !strings.Contains(err.Error(), "USA") {
		t.Fatalf("expected USA refusal, got %v", err)
	}
}

func TestNoProxiesRefused(t *testing.T) {
	if _, err := ApplyFilter("rules:\n  - MATCH,DIRECT\n"); err == nil {
		t.Fatal("expected refusal for missing proxies")
	}
}

func TestEmptyInlineGroupDropped(t *testing.T) {
	in := `proxies:
  - name: "US One"
    type: ss
    server: a.example.com
  - name: "Seoul"
    type: ss
    server: b.example.com

proxy-groups:
  - name: Inline
    type: select
    proxies: ["US One"]
  - name: Keeper
    type: select
    proxies: ["Inline", "Seoul"]

rules:
  - MATCH,Keeper`
	out := mustApply(t, in)
	if strings.Contains(out, "- name: Inline") {
		t.Fatal("emptied inline group not dropped")
	}
	if !strings.Contains(out, "- name: Keeper") {
		t.Fatal("keeper group lost")
	}
	if !strings.Contains(out, `"Seoul"`) {
		t.Fatal("Seoul ref lost")
	}
	if !strings.Contains(out, "- MATCH,SimpleVPN") {
		t.Fatal("catch-all not repointed")
	}
}
