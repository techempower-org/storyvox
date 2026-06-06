# Deterministic reader seed (Layer 1)

A no-network way to get a **known** book into the reader so reader /
highlight UI tests have fixed character offsets. Reuses the merged
"Open With" TXT import (#1000): a bundled plaintext fixture is ingested
through the production import path and becomes a single-chapter fiction.

- **Fixture:** `app/src/main/assets/sample/candela-reader-sample.txt`
- **Package (debug):** `org.techempower.candela`
- **Entry activity:** `org.techempower.candela/in.jphe.storyvox.MainActivity`

## The fixture (raw file)

Three short ASCII paragraphs, single-spaced, one `\n` per line:

```
The lamplighter walked the quiet street at dusk.
She lifted her pole to each lamp and a small flame answered.
One by one the windows of the town began to glow.
```

Pure ASCII (no `& < >`) so the importer's HTML-escape is a no-op.

## What the reader actually displays (and highlights against)

The TXT import wraps the body in `<pre>…</pre>` (`EpubSource.textBook`),
then the chapter's `plainBody` is `stripHtml(htmlBody)`: tags → spaces,
every whitespace run (incl. newlines) collapsed to a single space, then
trimmed. So the **displayed / highlight string** is one line, 159 chars:

```
The lamplighter walked the quiet street at dusk. She lifted her pole to each lamp and a small flame answered. One by one the windows of the town began to glow.
```

`length == 159`. These offsets are into THAT string (what the highlight
layer keys on), not the raw file.

### Known test passages (char offsets, end-exclusive)

| passage                | start | end |
|------------------------|------:|----:|
| `The lamplighter`      |     0 |  15 |
| `lamplighter`          |     4 |  15 |
| `quiet street`         |    27 |  39 |
| `dusk`                 |    43 |  47 |
| `small flame answered` |    88 | 108 |
| `windows of the town`  |   125 | 144 |
| `glow`                 |   154 | 158 |

**Primary highlight-verification passage:** the word `lamplighter`,
offset **4–15** (length 11) in the displayed string.

Per-word offsets for the first sentence:

| word          | start | end |
|---------------|------:|----:|
| `The`         |     0 |   3 |
| `lamplighter` |     4 |  15 |
| `walked`      |    16 |  22 |
| `the`         |    23 |  26 |
| `quiet`       |    27 |  32 |
| `street`      |    33 |  39 |
| `at`          |    40 |  42 |
| `dusk.`       |    43 |  48 |

The fiction title shown in the library/detail is the filename sans
extension: **`candela-reader-sample`**.

## Seed command (debug build, no network)

The debug build accepts an `ACTION_VIEW` intent with a boolean extra
that imports the bundled fixture and routes to its detail screen. This
is the recommended seed — it reads the app's own asset, so there is no
SAF picker, no `content://`/`file://` permission dance, and the result
is byte-identical every run.

```bash
adb -s R83W80CAFZB shell am start \
  -a android.intent.action.VIEW \
  -n org.techempower.candela/in.jphe.storyvox.MainActivity \
  --ez in.jphe.storyvox.debug.LOAD_SAMPLE true
```

The extra is honored only when `BuildConfig.DEBUG` is true; a release
build ignores it (the branch is compiled out of the hot path).

After it lands on the fiction-detail screen, tap the play/read affordance
(or, in an instrumented test, navigate the reader route) to open the
single chapter into the reader.

### Precondition: onboarding must be completed

On a **fresh install** the app boots into the first-run onboarding flow
(its start destination). It is **3 steps**:

1. "Stories, read aloud." intro (`Get started` / `I've used Candela
   before`).
2. (the welcome / theme step).
3. **"Pick a voice"** — a starter-voice gate that wants a voice download
   (e.g. "Lessac low/medium · 63 MB"). There is no skip on this step;
   the device must get past it before the main app is reachable.

The seed's navigation to the fiction-detail screen happens *behind* this
onboarding start destination, so the reader is **not observable until
onboarding is finished**. Verified on-device: the seed's asset-copy step
runs regardless (the fixture is staged byte-identically into the app's
`cacheDir`), but the reader landing requires a device/emulator that has
already completed onboarding.

For the Maestro / instrumented flows: drive through (or bypass)
onboarding **first**, then fire the seed intent — the import then
navigates straight to the fiction with no network. For a manual device
check, run the seed on an already-onboarded device.

### Gotcha: don't `force-stop` right after firing the seed

The import record is written via an async DataStore `edit {}` coroutine
(`EpubConfigImpl.importFile`). On-device, an `am force-stop`
**immediately after** the seed intent can kill that write mid-flight —
`files/datastore/storyvox_epub.preferences_pb` was observed empty/absent
when the process was stopped too soon, so the imported fiction did not
survive into Library. Give the activity a moment (a second or two, or
just don't force-stop between fires) so the write flushes before you
relaunch or assert on the persisted book. The `cacheDir` staging is
synchronous and is unaffected; only the persisted import record is at
risk.

### Instrumented-test use

The same path is callable from a Compose/instrumented test by launching
`MainActivity` with that intent (the import + navigation runs in the
activity's `LaunchedEffect`), giving a known chapter without any source
network call. The displayed-string offsets above are the fixtures for
asserting highlight ranges.

## Alternative: real "Open With" file path

To exercise the genuine #1000 file-import path end-to-end (rather than
the debug shortcut), stage the fixture where the app can read it and fire
a `file://` VIEW intent. On a debug build the app's private dir is
reachable via `run-as`:

```bash
adb -s R83W80CAFZB push app/src/main/assets/sample/candela-reader-sample.txt /data/local/tmp/
adb -s R83W80CAFZB shell run-as org.techempower.candela \
  cp /data/local/tmp/candela-reader-sample.txt files/candela-reader-sample.txt
adb -s R83W80CAFZB shell am start \
  -a android.intent.action.VIEW \
  -n org.techempower.candela/in.jphe.storyvox.MainActivity \
  -d "file:///data/data/org.techempower.candela/files/candela-reader-sample.txt" \
  -t text/plain
```

The debug-extra seed above is preferred for tests; this variant is for
verifying the production "Open With" intent-filter itself.
