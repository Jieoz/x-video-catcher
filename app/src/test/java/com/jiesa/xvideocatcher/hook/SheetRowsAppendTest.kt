package com.jiesa.xvideocatcher.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The append behaviour asked for in 1.50, asserted on the JVM.
 *
 * Device history is why these exist rather than a logcat read: 1.18 switched from appending to
 * overwriting a host row and shipped that for eight releases, and what finally surfaced it was the
 * user noticing his Telegram entry had vanished from the sheet. The property that matters is not
 * "substitute returned true" — 1.14–1.17 all logged success — it is that **every row the host built is
 * still in the list afterwards**, and that is checkable here.
 */
class SheetRowsAppendTest {

    /**
     * Minimal stand-in for a host share row.
     *
     * Field order matters: [HostRow.occupiedKeys] identifies the identity pair as the dotted string
     * fields and the label as the remaining one, so the fixture has to be shaped the same way the
     * 12.20.5 row is.
     */
    private class Row(val pkg: String, val activity: String, val label: String)

    private fun hostRows() = mutableListOf<Any?>(
        Row("org.telegram.messenger", "org.telegram.ui.LaunchActivity", "Telegram"),
        Row("com.whatsapp", "com.whatsapp.contact.ui.picker.ExternalShareAlias", "WhatsApp"),
        Row("com.discord", "com.discord.share.ShareActivity", "Discord"),
    )

    private val ours = Row("com.reddit.frontpage", "com.reddit.sharing.ShareActivity", "下载媒体")

    /** 12.20.5's own shape: the row list arrives as argument 8, declared `java.util.List`. */
    private fun stateArgs(rows: List<Any?>): Pair<Array<Any?>, Array<Class<*>>> {
        val args = arrayOf<Any?>(
            "https://x.com/i/status/2092985489599107413",
            null, null, null, null, null, null, null,
            rows,
            true,
        )
        val types = arrayOf<Class<*>>(
            String::class.java,
            Any::class.java, Any::class.java, Any::class.java, Any::class.java,
            Any::class.java, Any::class.java, Any::class.java,
            List::class.java,
            Boolean::class.javaPrimitiveType!!,
        )
        return args to types
    }

    /** A declared parameter type an `ArrayList` does not satisfy, forcing the in-place fallback. */
    private fun narrowedTypes(): Array<Class<*>> {
        val t = Array<Class<*>>(10) { Any::class.java }
        t[8] = java.util.concurrent.CopyOnWriteArrayList::class.java
        return t
    }

    @Test
    fun `append keeps every host row, including the one 1_18 used to eat`() {
        val rows = hostRows()
        val (args, types) = stateArgs(rows)
        val slot = SheetRows.locate(args, Row::class.java)!!

        assertTrue(SheetRows.substitute(args, types, slot, ours, insertAt = 0))

        @Suppress("UNCHECKED_CAST")
        val after = args[8] as List<Any?>
        assertEquals("row count must grow by exactly one", rows.size + 1, after.size)
        val labels = after.map { (it as Row).label }
        assertEquals(listOf("下载媒体", "Telegram", "WhatsApp", "Discord"), labels)
        // The regression the user reported, stated directly.
        assertTrue("Telegram must survive injection", labels.contains("Telegram"))
    }

    @Test
    fun `the host's own list is never mutated when the declared type allows a copy`() {
        val rows = hostRows()
        val snapshot = rows.toList()
        val (args, types) = stateArgs(rows)
        val slot = SheetRows.locate(args, Row::class.java)!!

        SheetRows.substitute(args, types, slot, ours, insertAt = 0)

        // Successive sheet states share structure; editing in place would rewrite a list the previous
        // state may still be holding.
        assertEquals("host list must be untouched", snapshot, rows.toList())
        assertNotSame(rows, args[8])
    }

    @Test
    fun `in-place fallback appends when the declared type refuses an ArrayList`() {
        val rows = hostRows()
        val (args, _) = stateArgs(rows)
        val slot = SheetRows.locate(args, Row::class.java)!!

        assertTrue(SheetRows.substitute(args, narrowedTypes(), slot, ours, insertAt = 0))
        assertSame("fallback writes through to the host's list", rows, args[8])
        assertEquals(4, rows.size)
        assertEquals("下载媒体", (rows[0] as Row).label)
    }

    @Test
    fun `an unwritable list is refused rather than throwing into the host`() {
        val rows: List<Any?> = java.util.Collections.unmodifiableList(hostRows())
        val (args, _) = stateArgs(rows)
        val slot = SheetRows.locate(args, Row::class.java)!!

        // A missing row is recoverable; an UnsupportedOperationException on X's UI thread is not.
        assertFalse(SheetRows.substitute(args, narrowedTypes(), slot, ours, insertAt = 0))
    }

    @Test
    fun `insertAt is clamped instead of throwing`() {
        val (args, types) = stateArgs(hostRows())
        val slot = SheetRows.locate(args, Row::class.java)!!

        assertTrue(SheetRows.substitute(args, types, slot, ours, insertAt = 999))

        @Suppress("UNCHECKED_CAST")
        val after = args[8] as List<Any?>
        assertEquals(4, after.size)
        assertEquals("下载媒体", (after.last() as Row).label)
    }

    @Test
    fun `canSubstitute answers the type question without writing anything`() {
        val rows = hostRows()
        val snapshot = rows.toList()
        val (args, types) = stateArgs(rows)
        val slot = SheetRows.locate(args, Row::class.java)!!

        // This is the probe's path. Before 1.50 the probe called substitute() to find out, which was
        // free while substitute overwrote and would now duplicate a row in the live sheet.
        assertTrue(SheetRows.canSubstitute(types, slot))
        assertEquals("probing must not change the sheet", snapshot, rows.toList())
        assertEquals(3, (args[8] as List<*>).size)

        assertFalse(SheetRows.canSubstitute(narrowedTypes(), slot))
    }

    @Test
    fun `idempotence is by label, so repeated state builds add one row only`() {
        val rows = hostRows()
        val (args, types) = stateArgs(rows)
        val slot = SheetRows.locate(args, Row::class.java)!!
        SheetRows.substitute(args, types, slot, ours, insertAt = 0)

        @Suppress("UNCHECKED_CAST")
        val after = args[8] as List<Any?>
        // The sheet state is rebuilt on every keystroke in the search field, so the injector's guard
        // has to recognise its own row in the list it just produced.
        assertTrue(SheetRows.alreadyCarries(after, "下载媒体"))
        assertFalse(SheetRows.alreadyCarries(hostRows(), "下载媒体"))
    }

    @Test
    fun `status id is read off the share url the state carries`() {
        val (args, _) = stateArgs(hostRows())
        assertEquals("2092985489599107413", SheetRows.statusIdIn(args))
    }
}
