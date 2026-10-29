package davsync

import (
	"crypto/md5"
	"encoding/hex"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path"
	"path/filepath"
	"strings"
	"time"

	"github.com/google/uuid"
)

// IsTemp tells the temporaries of atomic writes (`.<name>.synctmp-<uuid>`), never synced.
func IsTemp(name string) bool {
	return strings.HasPrefix(name, ".") && strings.Contains(name, ".synctmp-")
}

func tempName(final string) string {
	return "." + final + ".synctmp-" + uuid.NewString()
}

type localFile struct {
	Size  int64
	Mtime int64 // unix nanoseconds
	Hash  string
}

// fileMD5 hashes a file with a short-lived handle.
func fileMD5(p string) (string, error) {
	f, err := os.Open(p)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := md5.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

// writeAtomic writes the content of r to target through a temporary in the same folder, synced to
// disk, then renamed over target. Returns the MD5 of what was written.
func writeAtomic(target string, r io.Reader) (string, error) {
	dir := filepath.Dir(target)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", err
	}
	tmp := filepath.Join(dir, tempName(filepath.Base(target)))
	f, err := os.OpenFile(tmp, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o644)
	if err != nil {
		return "", err
	}
	h := md5.New()
	_, err = io.Copy(io.MultiWriter(f, h), r)
	if err == nil {
		err = f.Sync()
	}
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	if err == nil {
		err = os.Rename(tmp, target)
	}
	if err != nil {
		_ = os.Remove(tmp)
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

// localTree is what the local folder holds, as paths relative to its root with '/' separators.
type localTree struct {
	Files     map[string]localFile
	Dirs      map[string]bool
	StaleTemp []string // temporaries older than an hour: leftovers of killed writes
}

// listLocal walks root. Any unreadable entry fails the listing: a file missing from it would be deleted remotely.
func listLocal(root string, include func(rel string, dir bool) bool, known map[string]FileState) (*localTree, error) {
	t := &localTree{Files: map[string]localFile{}, Dirs: map[string]bool{}}
	cutoff := time.Now().Add(-time.Hour)
	err := filepath.WalkDir(root, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return fmt.Errorf("lecture locale impossible : %w", err)
		}
		if p == root {
			return nil
		}
		rel := filepath.ToSlash(strings.TrimPrefix(p, root+string(filepath.Separator)))
		if d.Type()&fs.ModeSymlink != 0 {
			return nil
		}
		if d.IsDir() {
			if !include(rel, true) {
				return filepath.SkipDir
			}
			t.Dirs[rel] = true
			return nil
		}
		if !d.Type().IsRegular() {
			return nil
		}
		info, err := d.Info()
		if err != nil {
			return fmt.Errorf("lecture locale impossible : %w", err)
		}
		if IsTemp(d.Name()) {
			if info.ModTime().Before(cutoff) {
				t.StaleTemp = append(t.StaleTemp, rel)
			}
			return nil
		}
		if !include(rel, false) {
			return nil
		}
		lf := localFile{Size: info.Size(), Mtime: info.ModTime().UnixNano()}
		if k, ok := known[rel]; ok && k.Size == lf.Size && k.Mtime == lf.Mtime && k.Mtime != 0 {
			lf.Hash = k.Hash
		} else if lf.Hash, err = fileMD5(p); err != nil {
			return fmt.Errorf("lecture locale impossible : %w", err)
		}
		t.Files[rel] = lf
		return nil
	})
	if err != nil {
		return nil, err
	}
	return t, nil
}

// statLocal reads the size and time of a file just written, to recognise it unchanged next time.
// A file modified less than 2 s ago could change again within the clock's resolution without a new
// time: its time is not trusted and it will be hashed again.
func statLocal(p string) (size, mtime int64) {
	info, err := os.Stat(p)
	if err != nil {
		return 0, 0
	}
	if time.Since(info.ModTime()) < 2*time.Second {
		return info.Size(), 0
	}
	return info.Size(), info.ModTime().UnixNano()
}

func localPath(root, rel string) string {
	return filepath.Join(root, filepath.FromSlash(path.Clean(rel)))
}
