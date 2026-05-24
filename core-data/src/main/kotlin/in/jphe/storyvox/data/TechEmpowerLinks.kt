package `in`.jphe.storyvox.data

/**
 * Issue #517 — single source of truth for TechEmpower brand links + copy
 * surfaced across storyvox's TechEmpower default-use-case surfaces
 * (Library hero card, TechEmpower Home screen, About sub-screen,
 * top-app-bar emergency icons). Constants here are referenced from
 * multiple feature surfaces; centralising them means a content edit
 * (new Discord URL, new tagline, new emergency number) is a one-line
 * change instead of a multi-file grep.
 *
 * Why `:app` and not `:core-data`: these are user-facing copy + URLs,
 * not domain entities. Anything cross-cutting enough to deserve a
 * `:core-content` module would also pull a translation layer; for now
 * the copy is English-only and lives next to the app's other
 * brand-adjacent constants.
 *
 * Why an `object` rather than a `data class` injected via Hilt: these
 * values don't change at runtime, don't vary per build flavor, and
 * don't carry secrets. A compile-time constant set is the right shape.
 * If TechEmpower ever needs locale-varying copy, this object's
 * properties become functions of `Locale` — that's a future refactor.
 *
 * v0.5.51 — first pass. Discord invite URL is a placeholder until JP
 * confirms the channel (see `feat/techempower-default` PR blocking
 * questions); the placeholder is structured the same way as a real
 * Discord invite URL so the surrounding intent code doesn't need to
 * special-case empty-string.
 */
object TechEmpowerLinks {

    /**
     * TechEmpower's mission tagline. Surfaced on the Library hero card,
     * the TechEmpower Home hero, and the About sub-screen. Two
     * sentences, matching the techempower.org website's lead copy:
     * "Technology for All. Access Made Easy."
     */
    const val MISSION_TAGLINE: String = "Technology for All. Access Made Easy."

    /**
     * Longer mission statement for the About sub-screen. Source:
     * techempower.org footer ("Closing the digital divide...") expanded
     * with the 501(c)(3) framing for the About surface.
     */
    const val MISSION_STATEMENT: String =
        "Closing the digital divide for low-income individuals and " +
            "families with free tools, training, and software. " +
            "TechEmpower is a 501(c)(3) nonprofit dedicated to making " +
            "technology accessible to everyone."

    /**
     * TechEmpower's public website. Used by the About sub-screen's
     * "Visit website" affordance and as the link target on the brand
     * logo tap. ACTION_VIEW with this URL opens the system browser.
     */
    const val WEBSITE_URL: String = "https://techempower.org"

    /**
     * Donate flow target — TechEmpower's Donate page on the public
     * site. Brief item #7 spec: link-out to the system browser, not an
     * in-app payment flow (in-app Stripe is a 501(c)(3) board
     * conversation, not a single-agent call — see blocking-questions
     * suggestion 3).
     */
    const val DONATE_URL: String = "https://techempower.org/donate"

    /**
     * Contact email for the user-facing "Get in touch" affordance.
     * `mailto:` ACTION_SENDTO opens the system mail composer
     * prefilled with this recipient.
     */
    const val CONTACT_EMAIL: String = "jp@techempower.org"

    /**
     * Partnerships email — surfaced on the About sub-screen for
     * organisations interested in working with TechEmpower. Separate
     * from [CONTACT_EMAIL] so partnership inquiries route to the right
     * mailbox without burying the user-facing contact path.
     */
    const val PARTNERSHIPS_EMAIL: String = "partnerships@techempower.org"

    /**
     * Peer-support Discord invite URL — confirmed by JP 2026-05-15
     * during PR development. HTTPS form (browser fallback for users
     * without the Discord app installed).
     */
    const val DISCORD_INVITE_URL: String = "https://discord.gg/j3SVttxw7k"

    /**
     * Discord deep-link form of the same invite — tried FIRST by the
     * intent-launcher so the Discord app opens directly if installed.
     * If no activity resolves the `discord://` scheme (Discord not
     * installed), the launcher falls back to [DISCORD_INVITE_URL]
     * which opens in the browser → Discord's "Open in app or join via
     * web" landing page.
     *
     * The slug after `discord.gg/` and `discord://invite/` is the
     * same value; pulled out as a separate constant so the two
     * URL shapes stay in sync — change one, change both.
     */
    const val DISCORD_INVITE_DEEPLINK: String = "discord://invite/j3SVttxw7k"

    /**
     * Primary helpline surfaced in the top-app-bar phone icon and on
     * the TechEmpower Home "Call 211" card. `tel:211` is the United
     * Way's national social-services helpline — housing, food
     * assistance, utility assistance, mental health referrals.
     * Available in most of the US + Canada; the OS dialer handles
     * region availability gracefully (dials normally if local).
     *
     * Issue #775 — 988 (Suicide & Crisis Lifeline) and 911 (emergency
     * dispatch) affordances were removed for the current app stage;
     * they can be reinstated when the user base is large enough that
     * a proper crisis-UX review with intervention experts is
     * warranted. 211 stays because it's the everyday social-services
     * line and aligns with TechEmpower's mission.
     */
    const val PRIMARY_HELP_NUMBER: String = "211"

    /**
     * Building a `tel:` URI for [Intent.ACTION_DIAL]. Returns a String
     * because URI construction differs subtly between Android API
     * levels for `tel:`. Calling sites wrap with `android.net.Uri.parse()`.
     */
    fun telUri(number: String): String = "tel:$number"

    /**
     * Building a `mailto:` URI for [Intent.ACTION_SENDTO]. Same
     * rationale as [telUri] — String return, calling sites wrap.
     */
    fun mailtoUri(email: String): String = "mailto:$email"
}
