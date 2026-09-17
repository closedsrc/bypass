package main

import (
	"log"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"

	"simplevpn/core"
)

type mihomoRuntime struct {
	binary string
	cmd    *exec.Cmd
}

func (r *mihomoRuntime) Start(config []byte) error {
	if r.binary == "" {
		return &missingEngineError{}
	}
	configPath := filepath.Join(".", "simplevpn-filtered.yaml")
	if err := os.WriteFile(configPath, config, 0600); err != nil {
		return err
	}
	r.cmd = exec.Command(r.binary, "-f", configPath)
	return r.cmd.Start()
}
func (r *mihomoRuntime) Stop() error {
	if r.cmd == nil || r.cmd.Process == nil {
		return nil
	}
	return r.cmd.Process.Kill()
}

type missingEngineError struct{}

func (*missingEngineError) Error() string {
	return "VPN engine is not installed. Add mihomo to the app folder."
}

func main() {
	runtime := &mihomoRuntime{binary: "mihomo.exe"}
	svc := core.NewService(runtime)
	log.Println("SimpleVPN control API listening on 127.0.0.1:38991")
	log.Fatal(http.ListenAndServe("127.0.0.1:38991", svc.Handler()))
}
