package bridge

import (
	"os"
	"testing"
)

func TestConfigReload(t *testing.T) {
	d := t.TempDir()
	p := d + "/rclone.conf"
	_ = os.WriteFile(p, []byte("[proton]\ntype = protondrive\nusername = one\n"), 0o600)
	call(t, "config/setpath", map[string]any{"path": p})
	t.Logf("1: %v", call(t, "config/get", map[string]any{"name": "proton"}))
	_ = os.WriteFile(p, []byte("[proton]\ntype = protondrive\nusername = two\n"), 0o600)
	call(t, "config/setpath", map[string]any{"path": p})
	t.Logf("2: %v", call(t, "config/get", map[string]any{"name": "proton"}))
	_ = os.Remove(p)
	call(t, "config/setpath", map[string]any{"path": p})
	t.Logf("3: %v", call(t, "config/dump", nil))
}
