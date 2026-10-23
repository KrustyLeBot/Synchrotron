package bridge

import (
	"fmt"
	"os"
	"strings"
	"testing"
)

func TestDeletes(t *testing.T) {
	root := t.TempDir()
	a, b, wd := root+"/a", root+"/b", root+"/wd"
	for i := 0; i < 10; i++ {
		write(t, fmt.Sprintf("%s/f%d.txt", a, i), "x")
	}
	_ = os.MkdirAll(b, 0o755); run(t, a, b, wd, true, nil)
	// delete 8 of 10 locally
	for i := 0; i < 8; i++ {
		_ = os.Remove(fmt.Sprintf("%s/f%d.txt", a, i))
	}
	ok, o := run(t, a, b, wd, false, nil)
	t.Logf("80%% deleted: ok=%v %s", ok, strings.ReplaceAll(o, "\n", " | "))
	// remaining state, then delete everything
	_ = os.RemoveAll(a); _ = os.MkdirAll(a, 0o755)
	ok, o = run(t, a, b, wd, false, nil)
	t.Logf("ALL deleted: ok=%v %s", ok, strings.ReplaceAll(o, "\n", " | "))
	ok, o = run(t, a, b, wd, false, nil)
	t.Logf("next run: ok=%v %s", ok, strings.ReplaceAll(o, "\n", " | "))
	n, _ := os.ReadDir(b)
	t.Logf("remote still has %d files", len(n))
}
