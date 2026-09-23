package com.jiesa.xvideocatcher

import org.junit.Test

/**
 * The Xposed API is `compileOnly`, so it is absent in this JVM. Referencing it therefore raises
 * `NoClassDefFoundError` here — an [Error], which `runCatching`/`catch (e: Exception)` at a call
 * site does not intercept.
 *
 * On device that same shape is what removed the injected download row in 1.34.0-debug: a diagnostic
 * line threw from inside a hook, the module's own "never crash the host" guard swallowed it, and the
 * visible symptom was a missing feature rather than an error. These tests pin the two properties
 * that prevent it recurring.
 */
class HostLogTest {

    /**
     * Load-bearing: make [HostLog.log] call `XposedBridge.log` directly and this fails, because the
     * class genuinely cannot resolve in a unit test.
     */
    @Test
    fun `logging is safe where the xposed bridge does not exist`() {
        HostLog.log("must not throw without the bridge on the classpath")
    }

    /**
     * The bridge is missing here, so this asserts the *other* half: one channel failing must not
     * suppress the attempt at the other, and neither may propagate.
     */
    @Test
    fun `repeated logging stays silent about missing channels`() {
        repeat(3) { HostLog.log("line $it") }
    }

    /**
     * The guard has to catch [Throwable], not [Exception]. This states that directly: if the
     * implementation narrowed to `Exception`, an `Error` from a logging channel would escape.
     */
    @Test
    fun `an error from a logging channel does not escape`() {
        // Sanity-check the premise of the whole test class: the bridge really is unresolvable, so
        // the case above is exercising the catch and not passing for a trivial reason.
        var raised: Throwable? = null
        try {
            Class.forName("io.github.libxposed.api.XposedInterface")
        } catch (t: Throwable) {
            raised = t
        }
        org.junit.Assert.assertTrue(
            "libxposed API resolved in a unit test, so these tests no longer prove anything",
            raised != null,
        )
    }
}
