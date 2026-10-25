// Package bridge is the thin gomobile layer between the Android app and rclone.
//
// It mirrors rclone's own librclone/gomobile shim, but additionally registers
// the rc calls Synchrotron needs (sync/bisync, sync/copy, operations/*) and lets
// the app pass the directories rclone may use before it initialises.
package bridge

import (
	"context"
	"os"
	"time"

	"github.com/rclone/rclone/fs"

	"github.com/rclone/rclone/librclone/librclone"

	_ "github.com/rclone/rclone/backend/local"       // local filesystem
	_ "github.com/rclone/rclone/backend/protondrive" // Proton Drive
	_ "github.com/rclone/rclone/cmd/bisync"          // registers sync/bisync
	_ "github.com/rclone/rclone/fs/operations"       // operations/*
	_ "github.com/rclone/rclone/fs/sync"             // sync/copy, sync/sync

	_ "golang.org/x/mobile/event/key" // keeps golang.org/x/mobile in go.mod for gomobile
)

// Result is returned by RPC.
//
//	Output is a serialized JSON object, Status an HTTP status (200 = OK).
type Result struct {
	Output string
	Status int
}

// Initialize starts rclone. home is an app-private writable directory; Go
// reads HOME/TMPDIR at start-up, so they are set before anything else.
func Initialize(home string, tmp string) {
	_ = os.Setenv("HOME", home)
	_ = os.Setenv("XDG_CACHE_HOME", home+"/cache")
	_ = os.Setenv("XDG_CONFIG_HOME", home+"/config")
	if tmp != "" {
		_ = os.Setenv("TMPDIR", tmp)
	}
	// Keep remotes (and so the Proton session with its unlocked keys) for the whole life of the
	// process instead of rclone's default 5 minutes: re-opening Proton costs seconds on every sync.
	// Must be set before the first remote is created, when the cache reads it.
	ci := fs.GetConfig(context.Background())
	ci.FsCacheExpireDuration = fs.Duration(24 * time.Hour)
	ci.FsCacheExpireInterval = fs.Duration(10 * time.Minute)
	librclone.Initialize()
}

// Shutdown releases resources held by rclone.
func Shutdown() {
	librclone.Finalize()
}

// RPC runs an rclone remote-control call. input is a JSON object.
func RPC(method string, input string) *Result {
	output, status := librclone.RPC(method, input)
	return &Result{Output: output, Status: status}
}
