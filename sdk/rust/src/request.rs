//! Request models — builder-pattern, with every unset option omitted from the
//! serialized JSON.

use std::collections::HashMap;

use serde::Serialize;
use serde_json::Value;

/// Cleaner mode used when `clean: true`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum ParseMode {
    /// More forgiving on malformed pages (default).
    Accurate,
    /// Faster, less forgiving.
    Speed,
}

/// Page-complexity hint for [`SmartScraperRequest`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum PageComplexity {
    /// Faster and cheaper (default).
    Low,
    /// For visually busy pages or deeply nested schemas.
    High,
}

/// How exhaustively [`SmartScraperRequest`] should populate the result.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum DetailLevel {
    /// Minimal.
    Low,
    /// Balanced (default).
    Medium,
    /// Exhaustive.
    High,
}

/// Request body for [`Client::scrape`](crate::Client::scrape).
///
/// Build with [`ScrapeRequest::new`] and chain the option setters; only fields
/// you touch are serialized.
///
/// ```
/// use webscrape_ai::ScrapeRequest;
/// let req = ScrapeRequest::new("https://example.com").clean(true).extract_links(true);
/// let json = serde_json::to_value(&req).unwrap();
/// assert_eq!(json["website_url"], "https://example.com");
/// assert_eq!(json["clean"], true);
/// assert!(json.get("tag_truncate").is_none()); // untouched -> absent
/// ```
#[derive(Debug, Clone, Default, Serialize)]
pub struct ScrapeRequest {
    /// The URL to fetch. Required.
    pub website_url: String,
    /// Convert HTML to cleaned markdown.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub clean: Option<bool>,
    /// Cleaner mode when `clean`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub parse_mode: Option<ParseMode>,
    /// Replace inline images with alt text when `clean` (tri-state).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tag_truncate: Option<bool>,
    /// Include a deduplicated list of outbound links.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub extract_links: Option<bool>,
    /// Whitelist of tags to keep when `clean`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub include_tags: Option<Vec<String>>,
    /// Blacklist of tags to drop when `clean`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub exclude_tags: Option<Vec<String>>,
    /// Custom request headers; providing any disables URL caching.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub headers: Option<HashMap<String, String>>,
    /// URL-cache opt-in in seconds (tri-state; omitted = fetch fresh).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub max_age: Option<u64>,
    /// Browser-based stealth fetch. +2 credits.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub stealth: Option<bool>,
}

impl ScrapeRequest {
    /// Start a request for `website_url`.
    pub fn new(website_url: impl Into<String>) -> Self {
        Self {
            website_url: website_url.into(),
            ..Default::default()
        }
    }

    /// Convert HTML to cleaned markdown.
    pub fn clean(mut self, clean: bool) -> Self {
        self.clean = Some(clean);
        self
    }

    /// Set the cleaner mode.
    pub fn parse_mode(mut self, mode: ParseMode) -> Self {
        self.parse_mode = Some(mode);
        self
    }

    /// Replace inline images with alt text when cleaning.
    pub fn tag_truncate(mut self, tag_truncate: bool) -> Self {
        self.tag_truncate = Some(tag_truncate);
        self
    }

    /// Include a deduplicated list of outbound links.
    pub fn extract_links(mut self, extract_links: bool) -> Self {
        self.extract_links = Some(extract_links);
        self
    }

    /// Set the tag whitelist.
    pub fn include_tags<I, S>(mut self, tags: I) -> Self
    where
        I: IntoIterator<Item = S>,
        S: Into<String>,
    {
        self.include_tags = Some(tags.into_iter().map(Into::into).collect());
        self
    }

    /// Set the tag blacklist.
    pub fn exclude_tags<I, S>(mut self, tags: I) -> Self
    where
        I: IntoIterator<Item = S>,
        S: Into<String>,
    {
        self.exclude_tags = Some(tags.into_iter().map(Into::into).collect());
        self
    }

    /// Add a single custom request header.
    pub fn header(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        self.headers
            .get_or_insert_with(HashMap::new)
            .insert(key.into(), value.into());
        self
    }

    /// Replace all custom request headers.
    pub fn headers(mut self, headers: HashMap<String, String>) -> Self {
        self.headers = Some(headers);
        self
    }

    /// Opt into URL caching: accept cache entries fresher than `seconds`.
    pub fn max_age(mut self, seconds: u64) -> Self {
        self.max_age = Some(seconds);
        self
    }

    /// Use browser-based stealth fetch (+2 credits).
    pub fn stealth(mut self, stealth: bool) -> Self {
        self.stealth = Some(stealth);
        self
    }
}

/// Request body for [`Client::smartscraper`](crate::Client::smartscraper).
///
/// Build with [`SmartScraperRequest::new`]; only fields you touch are serialized.
#[derive(Debug, Clone, Default, Serialize)]
pub struct SmartScraperRequest {
    /// The URL to extract from. Required.
    pub website_url: String,
    /// Plain-English description of what to extract. Required.
    /// (Wire field name is `user_prompt`, not `prompt`.)
    pub user_prompt: String,
    /// JSON Schema the result is validated against (one repair attempt).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub output_schema: Option<Value>,
    /// Page-complexity hint.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub page_complexity: Option<PageComplexity>,
    /// How exhaustively to populate the result.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub detail_level: Option<DetailLevel>,
    /// Cleaner mode.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub parse_mode: Option<ParseMode>,
    /// Return raw text under `result` (bypasses schema validation).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub plain_text: Option<bool>,
    /// Whitelist of tags to keep before extraction.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub include_tags: Option<Vec<String>>,
    /// Blacklist of tags to drop before extraction.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub exclude_tags: Option<Vec<String>>,
    /// Trim long content before extraction (tri-state; server default when unset).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reduce_content: Option<bool>,
    /// Opt in to an alternate extraction path that can do better on
    /// hard-to-parse pages. Behavior may change without notice.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub experimental: Option<bool>,
    /// Custom request headers forwarded to the fetcher. Providing headers
    /// disables URL caching for this request.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub headers: Option<HashMap<String, String>>,
    /// URL-cache opt-in in seconds (same semantics as `/scrape`).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub max_age: Option<u64>,
    /// Browser-based stealth fetch. +5 credits.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub stealth: Option<bool>,
}

impl SmartScraperRequest {
    /// Start a request for `website_url` with the given `user_prompt`.
    pub fn new(website_url: impl Into<String>, user_prompt: impl Into<String>) -> Self {
        Self {
            website_url: website_url.into(),
            user_prompt: user_prompt.into(),
            ..Default::default()
        }
    }

    /// Set the JSON Schema for the output.
    pub fn output_schema(mut self, schema: Value) -> Self {
        self.output_schema = Some(schema);
        self
    }

    /// Set the page-complexity hint.
    pub fn page_complexity(mut self, complexity: PageComplexity) -> Self {
        self.page_complexity = Some(complexity);
        self
    }

    /// Set the detail level.
    pub fn detail_level(mut self, level: DetailLevel) -> Self {
        self.detail_level = Some(level);
        self
    }

    /// Set the cleaner mode.
    pub fn parse_mode(mut self, mode: ParseMode) -> Self {
        self.parse_mode = Some(mode);
        self
    }

    /// Return raw text instead of parsed JSON (bypasses schema validation).
    pub fn plain_text(mut self, plain_text: bool) -> Self {
        self.plain_text = Some(plain_text);
        self
    }

    /// Set the tag whitelist.
    pub fn include_tags<I, S>(mut self, tags: I) -> Self
    where
        I: IntoIterator<Item = S>,
        S: Into<String>,
    {
        self.include_tags = Some(tags.into_iter().map(Into::into).collect());
        self
    }

    /// Set the tag blacklist.
    pub fn exclude_tags<I, S>(mut self, tags: I) -> Self
    where
        I: IntoIterator<Item = S>,
        S: Into<String>,
    {
        self.exclude_tags = Some(tags.into_iter().map(Into::into).collect());
        self
    }

    /// Trim long content before extraction.
    pub fn reduce_content(mut self, reduce_content: bool) -> Self {
        self.reduce_content = Some(reduce_content);
        self
    }

    /// Opt in to an alternate extraction path that can do better on
    /// hard-to-parse pages. Behavior may change without notice.
    pub fn experimental(mut self, experimental: bool) -> Self {
        self.experimental = Some(experimental);
        self
    }

    /// Add a single custom request header.
    pub fn header(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        self.headers
            .get_or_insert_with(HashMap::new)
            .insert(key.into(), value.into());
        self
    }

    /// Replace all custom request headers.
    pub fn headers(mut self, headers: HashMap<String, String>) -> Self {
        self.headers = Some(headers);
        self
    }

    /// Opt into URL caching: accept cache entries fresher than `seconds`.
    pub fn max_age(mut self, seconds: u64) -> Self {
        self.max_age = Some(seconds);
        self
    }

    /// Use browser-based stealth fetch (+5 credits).
    pub fn stealth(mut self, stealth: bool) -> Self {
        self.stealth = Some(stealth);
        self
    }
}
