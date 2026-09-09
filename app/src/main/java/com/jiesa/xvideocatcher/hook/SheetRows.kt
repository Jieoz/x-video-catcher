package com.jiesa.xvideocatcher.hook

/**
 * Surgery on the host's share-sheet state object: find the rows in it, and swap one for ours.
 *
 * ## Why this is a separate object from the hook that calls it
 *
 * Everything here is a pure function over a constructor's arguments. That is deliberate: the module
 * has shipped six releases whose injection logic could only be exercised on the user's device, and
 * each round trip cost a release. These functions take an `Array<Any?>` and return an answer, so the
 * 12.20.5 state shape can be reproduced as a JVM fixture and the whole decision — which list holds
 * the rows, which slot gets replaced, whether the substitution is type-safe — is asserted in the
 * unit suite instead of in a logcat.
 *
 * ## Why the *constructor* and not the reducer
 *
 * The sheet state is a Kotlin data class. Its fields are final, and its `copy` is compiled to a
 * static method that ends in a constructor call, so **every state instance the host renders passes
 * through here**, whichever code path produced it. On 12.20.5 four different writers
 * (`sharesheet.a.invokeSuspend`, `b.emit`, `d.invokeSuspend`, `e.invokeSuspend`) each read the state
 * flow, copy it, and write it straight back; the row list is built in the first of those, a
 * coroutine. A reducer method sees only the states it is itself handed, and on this host the reducer
 * (`sharesheet.g.b`) is the *message text* transform — it is called twice, from the component's
 * constructor and from its action handler, and never with a state that has rows in it.
 *
 * So the constructor is not merely a convenient interception point, it is the only complete one.
 */
internal object SheetRows {

    /**
     * Where the rows are: which constructor argument holds them, and which entry gets replaced.
     *
     * [rows] is captured rather than re-read so a caller cannot look at a different list than the
     * one that was located.
     */
    internal data class Slot(val argIndex: Int, val rowIndex: Int, val rows: List<Any?>)

    /**
     * The argument holding the sheet's rows, identified by **what the list actually contains**.
     *
     * Element type of a live object, not a declared type: the 12.20.5 state declares three separate
     * `List` fields (`suggestions`, `attachments`, `externalApps`) and reflection erases all three to
     * bare `java.util.List`, so nothing in the signature can tell them apart. What can is that only
     * one of them holds share rows at runtime. That property is immune to R8, to coroutine lowering,
     * and to the host reordering its own fields.
     *
     * Returns null when no argument holds rows, which is the normal case for the states built before
     * the row-loading coroutine has finished — those must be left alone, not guessed at.
     */
    fun locate(args: Array<Any?>, rowClass: Class<*>): Slot? {
        for (i in args.indices) {
            val list = args[i] as? List<*> ?: continue
            val rowIndex = list.indexOfFirst { it != null && rowClass.isInstance(it) }
            if (rowIndex >= 0) return Slot(i, rowIndex, list)
        }
        return null
    }

    /**
     * The status id carried by any `String` argument, or null.
     *
     * Up to 1.48 this came from the row provider's parameter. That method is gone, but the identity
     * did not go with it: the state carries the share URL it was opened for (12.20.5 names the field
     * `shareUrl` in its own `toString` template), so the id is still one hop away — just read off a
     * value instead of an argument position.
     *
     * Matched on the value, never on a field name or index. A numeric path segment after `status/` is
     * X's own URL grammar, which it cannot change without breaking every link ever posted.
     */
    fun statusIdIn(args: Array<Any?>): String? {
        for (arg in args) {
            val text = arg as? String ?: continue
            val id = text.substringAfter(STATUS_SEGMENT, missingDelimiterValue = "")
                .substringBefore('?')
                .substringBefore('/')
            if (id.isNotEmpty() && id.all(Char::isDigit)) return id
        }
        return null
    }

    /**
     * Whether [rows] already carries a row labelled [label].
     *
     * The state is rebuilt on every keystroke in the sheet's search field, so the hook runs many
     * times per open and has to be idempotent. Checked by label because that is the one field the
     * module writes — a host row cannot collide with a localised module string.
     */
    fun alreadyCarries(rows: List<Any?>, label: String): Boolean =
        rows.any { it != null && HostRow.labelOf(it) == label }

    /**
     * Whether [substitute] would be able to write, **without writing**.
     *
     * Split out for the probe. Up to 1.49 the probe answered this question by calling [substitute] on
     * a copied argument array with the row that was already in the slot: overwriting index *i* with
     * the object already at index *i* leaves the sheet byte-identical, so a true answer was free. That
     * stopped being true when 1.50 made [substitute] *insert* — on the fallback path it writes to the
     * host's live list, which the copied array still points at, so probing would have added a
     * duplicate row to a sheet the user was looking at.
     *
     * Answering from the declared parameter type alone costs nothing and cannot mutate anything. It
     * describes the path the shipping code actually takes on this host (12.20.5 declares
     * `java.util.List`, so the copy branch is used); when it returns false the in-place fallback may
     * still succeed at injection time, which is why the log line names it a *type* verdict.
     */
    fun canSubstitute(parameterTypes: Array<Class<*>>, slot: Slot): Boolean {
        val declared = parameterTypes.getOrNull(slot.argIndex) ?: return false
        return declared.isAssignableFrom(ArrayList::class.java)
    }

    /**
     * Puts [row] into [slot]. Returns true when the arguments were actually changed.
     *
     * ## Append (1.50), after replace (1.18–1.49)
     *
     * 1.14–1.17 appended and the row never rendered, so 1.18 switched to overwriting a slot the host
     * had already accepted. That worked, at a cost: X's own Telegram entry disappeared from the sheet.
     *
     * Appending works now for a reason that did not hold then, and it is not that the UI changed.
     * 1.14–1.17 hooked the **row provider** and added a row to a list the host had already finished
     * building — the sheet rendered the set it had, and a later mutation of that list was simply not
     * read. Since 1.49 the interception point is the sheet state's **constructor**, and what is
     * modified is the constructor's own argument: the host receives the longer list as its input and
     * builds its state from it. There is no "afterwards" left for the UI to ignore.
     *
     * [insertAt] governs position only. The module's row goes first so it cannot be pushed off the
     * end of a horizontally scrolling row of share targets.
     *
     * ## Why a fresh list rather than editing the host's
     *
     * The default path replaces the whole argument with an `ArrayList` copy. Two reasons, both
     * measured rather than assumed. First, this APK bundles `kotlinx.collections.immutable` (51
     * classes), so the host's list can be a persistent one and `add` on it throws. Second, successive
     * states share structure: the list handed to this constructor may still be reachable from the
     * *previous* state, and editing it in place would silently rewrite history the sheet may diff
     * against.
     *
     * The copy is only substituted when the constructor's own declared parameter type accepts an
     * `ArrayList`. On 12.20.5 that type is `java.util.List` and it does. A future host that narrows
     * it to an immutable list interface would be handed an incompatible object and would throw inside
     * X — so that case falls back to editing the host's list in place, and if the list refuses the
     * write, gives up and lets the caller log a miss. A missing row is recoverable; a
     * `ClassCastException` on X's UI thread is not.
     */
    fun substitute(
        args: Array<Any?>,
        parameterTypes: Array<Class<*>>,
        slot: Slot,
        row: Any,
        insertAt: Int = 0,
    ): Boolean {
        val declared = parameterTypes.getOrNull(slot.argIndex) ?: return false
        val at = insertAt.coerceIn(0, slot.rows.size)
        if (declared.isAssignableFrom(ArrayList::class.java)) {
            val copy = ArrayList<Any?>(slot.rows)
            copy.add(at, row)
            args[slot.argIndex] = copy
            return true
        }
        @Suppress("UNCHECKED_CAST")
        val live = slot.rows as? MutableList<Any?> ?: return false
        return runCatching {
            live.add(at, row)
            true
        }.getOrDefault(false)
    }

    /** X's URL grammar for a single post. Stable across every rename the product has had. */
    private const val STATUS_SEGMENT = "status/"
}
