package com.x1colegal.materialyt.sabr;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.extractor.DefaultExtractorInput;
import com.google.android.exoplayer2.extractor.ExtractorInput;
import com.google.android.exoplayer2.source.sabr.manifest.SabrManifest;
import com.google.android.exoplayer2.source.sabr.parser.SabrStream;
import com.google.android.exoplayer2.source.sabr.parser.parts.MediaSegmentDataSabrPart;
import com.google.android.exoplayer2.source.sabr.parser.parts.MediaSegmentEndSabrPart;
import com.google.android.exoplayer2.source.sabr.parser.parts.PoTokenStatusSabrPart;
import com.google.android.exoplayer2.source.sabr.parser.parts.SabrPart;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.VideoPlaybackAbrRequest;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.TransferListener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exposes one selected SABR track as a continuous byte stream for ExoPlayer's progressive source.
 * Audio and video use independent instances but share the same {@link SabrManifest} session.
 */
public final class SabrTrackDataSource implements DataSource {
    public static final class Factory implements DataSource.Factory {
        private final DataSource.Factory upstreamFactory;
        private final SabrManifest manifest;
        private final int trackType;
        private final List<TransferListener> listeners = new ArrayList<>();

        public Factory(@NonNull final DataSource.Factory upstreamFactory,
                       @NonNull final SabrManifest manifest,
                       final int trackType) {
            this.upstreamFactory = upstreamFactory;
            this.manifest = manifest;
            this.trackType = trackType;
        }

        @Override
        public DataSource createDataSource() {
            final DataSource source = upstreamFactory.createDataSource();
            for (final TransferListener listener : listeners) source.addTransferListener(listener);
            return new SabrTrackDataSource(source, manifest, trackType);
        }

        public Factory setTransferListener(@Nullable final TransferListener listener) {
            if (listener != null) listeners.add(listener);
            return this;
        }
    }

    private final DataSource upstream;
    private final SabrManifest manifest;
    private final int trackType;
    private final SabrStream stream;
    private final Map<String, String> requestHeaders;

    @Nullable private ExtractorInput responseInput;
    @Nullable private MediaSegmentDataSabrPart mediaPart;
    @Nullable private Uri currentUri;
    private int mediaPartRemaining;
    private int requestCount;
    private int emptyResponses;
    private long bytesRead;
    private boolean opened;
    private boolean ended;

    private SabrTrackDataSource(@NonNull final DataSource upstream,
                                @NonNull final SabrManifest manifest,
                                final int trackType) {
        this.upstream = upstream;
        this.manifest = manifest;
        this.trackType = trackType;
        this.stream = manifest.getSabrStream(trackType);
        final Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/vnd.yt-ump");
        headers.put("Content-Type", "application/x-protobuf");
        headers.put("Accept-Encoding", "identity");
        headers.put("User-Agent", manifest.requestUserAgent != null
                ? manifest.requestUserAgent
                : "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36");
        requestHeaders = Collections.unmodifiableMap(headers);
    }

    @Override
    public long open(@NonNull final DataSpec dataSpec) throws IOException {
        closeResponse();
        mediaPart = null;
        mediaPartRemaining = 0;
        requestCount = 0;
        emptyResponses = 0;
        bytesRead = 0;
        ended = false;
        opened = true;
        openNextResponse();
        return C.LENGTH_UNSET;
    }

    @Override
    public int read(@NonNull final byte[] buffer, final int offset, final int length)
            throws IOException {
        if (length == 0) return 0;
        if (!opened || ended) return C.RESULT_END_OF_INPUT;

        while (true) {
            if (mediaPart != null && mediaPartRemaining > 0) {
                final int requested = Math.min(length, mediaPartRemaining);
                final int read;
                try {
                    read = mediaPart.data.read(buffer, offset, requested);
                } catch (final RuntimeException error) {
                    throw new IOException("Could not read SABR media payload", error);
                }
                if (read > 0) {
                    mediaPartRemaining -= read;
                    bytesRead += read;
                    return read;
                }
                mediaPart = null;
                mediaPartRemaining = 0;
            }

            final SabrPart part = nextPart();
            if (part instanceof MediaSegmentDataSabrPart) {
                mediaPart = (MediaSegmentDataSabrPart) part;
                mediaPartRemaining = mediaPart.contentLength;
                emptyResponses = 0;
                continue;
            }
            if (part instanceof MediaSegmentEndSabrPart) {
                final MediaSegmentEndSabrPart end = (MediaSegmentEndSabrPart) part;
                if (!end.isInitSegment && end.totalSegments > 0
                        && end.sequenceNumber >= end.totalSegments) {
                    ended = true;
                    closeResponse();
                    return C.RESULT_END_OF_INPUT;
                }
                continue;
            }
            if (part instanceof PoTokenStatusSabrPart) {
                final PoTokenStatusSabrPart tokenStatus = (PoTokenStatusSabrPart) part;
                if (tokenStatus.status == PoTokenStatusSabrPart.PoTokenStatus.INVALID
                        || tokenStatus.status == PoTokenStatusSabrPart.PoTokenStatus.MISSING
                        || tokenStatus.status == PoTokenStatusSabrPart.PoTokenStatus.PENDING_MISSING) {
                    throw new IOException("YouTube rejected the SABR streaming PoToken: "
                            + tokenStatus.status);
                }
                continue;
            }
            if (part != null) continue;

            closeResponse();
            if (++emptyResponses > 3) {
                throw new IOException("YouTube returned no SABR media data after multiple requests");
            }
            final int backoffMs = Math.min(stream.getBackoffTimeMs(), 2_000);
            if (backoffMs > 0) {
                try {
                    Thread.sleep(backoffMs);
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for the next SABR request", interrupted);
                }
            }
            openNextResponse();
        }
    }

    @Nullable
    private SabrPart nextPart() throws IOException {
        if (responseInput == null) return null;
        try {
            return stream.parse(responseInput);
        } catch (final RuntimeException error) {
            throw new IOException("Could not parse YouTube SABR/UMP response", error);
        }
    }

    private void openNextResponse() throws IOException {
        final boolean initializationRequest = requestCount == 0;
        final VideoPlaybackAbrRequest request;
        final String requestUrl;
        try {
            request = manifest.createVideoPlaybackAbrRequest(trackType, initializationRequest);
            requestUrl = manifest.getRequestUrl(trackType);
        } catch (final RuntimeException error) {
            throw new IOException("Could not create SABR request", error);
        }

        currentUri = Uri.parse(requestUrl);
        final DataSpec spec = new DataSpec.Builder()
                .setUri(currentUri)
                .setHttpMethod(DataSpec.HTTP_METHOD_POST)
                .setHttpBody(request.toByteArray())
                .setHttpRequestHeaders(requestHeaders)
                .setFlags(DataSpec.FLAG_ALLOW_GZIP)
                .build();
        try {
            final long length = upstream.open(spec);
            responseInput = new DefaultExtractorInput(upstream, 0, length);
            requestCount++;
        } catch (final IOException first) {
            closeResponse();
            if (manifest.maybeUseNextCdn(requestUrl)) {
                currentUri = Uri.parse(manifest.getRequestUrl(trackType));
                final DataSpec retry = spec.buildUpon().setUri(currentUri).build();
                final long length = upstream.open(retry);
                responseInput = new DefaultExtractorInput(upstream, 0, length);
                requestCount++;
            } else {
                throw first;
            }
        }
    }

    private void closeResponse() {
        responseInput = null;
        try { upstream.close(); } catch (final IOException ignored) { }
    }

    @Nullable
    @Override
    public Uri getUri() { return currentUri; }

    @Override
    public Map<String, List<String>> getResponseHeaders() { return upstream.getResponseHeaders(); }

    @Override
    public void addTransferListener(@NonNull final TransferListener transferListener) {
        upstream.addTransferListener(transferListener);
    }

    @Override
    public void close() {
        opened = false;
        ended = true;
        mediaPart = null;
        mediaPartRemaining = 0;
        closeResponse();
    }
}
