package com.x.dms.components.sharesheet;

/**
 * The dispatch interface: abstract on the real build, and that is the point of this stub.
 *
 * On 12.13.0-release.0 `r` is an interface and `r.h` is abstract. It was a concrete final class here,
 * which is why the suite passed while 1.5.0-probe died on the device: `XposedBridge.hookMethod` throws
 * `IllegalArgumentException` on an abstract method, and a fixture that cannot produce one cannot
 * express the failure. A stub whose shape disagrees with the host tests the stub.
 *
 * `getState` keeps its real name because that name survives obfuscation -- it is a Kotlin property
 * accessor, so the JVM naming convention fixes it rather than R8 choosing it. The dispatch method is
 * named `onAction` rather than the real build's `h`, so a hardcoded name cannot pass.
 */
public interface r {

    String getState();

    void onAction(t action);
}
