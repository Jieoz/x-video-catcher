package dagger.internal;

/**
 * Shaped after the wrapper the device log counted 846 times under `dagger.internal.d`.
 *
 * The point of the double is its *package*, which is what the prune matches, plus a field graph
 * that leads somewhere expensive -- mirroring how a real DoubleCheck reaches the whole
 * application singleton graph through its provider.
 */
public final class DoubleCheck {
    public Object provider;
    public Object instance;

    public DoubleCheck(Object provider, Object instance) {
        this.provider = provider;
        this.instance = instance;
    }
}
