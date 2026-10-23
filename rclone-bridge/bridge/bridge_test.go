package bridge

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestMain(m *testing.M) {
	dir, _ := os.MkdirTemp("", "bridge")
	_ = os.MkdirAll(dir+"/tmp", 0o755)
	Initialize(dir+"/home", dir+"/tmp")
	code := m.Run()
	_ = os.RemoveAll(dir)
	os.Exit(code)
}

func call(t *testing.T, method string, in map[string]any) map[string]any {
	t.Helper()
	b, _ := json.Marshal(in)
	r := RPC(method, string(b))
	out := map[string]any{}
	_ = json.Unmarshal([]byte(r.Output), &out)
	if r.Status != 200 {
		t.Fatalf("%s failed (%d): %s", method, r.Status, r.Output)
	}
	return out
}

func write(t *testing.T, p, s string) {
	t.Helper()
	_ = os.MkdirAll(filepath.Dir(p), 0o755)
	if err := os.WriteFile(p, []byte(s), 0o644); err != nil {
		t.Fatal(err)
	}
}

// Runs a real bisync between two local directories with the exact parameters the app uses.
func TestBisyncLocal(t *testing.T) {
	root := t.TempDir()
	a, b, wd := root+"/a", root+"/b", root+"/wd"
	write(t, a+"/one.txt", "one")
	write(t, b+"/two.txt", "two")
	write(t, a+"/skip.tmp", "tmp")
	filters := root + "/filters.txt"
	write(t, filters, "- *.tmp\n")

	params := func(extra map[string]any) map[string]any {
		m := map[string]any{
			"path1": a, "path2": b, "workdir": wd, "filtersFile": filters,
			"compare": "size,checksum", "maxDelete": 50, "resilient": true, "recover": true,
			"createEmptySrcDirs": true, "conflictResolve": "none", "conflictLoser": "num",
			"conflictSuffix": "conflict", "_async": false,
		}
		for k, v := range extra {
			m[k] = v
		}
		return m
	}
	call(t, "sync/bisync", params(map[string]any{"resync": true, "resyncMode": "path1"}))
	for _, p := range []string{a + "/two.txt", b + "/one.txt"} {
		if _, err := os.Stat(p); err != nil {
			t.Fatalf("missing %s after resync", p)
		}
	}
	if _, err := os.Stat(b + "/skip.tmp"); err == nil {
		t.Fatal("excluded file was synced")
	}
	// conflict: both sides changed
	write(t, a+"/one.txt", "A side")
	write(t, b+"/one.txt", "B side!")
	out := call(t, "sync/bisync", params(nil))
	t.Logf("bisync output: %v", out["output"])
	entries, _ := os.ReadDir(a)
	names := []string{}
	for _, e := range entries {
		names = append(names, e.Name())
	}
	t.Logf("a: %s", strings.Join(names, ","))
	// deletion propagates
	_ = os.Remove(a + "/two.txt")
	call(t, "sync/bisync", params(nil))
	if _, err := os.Stat(b + "/two.txt"); err == nil {
		t.Fatal("deletion not propagated")
	}
	// operations/list recursive
	l := call(t, "operations/list", map[string]any{"fs": a, "remote": "", "opt": map[string]any{"recurse": true, "filesOnly": true}})
	t.Logf("list: %v", l["list"])
	// copy with filter
	call(t, "sync/copy", map[string]any{"srcFs": a, "dstFs": root + "/c", "_filter": map[string]any{"ExcludeRule": []string{"*.txt"}}})
}

func TestProtonConfig(t *testing.T) {
	root := t.TempDir()
	call(t, "config/setpath", map[string]any{"path": root + "/rclone.conf"})
	call(t, "config/create", map[string]any{
		"name": "proton", "type": "protondrive",
		"parameters": map[string]any{"username": "a@b.c", "password": "secret", "2fa": "123456"},
		"opt":        map[string]any{"obscure": true, "nonInteractive": true},
	})
	got := call(t, "config/get", map[string]any{"name": "proton"})
	t.Logf("config: %v", got)
	if got["password"] == "secret" {
		t.Fatal("password not obscured")
	}
}
