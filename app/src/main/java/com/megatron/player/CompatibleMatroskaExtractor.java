package com.megatron.player;

import androidx.media3.common.C;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.mkv.MatroskaExtractor;
import androidx.media3.extractor.text.SubtitleParser;

/** Narrow Media3 1.9.1 compatibility bridge. PGS/Vobsub parsers already inflate zlib frames. */
@UnstableApi
public final class CompatibleMatroskaExtractor extends MatroskaExtractor {
    // Matroska element IDs, matching the pinned Media3 extractor and Matroska specification.
    private static final int TRACK_ENTRY = 0xAE;
    private static final int CONTENT_COMPRESSION = 0x5034;
    private static final int CONTENT_COMP_ALGO = 0x4254;
    private boolean compressionSeen;
    private boolean zlib;

    public CompatibleMatroskaExtractor(SubtitleParser.Factory parserFactory, int flags) {
        super(parserFactory, flags);
    }

    @Override
    protected void startMasterElement(int id, long position, long size) throws ParserException {
        super.startMasterElement(id, position, size);
        if (id == TRACK_ENTRY) {
            compressionSeen = false;
            zlib = false;
        } else if (id == CONTENT_COMPRESSION) {
            if (compressionSeen) throw ParserException.createForUnsupportedContainerFeature("Multiple content compression layers not supported");
            compressionSeen = true;
            // ContentCompAlgo defaults to zlib when the integer element is omitted.
            zlib = true;
        }
    }

    @Override
    protected void integerElement(int id, long value) throws ParserException {
        if (id == CONTENT_COMP_ALGO && value == 0 && compressionSeen) {
            getCurrentTrack(id); // Keep the base extractor's structural validation.
            zlib = true;
            return;
        }
        if (id == CONTENT_COMP_ALGO) zlib = false;
        super.integerElement(id, value);
    }

    @Override
    protected void endMasterElement(int id) throws ParserException {
        if (id == TRACK_ENTRY && zlib) {
            Track track = getCurrentTrack(id);
            // Forward compressed bitmap subtitles unchanged to Media3's own parsers. Never treat
            // compressed audio/video, prefixed text, encryption or dictionaries as raw samples.
            if (track.type != C.TRACK_TYPE_TEXT || (!"S_HDMV/PGS".equals(track.codecId) && !"S_VOBSUB".equals(track.codecId))
                    || track.hasContentEncryption
                    || (track.sampleStrippedBytes != null && track.sampleStrippedBytes.length > 0)) {
                throw ParserException.createForUnsupportedContainerFeature("Zlib compression is supported only for plain PGS or Vobsub subtitle frames");
            }
        }
        super.endMasterElement(id);
    }
}
