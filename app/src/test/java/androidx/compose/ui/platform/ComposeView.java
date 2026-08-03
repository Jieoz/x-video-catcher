package androidx.compose.ui.platform;

/**
 * Test-only stand-in for Compose's `ComposeView`.
 *
 * The module has no Compose dependency and must not gain one: it is loaded into X's process, where
 * Compose is already present, and shipping a second copy would be dead weight in someone else's app.
 * The resolver therefore matches this type by *name* (`HostResolver.COMPOSE_VIEW`), never by class
 * identity, so a same-named stub exercises the real predicate exactly.
 *
 * That is also why this file is a fixture rather than a mock: `sheetOpen` asks "does this class have a
 * field whose type is named androidx.compose.ui.platform.ComposeView", and only a real class with that
 * fully-qualified name can answer it.
 */
public class ComposeView {
}
