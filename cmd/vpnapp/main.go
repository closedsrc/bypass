package main

import (
	"log"
	"net/http"

	"simplevpn/core"
)

func main() {
	rt, err := core.NewMihomoRuntime()
	if err != nil {
		log.Fatal(err)
	}
	svc := core.NewService(rt)
	log.Println("Bypass control API on 127.0.0.1:38991")
	log.Fatal(http.ListenAndServe("127.0.0.1:38991", svc.Handler()))
}
