package davsync

import (
	"bytes"
	"context"
	"crypto/md5"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/rclone/rclone/fs"
	"github.com/rclone/rclone/fs/accounting"
	"github.com/rclone/rclone/fs/filter"
)

// FileState is a file as it was after the last successful sync: Hash is the MD5 of the content both
// sides had (so the server's ETag then); Size and Mtime are the local file's, to skip hashing it again.
type FileState struct {
	Hash  string `json:"h"`
	Size  int64  `json:"s"`
	Mtime int64  `json:"m"`
}

// State is persisted per pair between syncs.
type State struct {
	Version int                  `json:"v"`
	Files   map[string]FileState `json:"files"`
	Dirs    map[string]bool      `json:"dirs"`
	// TooLarge holds files the server refused (413), with the local hash refused: not retried until they change.
	TooLarge map[string]string `json:"tooLarge,omitempty"`
}

func loadState(p string) (*State, string) {
	s := &State{Version: 1, Files: map[string]FileState{}, Dirs: map[string]bool{}, TooLarge: map[string]string{}}
	b, err := os.ReadFile(p)
	if errors.Is(err, os.ErrNotExist) {
		return s, ""
	}
	loaded := &State{}
	if err == nil {
		err = json.Unmarshal(b, loaded)
	}
	if err != nil {
		// An empty state never deletes anything: the next sync compares both sides by content.
		return s, "NOTICE: état de synchro illisible, reconstruit par comparaison des contenus"
	}
	if loaded.Files != nil {
		s.Files = loaded.Files
	}
	if loaded.Dirs != nil {
		s.Dirs = loaded.Dirs
	}
	if loaded.TooLarge != nil {
		s.TooLarge = loaded.TooLarge
	}
	return s, ""
}

func (s *State) save(p string) error {
	b, err := json.Marshal(s)
	if err != nil {
		return err
	}
	_, err = writeAtomic(p, bytes.NewReader(b))
	return err
}

// Options of one sync.
type Options struct {
	Local     string   `json:"local"`
	Remote    string   `json:"remote"`    // folder on the server, relative to its root
	StateFile string   `json:"stateFile"` // private to the app
	Mode      string   `json:"mode"`      // "bisync", "upload" or "download"
	Conflict  string   `json:"conflict"`  // "none", "newer", "path1" (phone wins), "path2" (server wins)
	Excludes  []string `json:"excludes"`  // rclone filter patterns
	MaxDelete int      `json:"maxDelete"` // percent of the known files
	Force     bool     `json:"force"`     // the user confirmed going over MaxDelete
	// AllowEmpty mirrors a side the user deliberately emptied.
	AllowEmpty bool `json:"allowEmpty"`
}

// Summary counts what a sync would do, for the first-sync preview.
type Summary struct {
	ToUpload   int `json:"toUpload"`
	ToDownload int `json:"toDownload"`
	Different  int `json:"different"`
	Identical  int `json:"identical"`
}

type opKind int

const (
	opUpload opKind = iota
	opDownload
	opDeleteLocal
	opDeleteRemote
	opConflict
	opSetBase
	opDropBase
)

type op struct {
	kind opKind
	path string
}

type syncer struct {
	ctx    context.Context
	c      *Client
	opt    Options
	flt    *filter.Filter
	state  *State
	local  *localTree
	remote map[string]Entry
	rdirs  map[string]bool
	stale  []string
	log    strings.Builder
	errs   int
	stats  *accounting.StatsInfo
}

func (s *syncer) logf(level, p, format string, args ...any) {
	fmt.Fprintf(&s.log, "%s %-6s: %s: %s\n", time.Now().Format("2006/01/02 15:04:05"), level, p, fmt.Sprintf(format, args...))
}

func (s *syncer) fail(p string, err error) {
	s.errs++
	s.logf("ERROR", p, "%v", err)
}

func newSyncer(ctx context.Context, c *Client, opt Options) (*syncer, error) {
	fo := filter.Options{MinAge: fs.DurationOff, MaxAge: fs.DurationOff, MinSize: -1, MaxSize: -1}
	fo.ExcludeRule = opt.Excludes
	flt, err := filter.NewFilter(&fo)
	if err != nil {
		return nil, fmt.Errorf("exclusion invalide : %w", err)
	}
	if opt.Mode == "" {
		opt.Mode = "bisync"
	}
	opt.Remote = strings.Trim(opt.Remote, "/")
	opt.Local = filepath.Clean(opt.Local)
	return &syncer{ctx: ctx, c: c, opt: opt, flt: flt, stats: accounting.Stats(ctx)}, nil
}

func (s *syncer) include(rel string, dir bool) bool {
	if dir {
		ok, _ := s.flt.IncludeDirectory(s.ctx, nil)(rel)
		return ok
	}
	return s.flt.IncludeRemote(rel)
}

func (s *syncer) rpath(rel string) string { return join(s.opt.Remote, rel) }

// load reads the state and both listings.
func (s *syncer) load(create bool) error {
	var notice string
	s.state, notice = loadState(s.opt.StateFile)
	if notice != "" {
		s.logf("NOTICE", "", "%s", notice)
	}
	if st, err := os.Stat(s.opt.Local); err != nil || !st.IsDir() {
		return fmt.Errorf("dossier local introuvable : %s", s.opt.Local)
	}
	var err error
	if s.local, err = listLocal(s.opt.Local, s.include, s.state.Files); err != nil {
		return err
	}
	return s.listRemote(create)
}

// listRemote walks the server folder one level at a time (Depth: infinity times out behind Cloudflare).
func (s *syncer) listRemote(create bool) error {
	s.remote, s.rdirs = map[string]Entry{}, map[string]bool{}
	if create {
		if err := s.c.MkdirAll(s.ctx, s.opt.Remote); err != nil {
			return err
		}
	}
	cutoff := time.Now().Add(-time.Hour)
	queue := []string{""}
	for len(queue) > 0 {
		dir := queue[0]
		queue = queue[1:]
		entries, found, err := s.c.List(s.ctx, s.rpath(dir))
		if err != nil {
			return err
		}
		if !found {
			if dir == "" && !create {
				return nil
			}
			if dir == "" {
				return &Error{Kind: KindProtocol, Msg: "dossier introuvable sur le serveur : /" + s.opt.Remote}
			}
			continue // removed while listing
		}
		for _, e := range entries {
			rel := strings.Trim(strings.TrimPrefix(e.Path, s.opt.Remote), "/")
			if s.opt.Remote == "" {
				rel = e.Path
			}
			if rel == "" || strings.HasPrefix(rel, "../") {
				continue
			}
			if e.IsDir {
				if s.include(rel, true) {
					s.rdirs[rel] = true
					queue = append(queue, rel)
				}
				continue
			}
			if IsTemp(path.Base(rel)) {
				if !e.Modified.IsZero() && e.Modified.Before(cutoff) {
					s.stale = append(s.stale, rel)
				}
				continue
			}
			if !s.include(rel, false) {
				continue
			}
			if len(e.ETag) != 32 {
				return &Error{Kind: KindProtocol, Msg: "le serveur ne donne pas le MD5 des fichiers (ETag) : il doit tourner avec --etag-hash MD5"}
			}
			e.Path = rel
			s.remote[rel] = e
		}
	}
	return nil
}

// plan decides what to do with every file, from the table of the spec.
func (s *syncer) plan() []op {
	paths := map[string]bool{}
	for p := range s.local.Files {
		paths[p] = true
	}
	for p := range s.remote {
		paths[p] = true
	}
	for p := range s.state.Files {
		paths[p] = true
	}
	sorted := make([]string, 0, len(paths))
	for p := range paths {
		sorted = append(sorted, p)
	}
	sort.Strings(sorted)

	var ops []op
	add := func(k opKind, p string) { ops = append(ops, op{k, p}) }
	for _, p := range sorted {
		l, hasL := s.local.Files[p]
		r, hasR := s.remote[p]
		b, hasB := s.state.Files[p]
		switch s.opt.Mode {
		case "upload":
			switch {
			case !hasL:
				if hasB {
					add(opDropBase, p)
				}
			case !hasR || r.ETag != l.Hash:
				add(opUpload, p)
			case !hasB || b.Hash != l.Hash || b.Mtime != l.Mtime:
				add(opSetBase, p)
			}
			continue
		case "download":
			switch {
			case !hasR:
				if hasB {
					add(opDropBase, p)
				}
			case !hasL || r.ETag != l.Hash:
				add(opDownload, p)
			case !hasB || b.Hash != l.Hash || b.Mtime != l.Mtime:
				add(opSetBase, p)
			}
			continue
		}
		switch {
		case !hasL && !hasR:
			if hasB {
				add(opDropBase, p)
			}
		case !hasB:
			switch {
			case hasL && hasR && l.Hash == r.ETag:
				add(opSetBase, p)
			case hasL && hasR:
				add(opConflict, p)
			case hasL:
				add(opUpload, p)
			default:
				add(opDownload, p)
			}
		default:
			localChanged := !hasL || l.Hash != b.Hash
			remoteChanged := !hasR || r.ETag != b.Hash
			switch {
			case !localChanged && !remoteChanged:
				if l.Mtime != b.Mtime || l.Size != b.Size {
					add(opSetBase, p) // same content, new time: remember it to skip hashing next time
				}
			case localChanged && !remoteChanged:
				if hasL {
					add(opUpload, p)
				} else {
					add(opDeleteRemote, p)
				}
			case !localChanged && remoteChanged:
				if hasR {
					add(opDownload, p)
				} else {
					add(opDeleteLocal, p)
				}
			case !hasL: // deleted here, modified there: the modification wins
				add(opDownload, p)
			case !hasR: // modified here, deleted there
				add(opUpload, p)
			case l.Hash == r.ETag:
				add(opSetBase, p)
			default:
				add(opConflict, p)
			}
		}
	}
	return ops
}

func (s *syncer) summary(ops []op) Summary {
	var sum Summary
	for _, o := range ops {
		switch o.kind {
		case opUpload:
			sum.ToUpload++
		case opDownload:
			sum.ToDownload++
		case opConflict:
			sum.Different++
		}
	}
	for p, l := range s.local.Files {
		if r, ok := s.remote[p]; ok && r.ETag == l.Hash {
			sum.Identical++
		}
	}
	return sum
}

// guard refuses syncs that would delete a lot, or mirror a side that is suddenly empty (unmounted
// storage, wrong folder), unless the user confirmed it. The messages are the ones bisync uses.
func (s *syncer) guard(ops []op) error {
	known := len(s.state.Files)
	if s.opt.Mode != "bisync" || known == 0 {
		return nil
	}
	if !s.opt.AllowEmpty {
		if len(s.local.Files) == 0 {
			return errors.New("Empty current Path1 : le dossier du téléphone est vide")
		}
		if len(s.remote) == 0 {
			return errors.New("Empty current Path2 : le dossier du serveur est vide")
		}
	}
	deletes := 0
	for _, o := range ops {
		if o.kind == opDeleteLocal || o.kind == opDeleteRemote {
			deletes++
		}
	}
	if !s.opt.Force && deletes > 0 && deletes*100 > s.opt.MaxDelete*known {
		return fmt.Errorf("Safety abort: too many deletes (%d sur %d fichiers)", deletes, known)
	}
	return nil
}

func (s *syncer) execute(ops []op) error {
	for _, o := range ops {
		if err := s.ctx.Err(); err != nil {
			return err
		}
		var err error
		switch o.kind {
		case opUpload:
			err = s.upload(o.path)
		case opDownload:
			err = s.download(o.path)
		case opDeleteLocal:
			err = s.deleteLocal(o.path)
		case opDeleteRemote:
			err = s.deleteRemote(o.path)
		case opConflict:
			err = s.conflict(o.path, s.local.Files[o.path], s.remote[o.path])
		case opSetBase:
			l := s.local.Files[o.path]
			s.state.Files[o.path] = remembered(l, l.Hash)
		case opDropBase:
			delete(s.state.Files, o.path)
		}
		if err != nil {
			if IsFatal(err) {
				return err
			}
			s.fail(o.path, err)
		}
	}
	return nil
}

// remembered is the state of a local file synced with content hash h. A file that changed while it
// was read (its hash is not the one listed) or too recently gets no trusted time: it is hashed again.
func remembered(l localFile, h string) FileState {
	if h != l.Hash || time.Since(time.Unix(0, l.Mtime)) < 2*time.Second {
		return FileState{Hash: h, Size: l.Size}
	}
	return FileState{Hash: h, Size: l.Size, Mtime: l.Mtime}
}

var errChanged = errors.New("modifié sur le serveur pendant la synchro")

// putFile uploads a local file to the server path rp through a temporary and a MOVE, after checking
// that the target still has the expected ETag ("" = must not exist). Returns the MD5 of what was sent.
// When the check fails the error is errChanged and cur is what the server has.
func (s *syncer) putFile(lp, rp, expect string, size int64) (sent string, cur *Entry, err error) {
	tmp := join(parent(rp), tempName(path.Base(rp)))
	tr := s.stats.NewTransferRemoteSize(rp, size, nil, nil)
	defer func() { tr.Done(s.ctx, err) }()
	h := md5.New()
	err = s.c.Put(s.ctx, tmp, func() (io.ReadCloser, int64, error) {
		f, err := os.Open(lp)
		if err != nil {
			return nil, 0, err
		}
		st, err := f.Stat()
		if err != nil {
			f.Close()
			return nil, 0, err
		}
		h.Reset()
		return readCloser{io.TeeReader(tr.Account(s.ctx, f), h), f}, st.Size(), nil
	})
	if err != nil {
		return "", nil, err
	}
	sent = hex.EncodeToString(h.Sum(nil))
	cur, err = s.c.Stat(s.ctx, rp)
	if err == nil && cur != nil && (cur.IsDir || cur.ETag != expect) {
		_ = s.c.Delete(s.ctx, tmp)
		return "", cur, errChanged
	}
	if err == nil {
		err = s.c.Move(s.ctx, tmp, rp, true)
	}
	if err != nil {
		_ = s.c.Delete(context.WithoutCancel(s.ctx), tmp)
		return "", nil, err
	}
	return sent, nil, nil
}

type readCloser struct {
	io.Reader
	c io.Closer
}

func (r readCloser) Close() error { return r.c.Close() }

func (s *syncer) upload(p string) error {
	l := s.local.Files[p]
	if s.state.TooLarge[p] == l.Hash {
		s.logf("NOTICE", p, "ignoré : refusé par le serveur car trop gros, tant qu'il ne change pas")
		return nil
	}
	expect := ""
	if r, ok := s.remote[p]; ok {
		expect = r.ETag
	}
	sent, cur, err := s.putFile(localPath(s.opt.Local, p), s.rpath(p), expect, l.Size)
	if kindOf(err) == KindTooLarge {
		s.state.TooLarge[p] = l.Hash
		return err
	}
	if errors.Is(err, errChanged) && s.opt.Mode == "bisync" {
		s.logf("NOTICE", p, "modifié des deux côtés pendant la synchro : conflit")
		return s.conflict(p, l, *cur)
	}
	if err != nil {
		return err
	}
	delete(s.state.TooLarge, p)
	s.state.Files[p] = remembered(l, sent)
	s.logf("INFO", p, "envoyé")
	return nil
}

// fetch downloads the server path rp to the local file lp. Just before replacing lp, unchanged must
// confirm the local file is still the one planned for (otherwise the download is dropped).
func (s *syncer) fetch(rp, lp string, size int64, unchanged func() bool) (string, error) {
	dir := filepath.Dir(lp)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", err
	}
	tmp := filepath.Join(dir, tempName(filepath.Base(lp)))
	f, err := os.OpenFile(tmp, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o644)
	if err != nil {
		return "", err
	}
	tr := s.stats.NewTransferRemoteSize(rp, size, nil, nil)
	h := md5.New()
	err = s.c.Get(s.ctx, rp, func(body io.ReadCloser) error {
		in := tr.Account(s.ctx, body)
		defer in.Close()
		_, err := io.Copy(io.MultiWriter(f, h), in)
		return err
	})
	tr.Done(s.ctx, err)
	if err == nil {
		err = f.Sync()
	}
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	if err == nil && !unchanged() {
		err = errors.New("modifié sur le téléphone pendant la synchro : traité à la prochaine")
	}
	if err == nil {
		err = os.Rename(tmp, lp)
	}
	if err != nil {
		_ = os.Remove(tmp)
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

// sameLocal tells whether the local file is still as listed (absent when it was absent).
func (s *syncer) sameLocal(p string) func() bool {
	l, had := s.local.Files[p]
	lp := localPath(s.opt.Local, p)
	return func() bool {
		st, err := os.Stat(lp)
		if !had {
			return errors.Is(err, os.ErrNotExist)
		}
		if err != nil {
			return false
		}
		if st.Size() == l.Size && st.ModTime().UnixNano() == l.Mtime {
			return true
		}
		h, err := fileMD5(lp)
		return err == nil && h == l.Hash
	}
}

func (s *syncer) download(p string) error {
	r := s.remote[p]
	lp := localPath(s.opt.Local, p)
	got, err := s.fetch(s.rpath(p), lp, r.Size, s.sameLocal(p))
	if err != nil {
		return err
	}
	size, mtime := statLocal(lp)
	s.state.Files[p] = FileState{Hash: got, Size: size, Mtime: mtime}
	s.logf("INFO", p, "reçu")
	return nil
}

func (s *syncer) deleteRemote(p string) error {
	b := s.state.Files[p]
	cur, err := s.c.Stat(s.ctx, s.rpath(p))
	if err != nil {
		return err
	}
	if cur != nil && cur.ETag != b.Hash {
		s.logf("NOTICE", p, "modifié sur le serveur pendant la synchro : récupéré au lieu d'être supprimé")
		s.remote[p] = *cur
		return s.download(p)
	}
	if cur != nil {
		if err := s.c.Delete(s.ctx, s.rpath(p)); err != nil {
			return err
		}
		_ = s.stats.DeleteFile(s.ctx, cur.Size)
	}
	delete(s.state.Files, p)
	s.logf("INFO", p, "supprimé du serveur")
	return nil
}

func (s *syncer) deleteLocal(p string) error {
	l := s.local.Files[p]
	if !s.sameLocal(p)() {
		return errors.New("modifié sur le téléphone pendant la synchro : traité à la prochaine")
	}
	if err := os.Remove(localPath(s.opt.Local, p)); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	_ = s.stats.DeleteFile(s.ctx, l.Size)
	delete(s.state.Files, p)
	s.logf("INFO", p, "supprimé du téléphone")
	return nil
}

// conflictName finds the first N such that name.conflictN … name.conflict(N+count-1) are free on both sides.
func (s *syncer) conflictName(p string, count int) int {
	taken := func(q string) bool {
		if _, ok := s.local.Files[q]; ok {
			return true
		}
		if _, ok := s.remote[q]; ok {
			return true
		}
		_, err := os.Lstat(localPath(s.opt.Local, q))
		return err == nil
	}
	for n := 1; ; n++ {
		free := true
		for k := n; k < n+count; k++ {
			if taken(p + ".conflict" + strconv.Itoa(k)) {
				free = false
				break
			}
		}
		if free {
			return n
		}
	}
}

// conflict keeps both versions, named like bisync does: with a winner only the loser becomes
// name.conflictN; without one the phone copy is name.conflictN and the server copy name.conflict(N+1).
func (s *syncer) conflict(p string, l localFile, r Entry) error {
	winner := ""
	switch s.opt.Conflict {
	case "path1":
		winner = "local"
	case "path2":
		winner = "remote"
	case "newer":
		winner = "remote"
		if r.Modified.IsZero() || l.Mtime > r.Modified.UnixNano() {
			winner = "local"
		}
	}
	count := 1
	if winner == "" {
		count = 2
	}
	n := s.conflictName(p, count)
	lp, rp := localPath(s.opt.Local, p), s.rpath(p)
	name := func(k int) string { return p + ".conflict" + strconv.Itoa(k) }

	// The phone copy moves aside, and is uploaded under its new name.
	movePhoneCopy := func(to string) error {
		if !s.sameLocal(p)() {
			return errors.New("modifié sur le téléphone pendant la synchro : traité à la prochaine")
		}
		if err := os.Rename(lp, localPath(s.opt.Local, to)); err != nil {
			return err
		}
		delete(s.local.Files, p)
		s.local.Files[to] = l
		sent, _, err := s.putFile(localPath(s.opt.Local, to), s.rpath(to), "", l.Size)
		if err != nil {
			return err
		}
		s.state.Files[to] = remembered(l, sent)
		return nil
	}
	// The server copy moves aside on the server, and is downloaded under its new name.
	moveServerCopy := func(to string) error {
		if err := s.c.Move(s.ctx, rp, s.rpath(to), false); err != nil {
			return err
		}
		delete(s.remote, p)
		r.Path = to
		s.remote[to] = r
		got, err := s.fetch(s.rpath(to), localPath(s.opt.Local, to), r.Size, func() bool { return true })
		if err != nil {
			return err
		}
		size, mtime := statLocal(localPath(s.opt.Local, to))
		s.state.Files[to] = FileState{Hash: got, Size: size, Mtime: mtime}
		return nil
	}

	var err error
	switch winner {
	case "local":
		if err = moveServerCopy(name(n)); err == nil {
			var sent string
			if sent, _, err = s.putFile(lp, rp, "", l.Size); err == nil {
				s.state.Files[p] = remembered(l, sent)
			}
		}
	case "remote":
		if err = movePhoneCopy(name(n)); err == nil {
			delete(s.local.Files, p)
			var got string
			if got, err = s.fetch(rp, lp, r.Size, func() bool { _, e := os.Lstat(lp); return errors.Is(e, os.ErrNotExist) }); err == nil {
				size, mtime := statLocal(lp)
				s.state.Files[p] = FileState{Hash: got, Size: size, Mtime: mtime}
			}
		}
	default:
		if err = movePhoneCopy(name(n)); err == nil {
			err = moveServerCopy(name(n + 1))
		}
		delete(s.state.Files, p)
	}
	if err != nil {
		return err
	}
	s.logf("NOTICE", p, "conflit : version écartée gardée en %s", name(n))
	return nil
}

// syncDirs mirrors folders (empty ones included). A folder that existed on both sides at the last
// sync and is gone from one side is removed from the other, but only when empty.
func (s *syncer) syncDirs() {
	if s.opt.Mode == "download" {
		for d := range s.rdirs {
			if !s.local.Dirs[d] {
				_ = os.MkdirAll(localPath(s.opt.Local, d), 0o755)
			}
		}
		return
	}
	var removeLocal, removeRemote []string
	for d := range s.local.Dirs {
		if s.rdirs[d] {
			continue
		}
		if s.opt.Mode == "bisync" && s.state.Dirs[d] {
			removeLocal = append(removeLocal, d)
		} else if err := s.c.MkdirAll(s.ctx, s.rpath(d)); err != nil {
			s.fail(d+"/", err)
		}
	}
	if s.opt.Mode == "bisync" {
		for d := range s.rdirs {
			if s.local.Dirs[d] {
				continue
			}
			if s.state.Dirs[d] {
				removeRemote = append(removeRemote, d)
			} else if err := os.MkdirAll(localPath(s.opt.Local, d), 0o755); err != nil {
				s.fail(d+"/", err)
			}
		}
	}
	// Deepest first, so that parents are empty when their turn comes.
	byDepth := func(ds []string) {
		sort.Slice(ds, func(i, j int) bool { return strings.Count(ds[i], "/") > strings.Count(ds[j], "/") })
	}
	byDepth(removeLocal)
	byDepth(removeRemote)
	for _, d := range removeLocal {
		if err := os.Remove(localPath(s.opt.Local, d)); err != nil {
			// Not empty (new files in it): keep it on both sides.
			_ = s.c.MkdirAll(s.ctx, s.rpath(d))
		} else {
			delete(s.local.Dirs, d)
		}
	}
	for _, d := range removeRemote {
		entries, found, err := s.c.List(s.ctx, s.rpath(d))
		if err != nil {
			s.fail(d+"/", err)
			continue
		}
		empty := true
		for _, e := range entries {
			if !IsTemp(path.Base(e.Path)) {
				empty = false
				break
			}
		}
		if found && empty {
			if err := s.c.Delete(s.ctx, s.rpath(d)); err != nil {
				s.fail(d+"/", err)
			} else {
				delete(s.rdirs, d)
			}
		} else if found {
			_ = os.MkdirAll(localPath(s.opt.Local, d), 0o755)
		}
	}
	dirs := map[string]bool{}
	for d := range s.local.Dirs {
		dirs[d] = true
	}
	for d := range s.rdirs {
		dirs[d] = true
	}
	for _, d := range removeLocal {
		delete(dirs, d)
	}
	for _, d := range removeRemote {
		delete(dirs, d)
	}
	s.state.Dirs = dirs
}

func (s *syncer) cleanTemps() {
	for _, p := range s.stale {
		if err := s.c.Delete(s.ctx, s.rpath(p)); err == nil {
			s.logf("INFO", p, "temporaire abandonné supprimé du serveur")
		}
	}
	for _, p := range s.local.StaleTemp {
		if err := os.Remove(localPath(s.opt.Local, p)); err == nil {
			s.logf("INFO", p, "temporaire abandonné supprimé du téléphone")
		}
	}
}

// Sync runs one sync. The returned log has rclone-like lines; it is returned even on error.
func Sync(ctx context.Context, c *Client, opt Options) (string, error) {
	s, err := newSyncer(ctx, c, opt)
	if err != nil {
		return "", err
	}
	if err := s.load(true); err != nil {
		return s.log.String(), err
	}
	ops := s.plan()
	if err := s.guard(ops); err != nil {
		return s.log.String(), err
	}
	err = s.execute(ops)
	if err == nil {
		s.syncDirs()
		s.cleanTemps()
	}
	if serr := s.state.save(s.opt.StateFile); serr != nil && err == nil {
		err = fmt.Errorf("état de synchro non enregistré : %w", serr)
	}
	if err == nil && s.errs > 0 {
		err = fmt.Errorf("%d fichier(s) en erreur", s.errs)
	}
	return s.log.String(), err
}

// Preview tells what the first sync would do, without changing anything.
func Preview(ctx context.Context, c *Client, opt Options) (Summary, error) {
	s, err := newSyncer(ctx, c, opt)
	if err != nil {
		return Summary{}, err
	}
	if err := s.load(false); err != nil {
		return Summary{}, err
	}
	return s.summary(s.plan()), nil
}
