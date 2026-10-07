package com.google.android.exoplayer2.source.sabr.parser.ump;

import com.google.android.exoplayer2.extractor.ExtractorInput;

public class UMPPart {
    public final int partId;
    public final int size;
    public final ExtractorInput data;

    public UMPPart(int partId, int size, ExtractorInput data) {
        this.partId = partId;
        this.size = size;
        this.data = data;
    }

    public UMPInputStream toStream() {
        return new UMPInputStream(this);
    }

    public void skip() {
        try {
            data.skipFully(size);
        } catch (Exception e) {
            // A response may end with a truncated advisory extension. There is no media payload to
            // recover from an unknown part, so let the decoder observe EOF and request the next SABR
            // response instead of failing the entire playback session.
        }
    }
}
