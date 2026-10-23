package com.megatron.player

import android.net.Uri
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.avi.AviExtractor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class VideoExtractorsFactory : ExtractorsFactory {
    // Default TS scan is only 112,800 bytes; sparse PCR/high bitrate files can miss it.
    private val standard = DefaultExtractorsFactory().setTsExtractorTimestampSearchBytes(4 * 1024 * 1024)
    private var subtitleFactory: SubtitleParser.Factory = DefaultSubtitleParserFactory()
    private var transcodeText = true

    @Synchronized
    override fun setSubtitleParserFactory(factory: SubtitleParser.Factory): ExtractorsFactory = apply {
        subtitleFactory = factory
        standard.setSubtitleParserFactory(factory)
    }

    @Suppress("DEPRECATION")
    @Synchronized
    override fun experimentalSetTextTrackTranscodingEnabled(enabled: Boolean): ExtractorsFactory = apply {
        transcodeText = enabled
        standard.experimentalSetTextTrackTranscodingEnabled(enabled)
    }

    @Synchronized
    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(flags: Int): ExtractorsFactory = apply {
        standard.experimentalSetCodecsToParseWithinGopSampleDependencies(flags)
    }

    override fun createExtractors(): Array<Extractor> = createExtractors(Uri.EMPTY, emptyMap())

    @Synchronized
    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        standard.createExtractors(uri, responseHeaders).map { extractor ->
            if (extractor is MatroskaExtractor) CompatibleMatroskaExtractor(subtitleFactory,
                if (transcodeText) 0 else MatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA)
            else if (extractor is AviExtractor) CompatibleAviExtractor(extractor)
            else extractor
        }.toTypedArray()
}
