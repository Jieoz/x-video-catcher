package androidx.media3.datasource;

import android.net.Uri;
import java.util.Map;

/**
 * The decoy that matters: {@code DataSpec.Builder}, which R8 emits as {@code j$a}.
 *
 * It declares the <em>same nine field types in the same order</em> as {@link DataSpecFixture}. An
 * ablation against the real 12.13.0-release.0 APK matched both classes on field types alone, so this
 * is a reproduction of a real ambiguity rather than a hypothetical one.
 *
 * The difference the resolver must rely on is mutability: a builder's fields are assignable, a
 * DataSpec's are final. Deleting the final check from {@code MediaSpy.hasDataSpecShape} must turn
 * {@code rejectsTheBuilder} red.
 */
public final class BuilderDecoy {
    public Uri uri;
    public long uriPositionOffset;
    public int httpMethod;
    public byte[] httpBody;
    public Map<String, String> httpRequestHeaders;
    public long position;
    public long length;
    public String key;
    public int flags;
}
