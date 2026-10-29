package bridge

import (
	"strings"
	"testing"
)

// The server calls are registered in the library the app embeds, and report the error kind.
func TestDavCallsRegistered(t *testing.T) {
	r := RPC("synchrotron/davtest", `{"server":{"url":"http://127.0.0.1:1/","user":"u","password":"p"}}`)
	if r.Status == 200 || !strings.Contains(r.Output, "[offline]") {
		t.Fatalf("want an offline error, got %d %s", r.Status, r.Output)
	}
}
