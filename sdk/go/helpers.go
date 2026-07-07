package webscrape

// Bool returns a pointer to v. Use it to set tri-state boolean request options
// (e.g. ScrapeRequest.TagTruncate) so that "unset" is distinguishable from an
// explicit false.
func Bool(v bool) *bool { return &v }

// Int returns a pointer to v. Use it for tri-state integer request options
// (e.g. ScrapeRequest.MaxAge) so that "unset" is distinguishable from an
// explicit 0.
func Int(v int) *int { return &v }

// String returns a pointer to v.
func String(v string) *string { return &v }
