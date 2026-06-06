package `in`.jphe.storyvox.source.ocr.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.source.ocr.OcrSource
import javax.inject.Singleton

/**
 * Issue #995 — contributes [OcrSource] into the multi-source
 * `Map<String, FictionSource>` (#235). With this binding active, the
 * segmented source picker in Browse gets a "Scanned text" entry, and
 * any persisted fiction with sourceId="ocr" routes through this source.
 *
 * Kept alongside the `@SourcePlugin`-generated descriptor binding while
 * the plugin seam (#384) completes its Phase-3 legacy-binding removal —
 * same coexistence posture as `:source-epub`.
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface OcrBindings {

    @Binds
    @Singleton
    @IntoMap
    @StringKey(SourceIds.OCR)
    fun bindFictionSource(impl: OcrSource): FictionSource
}
