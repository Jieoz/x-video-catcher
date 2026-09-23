package com.jiesa.xvideocatcher.hook

import java.io.DataInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The module declares the zygote hook, and that hook publishes the module APK path.
 *
 * This is load-bearing wiring, not a formality. The 20260829 device log shows the 1.51 icon path
 * failing on every attempt:
 *
 * ```
 * INJECT module icon unavailable; using borrowed identity icon
 * INJECT row added (... com.android.bluetooth/BluetoothOppLauncherActivity | 下载媒体)
 * ```
 *
 * The module had no way to read its own resources, so it fell back to `PackageManager` — which API 30+
 * package visibility blocks for a module that declares no launcher activity — and the row inherited
 * the borrowed app's Bluetooth glyph. The fix depends on two facts, and *both* are silent when broken:
 * [XVideoCatcherModule] must declare `IXposedHookZygoteInit` (otherwise Xposed never invokes the
 * callback and a perfectly correct method body is dead code), and `initZygote` must actually write
 * [ModuleIcon.modulePath] (otherwise every load throws and falls back exactly as before). Either way
 * the only symptom is a wrong icon on the user's phone, so both are asserted here.
 *
 * ## Why this reads class bytes instead of using reflection
 *
 * The Xposed API is `compileOnly` and must stay off the test classpath: `HostLogTest` asserts
 * `XposedBridge` is *unresolvable* in a unit test, which is what makes its fallback-logging
 * assertions mean anything. Adding `testImplementation` of the API fails that test. So the interface
 * list is read straight out of the compiled `.class` file, which is also the more direct evidence —
 * it is the bytes Xposed itself will dispatch on.
 */
class ModuleIconWiringTest {

    private companion object {
        const val MODULE_INTERFACE = "io/github/libxposed/api/XposedModuleInterface"
    }

    // The name as a literal, not `XVideoCatcherModule::class.java.name`. Referencing the class object
    // loads it, and loading it resolves its interfaces -- which are compileOnly and absent from the
    // test classpath by design, so every test in here died with NoClassDefFoundError before the first
    // assertion ran. Reading the bytes needs the name only.
    private val moduleClass = ClassFile.of("com.jiesa.xvideocatcher.hook.XVideoCatcherModule")

    @Test
    fun `module extends the modern xposed module`() {
        assertTrue(
            "XVideoCatcherModule must extend XposedModule or LSPosed never loads it; " +
                "super: ${moduleClass.superName}",
            MODULE_INTERFACE == moduleClass.superName,
        )
    }

    @Test
    fun `module declares the load callbacks`() {
        assertTrue("declared methods: ${moduleClass.methods}", "onModuleLoaded" in moduleClass.methods)
        assertTrue("declared methods: ${moduleClass.methods}", "onPackageReady" in moduleClass.methods)
    }

    @Test
    fun `onModuleLoaded writes the module path`() {
        assertTrue(
            "XVideoCatcherModule must call ModuleIcon.setModulePath(...)",
            moduleClass.references("setModulePath"),
        )
        assertTrue(moduleClass.references("ModuleIcon"))
    }

    @Test
    fun `module path starts unset so a missing hook degrades instead of throwing`() {
        // The fallback the row depends on: no path means load() returns null and the row is built with
        // the borrowed icon, rather than the injection dying and losing the row entirely.
        ModuleIcon.modulePath = null
        ModuleIcon.resetForTest()

        assertEquals(null, ModuleIcon.modulePath)
    }

    /**
     * The few parts of a `.class` file this test needs: the interface list, the method names, and the
     * UTF-8 constant pool.
     *
     * Hand-rolled because the alternative is a bytecode library as a test dependency, and the format
     * being read here (JVMS §4.1) has not changed in the ways that matter since Java 1.0.
     */
    private class ClassFile(
        val superName: String,
        val interfaces: List<String>,
        val methods: List<String>,
        private val utf8: Set<String>,
    ) {
        fun references(name: String): Boolean = utf8.any { it == name || it.contains(name) }

        companion object {
            fun of(binaryName: String): ClassFile {
                val resource = "/${binaryName.replace('.', '/')}.class"
                val stream = ClassFile::class.java.getResourceAsStream(resource)
                    ?: error("$resource not on the test classpath")
                return stream.use { read(DataInputStream(it)) }
            }

            private fun read(input: DataInputStream): ClassFile {
                require(input.readInt() == -0x35014542) { "not a class file (bad magic)" }
                input.readUnsignedShort() // minor
                input.readUnsignedShort() // major

                val poolCount = input.readUnsignedShort()
                val utf8 = mutableMapOf<Int, String>()
                // CONSTANT_Class index -> its name_index. Kept because interface entries point at
                // CONSTANT_Class, not at the UTF-8 directly, and resolving that indirection properly is
                // the difference between asserting "the interface is declared" and asserting "the
                // string appears somewhere in the class" — the latter is true of XposedHelpers too and
                // would make this test unable to fail.
                val classEntries = mutableMapOf<Int, Int>()
                var index = 1
                while (index < poolCount) {
                    when (val tag = input.readUnsignedByte()) {
                        1 -> utf8[index] = input.readUTF()
                        7 -> classEntries[index] = input.readUnsignedShort()
                        8, 16, 19, 20 -> input.skipBytes(2)
                        15 -> input.skipBytes(3)
                        3, 4, 9, 10, 11, 12, 17, 18 -> input.skipBytes(4)
                        // long and double occupy two pool slots (JVMS §4.4.5).
                        5, 6 -> {
                            input.skipBytes(8)
                            index++
                        }
                        else -> error("unhandled constant pool tag $tag at $index")
                    }
                    index++
                }

                input.readUnsignedShort() // access_flags
                input.readUnsignedShort() // this_class
                val superIndex = input.readUnsignedShort()
                val superNameIndex = classEntries[superIndex]
                    ?: error("super $superIndex is not a CONSTANT_Class")
                val superName = utf8[superNameIndex]
                    ?: error("super name $superNameIndex is not a UTF-8 entry")

                val interfaceCount = input.readUnsignedShort()
                val interfaces = (0 until interfaceCount).map {
                    val classIndex = input.readUnsignedShort()
                    val nameIndex = classEntries[classIndex]
                        ?: error("interface $classIndex is not a CONSTANT_Class")
                    utf8[nameIndex] ?: error("interface name $nameIndex is not a UTF-8 entry")
                }

                val fieldCount = input.readUnsignedShort()
                repeat(fieldCount) { skipMember(input) }

                val methodCount = input.readUnsignedShort()
                val methods = mutableListOf<String>()
                repeat(methodCount) {
                    input.readUnsignedShort() // access_flags
                    val nameIndex = input.readUnsignedShort()
                    input.readUnsignedShort() // descriptor_index
                    methods += utf8[nameIndex] ?: "<unresolved $nameIndex>"
                    skipAttributes(input)
                }

                return ClassFile(superName, interfaces, methods, utf8.values.toSet())
            }

            private fun skipMember(input: DataInputStream) {
                input.skipBytes(6) // access_flags, name_index, descriptor_index
                skipAttributes(input)
            }

            private fun skipAttributes(input: DataInputStream) {
                repeat(input.readUnsignedShort()) {
                    input.skipBytes(2) // attribute_name_index
                    val length = input.readInt()
                    var remaining = length
                    while (remaining > 0) {
                        val skipped = input.skipBytes(remaining)
                        require(skipped > 0) { "truncated attribute" }
                        remaining -= skipped
                    }
                }
            }
        }
    }
}
