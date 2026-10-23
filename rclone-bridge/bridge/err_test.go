package bridge

import (
	"encoding/json"
	"testing"
	"time"
)

func job(t *testing.T, params map[string]any) map[string]any {
	params["_async"] = true
	id := call(t, "sync/bisync", params)["jobid"]
	for i := 0; i < 100; i++ {
		time.Sleep(50 * time.Millisecond)
		st := call(t, "job/status", map[string]any{"jobid": id})
		if st["finished"] == true {
			return st
		}
	}
	t.Fatal("timeout")
	return nil
}

func TestErrors(t *testing.T) {
	root := t.TempDir()
	a, b := root+"/a", root+"/b"
	write(t, a+"/x.txt", "x")
	base := func() map[string]any {
		return map[string]any{"path1": a, "path2": b, "workdir": root + "/wd", "resilient": true, "recover": true, "maxDelete": 50}
	}
	p := base(); p["resync"] = true
	job(t, p)
	// empty path1
	_ = removeAll(a)
	st := job(t, base())
	js, _ := json.Marshal(st)
	t.Logf("EMPTY: %s", js)
}

func removeAll(p string) error { return osRemoveAll(p) }
