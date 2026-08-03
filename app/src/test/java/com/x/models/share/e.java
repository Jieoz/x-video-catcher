package com.x.models.share;

/**
 * Decoy: five fields and full value-type methods, but the wrong field *types*.
 *
 * Pins the per-type count clause, which ablation exposed as a no-op: every other row decoy also
 * differs in total field count, so deleting the type counts left the suite green. Five fields with
 * data-class methods and no Drawable is the only fixture that isolates it — it satisfies the count and
 * the value-type checks and must still be refused, because a row without an icon field is not the
 * model the sheet renders.
 */
public final class e {

    public final String one;
    public final String two;
    public final String three;
    public final int rank;
    public final boolean flag;

    public e(String one, String two, String three, int rank, boolean flag) {
        this.one = one;
        this.two = two;
        this.three = three;
        this.rank = rank;
        this.flag = flag;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof e && ((e) other).one.equals(one);
    }

    @Override
    public int hashCode() {
        return one.hashCode();
    }

    @Override
    public String toString() {
        return "DecoyNoDrawable";
    }
}
