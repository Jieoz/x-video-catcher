package com.arkivanov.decompose;

/**
 * Stand-in for X's navigation component (`com.arkivanov.decompose.c`), for unit tests only.
 *
 * The lookup matches on the package rather than the class name -- Decompose is a third-party library,
 * so its package is stable across X releases while R8 shortens the class names inside it. This double
 * therefore has to sit in the real package for the test to exercise the real rule.
 */
public class FakeComponent {
    public final String id;

    public FakeComponent() {
        this("component");
    }

    public FakeComponent(String id) {
        this.id = id;
    }
}
