package `in`.jphe.storyvox.playback.voice

import `in`.jphe.storyvox.data.source.AzureVoiceDescriptor
import `in`.jphe.storyvox.data.source.AzureVoiceTier
import `in`.jphe.storyvox.data.source.SystemTtsVoiceDescriptor

object VoiceCatalog {
    /**
     * The static catalog — Piper, Kokoro, and Kitten entries that ship
     * in-app. Azure voices are NOT here anymore; they're populated at
     * runtime from the live roster (see [azureEntriesFromRoster] and
     * `AzureVoiceProvider`). Combine via [voicesWithAzure] when you
     * need a unified list.
     */
    val voices: List<CatalogEntry> = piperEntries() + kokoroEntries() + kittenEntries()

    /**
     * Combine the static catalog with the live Azure roster — used by
     * VoiceManager when projecting catalog rows to the picker. The
     * roster is parameterized so the catalog stays a pure object;
     * callers provide the current roster snapshot.
     */
    fun voicesWithAzure(roster: List<AzureVoiceDescriptor>): List<CatalogEntry> =
        voices + azureEntriesFromRoster(roster)

    /**
     * Issue #676 — combine the static catalog with both live rosters
     * (Azure HD voices, System TTS voices). VoiceManager uses this when
     * the picker / catalog projection needs to surface every available
     * voice across all engines. Identical contract to [voicesWithAzure]
     * with an extra System TTS roster appended.
     */
    fun voicesWithAzureAndSystemTts(
        azureRoster: List<AzureVoiceDescriptor>,
        systemTtsRoster: List<SystemTtsVoiceDescriptor>,
    ): List<CatalogEntry> =
        systemTtsEntriesFromRoster(systemTtsRoster) + voices + azureEntriesFromRoster(azureRoster)

    fun byId(id: String): CatalogEntry? = voices.firstOrNull { it.id == id }

    /**
     * Lookup that includes live Azure entries. Used by playback /
     * VoiceManager paths that need to resolve an active voice ID
     * which may be Azure. Lookups against [byId] alone will miss
     * Azure rows now.
     */
    fun byIdWithAzure(id: String, roster: List<AzureVoiceDescriptor>): CatalogEntry? =
        voicesWithAzure(roster).firstOrNull { it.id == id }

    /**
     * Issue #676 — lookup that includes live Azure + System TTS
     * entries. Used by playback paths (active-voice resolution) that
     * may land on any of the four backends.
     */
    fun byIdWithAzureAndSystemTts(
        id: String,
        azureRoster: List<AzureVoiceDescriptor>,
        systemTtsRoster: List<SystemTtsVoiceDescriptor>,
    ): CatalogEntry? =
        voicesWithAzureAndSystemTts(azureRoster, systemTtsRoster).firstOrNull { it.id == id }

    /** The three voices we hand-picked as the strongest starters. Surfaced
     *  on the first-launch [VoicePickerGate] picker so newcomers don't
     *  have to scroll the 98-voice catalog hunting for the good ones.
     *
     *  Revised 2026-05-13: the three starter voices are now the three
     *  quality tiers of ONE Piper voice (Lessac en_US — low / medium /
     *  high) so a first-time user can hear the quality-vs-download-size
     *  trade-off directly. The earlier "one Cori, one Lessac, one
     *  Kokoro" mix had a Kokoro entry whose declared `sizeBytes = 0`
     *  rendered as "1 MB" in the picker (shared-model artefact —
     *  Kokoro's ~330 MB model is downloaded once and shared across
     *  every speaker, so per-voice byte counts are meaningless).
     *  Sticking to Piper for the starter triplet keeps the sizes
     *  honest: 63 / 63 / 114 MB.
     *
     *  The inline ⭐ prefix on the curated displayNames that used to
     *  pair with this list has been removed — the favorites feature
     *  in the Voice Library now owns the ⭐ glyph, and double-using it
     *  for "featured" was reading as a stale favorite to users.
     *
     *  Cori (en_GB) joins Lessac (en_US) so the starter triplet
     *  becomes a Lessac/Cori pair across two accents. Cori only
     *  ships in medium and high upstream (rhasspy/piper-voices
     *  doesn't publish a Cori-low), so the en_GB column is two
     *  entries to en_US's three — five voices total in the gate. */
    val featuredIds: List<String> = listOf(
        "piper_lessac_en_US_low",
        "piper_lessac_en_US_medium",
        "piper_lessac_en_US_high",
        "piper_cori_en_GB_medium",
        "piper_cori_en_GB_high",
    )
    private fun piperEntries(): List<CatalogEntry> = listOf(
        CatalogEntry(
            id = "piper_lessac_en_US_high",
            displayName = "Lessac",
            language = "en_US",
            sizeBytes = 113895332L,
            qualityLevel = QualityLevel.High,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-lessac-high.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-lessac-high.tokens.txt",
            ),
            gender = VoiceGender.Female,
        ),
        CatalogEntry(
            id = "piper_ryan_en_US_high",
            displayName = "Ryan",
            language = "en_US",
            sizeBytes = 120786923L,
            qualityLevel = QualityLevel.High,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-ryan-high.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-ryan-high.tokens.txt",
            ),
            gender = VoiceGender.Male,
        ),
        CatalogEntry(
            id = "piper_amy_en_US_medium",
            displayName = "Amy",
            language = "en_US",
            sizeBytes = 63201425L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-amy-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-amy-medium.tokens.txt",
            ),
            gender = VoiceGender.Female,
        ),
        CatalogEntry(
            id = "piper_alan_en_GB_low",
            displayName = "Alan",
            language = "en_GB",
            sizeBytes = 63104662L,
            qualityLevel = QualityLevel.Low,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-alan-low.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-alan-low.tokens.txt",
            ),
            gender = VoiceGender.Male,
        ),
        CatalogEntry(
            id = "piper_alan_en_GB_medium",
            displayName = "Alan",
            language = "en_GB",
            sizeBytes = 63201430L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-alan-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-alan-medium.tokens.txt",
            ),
            gender = VoiceGender.Male,
        ),
        CatalogEntry(
            id = "piper_alba_en_GB_medium",
            displayName = "Alba",
            language = "en_GB",
            sizeBytes = 63201430L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-alba-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-alba-medium.tokens.txt",
            ),
            gender = VoiceGender.Female,
        ),
        CatalogEntry(
            id = "piper_aru_en_GB_medium",
            displayName = "Aru",
            language = "en_GB",
            sizeBytes = 76754234L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-aru-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-aru-medium.tokens.txt",
            ),
            // Aru is a multi-speaker dataset — leave gender Unknown so
            // the subtitle collapses cleanly to "Piper · Medium".
        ),
        CatalogEntry(
            id = "piper_cori_en_GB_medium",
            displayName = "Cori",
            language = "en_GB",
            sizeBytes = 63531507L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-cori-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-cori-medium.tokens.txt",
            ),
            gender = VoiceGender.Female,
        ),
        CatalogEntry(
            id = "piper_cori_en_GB_high",
            displayName = "Cori",
            language = "en_GB",
            sizeBytes = 114219480L,
            qualityLevel = QualityLevel.High,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-cori-high.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-cori-high.tokens.txt",
            ),
            gender = VoiceGender.Female,
        ),
        CatalogEntry(
            id = "piper_dii_en_GB_high",
            displayName = "Dii",
            language = "en_GB",
            sizeBytes = 63511174L,
            qualityLevel = QualityLevel.High,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-dii-high.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-dii-high.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_jenny_dioco_en_GB_medium",
            displayName = "Jenny Dioco",
            language = "en_GB",
            sizeBytes = 63201430L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-jenny_dioco-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-jenny_dioco-medium.tokens.txt",
            ),
            gender = VoiceGender.Female,
        ),
        CatalogEntry(
            id = "piper_miro_en_GB_high",
            displayName = "Miro",
            language = "en_GB",
            sizeBytes = 63511174L,
            qualityLevel = QualityLevel.High,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-miro-high.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-miro-high.tokens.txt",
            ),
            gender = VoiceGender.Male,
        ),
        CatalogEntry(
            id = "piper_northern_english_male_en_GB_medium",
            displayName = "Northern English",
            language = "en_GB",
            sizeBytes = 63201430L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-northern_english_male-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-northern_english_male-medium.tokens.txt",
            ),
            // Gender lived in the title before #128 ("Northern English Male");
            // it now lives in the subtitle, so the title drops the suffix.
            gender = VoiceGender.Male,
        ),
        CatalogEntry(
            id = "piper_semaine_en_GB_medium",
            displayName = "Semaine",
            language = "en_GB",
            sizeBytes = 76737847L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-semaine-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-semaine-medium.tokens.txt",
            ),
            // Semaine is a multi-speaker corpus — leave gender Unknown.
        ),
        CatalogEntry(
            id = "piper_southern_english_female_en_GB_low",
            displayName = "Southern English",
            language = "en_GB",
            sizeBytes = 63104662L,
            qualityLevel = QualityLevel.Low,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-southern_english_female-low.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-southern_english_female-low.tokens.txt",
            ),
            gender = VoiceGender.Female,
        ),
        CatalogEntry(
            id = "piper_southern_english_female_en_GB_medium",
            displayName = "Southern English",
            language = "en_GB",
            sizeBytes = 77059414L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-southern_english_female-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-southern_english_female-medium.tokens.txt",
            ),
            gender = VoiceGender.Female,
        ),
        CatalogEntry(
            id = "piper_southern_english_male_en_GB_medium",
            displayName = "Southern English",
            language = "en_GB",
            sizeBytes = 77063512L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-southern_english_male-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-southern_english_male-medium.tokens.txt",
            ),
            gender = VoiceGender.Male,
        ),
        CatalogEntry(
            id = "piper_sweetbbak_amy_en_GB_high",
            displayName = "Sweetbbak Amy",
            language = "en_GB",
            sizeBytes = 114199142L,
            qualityLevel = QualityLevel.High,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-sweetbbak-amy.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-sweetbbak-amy.tokens.txt",
            ),
            gender = VoiceGender.Female,
        ),
        CatalogEntry(
            id = "piper_vctk_en_GB_medium",
            displayName = "VCTK",
            language = "en_GB",
            sizeBytes = 76952891L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-vctk-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-vctk-medium.tokens.txt",
            ),
            // VCTK is a multi-speaker corpus — leave gender Unknown.
        ),
        CatalogEntry(
            id = "piper_amy_en_US_low",
            displayName = "Amy",
            language = "en_US",
            sizeBytes = 63104657L,
            qualityLevel = QualityLevel.Low,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-amy-low.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-amy-low.tokens.txt",
            ),
            gender = VoiceGender.Female,
        ),
        CatalogEntry(
            id = "piper_arctic_en_US_medium",
            displayName = "Arctic",
            language = "en_US",
            sizeBytes = 76715309L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-arctic-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-arctic-medium.tokens.txt",
            ),
            // CMU Arctic is a multi-speaker corpus — gender Unknown.
        ),
        CatalogEntry(
            id = "piper_bryce_en_US_medium",
            displayName = "Bryce",
            language = "en_US",
            sizeBytes = 63152970L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-bryce-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-bryce-medium.tokens.txt",
            ),
            gender = VoiceGender.Male,
        ),
        CatalogEntry(
            id = "piper_danny_en_US_low",
            displayName = "Danny",
            language = "en_US",
            sizeBytes = 63052430L,
            qualityLevel = QualityLevel.Low,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-danny-low.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-danny-low.tokens.txt",
            ),
            gender = VoiceGender.Male,
        ),
        CatalogEntry(
            id = "piper_glados_en_US_high",
            displayName = "GLaDOS",
            language = "en_US",
            sizeBytes = 113800584L,
            qualityLevel = QualityLevel.High,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-glados-high.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-glados-high.tokens.txt",
            ),
            gender = VoiceGender.Female,
        ),
        CatalogEntry(
            id = "piper_glados_en_US_medium",
            displayName = "GLaDOS",
            language = "en_US",
            sizeBytes = 63511169L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-glados.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-glados.tokens.txt",
            ),
            gender = VoiceGender.Female,
        ),
        CatalogEntry(
            id = "piper_hfc_female_en_US_medium",
            displayName = "HFC",
            language = "en_US",
            sizeBytes = 63149198L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-hfc_female-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-hfc_female-medium.tokens.txt",
            ),
            // "Female" lived in the title before #128; now it lives only in the subtitle.
            gender = VoiceGender.Female,
        ),
        CatalogEntry(
            id = "piper_hfc_male_en_US_medium",
            displayName = "HFC",
            language = "en_US",
            sizeBytes = 63149198L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-hfc_male-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-hfc_male-medium.tokens.txt",
            ),
            gender = VoiceGender.Male,
        ),
        CatalogEntry(
            id = "piper_joe_en_US_medium",
            displayName = "Joe",
            language = "en_US",
            sizeBytes = 63149198L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-joe-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-joe-medium.tokens.txt",
            ),
            gender = VoiceGender.Male,
        ),
        CatalogEntry(
            id = "piper_john_en_US_medium",
            displayName = "John",
            language = "en_US",
            sizeBytes = 63152970L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-john-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-john-medium.tokens.txt",
            ),
            gender = VoiceGender.Male,
        ),
        CatalogEntry(
            id = "piper_kathleen_en_US_low",
            displayName = "Kathleen",
            language = "en_US",
            sizeBytes = 63052430L,
            qualityLevel = QualityLevel.Low,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-kathleen-low.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-kathleen-low.tokens.txt",
            ),
            gender = VoiceGender.Female,
        ),
        CatalogEntry(
            id = "piper_kristin_en_US_medium",
            displayName = "Kristin",
            language = "en_US",
            sizeBytes = 63531507L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-kristin-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-kristin-medium.tokens.txt",
            ),
            gender = VoiceGender.Female,
        ),
        CatalogEntry(
            id = "piper_kusal_en_US_medium",
            displayName = "Kusal",
            language = "en_US",
            sizeBytes = 63201425L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-kusal-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-kusal-medium.tokens.txt",
            ),
            gender = VoiceGender.Male,
        ),
        CatalogEntry(
            id = "piper_l2arctic_en_US_medium",
            displayName = "L2 Arctic",
            language = "en_US",
            sizeBytes = 76778805L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-l2arctic-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-l2arctic-medium.tokens.txt",
            ),
            // L2-Arctic is a multi-speaker non-native-English corpus — gender Unknown.
        ),
        CatalogEntry(
            id = "piper_lessac_en_US_low",
            displayName = "Lessac",
            language = "en_US",
            sizeBytes = 63149198L,
            qualityLevel = QualityLevel.Low,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-lessac-low.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-lessac-low.tokens.txt",
            ),
            gender = VoiceGender.Female,
        ),
        CatalogEntry(
            id = "piper_lessac_en_US_medium",
            displayName = "Lessac",
            language = "en_US",
            sizeBytes = 63149198L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-lessac-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-lessac-medium.tokens.txt",
            ),
            gender = VoiceGender.Female,
        ),
        CatalogEntry(
            id = "piper_libritts_en_US_high",
            displayName = "LibriTTS",
            language = "en_US",
            sizeBytes = 129438513L,
            qualityLevel = QualityLevel.High,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-libritts-high.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-libritts-high.tokens.txt",
            ),
            // LibriTTS is a multi-speaker corpus — gender Unknown.
        ),
        CatalogEntry(
            id = "piper_libritts_r_en_US_medium",
            displayName = "LibriTTS R",
            language = "en_US",
            sizeBytes = 78529840L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-libritts_r-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-libritts_r-medium.tokens.txt",
            ),
            // LibriTTS-R is a multi-speaker corpus — gender Unknown.
        ),
        CatalogEntry(
            id = "piper_ljspeech_en_US_medium",
            displayName = "LJ Speech",
            language = "en_US",
            sizeBytes = 63531507L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-ljspeech-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-ljspeech-medium.tokens.txt",
            ),
            gender = VoiceGender.Female,
        ),
        CatalogEntry(
            id = "piper_ljspeech_en_US_high",
            displayName = "LJ Speech",
            language = "en_US",
            sizeBytes = 114199139L,
            qualityLevel = QualityLevel.High,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-ljspeech-high.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-ljspeech-high.tokens.txt",
            ),
            gender = VoiceGender.Female,
        ),
        CatalogEntry(
            id = "piper_miro_en_US_high",
            displayName = "Miro",
            language = "en_US",
            sizeBytes = 63511169L,
            qualityLevel = QualityLevel.High,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-miro-high.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-miro-high.tokens.txt",
            ),
            gender = VoiceGender.Male,
        ),
        CatalogEntry(
            id = "piper_norman_en_US_medium",
            displayName = "Norman",
            language = "en_US",
            sizeBytes = 63531507L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-norman-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-norman-medium.tokens.txt",
            ),
            gender = VoiceGender.Male,
        ),
        CatalogEntry(
            id = "piper_reza_ibrahim_en_US_medium",
            displayName = "Reza Ibrahim",
            language = "en_US",
            sizeBytes = 63511169L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-reza_ibrahim-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-reza_ibrahim-medium.tokens.txt",
            ),
            gender = VoiceGender.Male,
        ),
        CatalogEntry(
            id = "piper_ryan_en_US_low",
            displayName = "Ryan",
            language = "en_US",
            sizeBytes = 63052430L,
            qualityLevel = QualityLevel.Low,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-ryan-low.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-ryan-low.tokens.txt",
            ),
            gender = VoiceGender.Male,
        ),
        CatalogEntry(
            id = "piper_ryan_en_US_medium",
            displayName = "Ryan",
            language = "en_US",
            sizeBytes = 63149198L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-ryan-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-ryan-medium.tokens.txt",
            ),
            gender = VoiceGender.Male,
        ),
        CatalogEntry(
            id = "piper_sam_en_US_medium",
            displayName = "Sam",
            language = "en_US",
            sizeBytes = 62946438L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-sam-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-sam-medium.tokens.txt",
            ),
            // "Sam" is unisex — leave gender Unknown.
        ),
    )

    /** Kokoro entries — 53 speakers all sharing one bundled model.
     *  Quality tier is **derived** from voice id via [VoiceTier.forKokoroId]
     *  rather than hardcoded inline so the rule lives in one place and
     *  stays unit-testable. The vast majority resolve to [QualityLevel.High];
     *  a curated few (see [VoiceTier.STUDIO_KOKORO_IDS]) get bumped to
     *  [QualityLevel.Studio].
     *
     *  Display names are clean (no flag prefix, no "(Language Gender)"
     *  parenthetical) per #128 — the Voice Library composes the on-screen
     *  title as `<flag> <displayName>` and the subtitle as
     *  `<Engine> · <Tier> · <Gender>`. Language is already represented by
     *  the rendered flag, so we don't repeat it. */
    private fun kokoroEntries(): List<CatalogEntry> {
        fun kokoro(id: String, displayName: String, language: String, speakerId: Int, gender: VoiceGender): CatalogEntry =
            CatalogEntry(
                id = id,
                displayName = displayName,
                language = language,
                sizeBytes = 0L,
                qualityLevel = VoiceTier.forKokoroId(id),
                engineType = EngineType.Kokoro(speakerId = speakerId),
                piper = null,
                gender = gender,
            )
        val F = VoiceGender.Female
        val M = VoiceGender.Male
        return listOf(
            kokoro("kokoro_alloy_en_US_0", "Alloy", "en_US", 0, F),
            kokoro("kokoro_aoede_en_US_1", "Aoede", "en_US", 1, F),
            kokoro("kokoro_bella_en_US_2", "Bella", "en_US", 2, F),
            kokoro("kokoro_heart_en_US_3", "Heart", "en_US", 3, F),
            kokoro("kokoro_jessica_en_US_4", "Jessica", "en_US", 4, F),
            kokoro("kokoro_kore_en_US_5", "Kore", "en_US", 5, F),
            kokoro("kokoro_nicole_en_US_6", "Nicole", "en_US", 6, F),
            kokoro("kokoro_nova_en_US_7", "Nova", "en_US", 7, F),
            kokoro("kokoro_river_en_US_8", "River", "en_US", 8, F),
            kokoro("kokoro_sarah_en_US_9", "Sarah", "en_US", 9, F),
            kokoro("kokoro_sky_en_US_10", "Sky", "en_US", 10, F),
            kokoro("kokoro_adam_en_US_11", "Adam", "en_US", 11, M),
            kokoro("kokoro_echo_en_US_12", "Echo", "en_US", 12, M),
            kokoro("kokoro_eric_en_US_13", "Eric", "en_US", 13, M),
            kokoro("kokoro_fenrir_en_US_14", "Fenrir", "en_US", 14, M),
            kokoro("kokoro_liam_en_US_15", "Liam", "en_US", 15, M),
            kokoro("kokoro_michael_en_US_16", "Michael", "en_US", 16, M),
            kokoro("kokoro_onyx_en_US_17", "Onyx", "en_US", 17, M),
            kokoro("kokoro_puck_en_US_18", "Puck", "en_US", 18, M),
            kokoro("kokoro_santa_en_US_19", "Santa", "en_US", 19, M),
            kokoro("kokoro_alice_en_GB_20", "Alice", "en_GB", 20, F),
            kokoro("kokoro_emma_en_GB_21", "Emma", "en_GB", 21, F),
            kokoro("kokoro_isabella_en_GB_22", "Isabella", "en_GB", 22, F),
            kokoro("kokoro_lily_en_GB_23", "Lily", "en_GB", 23, F),
            kokoro("kokoro_daniel_en_GB_24", "Daniel", "en_GB", 24, M),
            kokoro("kokoro_fable_en_GB_25", "Fable", "en_GB", 25, M),
            kokoro("kokoro_george_en_GB_26", "George", "en_GB", 26, M),
            kokoro("kokoro_lewis_en_GB_27", "Lewis", "en_GB", 27, M),
            kokoro("kokoro_dora_es_ES_28", "Dora", "es_ES", 28, F),
            kokoro("kokoro_alex_es_ES_29", "Alex", "es_ES", 29, M),
            kokoro("kokoro_siwis_fr_FR_30", "Siwis", "fr_FR", 30, F),
            kokoro("kokoro_alpha_hi_IN_31", "Alpha", "hi_IN", 31, F),
            kokoro("kokoro_beta_hi_IN_32", "Beta", "hi_IN", 32, F),
            kokoro("kokoro_omega_hi_IN_33", "Omega", "hi_IN", 33, M),
            kokoro("kokoro_psi_hi_IN_34", "Psi", "hi_IN", 34, M),
            kokoro("kokoro_sara_it_IT_35", "Sara", "it_IT", 35, F),
            kokoro("kokoro_nicola_it_IT_36", "Nicola", "it_IT", 36, M),
            kokoro("kokoro_alpha_ja_JP_37", "Alpha", "ja_JP", 37, F),
            kokoro("kokoro_gongitsune_ja_JP_38", "Gongitsune", "ja_JP", 38, F),
            kokoro("kokoro_nezumi_ja_JP_39", "Nezumi", "ja_JP", 39, F),
            kokoro("kokoro_tebukuro_ja_JP_40", "Tebukuro", "ja_JP", 40, F),
            kokoro("kokoro_kumo_ja_JP_41", "Kumo", "ja_JP", 41, M),
            kokoro("kokoro_dora_pt_PT_42", "Dora", "pt_PT", 42, F),
            kokoro("kokoro_alex_pt_PT_43", "Alex", "pt_PT", 43, M),
            kokoro("kokoro_santa_pt_PT_44", "Santa", "pt_PT", 44, M),
            kokoro("kokoro_xiaobei_zh_CN_45", "Xiaobei", "zh_CN", 45, F),
            kokoro("kokoro_xiaoni_zh_CN_46", "Xiaoni", "zh_CN", 46, F),
            kokoro("kokoro_xiaoxiao_zh_CN_47", "Xiaoxiao", "zh_CN", 47, F),
            kokoro("kokoro_xiaoyi_zh_CN_48", "Xiaoyi", "zh_CN", 48, F),
            kokoro("kokoro_yunjian_zh_CN_49", "Yunjian", "zh_CN", 49, M),
            kokoro("kokoro_yunxi_zh_CN_50", "Yunxi", "zh_CN", 50, M),
            kokoro("kokoro_yunxia_zh_CN_51", "Yunxia", "zh_CN", 51, M),
            kokoro("kokoro_yunyang_zh_CN_52", "Yunyang", "zh_CN", 52, M),
        )
    }

    /** KittenTTS entries — 8 speakers all sharing one ~25 MB fp16 model.
     *  Issue #119 — the smallest in-process tier, slotted below Piper-low
     *  as the friendliest "try a neural voice" first-launch onboarding
     *  path for slow devices (Raspberry Pi, old phones, wearables).
     *
     *  Shape mirrors Kokoro's: one shared model on disk (`_kitten_shared`
     *  dir handled by VoiceManager), 8 speaker indices baked into the
     *  voice ID. Picking any Kitten voice triggers the shared-model
     *  download exactly once; subsequent picks just flip the active
     *  speaker. The `kitten-nano-en-v0_1-fp16` upstream model ships 4
     *  female + 4 male English voices labelled `expr-voice-2-f`,
     *  `expr-voice-2-m`, etc. Display names are simplified (F1..F4 /
     *  M1..M4) so the picker row stays readable.
     *
     *  Tier is [QualityLevel.Low] because Kitten produces audibly
     *  more-compressed audio than Piper-medium and notably less prosody
     *  variety than Kokoro (it's optimized for size, not quality). The
     *  Low tier sits naturally below Piper's existing Low entries; the
     *  picker's group-by-engine ordering will surface Kitten beneath
     *  Piper / above Kokoro per the VoiceLibraryViewModel engine-sort
     *  rule. */
    private fun kittenEntries(): List<CatalogEntry> {
        fun kitten(id: String, displayName: String, speakerId: Int, gender: VoiceGender): CatalogEntry =
            CatalogEntry(
                id = id,
                displayName = displayName,
                language = "en_US",
                // sizeBytes mirrors Kokoro's 0L sentinel — Kitten voices
                // share one ~25 MB download, so per-voice byte counts
                // are misleading. The Voice Library suppresses the size
                // chip when sizeBytes == 0L (same code path as Kokoro).
                sizeBytes = 0L,
                qualityLevel = QualityLevel.Low,
                engineType = EngineType.Kitten(speakerId = speakerId),
                piper = null,
                gender = gender,
            )
        val F = VoiceGender.Female
        val M = VoiceGender.Male
        // Speaker order matches sherpa-onnx's voices.bin layout for
        // `kitten-nano-en-v0_1-fp16`: 4 female embeddings (indices 0..3)
        // followed by 4 male embeddings (indices 4..7). Names follow
        // upstream's `expr-voice-N-{f,m}` convention but rendered as
        // F1..F4 / M1..M4 for picker readability.
        return listOf(
            kitten("kitten_f1_en_US_0", "Kitten F1", 0, F),
            kitten("kitten_f2_en_US_1", "Kitten F2", 1, F),
            kitten("kitten_f3_en_US_2", "Kitten F3", 2, F),
            kitten("kitten_f4_en_US_3", "Kitten F4", 3, F),
            kitten("kitten_m1_en_US_4", "Kitten M1", 4, M),
            kitten("kitten_m2_en_US_5", "Kitten M2", 5, M),
            kitten("kitten_m3_en_US_6", "Kitten M3", 6, M),
            kitten("kitten_m4_en_US_7", "Kitten M4", 7, M),
        )
    }

    /**
     * Project the live Azure voice roster into [CatalogEntry] rows.
     * Originally this was a hardcoded curated list, but the curated
     * names drifted out of date with Azure's actual catalog (Dragon HD
     * voices use a colon-separated form like
     * `en-US-Ava:DragonHDLatestNeural` that v0.4.75 had wrong, and the
     * Dragon HD tier itself is region-scoped: eastus has it, westus
     * doesn't). The live roster — fetched on demand from Azure's
     * `voices/list` endpoint via `AzureVoiceProvider` — solves both
     * problems at once: the picker only ever surfaces voices that
     * actually exist in the user's configured region.
     *
     * Filters and sort:
     * - English locales first (en-*), then everything else.
     * - Within English, US → GB → AU → IN → CA, then alphabetical.
     * - Tiers grouped Dragon HD → HD Multilingual → Neural (Dragon HD
     *   surfaces first because it's the highest quality).
     * - Display name composes "☁️ {name} · {locale} · {tier}" so the
     *   picker can render a single line without computing the
     *   subtitle separately.
     *
     * `sizeBytes = 0` — Azure voices live server-side; nothing to
     * download. The runtime region used for the actual SSML POST
     * comes from `AzureCredentials.region()`; the catalog entry's
     * region is just a seed for code paths that key on
     * `EngineType.Azure.region` before the user opens Settings.
     */
    fun azureEntriesFromRoster(roster: List<AzureVoiceDescriptor>): List<CatalogEntry> {
        if (roster.isEmpty()) return emptyList()
        val cost = VoiceCost(centsPer1MChars = 3000, billedBy = "Azure")
        val defaultRegion = "eastus"
        // ShortName carries colons for Dragon HD entries — sanitize to
        // underscores when building the catalog ID so the ID stays
        // greppable and survives any code path that splits on ':'.
        fun stableId(shortName: String): String =
            "azure_" + shortName.replace(':', '_')
        return roster
            .asSequence()
            .sortedWith(compareBy(
                { englishLocaleRank(it.locale) },
                { tierRank(it.tier) },
                { it.shortName },
            ))
            .map { v ->
                val localeUnderscored = v.locale.replace('-', '_')
                CatalogEntry(
                    id = stableId(v.shortName),
                    displayName = "☁️ ${v.displayName} · ${v.locale} · ${v.tier.displayLabel}",
                    language = localeUnderscored,
                    sizeBytes = 0L,
                    qualityLevel = QualityLevel.Studio,
                    engineType = EngineType.Azure(v.shortName, defaultRegion),
                    piper = null,
                    cost = cost,
                )
            }
            .toList()
    }

    /** English locales surface first, then the rest in alpha order.
     *  Returns a sortable key — lower = earlier in the list. */
    private fun englishLocaleRank(locale: String): Int = when {
        locale == "en-US" -> 0
        locale == "en-GB" -> 1
        locale == "en-AU" -> 2
        locale == "en-IN" -> 3
        locale == "en-CA" -> 4
        locale.startsWith("en-") -> 5
        else -> 100
    }

    /** Dragon HD first (best quality), then HD Multilingual, then plain Neural. */
    private fun tierRank(tier: AzureVoiceTier): Int = when (tier) {
        AzureVoiceTier.DragonHd -> 0
        AzureVoiceTier.HdMultilingual -> 1
        AzureVoiceTier.Neural -> 2
    }

    /**
     * Issue #676 — project the live System TTS roster into
     * [CatalogEntry] rows.
     *
     * Filters and sort:
     *  - Roster comes pre-sorted from [SystemTtsVoiceProvider] (English
     *    locales first, offline before network); we preserve order.
     *  - Display name is the upstream roster's [displayName] —
     *    framework `Voice.name` humanized + the engine label as
     *    subtitle anchor. Catalog entry's `displayName` keeps the
     *    voice name clean and lets the picker compose the
     *    `<flag> displayName` title separately, matching the #128
     *    rendering contract.
     *  - `sizeBytes = 0L` because there's nothing to download —
     *    everything lives in the OS's installed TTS engines already.
     *  - `qualityLevel = QualityLevel.Medium` is a reasonable middle
     *    default; the framework doesn't expose a quality grade and
     *    bumping every entry to High would push System TTS above Piper
     *    in the picker, which would mislead users who picked the family
     *    expecting "the OS default, lowest commitment". Medium tier
     *    matches Piper-medium's audible bar while keeping the section
     *    clearly subordinate to High/Studio Piper/Kokoro picks for
     *    listeners who prefer neural quality.
     *  - ID format: `system_tts_{engineName}_{voiceName}` — the
     *    engine package id keeps multiple engines (Google + Samsung)
     *    distinguishable even when their voice names collide
     *    (`en-US-language` appears on both). The id replaces non-ID
     *    characters with `_` so it survives any code path that
     *    splits on `:` / `.`.
     */
    fun systemTtsEntriesFromRoster(
        roster: List<SystemTtsVoiceDescriptor>,
    ): List<CatalogEntry> {
        if (roster.isEmpty()) return emptyList()
        return roster.map { v ->
            val localeUnderscored = v.locale.replace('-', '_')
            val safeEngine = v.engineName.replace(Regex("[^A-Za-z0-9]"), "_")
            val safeVoice = v.voiceName.replace(Regex("[^A-Za-z0-9]"), "_")
            CatalogEntry(
                id = "system_tts_${safeEngine}_${safeVoice}",
                displayName = v.displayName,
                language = localeUnderscored,
                sizeBytes = 0L,
                qualityLevel = QualityLevel.Medium,
                engineType = EngineType.SystemTts(
                    engineName = v.engineName,
                    voiceName = v.voiceName,
                ),
                piper = null,
                gender = VoiceGender.Unknown,
            )
        }
    }

}

data class CatalogEntry(
    val id: String,
    /** Clean voice name only — e.g. "Lessac", "Aoede". No tier
     *  parentheticals, no flag prefix, no engine/quality clutter. The
     *  ⭐ marker on curated entries stays inline (see [VoiceCatalog.featuredIds]).
     *  See #128 — the Voice Library composes the on-screen title as
     *  `<flag> <displayName>` and the subtitle as `<Engine> · <Tier> · <Gender>`. */
    val displayName: String,
    val language: String,
    val sizeBytes: Long,
    val qualityLevel: QualityLevel,
    val engineType: EngineType,
    val piper: PiperPaths?,
    /**
     * Per-voice billing rate for cloud engines. `null` for local
     * engines (Piper, Kokoro) — no per-character cost.
     *
     * Surfaces in the picker as a small annotation chip beneath the
     * display name (e.g. "$30 / 1M chars · Azure"), and feeds the
     * first-time cost-disclosure modal that fires when a user picks
     * an Azure voice for the first time. Single source of truth for
     * cost numbers — bumping a provider's published price is a
     * one-line change here, not a UI hunt.
     */
    val cost: VoiceCost? = null,
    /** Best-effort gender from upstream metadata. Defaults to
     *  [VoiceGender.Unknown] for Piper voices whose filenames don't
     *  encode gender (e.g. "lessac" — a name, not a gender marker). */
    val gender: VoiceGender = VoiceGender.Unknown,
)

data class PiperPaths(val onnxUrl: String, val tokensUrl: String)

/**
 * Per-million-character billing for a cloud TTS voice. Stored as
 * integer cents to avoid floating-point cost arithmetic — the picker
 * formats the chip ("$30 / 1M chars · $billedBy") and the per-chapter
 * estimate computes `chars × centsPer1MChars / 1_000_000` cents, which
 * stays an integer until the final display.
 *
 * [billedBy] surfaces the provider name in the cost disclosure modal
 * ("You pay $billedBy directly — storyvox does not bill you.") so the
 * trust-boundary statement reads naturally regardless of which cloud
 * provider the entry routes to.
 */
data class VoiceCost(
    val centsPer1MChars: Int,
    val billedBy: String,
)

/** Map a BCP-47-ish language code (the catalog uses POSIX-style
 *  `xx_YY`) to a country flag emoji. Falls back to a globe glyph for
 *  any code we haven't enumerated — keeps the title prefix non-empty
 *  so layout doesn't shift when a new language lands in the catalog
 *  before its flag mapping does. */
fun flagForLanguage(language: String): String = when (language) {
    "en_US" -> "🇺🇸"
    "en_GB" -> "🇬🇧"
    "es_ES" -> "🇪🇸"
    "fr_FR" -> "🇫🇷"
    "hi_IN" -> "🇮🇳"
    "it_IT" -> "🇮🇹"
    "ja_JP" -> "🇯🇵"
    "pt_PT" -> "🇵🇹"
    "zh_CN" -> "🇨🇳"
    else -> "🌐"
}
