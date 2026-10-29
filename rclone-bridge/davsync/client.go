// Package davsync syncs a local folder with a folder of the Synchrotron server, a WebDAV server
// (rclone serve webdav --etag-hash MD5) behind Cloudflare Access.
//
// The server is not the only writer (PCs write to the same files over SMB), has no conditional PUT
// and does not keep client modification times: changes are detected by comparing MD5 hashes (the
// server's ETag) with the hash recorded at the last successful sync.
package davsync

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"
)

// Config is how to reach the server. Secrets are never logged.
type Config struct {
	URL      string `json:"url"`
	User     string `json:"user"`
	Password string `json:"password"`
	CFID     string `json:"cfId"`
	CFSecret string `json:"cfSecret"`
	// LanURL, when set and reachable, is used instead of URL (at home, without Cloudflare).
	LanURL string `json:"lanUrl"`
}

// Entry is a file or folder on the server. Path is relative to the server root, decoded, without
// leading or trailing slash.
type Entry struct {
	Path     string
	IsDir    bool
	Size     int64
	Modified time.Time
	ETag     string // MD5 hex, lower case, without quotes
}

// Kind tells the app how to react to an error.
type Kind string

const (
	KindAuth      Kind = "auth"      // 401: wrong WebDAV user or password
	KindAccess    Kind = "access"    // 403, redirect to Cloudflare Access, HTML page: bad service token or bot challenge
	KindOffline   Kind = "offline"   // Cloudflare cannot reach the NAS, or the server cannot be reached at all
	KindTooLarge  Kind = "toolarge"  // 413: body over Cloudflare's per-request limit
	KindTransient Kind = "transient" // other 5xx, timeouts
	KindProtocol  Kind = "protocol"  // unexpected answer
)

// Error is a failed request. Its message starts with the kind in brackets, which the app parses.
type Error struct {
	Kind   Kind
	Status int
	Msg    string
}

func (e *Error) Error() string { return fmt.Sprintf("[%s] %s", e.Kind, e.Msg) }

func kindOf(err error) Kind {
	var e *Error
	if errors.As(err, &e) {
		return e.Kind
	}
	return ""
}

// IsFatal errors stop the whole sync: every later request would fail the same way.
func IsFatal(err error) bool {
	switch kindOf(err) {
	case KindAuth, KindAccess, KindOffline, KindTransient:
		return true
	}
	return errors.Is(err, context.Canceled)
}

// Client talks to the server. Basic auth and the Cloudflare headers go with every request;
// redirects are never followed (one means Cloudflare Access refused the token).
type Client struct {
	cfg      Config
	base     *url.URL
	http     *http.Client
	retries  []time.Duration
	authLine string
}

const propfindBody = `<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:getetag/></d:prop></d:propfind>`

// NewClient checks the configuration. It does not contact the server.
func NewClient(cfg Config) (*Client, error) {
	c, err := newClient(cfg, cfg.URL, 15*time.Second)
	if err != nil {
		return nil, err
	}
	return c, nil
}

func newClient(cfg Config, raw string, connect time.Duration) (*Client, error) {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || (u.Scheme != "https" && u.Scheme != "http") || u.Host == "" {
		return nil, &Error{Kind: KindProtocol, Msg: "adresse du serveur invalide"}
	}
	if !strings.HasSuffix(u.Path, "/") {
		u.Path += "/"
	}
	u.RawPath = ""
	transport := &http.Transport{
		Proxy:                 http.ProxyFromEnvironment,
		DialContext:           (&net.Dialer{Timeout: connect, KeepAlive: 30 * time.Second}).DialContext,
		TLSHandshakeTimeout:   connect,
		ResponseHeaderTimeout: 60 * time.Second, // Cloudflare cuts requests at about 100 s
		IdleConnTimeout:       90 * time.Second,
		MaxIdleConnsPerHost:   4,
	}
	return &Client{
		cfg:  cfg,
		base: u,
		http: &http.Client{
			Transport:     transport,
			CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
		},
		retries:  []time.Duration{2 * time.Second, 6 * time.Second},
		authLine: "Basic " + base64.StdEncoding.EncodeToString([]byte(cfg.User+":"+cfg.Password)),
	}, nil
}

// Connect returns a client for the LAN address when one is set and answers quickly, else for the main one.
func Connect(ctx context.Context, cfg Config) (*Client, error) {
	if strings.TrimSpace(cfg.LanURL) != "" {
		if lan, err := newClient(cfg, cfg.LanURL, 2*time.Second); err == nil {
			lan.retries = nil
			probe, cancel := context.WithTimeout(ctx, 3*time.Second)
			_, err = lan.Stat(probe, "")
			cancel()
			if err == nil {
				lan.retries = []time.Duration{2 * time.Second, 6 * time.Second}
				return lan, nil
			}
		}
	}
	return NewClient(cfg)
}

// Host is the server actually used, for logs.
func (c *Client) Host() string { return c.base.Host }

// URL of a path relative to the server root; every segment is percent-encoded.
func (c *Client) url(p string) string {
	segs := strings.Split(strings.Trim(p, "/"), "/")
	for i, s := range segs {
		segs[i] = url.PathEscape(s)
	}
	u := *c.base
	u.Path = ""
	u.RawPath = ""
	return u.String() + escapeBase(c.base.Path) + strings.Join(segs, "/")
}

func escapeBase(p string) string {
	segs := strings.Split(p, "/")
	for i, s := range segs {
		segs[i] = url.PathEscape(s)
	}
	return strings.Join(segs, "/")
}

type body func() (io.ReadCloser, int64, error)

// do sends a request, retrying transient failures. ok lists the statuses that are not errors.
func (c *Client) do(ctx context.Context, method, p string, hdr map[string]string, b body, ok ...int) (*http.Response, error) {
	var last error
	for attempt := 0; ; attempt++ {
		resp, err := c.once(ctx, method, p, hdr, b, ok)
		if err == nil {
			return resp, nil
		}
		last = err
		if kindOf(err) != KindTransient || attempt >= len(c.retries) {
			return nil, last
		}
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(c.retries[attempt]):
		}
	}
}

func (c *Client) once(ctx context.Context, method, p string, hdr map[string]string, b body, ok []int) (*http.Response, error) {
	var rd io.ReadCloser
	var size int64 = -1
	if b != nil {
		var err error
		if rd, size, err = b(); err != nil {
			return nil, err
		}
	}
	req, err := http.NewRequestWithContext(ctx, method, c.url(p), rd)
	if err != nil {
		if rd != nil {
			rd.Close()
		}
		return nil, err
	}
	if size >= 0 {
		req.ContentLength = size
	}
	req.Header.Set("Authorization", c.authLine)
	if c.cfg.CFID != "" {
		req.Header.Set("CF-Access-Client-Id", c.cfg.CFID)
		req.Header.Set("CF-Access-Client-Secret", c.cfg.CFSecret)
	}
	req.Header.Set("User-Agent", "Synchrotron")
	for k, v := range hdr {
		req.Header.Set(k, v)
	}
	resp, err := c.http.Do(req)
	if err != nil {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, netError(err)
	}
	for _, s := range ok {
		if resp.StatusCode == s {
			return resp, nil
		}
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 64<<10))
	return nil, statusError(method, p, c.cfg.User, fingerprint(c.cfg.Password), resp)
}

func netError(err error) error {
	var ne net.Error
	if errors.As(err, &ne) && ne.Timeout() {
		return &Error{Kind: KindTransient, Msg: "délai dépassé : " + err.Error()}
	}
	var dnsErr *net.DNSError
	var opErr *net.OpError
	if errors.As(err, &dnsErr) || (errors.As(err, &opErr) && opErr.Op == "dial") {
		return &Error{Kind: KindOffline, Msg: "serveur injoignable : " + err.Error()}
	}
	return &Error{Kind: KindTransient, Msg: err.Error()}
}

// fingerprint lets the user compare the password the app holds with the right one (same command on
// a PC) without showing it: its length and the start of its SHA-256.
func fingerprint(password string) string {
	h := sha256.Sum256([]byte(password))
	return fmt.Sprintf("%d caractères, empreinte %s", utf8.RuneCountInString(password), hex.EncodeToString(h[:])[:6])
}

func statusError(method, p, user, pwPrint string, resp *http.Response) error {
	s := resp.StatusCode
	where := fmt.Sprintf("%s /%s : HTTP %d", method, strings.Trim(p, "/"), s)
	switch {
	case s == http.StatusUnauthorized:
		// The WebDAV server asks for Basic auth; a 401 without that challenge comes from something in between.
		who := "le serveur WebDAV"
		if resp.Header.Get("WWW-Authenticate") == "" {
			who = "un intermédiaire (pas le serveur WebDAV : Cloudflare ?)"
		}
		return &Error{Kind: KindAuth, Status: s, Msg: fmt.Sprintf("%s refuse l'utilisateur « %s » sur %s (%s ; mot de passe envoyé : %s)",
			who, user, resp.Request.URL.Host, where, pwPrint)}
	case s >= 300 && s < 400:
		loc := resp.Header.Get("Location")
		if strings.Contains(loc, "cloudflareaccess.com") {
			return &Error{Kind: KindAccess, Status: s, Msg: "Cloudflare Access refuse l'accès : vérifier le token Cloudflare"}
		}
		return &Error{Kind: KindAccess, Status: s, Msg: "redirection inattendue (" + where + ") : vérifier l'adresse et le token Cloudflare"}
	case s == http.StatusForbidden:
		return &Error{Kind: KindAccess, Status: s, Msg: "accès refusé (" + where + ") : vérifier le token Cloudflare"}
	case s == http.StatusRequestEntityTooLarge:
		return &Error{Kind: KindTooLarge, Status: s, Msg: "fichier trop gros pour le serveur (limite Cloudflare d'environ 100 Mo)"}
	case s == http.StatusBadGateway || (s >= 520 && s <= 530):
		return &Error{Kind: KindOffline, Status: s, Msg: "le serveur ne répond pas (NAS éteint ou tunnel coupé, " + where + ")"}
	case s >= 500:
		return &Error{Kind: KindTransient, Status: s, Msg: where}
	}
	return &Error{Kind: KindProtocol, Status: s, Msg: where}
}

// isHTML spots a challenge or login page served instead of a WebDAV answer.
func isHTML(resp *http.Response) bool {
	return strings.Contains(strings.ToLower(resp.Header.Get("Content-Type")), "text/html")
}

func htmlError() error {
	return &Error{Kind: KindAccess, Msg: "page HTML reçue au lieu d'une réponse WebDAV (challenge Cloudflare ?) : vérifier le token Cloudflare"}
}

type multistatus struct {
	Responses []struct {
		Href     string `xml:"DAV: href"`
		Propstat []struct {
			Prop struct {
				ResourceType struct {
					Collection *struct{} `xml:"DAV: collection"`
				} `xml:"DAV: resourcetype"`
				Length  string `xml:"DAV: getcontentlength"`
				LastMod string `xml:"DAV: getlastmodified"`
				ETag    string `xml:"DAV: getetag"`
			} `xml:"DAV: prop"`
			Status string `xml:"DAV: status"`
		} `xml:"DAV: propstat"`
	} `xml:"DAV: response"`
}

// propfind returns the entries of a PROPFIND; with depth 1 the first one is the folder itself.
func (c *Client) propfind(ctx context.Context, p string, depth string) ([]Entry, error) {
	resp, err := c.do(ctx, "PROPFIND", p, map[string]string{"Depth": depth, "Content-Type": "application/xml; charset=utf-8"},
		func() (io.ReadCloser, int64, error) {
			return io.NopCloser(strings.NewReader(propfindBody)), int64(len(propfindBody)), nil
		}, http.StatusMultiStatus, http.StatusNotFound)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotFound {
		return nil, nil
	}
	if isHTML(resp) {
		return nil, htmlError()
	}
	var ms multistatus
	if err := xml.NewDecoder(resp.Body).Decode(&ms); err != nil {
		return nil, &Error{Kind: KindProtocol, Msg: "réponse PROPFIND illisible : " + err.Error()}
	}
	out := make([]Entry, 0, len(ms.Responses))
	for _, r := range ms.Responses {
		rel, err := c.relPath(r.Href)
		if err != nil {
			return nil, err
		}
		for _, ps := range r.Propstat {
			if !strings.Contains(ps.Status, " 200") {
				continue
			}
			e := Entry{Path: rel, IsDir: ps.Prop.ResourceType.Collection != nil}
			e.Size, _ = strconv.ParseInt(strings.TrimSpace(ps.Prop.Length), 10, 64)
			if t, err := http.ParseTime(strings.TrimSpace(ps.Prop.LastMod)); err == nil {
				e.Modified = t
			}
			e.ETag = cleanETag(ps.Prop.ETag)
			out = append(out, e)
			break
		}
	}
	return out, nil
}

func cleanETag(s string) string {
	s = strings.TrimSpace(s)
	s = strings.TrimPrefix(s, "W/")
	return strings.ToLower(strings.Trim(s, `"`))
}

// relPath turns an href (absolute path or URL, percent-encoded) into a path relative to the server root.
func (c *Client) relPath(href string) (string, error) {
	u, err := url.Parse(strings.TrimSpace(href))
	if err != nil {
		return "", &Error{Kind: KindProtocol, Msg: "href invalide : " + href}
	}
	p := u.Path
	if !strings.HasPrefix(p, c.base.Path) && p+"/" != c.base.Path {
		return "", &Error{Kind: KindProtocol, Msg: "href hors du serveur : " + href}
	}
	return strings.Trim(strings.TrimPrefix(p, c.base.Path), "/"), nil
}

// Stat returns the entry at p, or nil when it does not exist.
func (c *Client) Stat(ctx context.Context, p string) (*Entry, error) {
	es, err := c.propfind(ctx, p, "0")
	if err != nil || len(es) == 0 {
		return nil, err
	}
	e := es[0]
	e.Path = strings.Trim(p, "/")
	return &e, nil
}

// List returns the direct children of folder p; nil (no error) when p does not exist.
func (c *Client) List(ctx context.Context, p string) ([]Entry, bool, error) {
	es, err := c.propfind(ctx, p, "1")
	if err != nil {
		return nil, false, err
	}
	if es == nil {
		return nil, false, nil
	}
	self := strings.Trim(p, "/")
	out := es[:0]
	for _, e := range es {
		if e.Path != self {
			out = append(out, e)
		}
	}
	return out, true, nil
}

// Get hands the content of p to read, which must consume (and may close) it.
func (c *Client) Get(ctx context.Context, p string, read func(io.ReadCloser) error) error {
	resp, err := c.do(ctx, "GET", p, nil, nil, http.StatusOK)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if isHTML(resp) {
		return htmlError()
	}
	err = read(resp.Body)
	if err != nil && ctx.Err() == nil {
		return &Error{Kind: KindTransient, Msg: "téléchargement interrompu : " + err.Error()}
	}
	return err
}

// Put writes p from b. A missing parent folder is created, then the PUT retried.
func (c *Client) Put(ctx context.Context, p string, b body) error {
	put := func() error {
		resp, err := c.do(ctx, "PUT", p, nil, b, http.StatusCreated, http.StatusNoContent, http.StatusOK)
		if err == nil {
			resp.Body.Close()
		}
		return err
	}
	err := put()
	var e *Error
	if errors.As(err, &e) && e.Status == http.StatusConflict {
		if err := c.MkdirAll(ctx, parent(p)); err != nil {
			return err
		}
		err = put()
	}
	return err
}

// Mkcol creates one folder level; an existing folder is fine.
func (c *Client) Mkcol(ctx context.Context, p string) error {
	resp, err := c.do(ctx, "MKCOL", p, nil, nil, http.StatusCreated, http.StatusMethodNotAllowed, http.StatusOK)
	if err == nil {
		resp.Body.Close()
	}
	return err
}

// MkdirAll creates p and its missing parents, one MKCOL per level.
func (c *Client) MkdirAll(ctx context.Context, p string) error {
	p = strings.Trim(p, "/")
	if p == "" {
		return nil
	}
	if e, err := c.Stat(ctx, p); err != nil {
		return err
	} else if e != nil {
		if !e.IsDir {
			return &Error{Kind: KindProtocol, Msg: "/" + p + " est un fichier sur le serveur, pas un dossier"}
		}
		return nil
	}
	if err := c.MkdirAll(ctx, parent(p)); err != nil {
		return err
	}
	return c.Mkcol(ctx, p)
}

// Delete removes a file, or a folder with everything in it. A missing one is fine.
func (c *Client) Delete(ctx context.Context, p string) error {
	resp, err := c.do(ctx, "DELETE", p, nil, nil, http.StatusNoContent, http.StatusOK, http.StatusNotFound)
	if err == nil {
		resp.Body.Close()
	}
	return err
}

// Move renames from to to.
func (c *Client) Move(ctx context.Context, from, to string, overwrite bool) error {
	ow := "F"
	if overwrite {
		ow = "T"
	}
	resp, err := c.do(ctx, "MOVE", from, map[string]string{"Destination": c.url(to), "Overwrite": ow},
		nil, http.StatusCreated, http.StatusNoContent)
	if err == nil {
		resp.Body.Close()
	}
	return err
}

// Test checks the address, the Cloudflare token and the credentials: PROPFIND Depth 0 on the root.
func (c *Client) Test(ctx context.Context) error {
	e, err := c.Stat(ctx, "")
	if err != nil {
		return err
	}
	if e == nil || !e.IsDir {
		return &Error{Kind: KindProtocol, Msg: "la racine du serveur n'est pas un dossier WebDAV"}
	}
	return nil
}

func parent(p string) string {
	p = strings.Trim(p, "/")
	if i := strings.LastIndex(p, "/"); i >= 0 {
		return p[:i]
	}
	return ""
}

func join(a, b string) string {
	a, b = strings.Trim(a, "/"), strings.Trim(b, "/")
	switch {
	case a == "":
		return b
	case b == "":
		return a
	}
	return a + "/" + b
}
