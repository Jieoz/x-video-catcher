package com.jiesa.xvideocatcher.hook

import com.twitter.app.common.dialog.BaseDialogFragment
import com.twitter.app.common.dialog.ClickContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for resolving the sheet's click dispatch.
 *
 * These pin the finding that produced the `no item dispatch method` failure: the click does NOT
 * arrive on the controller. It arrives on the dialog fragment as `void(int)` carrying the action
 * id, and that method is selected via the interface it implements — because the fragment declares
 * *two* void(int) methods, so "the only void(int)" would refuse on every build.
 *
 * The doubles live in `com.twitter.app.common.dialog` (test-only sources) because the resolver
 * anchors on that fully-qualified class name. Their *method names* deliberately differ from the
 * real build's (`dispatchAction` vs `u`) — the whole claim under test is that the name is derived
 * from the interface rather than hardcoded, so using the real name would let a hardcoded
 * implementation pass.
 */
class ClickDispatchTest {

    private val loader = javaClass.classLoader!!

    @Test
    fun `resolves the interface-declared void int on the dialog fragment`() {
        val m = HostResolver.clickDispatch(loader)
        assertNotNull("dispatch should resolve", m)
        assertEquals("dispatchAction", m!!.name)
        assertEquals(BaseDialogFragment::class.java, m.declaringClass)
    }

    @Test
    fun `does not pick the unrelated void int method`() {
        // The real build has two void(int) methods on this class (`u` and `R0`); the fixture has
        // four, because the decoy contracts contribute their own dispatch methods. More ambiguity
        // than production, which is the right direction for a fixture.
        val voidInts = BaseDialogFragment::class.java.declaredMethods.filter {
            it.returnType == Void.TYPE &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        }
        assertTrue(
            "fixture must be ambiguous, found ${voidInts.size}",
            voidInts.size >= 2,
        )
        assertTrue(
            "the unrelated setStyleRes must be among them",
            voidInts.any { it.name == "setStyleRes" },
        )

        val m = HostResolver.clickDispatch(loader)
        assertEquals("dispatchAction", m!!.name)
    }

    @Test
    fun `the resolved method is the interface implementation`() {
        val m = HostResolver.clickDispatch(loader)!!
        val fromInterface = ClickContract::class.java.declaredMethods.single {
            it.returnType == Void.TYPE &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        }
        assertEquals(
            "resolver must take the name from the interface",
            fromInterface.name,
            m.name,
        )
    }

    @Test
    fun `missing dialog fragment class resolves to null`() {
        val empty = object : ClassLoader(null) {
            override fun loadClass(name: String): Class<*> = throw ClassNotFoundException(name)
        }
        assertNull(HostResolver.clickDispatch(empty))
    }

    @Test
    fun `interface without the contract shape is refused`() {
        // NoContractFragment implements an interface of the wrong shape, so no dispatch should be
        // resolved even though the class itself has a void(int).
        assertNull(HostResolver.clickContractForTest(NoContractFragment::class.java))
    }

    @Test
    fun `contract shape is matched on the real fixture`() {
        val c = HostResolver.clickContractForTest(BaseDialogFragment::class.java)
        assertEquals(ClickContract::class.java, c)
    }

    /**
     * The fixture must reproduce the real build's ambiguity, or the shape matching is asserted by
     * nothing. Five interfaces, contract declared last, three decoys each missing on a different
     * axis. Asserting the fixture itself because a weaker fixture is exactly how three of these
     * guards were first shipped as no-ops.
     */
    @Test
    fun `fixture reproduces the real ambiguity`() {
        val ifs = BaseDialogFragment::class.java.interfaces
        assertEquals("real build declares 5 interfaces", 5, ifs.size)
        assertEquals(
            "contract must be last so first-match implementations fail",
            ClickContract::class.java,
            ifs.last(),
        )

        // One decoy has the full contract shape but 6 methods, pinning the count check.
        val six = ifs.single { it.simpleName == "DecoySixMethods" }
        assertEquals(6, six.declaredMethods.size)

        // One decoy has the full shape but declares a field, pinning the no-fields check.
        val fielded = ifs.single { it.simpleName == "DecoyFielded" }
        assertEquals(5, fielded.declaredMethods.size)
        assertEquals(1, fielded.declaredFields.size)
    }

    /** Implements a 5-method interface whose shape does not match the click contract. */
    @Suppress("unused")
    class NoContractFragment : WrongShape {
        override fun a() = Unit
        override fun b(x: Int) = Unit
        override fun c(x: Int) = Unit
        override fun d(x: Int) = Unit
        override fun e(x: Int) = Unit
    }

    interface WrongShape {
        fun a()
        fun b(x: Int)
        fun c(x: Int)
        fun d(x: Int)
        fun e(x: Int)
    }
}
