package bridge

import "os"

func osRemoveAll(p string) error { _ = os.RemoveAll(p); return os.MkdirAll(p, 0o755) }
