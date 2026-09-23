package com.jiesa.xvideocatcher.hook

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable

/**
 * Adapts the module's before/after hooks onto libxposed's interceptor chain.
 *
 * A before-hook that sets [Call.result] swallows the original call. Otherwise the original runs,
 * then the after-hook sees its return value. Exceptions from the original still propagate.
 */
internal object HookBridge {

    fun interface Before {
        fun before(call: Call)
    }

    fun interface After {
        fun after(call: Call)
    }

    class Call(private val chain: XposedInterface.Chain) {
        val thisObject: Any? get() = chain.thisObject
        val args: List<Any?> get() = chain.args

        private var replaced = false
        private var replacement: Any? = null

        var result: Any?
            get() = replacement
            set(value) {
                replacement = value
                replaced = true
            }

        internal val swallowed: Boolean get() = replaced

        internal fun proceed(): Any? = chain.proceed()
    }

    fun hook(origin: Executable, before: Before? = null, after: After? = null) {
        XVideoCatcherModule.framework.hook(origin).intercept { chain ->
            val call = Call(chain)
            before?.before(call)
            if (call.swallowed) return@intercept call.result
            val value = call.proceed()
            call.result = value
            after?.after(call)
            call.result
        }
    }
}
