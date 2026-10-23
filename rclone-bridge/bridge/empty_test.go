package bridge

import (
	"os"
	"strings"
	"testing"
)

func run(t *testing.T, a, b, wd string, resync bool, extra map[string]any) (bool, string) {
	p := map[string]any{"path1": a, "path2": b, "workdir": wd, "resilient": true, "recover": true, "maxDelete": 50, "createEmptySrcDirs": true, "resync": resync}
	for k, v := range extra {
		p[k] = v
	}
	st := job(t, p)
	out, _ := st["output"].(map[string]any)
	o, _ := out["output"].(string)
	return st["success"] == true, o
}

func TestEmptyLocal(t *testing.T) {
	root := t.TempDir()
	a, b := root+"/a", root+"/b"
	_ = os.MkdirAll(a, 0o755)
	write(t, b+"/r1.txt", "remote")
	ok, o := run(t, a, b, root+"/wd", true, nil)
	t.Logf("resync ok=%v", ok)
	ok, o = run(t, a, b, root+"/wd", false, nil)
	t.Logf("second ok=%v %s", ok, o)
}

func TestBothEmpty(t *testing.T) {
	root := t.TempDir()
	a, b := root+"/a", root+"/b"
	_ = os.MkdirAll(a, 0o755)
	_ = os.MkdirAll(b, 0o755)
	ok, o := run(t, a, b, root+"/wd", true, nil)
	t.Logf("resync ok=%v %s", ok, strings.ReplaceAll(o, "\n", " | "))
	ok, o = run(t, a, b, root+"/wd", false, nil)
	t.Logf("second ok=%v %s", ok, strings.ReplaceAll(o, "\n", " | "))
	write(t, a+"/new.txt", "n")
	ok, o = run(t, a, b, root+"/wd", false, nil)
	t.Logf("third (after adding file) ok=%v %s", ok, strings.ReplaceAll(o, "\n", " | "))
}
