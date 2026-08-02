package androidx.fragment.app;

/**
 * Compile-time stand-in for the real FragmentManager, for unit tests only.
 *
 * HostResolver matches the show method by its parameter's fully-qualified name, so a test double
 * has to live in the real package to exercise that path. The androidx artifact is not on the
 * unit-test classpath (it is an Android-only dependency), and pulling it in would drag the whole
 * fragment library into a pure-JVM test run for one type name.
 */
public class FragmentManager {
}
