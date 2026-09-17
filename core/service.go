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
}

func NewService(runtime Runtime) *Service {
	return &Service{runtime: runtime, state: NeedsConfig}
}

func (s *Service) Snapshot() map[string]any {
	s.mu.RLock()
	defer s.mu.RUnlock()
	eligible := len(s.config.Eligible())
	return map[string]any{"state": s.state, "message": s.errText, "eligible": eligible}
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
	cfg := s.config
	s.state = Checking
	s.errText = ""
	s.mu.Unlock()
	if len(raw) == 0 {
		s.setError(NeedsConfig, "Import a Clash configuration first")
		return fmt.Errorf("missing config")
	}
	filtered, err := cfg.FilteredYAML()
	if err != nil {
		s.setError(Blocked, err.Error())
		return err
	}
	if err = s.runtime.Start(filtered); err != nil {
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
