package `in`.jphe.storyvox.source.matrix.config

/**
 * Issue 457 — Matrix backend defaults. Matrix is federated, so there
 * is no single matrix.org-style default homeserver baked in — the
 * user picks one. The placeholder string shown in the Settings
 * homeserver field is the matrix.org URL (the dominant public
 * homeserver) but it is NOT a default value; an unconfigured install
 * has an empty homeserver URL and the source returns AuthRequired
 * until the user types one in.
 *
 * Same posture as Discord (#403): no bundled credentials,
 * defaultEnabled = false on the SourcePlugin annotation so the chip
 * is hidden on fresh installs and only appears once the user opts in
 * via the Settings Plugins screen.
 */
object MatrixDefaults {
    /** Placeholder homeserver shown in the Settings homeserver field
     *  (hint text, not a stored default). matrix.org is the dominant
     *  Matrix homeserver and the one the official Matrix account-
     *  creation flow lands on. Self-hosted Synapse, Dendrite, or
     *  Conduit users replace this with their own URL. */
    const val HOMESERVER_HINT: String = "https://matrix.org"

    /** Matrix Client-Server API version segment. v3 is the stable
     *  client-server spec floor as of 2026; r0 is the legacy form
     *  some old endpoints still accept. We pin to v3 because every
     *  endpoint storyvox calls — whoami, joined_rooms, room state
     *  lookups, messages, profile/displayname, search — has been
     *  stable under the v3 prefix since Matrix spec v1.1. */
    const val API_VERSION: String = "v3"

    /** Default page size for the messages endpoint. The Matrix spec
     *  allows up to 200; we send 100 to mirror the Discord default
     *  and keep response sizes manageable on cellular. */
    const val DEFAULT_MESSAGES_LIMIT: Int = 100

    /** Default same-sender coalesce window (minutes). Matches the
     *  Discord and Slack pattern — consecutive messages from the
     *  same sender inside the window collapse into one chapter. The
     *  Settings slider exposes 1-30 minutes; v1 ships the slider per
     *  the issue spec's coalesce-minutes acceptance bullet. */
    const val DEFAULT_COALESCE_MINUTES: Int = 5

    /** Slider lower bound. Below 1 min the coalesce loop is
     *  effectively disabled (every event becomes its own chapter)
     *  which we surface as set-to-1-min rather than a separate
     *  boolean, matching Discord's UI shape. */
    const val MIN_COALESCE_MINUTES: Int = 1

    /** Slider upper bound. Above 30 min a single chapter could span
     *  many hours of unrelated thoughts; Matrix room cadences rarely
     *  warrant a wider window. */
    const val MAX_COALESCE_MINUTES: Int = 30

    /** User-Agent header surfaced to the homeserver so admins can
     *  identify storyvox traffic + the version in their logs. Matrix
     *  homeservers are commonly self-hosted on small hardware; a
     *  clear UA keeps storyvox from looking like an anonymous scrape. */
    const val USER_AGENT: String =
        "Storyvox-Matrix/1.0 (+https://github.com/techempower-org/candela)"
}
