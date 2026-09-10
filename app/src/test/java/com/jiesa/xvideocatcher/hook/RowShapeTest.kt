package com.jiesa.xvideocatcher.hook

import android.graphics.drawable.Drawable
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the predicates to the shapes host 12.20.5 actually declares.
 *
 * Device logs from 20260828 and 20260909 are the evidence. On 12.20.5 the package holds one class;
 * on 12.24 it holds the row plus its metadata class:
 *
 * ```
 *   package com.x.models.share has 1 class(es); shapes follow
 *   shape a [String,String,String,Object,boolean]
 *   package com.x.models.share has 2 class(es); shapes follow
 *   shape a [String,String,String,Object,b]
 *   shape b [boolean,String,int]
 * ```
 *
 * That is the share row: three strings, an icon, a flag. The only difference from the verified
 * 12.13 build is the icon's declared type — `Drawable` widened to `java.lang.Object`. The old
 * predicate required `Drawable`, so it rejected the single candidate that existed and every release
 * from 1.43 to 1.46 injected no download row at all.
 *
 * These tests fail against the `Drawable`-typed predicate and pass against the widened one, so the
 * regression cannot come back silently.
 */
class RowShapeTest {

    /** The 12.20.5 shape, verbatim from the device log. */
    @Suppress("unused")
    private class ObjectIconRow(
        val a: String,
        val b: String,
        val c: String,
        val d: Any?,
        val e: Boolean,
    ) {
        override fun equals(other: Any?): Boolean = other is ObjectIconRow
        override fun hashCode(): Int = 0
        override fun toString(): String = "row"
    }

    /** The 12.13 shape, which must keep matching. */
    @Suppress("unused")
    private class DrawableIconRow(
        val a: String,
        val b: String,
        val c: String,
        val d: Drawable?,
        val e: Boolean,
    ) {
        override fun equals(other: Any?): Boolean = other is DrawableIconRow
        override fun hashCode(): Int = 0
        override fun toString(): String = "row"
    }

    /**
     * The 12.24 metadata type is an enum. Its diagnostic instance shape is
     * `[boolean,String,int]` because every enum instance inherits `name` and `ordinal`; only the
     * boolean is declared by the host enum itself.
     */
    @Suppress("unused")
    private enum class Metadata(val capturesShareCard: Boolean) {
        Text(false),
        InstagramStories(true),
        SnapchatCamera(true),
    }

    @Suppress("unused")
    private class MetadataRow(
        val a: String,
        val b: String,
        val c: String,
        val d: Any?,
        val e: Metadata,
    ) {
        override fun equals(other: Any?): Boolean = other is MetadataRow
        override fun hashCode(): Int = 0
        override fun toString(): String = "row"
    }

    /** A reference tail must not be accepted merely because it is a three-field object. */
    @Suppress("unused")
    private class WrongMetadataTypes(
        val a: Boolean,
        val b: String,
        val c: Long,
    )

    @Suppress("unused")
    private class WrongMetadataRow(
        val a: String,
        val b: String,
        val c: String,
        val d: Any?,
        val e: WrongMetadataTypes,
    ) {
        override fun equals(other: Any?): Boolean = other is WrongMetadataRow
        override fun hashCode(): Int = 0
        override fun toString(): String = "row"
    }

    /** Metadata cannot be mistaken for the icon in a legacy boolean-tail row. */
    @Suppress("unused")
    private class MetadataPlusBooleanRow(
        val a: String,
        val b: String,
        val c: String,
        val d: Metadata,
        val e: Boolean,
    ) {
        override fun equals(other: Any?): Boolean = other is MetadataPlusBooleanRow
        override fun hashCode(): Int = 0
        override fun toString(): String = "row"
    }

    /** Two non-String references: ambiguous, so it must stay rejected. */
    @Suppress("unused")
    private class TwoObjectRow(
        val a: String,
        val b: String,
        val c: Any?,
        val d: Any?,
        val e: Boolean,
    ) {
        override fun equals(other: Any?): Boolean = other is TwoObjectRow
        override fun hashCode(): Int = 0
        override fun toString(): String = "row"
    }

    /** Right field count and types, but no data-class methods. */
    @Suppress("unused")
    private class NoDataMethodsRow(
        val a: String,
        val b: String,
        val c: String,
        val d: Any?,
        val e: Boolean,
    )

    @Test
    fun `the 12_20_5 row with an Object icon matches`() {
        assertTrue(HostResolver.isRowShape(ObjectIconRow::class.java))
    }

    @Test
    fun `the 12_13 row with a Drawable icon still matches`() {
        assertTrue(HostResolver.isRowShape(DrawableIconRow::class.java))
    }

    @Test
    fun `the 12_24 row with the verified nested metadata shape matches`() {
        assertTrue(HostResolver.isRowShape(MetadataRow::class.java))
    }

    @Test
    fun `the 12_24 enum metadata shape matches including inherited enum state`() {
        assertTrue(HostResolver.isRowMetadataShape(Metadata::class.java))
    }

    @Test
    fun `an arbitrary reference tail is rejected`() {
        assertFalse(HostResolver.isRowShape(WrongMetadataRow::class.java))
    }

    @Test
    fun `metadata cannot occupy the icon slot on the legacy boolean shape`() {
        assertFalse(HostResolver.isRowShape(MetadataPlusBooleanRow::class.java))
    }

    @Test
    fun `a second non-String reference field is still rejected`() {
        assertFalse(HostResolver.isRowShape(TwoObjectRow::class.java))
    }

    @Test
    fun `data-class methods are still required`() {
        assertFalse(HostResolver.isRowShape(NoDataMethodsRow::class.java))
    }

    /** Right types but one field too few, so the count clause is what rejects it. */
    @Suppress("unused")
    private class ThreeFieldRow(
        val a: String,
        val b: String,
        val c: Boolean,
    ) {
        override fun equals(other: Any?): Boolean = other is ThreeFieldRow
        override fun hashCode(): Int = 0
        override fun toString(): String = "row"
    }

    @Test
    fun `wrong field count is still rejected`() {
        // A project fixture, not java.lang.String: reflecting over JDK internals throws
        // InaccessibleObjectException under the toolchain's module rules, which fails the test for a
        // reason that has nothing to do with the predicate.
        assertFalse(HostResolver.isRowShape(ThreeFieldRow::class.java))
    }
}
