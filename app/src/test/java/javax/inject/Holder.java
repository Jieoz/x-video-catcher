package javax.inject;

/** The JSR-330 provider surface, pruned for the same reason as Dagger's own wrappers. */
public final class Holder {
    public Object t;

    public Holder(Object t) {
        this.t = t;
    }
}
