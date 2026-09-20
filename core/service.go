package core

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"sync"
)

type State string

const (
	Idle        State = "idle"
	NeedsConfig State = "needs-config"
	Checking    State = "checking"
	Connected   State = "connected"
	ErrorState  State = "error"
	Blocked     State = "blocked"
)

type Runtime interface {
	Start(config []byte) error
	Stop() error
}

type Service struct {
	mu      sync.RWMutex
	config  Config
	raw     []byte
	runtime Runtime
	state   State
	errText string
	excl    *Exclusions

	// persist writes the exclusion list back to disk. It is a field so tests can run
	// without touching the user's config directory.
	persist func(*Exclusions) error
}

func NewService(runtime Runtime) *Service {
	// A saved exclusion list is a nice-to-have: if it cannot be read the app still works,
	// it just starts with nothing excluded.
	excl := NewExclusions()
	if saved, err := LoadExclusions(); err == nil {
		excl = saved
	}
	return &Service{runtime: runtime, state: NeedsConfig, excl: excl, persist: (*Exclusions).Save}
}

// Exclusions is the set of apps that must not go through the tunnel.
func (s *Service) Exclusions() *Exclusions { return s.excl }

// SetExclusions replaces the excluded apps and persists them. If the tunnel is already
// up it is restarted so the change takes effect now instead of at the next connect.
func (s *Service) SetExclusions(names []string) error {
	if !s.excl.Set(names) {
		return nil
	}
	if s.persist != nil {
		_ = s.persist(s.excl)
	}
	if s.Snapshot()["state"] != Connected {
		return nil
	}
	if err := s.Disconnect(); err != nil {
		return err
	}
	return s.Connect()
}

func (s *Service) Snapshot() map[string]any {
	s.mu.RLock()
	defer s.mu.RUnlock()
	eligible := len(s.config.Eligible())
	return map[string]any{
		"state":    s.state,
		"message":  s.errText,
		"eligible": eligible,
		"excluded": len(s.excl.List()),
	}
}

func (s *Service) Import(data []byte) error {
	cfg, err := Parse(data)
	if err != nil {
		return err
	}
	if len(cfg.Eligible()) == 0 {
		return fmt.Errorf("all configured proxies are USA nodes")
	}
	s.mu.Lock()
	s.config, s.raw, s.state, s.errText = cfg, append([]byte(nil), data...), Idle, ""
	s.mu.Unlock()
	return nil
}

func (s *Service) Connect() error {
	s.mu.Lock()
	raw := append([]byte(nil), s.raw...)
	s.state = Checking
	s.errText = ""
	s.mu.Unlock()
	if len(raw) == 0 {
		s.setError(NeedsConfig, "Import a Clash configuration first")
		return fmt.Errorf("missing config")
	}
	result, err := ApplyFilter(string(raw))
	if err != nil {
		s.setError(Blocked, err.Error())
		return err
	}
	// Apps the user excluded get a DIRECT rule ahead of everything else, so they leave
	// the device untouched even though the tunnel is up.
	profile := ApplyExclusions(result.YAML, s.excl.List())
	if err = s.runtime.Start([]byte(profile)); err != nil {
		s.setError(ErrorState, err.Error())
		return err
	}
	s.mu.Lock()
	s.state = Connected
	s.mu.Unlock()
	return nil
}

func (s *Service) Disconnect() error {
	if err := s.runtime.Stop(); err != nil {
		s.setError(ErrorState, err.Error())
		return err
	}
	s.mu.Lock()
	s.state, s.errText = Idle, ""
	s.mu.Unlock()
	return nil
}

func (s *Service) setError(state State, msg string) {
	s.mu.Lock()
	s.state, s.errText = state, msg
	s.mu.Unlock()
}

func (s *Service) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/api/status", func(w http.ResponseWriter, r *http.Request) { writeJSON(w, s.Snapshot()) })
	mux.HandleFunc("/api/config", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		data, err := os.ReadFile(r.URL.Query().Get("path"))
		if err != nil {
			http.Error(w, err.Error(), 400)
			return
		}
		if err = s.Import(data); err != nil {
			http.Error(w, err.Error(), 400)
			return
		}
		writeJSON(w, s.Snapshot())
	})
	mux.HandleFunc("/api/connect", func(w http.ResponseWriter, r *http.Request) {
		if err := s.Connect(); err != nil {
			http.Error(w, err.Error(), 400)
			return
		}
		writeJSON(w, s.Snapshot())
	})
	mux.HandleFunc("/api/exclusions", func(w http.ResponseWriter, r *http.Request) {
		switch r.Method {
		case http.MethodGet:
			writeJSON(w, map[string]any{"apps": s.excl.List()})
		case http.MethodPost:
			var body struct {
				Apps []string `json:"apps"`
			}
			if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
				http.Error(w, err.Error(), http.StatusBadRequest)
				return
			}
			if err := s.SetExclusions(body.Apps); err != nil {
				http.Error(w, err.Error(), http.StatusBadRequest)
				return
			}
			writeJSON(w, s.Snapshot())
		default:
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		}
	})
	mux.HandleFunc("/api/disconnect", func(w http.ResponseWriter, r *http.Request) {
		if err := s.Disconnect(); err != nil {
			http.Error(w, err.Error(), 400)
			return
		}
		writeJSON(w, s.Snapshot())
	})
	return mux
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(v)
}
