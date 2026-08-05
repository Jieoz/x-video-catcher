package androidx.media3.datasource;

import android.net.Uri;
import java.util.Map;

/**
 * Same nine types, all final, but two of them swapped: {@code position} and {@code key} traded
 * places, so the sequence reads {@code ... long, String, long, int}.
 *
 * This is what makes the resolver's check an ordered comparison rather than a set comparison. If
 * {@code hasDataSpecShape} compared field types as an unordered bag, this class would match and the
 * hook would read a {@code String} where it expects a {@code Uri} at argument 0.
 */
public final class ReorderedDecoy {
    public final Uri uri;
    public final long uriPositionOffset;
    public final int httpMethod;
    public final byte[] httpBody;
    public final Map<String, String> httpRequestHeaders;
    public final long position;
    public final String key;
    public final long length;
    public final int flags;

    public ReorderedDecoy(
            Uri uri,
            long uriPositionOffset,
            int httpMethod,
            byte[] httpBody,
            Map<String, String> httpRequestHeaders,
            long position,
            String key,
            long length,
            int flags) {
        this.uri = uri;
        this.uriPositionOffset = uriPositionOffset;
        this.httpMethod = httpMethod;
        this.httpBody = httpBody;
        this.httpRequestHeaders = httpRequestHeaders;
        this.position = position;
        this.key = key;
        this.length = length;
        this.flags = flags;
    }
}
