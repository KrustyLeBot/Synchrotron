package bridge

import (
	"encoding/json"
	"testing"
	"time"
)

// Same call shape as RcloneEngine.runJob / SyncRunner.bisync.
func TestAsyncJob(t *testing.T) {
	root := t.TempDir()
	a, b := root+"/a", root+"/b"
	write(t, a+"/x.txt", "hello")
	write(t, b+"/y.txt", "world")
	write(t, a+"/.trashed-1", "t")
	filters := root + "/f.txt"
	write(t, filters, "- .trashed-*\n- .thumbnails/\n")
	params := map[string]any{
		"path1": a, "path2": b, "workdir": root + "/wd", "filtersFile": filters,
		"resync": true, "resyncMode": "newer", "maxDelete": 50, "force": false,
		"conflictResolve": "newer", "conflictLoser": "num", "conflictSuffix": "conflict",
		"createEmptySrcDirs": true, "ignoreListingChecksum": true, "resilient": true, "recover": true,
		"maxLock": "15m", "_async": true, "_group": "pair-1",
	}
	start := call(t, "sync/bisync", params)
	id := start["jobid"]
	for i := 0; i < 50; i++ {
		time.Sleep(100 * time.Millisecond)
		st := call(t, "job/status", map[string]any{"jobid": id})
		if st["finished"] == true {
			js, _ := json.Marshal(st)
			t.Logf("status: %s", js)
			stats := call(t, "core/stats", map[string]any{"group": "pair-1"})
			t.Logf("transfers=%v deletes=%v", stats["transfers"], stats["deletes"])
			if st["success"] != true {
				t.Fatalf("job failed: %v", st["error"])
			}
			// second run without resync
			params["resync"] = false
			delete(params, "resyncMode")
			write(t, a+"/z.txt", "z")
			p2 := call(t, "sync/bisync", map[string]any{"path1": a, "path2": b, "workdir": root + "/wd", "filtersFile": filters,
				"maxDelete": 50, "conflictResolve": "newer", "conflictLoser": "num", "conflictSuffix": "conflict",
				"createEmptySrcDirs": true, "ignoreListingChecksum": true, "resilient": true, "recover": true, "maxLock": "15m"})
			t.Logf("second: %v", p2["output"])
			return
		}
	}
	t.Fatal("job did not finish")
}
