package bridge

import "testing"

func TestFsCacheSettings(t *testing.T) {
	opts := call(t, "options/get", nil)["main"].(map[string]any)
	if d := opts["FsCacheExpireDuration"]; d != 86400000000000.0 && d != "24h0m0s" {
		t.Fatalf("FsCacheExpireDuration = %v", d)
	}
	call(t, "fscache/clear", nil)
	t.Logf("entries: %v", call(t, "fscache/entries", nil))
}
