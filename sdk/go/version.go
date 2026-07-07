package webscrape

// Version is the SDK version. It is the single source of truth for the version
// string across the module and feeds the User-Agent header sent on every
// request.
const Version = "0.1.0"

// userAgent is the value of the User-Agent header sent on every request.
const userAgent = "webscrape-ai-go/" + Version
