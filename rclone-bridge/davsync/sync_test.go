package davsync

import (
	"context"
	"crypto/md5"
	"encoding/hex"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"
	"time"

	"golang.org/x/net/webdav"
)

// md5FS serves ETags that are the MD5 of the content, like rclone serve webdav --etag-hash MD5.
type md5FS struct{ webdav.Dir }

type md5Info struct {
	os.FileInfo
	path string
}

func (i md5Info) ETag(context.Context) (string, error) {
	h, err := fileMD5(i.path)
	return `"` + h + `"`, err
}

func (f md5FS) Stat(ctx context.Context, name string) (os.FileInfo, error) {
	fi, err := f.Dir.Stat(ctx, name)
	if err != nil || fi.IsDir() {
		return fi, err
	}
	return md5Info{fi, filepath.Join(string(f.Dir), filepath.FromSlash(name))}, nil
}

func (f md5FS) OpenFile(ctx context.Context, name string, flag int, perm os.FileMode) (webdav.File, error) {
	file, err := f.Dir.OpenFile(ctx, name, flag, perm)
	if err != nil {
		return nil, err
	}
	return md5File{file, filepath.Join(string(f.Dir), filepath.FromSlash(name))}, nil
}

type md5File struct {
	webdav.File
	path string
}

func (f md5File) Stat() (os.FileInfo, error) {
	fi, err := f.File.Stat()
	if err != nil || fi.IsDir() {
		return fi, err
	}
	return md5Info{fi, f.path}, nil
}

func (f md5File) Readdir(n int) ([]os.FileInfo, error) {
	fis, err := f.File.Readdir(n)
	for i, fi := range fis {
		if !fi.IsDir() {
			fis[i] = md5Info{fi, filepath.Join(f.path, fi.Name())}
		}
	}
	return fis, err
}

type server struct {
	*httptest.Server
	root  string
	reqs  []string
	fail  func(r *http.Request) int // non-zero: answer this status instead
	block bool                      // Cloudflare Access: needs the service token
}

func newServer(t *testing.T) *server {
	t.Helper()
	s := &server{root: t.TempDir()}
	h := &webdav.Handler{FileSystem: md5FS{webdav.Dir(s.root)}, LockSystem: webdav.NewMemLS()}
	s.Server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		s.reqs = append(s.reqs, r.Method+" "+r.URL.Path)
		if s.block && r.Header.Get("CF-Access-Client-Secret") != "secret" {
			http.Redirect(w, r, "https://team.cloudflareaccess.com/cdn-cgi/access/login", http.StatusFound)
			return
		}
		if u, p, ok := r.BasicAuth(); !ok || u != "fold6" || p != "pw" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		if s.fail != nil {
			if code := s.fail(r); code != 0 {
				w.WriteHeader(code)
				return
			}
		}
		h.ServeHTTP(w, r)
	}))
	t.Cleanup(s.Close)
	return s
}

func (s *server) cfg() Config {
	return Config{URL: s.URL + "/", User: "fold6", Password: "pw", CFID: "id.access", CFSecret: "secret"}
}

func (s *server) client(t *testing.T) *Client {
	c, err := NewClient(s.cfg())
	if err != nil {
		t.Fatal(err)
	}
	c.retries = nil
	return c
}

type pair struct {
	t      *testing.T
	srv    *server
	local  string
	state  string
	remote string
	opt    Options
}

func newPair(t *testing.T) *pair {
	srv := newServer(t)
	root := t.TempDir()
	p := &pair{t: t, srv: srv, local: filepath.Join(root, "phone"), state: filepath.Join(root, "state.json"), remote: "Apps/Notes"}
	_ = os.MkdirAll(p.local, 0o755)
	p.opt = Options{Local: p.local, Remote: p.remote, StateFile: p.state, Mode: "bisync", Conflict: "none",
		Excludes: []string{"*.tmp", "cache/"}, MaxDelete: 50}
	return p
}

func (p *pair) sync() string {
	p.t.Helper()
	log, err := Sync(context.Background(), p.srv.client(p.t), p.opt)
	if err != nil {
		p.t.Fatalf("sync: %v\n%s", err, log)
	}
	return log
}

func (p *pair) syncErr() error {
	_, err := Sync(context.Background(), p.srv.client(p.t), p.opt)
	return err
}

func put(t *testing.T, p, s string) {
	t.Helper()
	_ = os.MkdirAll(filepath.Dir(p), 0o755)
	if err := os.WriteFile(p, []byte(s), 0o644); err != nil {
		t.Fatal(err)
	}
	// Different content with the same size and second must still be seen: move the time on.
	future := time.Now().Add(time.Duration(len(s)+1) * time.Second)
	_ = os.Chtimes(p, future, future)
}

func (p *pair) phone(rel string) string  { return filepath.Join(p.local, filepath.FromSlash(rel)) }
func (p *pair) server(rel string) string { return filepath.Join(p.srv.root, "Apps", "Notes", filepath.FromSlash(rel)) }

func read(p string) string {
	b, err := os.ReadFile(p)
	if err != nil {
		return "<absent>"
	}
	return string(b)
}

func files(root string) []string {
	var out []string
	_ = filepath.Walk(root, func(p string, info os.FileInfo, err error) error {
		if err == nil && !info.IsDir() {
			rel, _ := filepath.Rel(root, p)
			out = append(out, filepath.ToSlash(rel))
		}
		return nil
	})
	sort.Strings(out)
	return out
}

func TestConnection(t *testing.T) {
	srv := newServer(t)
	srv.block = true
	ctx := context.Background()
	if err := srv.client(t).Test(ctx); err != nil {
		t.Fatalf("good credentials: %v", err)
	}
	bad := srv.cfg()
	bad.Password = "nope"
	c, _ := NewClient(bad)
	if err := c.Test(ctx); kindOf(err) != KindAuth {
		t.Fatalf("wrong password: want auth error, got %v", err)
	}
	bad = srv.cfg()
	bad.CFSecret = "nope"
	c, _ = NewClient(bad)
	if err := c.Test(ctx); kindOf(err) != KindAccess || !strings.Contains(err.Error(), "token Cloudflare") {
		t.Fatalf("wrong token: want access error, got %v", err)
	}
	srv.fail = func(*http.Request) int { return 530 }
	if err := srv.client(t).Test(ctx); kindOf(err) != KindOffline {
		t.Fatalf("NAS off: want offline, got %v", err)
	}
	c, _ = NewClient(Config{URL: "http://127.0.0.1:1/", User: "u", Password: "p"})
	if err := c.Test(ctx); kindOf(err) != KindOffline {
		t.Fatalf("unreachable: want offline, got %v", err)
	}
}

func TestLanAddressPreferredWhenReachable(t *testing.T) {
	srv := newServer(t)
	cfg := srv.cfg()
	cfg.URL = "https://sync.invalid/"
	cfg.LanURL = srv.URL
	c, err := Connect(context.Background(), cfg)
	if err != nil || !strings.HasPrefix(srv.URL, "http://"+c.Host()) {
		t.Fatalf("want LAN host, got %v %v", c, err)
	}
	cfg.LanURL = "http://127.0.0.1:1"
	c, _ = Connect(context.Background(), cfg)
	if c.Host() != "sync.invalid" {
		t.Fatalf("want main host when LAN is down, got %s", c.Host())
	}
}

func TestFirstSyncMergesWithoutDeleting(t *testing.T) {
	p := newPair(t)
	put(t, p.phone("same.json"), `{"a":1}`)
	put(t, p.phone("phone-only.md"), "phone")
	put(t, p.phone("notes/différent #1 50%.md"), "phone version")
	put(t, p.phone("skip.tmp"), "temp")
	put(t, p.phone("cache/big.bin"), "cache")
	put(t, p.server("same.json"), `{"a":1}`)
	put(t, p.server("server-only.md"), "server")
	put(t, p.server("notes/différent #1 50%.md"), "server version")

	sum, err := Preview(context.Background(), p.srv.client(t), p.opt)
	if err != nil {
		t.Fatal(err)
	}
	if sum != (Summary{ToUpload: 1, ToDownload: 1, Different: 1, Identical: 1}) {
		t.Fatalf("preview: %+v", sum)
	}
	p.srv.reqs = nil
	p.sync()
	for _, r := range p.srv.reqs {
		if strings.HasPrefix(r, "GET") && strings.Contains(r, "same.json") {
			t.Fatal("identical file downloaded again")
		}
	}
	if read(p.phone("server-only.md")) != "server" || read(p.server("phone-only.md")) != "phone" {
		t.Fatal("one-sided files not copied")
	}
	if read(p.server("skip.tmp")) != "<absent>" || read(p.server("cache/big.bin")) != "<absent>" {
		t.Fatal("excluded files uploaded")
	}
	want := []string{"cache/big.bin", "notes/différent #1 50%.md.conflict1", "notes/différent #1 50%.md.conflict2", "phone-only.md", "same.json", "server-only.md", "skip.tmp"}
	if got := files(p.local); strings.Join(got, "|") != strings.Join(want, "|") {
		t.Fatalf("phone files: %v", got)
	}
	if read(p.phone("notes/différent #1 50%.md.conflict1")) != "phone version" ||
		read(p.server("notes/différent #1 50%.md.conflict2")) != "server version" {
		t.Fatal("conflict copies wrong")
	}
	// Second run: nothing to do.
	p.srv.reqs = nil
	p.sync()
	for _, r := range p.srv.reqs {
		if !strings.HasPrefix(r, "PROPFIND") {
			t.Fatalf("second sync did %s", r)
		}
	}
}

func TestChangesBothWays(t *testing.T) {
	p := newPair(t)
	put(t, p.phone("a.md"), "a1")
	put(t, p.phone("b.md"), "b1")
	put(t, p.phone("c.md"), "c1")
	put(t, p.phone("d.md"), "d1")
	put(t, p.phone("sub/e.md"), "e1")
	p.sync()

	put(t, p.phone("a.md"), "a2 from phone")   // local edit → upload
	put(t, p.server("b.md"), "b2 from the PC") // edit on the NAS over SMB → download
	_ = os.Remove(p.phone("c.md"))             // local delete → remote delete
	_ = os.Remove(p.server("d.md"))            // remote delete → local delete
	p.sync()
	if read(p.server("a.md")) != "a2 from phone" || read(p.phone("b.md")) != "b2 from the PC" {
		t.Fatal("edits not propagated")
	}
	if read(p.server("c.md")) != "<absent>" || read(p.phone("d.md")) != "<absent>" {
		t.Fatal("deletes not propagated")
	}
	// A deleted folder goes away on the other side too.
	_ = os.RemoveAll(p.phone("sub"))
	p.sync()
	if _, err := os.Stat(p.server("sub")); !os.IsNotExist(err) {
		t.Fatal("deleted folder still on the server")
	}

	// Deleted here, modified there: the modification wins.
	_ = os.Remove(p.phone("a.md"))
	put(t, p.server("a.md"), "a3 newer")
	p.sync()
	if read(p.phone("a.md")) != "a3 newer" {
		t.Fatal("modification should win over deletion")
	}
	// Same change on both sides: no conflict.
	put(t, p.phone("b.md"), "same")
	put(t, p.server("b.md"), "same")
	p.sync()
	if got := files(p.local); strings.Contains(strings.Join(got, "|"), "conflict") {
		t.Fatalf("identical changes made a conflict: %v", got)
	}
}

func TestConflictPolicies(t *testing.T) {
	for _, tc := range []struct{ policy, keep, loser string }{
		{"path1", "phone", "server"},
		{"path2", "server", "phone"},
		{"newer", "server", "phone"}, // the server copy is written last
	} {
		t.Run(tc.policy, func(t *testing.T) {
			p := newPair(t)
			p.opt.Conflict = tc.policy
			put(t, p.phone("n.md"), "base")
			p.sync()
			put(t, p.phone("n.md"), "phone")
			time.Sleep(10 * time.Millisecond)
			put(t, p.server("n.md"), "server")
			future := time.Now().Add(time.Hour)
			_ = os.Chtimes(p.server("n.md"), future, future)
			p.sync()
			for _, side := range []func(string) string{p.phone, p.server} {
				if read(side("n.md")) != tc.keep || read(side("n.md.conflict1")) != tc.loser {
					t.Fatalf("%s: got %q / %q", side("n.md"), read(side("n.md")), read(side("n.md.conflict1")))
				}
			}
		})
	}
}

func TestSafeguards(t *testing.T) {
	p := newPair(t)
	for _, n := range []string{"1", "2", "3", "4"} {
		put(t, p.phone(n+".md"), n)
	}
	p.sync()
	for _, n := range []string{"1", "2", "3"} {
		_ = os.Remove(p.phone(n + ".md"))
	}
	if err := p.syncErr(); err == nil || !strings.Contains(err.Error(), "too many deletes") {
		t.Fatalf("want delete safeguard, got %v", err)
	}
	if len(files(filepath.Join(p.srv.root, "Apps", "Notes"))) != 4 {
		t.Fatal("safeguard deleted files")
	}
	_ = os.Remove(p.phone("4.md"))
	if err := p.syncErr(); err == nil || !strings.Contains(err.Error(), "Empty current Path1") {
		t.Fatalf("want empty-folder safeguard, got %v", err)
	}
	p.opt.Force, p.opt.AllowEmpty = true, true
	p.sync()
	if n := len(files(filepath.Join(p.srv.root, "Apps", "Notes"))); n != 0 {
		t.Fatalf("confirmed wipe left %d files", n)
	}
}

func TestAtomicUploadAndServerErrors(t *testing.T) {
	p := newPair(t)
	put(t, p.phone("ok.md"), "fine")
	put(t, p.phone("huge.bin"), "pretend this is 200 MB")
	p.srv.fail = func(r *http.Request) int {
		if r.Method == "PUT" && strings.Contains(r.URL.Path, "huge.bin") {
			return http.StatusRequestEntityTooLarge
		}
		return 0
	}
	if err := p.syncErr(); err == nil {
		t.Fatal("a refused file must be reported")
	}
	if read(p.server("ok.md")) != "fine" {
		t.Fatal("the other files must go on")
	}
	for _, r := range p.srv.reqs {
		if strings.HasPrefix(r, "PUT") && !strings.Contains(r, ".synctmp-") {
			t.Fatalf("upload not through a temporary: %s", r)
		}
	}
	// Not retried while unchanged, so the pair is not stuck in error.
	p.srv.reqs = nil
	p.sync()
	for _, r := range p.srv.reqs {
		if strings.HasPrefix(r, "PUT") {
			t.Fatal("too large file retried")
		}
	}

	// An upload cut short leaves no partial file under the real name; stale temporaries are cleaned.
	put(t, p.phone("ok.md"), "fine, edited")
	p.srv.fail = func(r *http.Request) int {
		if r.Method == "MOVE" {
			return http.StatusBadGateway
		}
		return 0
	}
	if err := p.syncErr(); kindOf(err) != KindOffline {
		t.Fatalf("want offline, got %v", err)
	}
	if read(p.server("ok.md")) != "fine" {
		t.Fatal("interrupted upload changed the file")
	}
	stale := p.server(".ok.md.synctmp-dead")
	put(t, stale, "partial")
	old := time.Now().Add(-2 * time.Hour)
	_ = os.Chtimes(stale, old, old)
	p.srv.fail = nil
	p.sync()
	if read(stale) != "<absent>" || read(p.server("ok.md")) != "fine, edited" {
		t.Fatal("temporary not cleaned or file not uploaded")
	}
	for _, f := range files(filepath.Join(p.srv.root, "Apps", "Notes")) {
		if IsTemp(filepath.Base(f)) {
			t.Fatalf("temporary left: %s", f)
		}
	}
}

func TestGuardAgainstOverwrite(t *testing.T) {
	p := newPair(t)
	put(t, p.phone("n.md"), "base")
	p.sync()
	put(t, p.phone("n.md"), "phone edit")
	// The PC writes the file between our listing and our upload.
	p.srv.fail = func(r *http.Request) int {
		if r.Method == "PUT" {
			put(t, p.server("n.md"), "pc edit")
			p.srv.fail = nil
		}
		return 0
	}
	p.sync()
	all := read(p.phone("n.md.conflict1")) + "|" + read(p.phone("n.md.conflict2"))
	if !strings.Contains(all, "phone edit") || !strings.Contains(all, "pc edit") {
		t.Fatalf("an edit was lost: %v %s", files(p.local), all)
	}
}

func TestUploadAndDownloadOnly(t *testing.T) {
	p := newPair(t)
	p.opt.Mode = "upload"
	put(t, p.phone("a.md"), "a")
	put(t, p.server("remote.md"), "r")
	p.sync()
	_ = os.Remove(p.phone("a.md"))
	p.sync()
	if read(p.server("a.md")) != "a" || read(p.phone("remote.md")) != "<absent>" {
		t.Fatal("upload only must not delete nor download")
	}
	p.opt.Mode = "download"
	put(t, p.phone("local.md"), "l")
	p.sync()
	if read(p.phone("remote.md")) != "r" || read(p.server("local.md")) != "<absent>" {
		t.Fatal("download only")
	}
}

func TestEncodedNamesAndHref(t *testing.T) {
	c, _ := NewClient(Config{URL: "https://h.example/dav"})
	if got := c.url("Apps/a b#1%é.md"); got != "https://h.example/dav/Apps/a%20b%231%25%C3%A9.md" {
		t.Fatal(got)
	}
	for href, want := range map[string]string{
		"/dav/Apps/a%20b%231%25%C3%A9.md":              "Apps/a b#1%é.md",
		"https://h.example/dav/Apps/Dossier%20%C3%A9/": "Apps/Dossier é",
		"/dav/": "",
	} {
		if got, err := c.relPath(href); err != nil || got != want {
			t.Fatalf("%s: %q %v", href, got, err)
		}
	}
}

func TestStateSurvivesCorruption(t *testing.T) {
	p := newPair(t)
	put(t, p.phone("a.md"), "a")
	p.sync()
	_ = os.WriteFile(p.state, []byte("{garbage"), 0o644)
	_ = os.Remove(p.phone("a.md"))
	log := p.sync()
	// Without a state nothing is known as deleted: the file comes back instead of being deleted remotely.
	if read(p.phone("a.md")) != "a" || !strings.Contains(log, "illisible") {
		t.Fatalf("corrupt state must merge, not delete:\n%s", log)
	}
	h := md5.Sum([]byte("a"))
	b, _ := os.ReadFile(p.state)
	if !strings.Contains(string(b), hex.EncodeToString(h[:])) {
		t.Fatal("state not rebuilt")
	}
}
