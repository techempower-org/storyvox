package `in`.jphe.storyvox.source.slack.config

/**
 * Issue #454 — Slack backend defaults. Minimal: no bundled bot
 * token (ToS posture — storyvox doesn't ship credentials and won't
 * auto-install into anyone's workspace), no default workspace (one
 * token = one workspace; the user picks it via the install step
 * on api.slack.com).
 *
 * The Slack Web API is hosted at `slack.com/api/<method>`. v1 only
 * uses the stable, documented Web API surface — no RTM / Events
 * websocket API (storyvox doesn't need real-time push for the
 * channels-as-fictions read model), and no MCP / Socket Mode (those
 * require a separate websocket server which doesn't fit the
 * on-demand-fetch pattern the rest of the backends use).
 */
object SlackDefaults {
    /** Slack Web API base URL. Methods are GET-able as
     *  `https://slack.com/api/<method>?token=…&…`, though storyvox
     *  uses the `Authorization: Bearer <token>` header form for
     *  parity with the other backends. Overridable for test fakes
     *  (the test classes inject a copy-of-SlackConfigState with their
     *  own base URL). */
    const val BASE_URL: String = "https://slack.com"

    /** User-Agent header. Slack doesn't require a specific UA but
     *  identifying ourselves makes abuse-team contact easier if a
     *  bot misbehaves. Mirrors the Discord / Telegram UA pattern. */
    const val USER_AGENT: String =
        "Storyvox-Slack/1.0 (+https://github.com/techempower-org/candela)"

    /** `conversations.list` page size. Slack caps this at 1000 but
     *  recommends staying ≤200 for performance + rate-limit
     *  friendliness (the endpoint is Tier 2 — ~20 req/min). 200 is
     *  enough for the vast majority of workspaces in one page. */
    const val CHANNELS_PAGE_SIZE: Int = 200

    /** `conversations.history` page size. Slack caps at 1000;
     *  storyvox uses 200 for parity with channels and to keep
     *  per-page bandwidth modest on mobile. The source layer
     *  exhausts pagination only on explicit chapter-list refresh,
     *  so 200 messages per round-trip is a comfortable default. */
    const val HISTORY_PAGE_SIZE: Int = 200

    /** Max pages of `conversations.history` to walk per fiction
     *  detail. Slack's pagination is cursor-based and unbounded;
     *  capping at 5 pages (≤1000 messages = ~1000 chapters) prevents
     *  a runaway loop on workspaces with massive channels. A
     *  follow-up issue can lift this when storyvox grows a
     *  load-more-chapters affordance in the Detail UI. */
    const val HISTORY_MAX_PAGES: Int = 5
}
