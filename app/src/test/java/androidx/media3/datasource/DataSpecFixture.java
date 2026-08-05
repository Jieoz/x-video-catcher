package androidx.media3.datasource;

import android.net.Uri;
import java.util.Map;

/**
 * Stands in for the real {@code DataSpec}: nine final fields in the order media3 declares them.
 *
 * Field <em>names</em> are deliberately spelled out where R8 shortens them to {@code a}..{@code i},
 * so a resolver that matched on names instead of types and order would fail this fixture.
 */
public final class DataSpecFixture {
    public final Uri uri;
    public final long uriPositionOffset;
    public final int httpMethod;
    public final byte[] httpBody;
    public final Map<String, String> httpRequestHeaders;
    public final long position;
    public final long length;
    public final String key;
    public final int flags;

    public DataSpecFixture(
            Uri uri,
            long uriPositionOffset,
            int httpMethod,
            byte[] httpBody,
            Map<String, String> httpRequestHeaders,
            long position,
            long length,
            String key,
            int flags) {
        this.uri = uri;
        this.uriPositionOffset = uriPositionOffset;
        this.httpMethod = httpMethod;
        this.httpBody = httpBody;
        this.httpRequestHeaders = httpRequestHeaders;
        this.position = position;
        this.length = length;
        this.key = key;
        this.flags = flags;
    }
}
