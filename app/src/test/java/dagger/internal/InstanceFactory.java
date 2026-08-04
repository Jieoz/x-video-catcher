package dagger.internal;

/** A second DI wrapper class, so the prune cannot be a single hard-coded class name. */
public final class InstanceFactory {
    public Object value;

    public InstanceFactory(Object value) {
        this.value = value;
    }
}
