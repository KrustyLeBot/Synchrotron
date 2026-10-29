package davsync

import (
	"context"
	"path"
	"sort"
	"strings"

	"github.com/rclone/rclone/fs/rc"
)

// The rc calls the app uses for the Synchrotron server. Each takes a "server" object (Config).
func init() {
	rc.Add(rc.Call{Path: "synchrotron/davtest", Fn: rcTest, Title: "Test the Synchrotron server connection"})
	rc.Add(rc.Call{Path: "synchrotron/davlist", Fn: rcList, Title: "List the folders of a server folder"})
	rc.Add(rc.Call{Path: "synchrotron/davmkdir", Fn: rcMkdir, Title: "Create a server folder"})
	rc.Add(rc.Call{Path: "synchrotron/davsync", Fn: rcSync, Title: "Sync a local folder with a server folder"})
	rc.Add(rc.Call{Path: "synchrotron/davpreview", Fn: rcPreview, Title: "What the first sync would do"})
}

func client(ctx context.Context, in rc.Params) (*Client, error) {
	var cfg Config
	if err := in.GetStruct("server", &cfg); err != nil {
		return nil, err
	}
	return Connect(ctx, cfg)
}

func rcTest(ctx context.Context, in rc.Params) (rc.Params, error) {
	c, err := client(ctx, in)
	if err != nil {
		return nil, err
	}
	if err := c.Test(ctx); err != nil {
		return nil, err
	}
	return rc.Params{"host": c.Host()}, nil
}

func rcList(ctx context.Context, in rc.Params) (rc.Params, error) {
	c, err := client(ctx, in)
	if err != nil {
		return nil, err
	}
	p, _ := in.GetString("path")
	entries, _, err := c.List(ctx, p)
	if err != nil {
		return nil, err
	}
	list := []rc.Params{}
	for _, e := range entries {
		if !e.IsDir || strings.HasPrefix(path.Base(e.Path), ".") {
			continue
		}
		list = append(list, rc.Params{"Name": path.Base(e.Path), "Path": e.Path, "IsDir": true, "Size": int64(0)})
	}
	sort.Slice(list, func(i, j int) bool {
		return strings.ToLower(list[i]["Name"].(string)) < strings.ToLower(list[j]["Name"].(string))
	})
	return rc.Params{"list": list}, nil
}

func rcMkdir(ctx context.Context, in rc.Params) (rc.Params, error) {
	c, err := client(ctx, in)
	if err != nil {
		return nil, err
	}
	p, err := in.GetString("path")
	if err != nil {
		return nil, err
	}
	return rc.Params{}, c.MkdirAll(ctx, p)
}

func options(in rc.Params) (Options, error) {
	var opt Options
	err := in.GetStruct("sync", &opt)
	return opt, err
}

func rcSync(ctx context.Context, in rc.Params) (rc.Params, error) {
	opt, err := options(in)
	if err != nil {
		return nil, err
	}
	c, err := client(ctx, in)
	if err != nil {
		return nil, err
	}
	log, err := Sync(ctx, c, opt)
	return rc.Params{"output": "Serveur : " + c.Host() + "\n" + log}, err
}

func rcPreview(ctx context.Context, in rc.Params) (rc.Params, error) {
	opt, err := options(in)
	if err != nil {
		return nil, err
	}
	c, err := client(ctx, in)
	if err != nil {
		return nil, err
	}
	sum, err := Preview(ctx, c, opt)
	if err != nil {
		return nil, err
	}
	return rc.Params{"toUpload": sum.ToUpload, "toDownload": sum.ToDownload, "different": sum.Different, "identical": sum.Identical}, nil
}
