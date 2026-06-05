package `in`.jphe.storyvox.source.telegram.config

/**
 * Issue #462 — Telegram backend defaults. Minimal: no bundled bot
 * token (ToS posture — storyvox doesn't ship credentials and won't
 * auto-join anyone's channel), no default channel list (the user's
 * bot has to be invited to each channel they want to read).
 *
 * The Bot API is hosted at `api.telegram.org`. v1 only uses the
 * stable, documented REST surface — no MTProto user-side API,
 * because that requires a much heavier integration (login flow,
 * 2FA, secret-chat session pairing) and doesn't fit the public-
 * channel reader scope.
 */
object TelegramDefaults {
    /** Telegram Bot API base URL. The token is interpolated into
     *  the path (`/bot{TOKEN}/method`) rather than carried in a
     *  header, which is mildly unusual but documented and stable.
     *  Overridable for test fakes (the test classes inject a
     *  copy-of-TelegramConfigState with their own base URL). */
    const val BASE_URL: String = "https://api.telegram.org"

    /** User-Agent header. Telegram doesn't require a specific UA
     *  but identifying ourselves makes abuse-team contact easier
     *  if a bot misbehaves. Mirrors the Discord UA pattern. */
    const val USER_AGENT: String =
        "Storyvox-Telegram/1.0 (+https://github.com/techempower-org/candela)"

    /** Max messages per `getUpdates` poll. The API caps at 100;
     *  we pull the full window so a single poll reflects every
     *  recently-active channel the bot is invited to. */
    const val GET_UPDATES_LIMIT: Int = 100

    /** Max chat-history page size. Telegram's `forwardMessages`
     *  + similar bulk endpoints document a 100-message ceiling;
     *  the channel-history endpoint (via `getUpdates` filtering +
     *  manual paging by message id) follows the same convention. */
    const val MESSAGE_PAGE_SIZE: Int = 100
}
