package davsync

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"
)

// Against the real server software: DAVSYNC_RCLONE=/path/to/rclone go test -run Real ./davsync/
func TestRealRcloneServer(t *testing.T) {
	bin := os.Getenv("DAVSYNC_RCLONE")
	if bin == "" {
		t.Skip("DAVSYNC_RCLONE not set")
	}
	data := t.TempDir()
	l, _ := net.Listen("tcp", "127.0.0.1:0")
	addr := l.Addr().String()
	l.Close()
	cmd := exec.Command(bin, "serve", "webdav", data, "--addr", addr, "--user", "fold6", "--pass", "pw", "--etag-hash", "MD5")
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = cmd.Process.Kill(); _ = cmd.Wait() })
	for i := 0; i < 100; i++ {
		if r, err := http.Get("http://" + addr); err == nil {
			r.Body.Close()
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	c, _ := NewClient(Config{URL: "http://" + addr + "/", User: "fold6", Password: "pw"})
	ctx := context.Background()
	if err := c.Test(ctx); err != nil {
		t.Fatal(err)
	}
	root := t.TempDir()
	local := filepath.Join(root, "phone")
	opt := Options{Local: local, Remote: "Apps/Réel", StateFile: filepath.Join(root, "s.json"), Mode: "bisync", Conflict: "none", MaxDelete: 50}
	put(t, filepath.Join(local, "a b#%é.md"), "one")
	put(t, filepath.Join(local, "dir/x.json"), `{}`)
	srv := func(rel string) string { return filepath.Join(data, "Apps", "Réel", filepath.FromSlash(rel)) }
	run := func() {
		t.Helper()
		if log, err := Sync(ctx, c, opt); err != nil {
			t.Fatalf("%v\n%s", err, log)
		}
	}
	run()
	if read(srv("a b#%é.md")) != "one" || read(srv("dir/x.json")) != "{}" {
		t.Fatal("upload")
	}
	put(t, srv("dir/x.json"), `{"pc":true}`) // written over SMB
	run()
	if read(filepath.Join(local, "dir/x.json")) != `{"pc":true}` {
		t.Fatal("SMB change not downloaded")
	}
	os.Remove(filepath.Join(local, "a b#%é.md"))
	run()
	if read(srv("a b#%é.md")) != "<absent>" {
		t.Fatal("delete")
	}
	for _, f := range files(data) {
		if IsTemp(filepath.Base(f)) {
			t.Fatalf("temporary left: %s", f)
		}
	}
	fmt.Println("real server files:", files(data))
}
