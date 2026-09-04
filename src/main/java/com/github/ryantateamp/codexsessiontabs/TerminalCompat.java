package com.github.ryantateamp.codexsessiontabs;

import com.intellij.terminal.frontend.view.TerminalView;
import kotlin.Unit;
import org.jetbrains.annotations.Nullable;

/**
 * Java bridge for terminal members whose public JVM methods are hidden from Kotlin by
 * Kotlin metadata in the 2026.2 platform build.
 */
final class TerminalCompat {
    private TerminalCompat() {}

    static void setUserDefinedTitle(TerminalView view, String title) {
        view.getTitle().change(state -> {
            state.setUserDefinedTitle(title);
            return Unit.INSTANCE;
        });
    }

    static @Nullable String currentDirectory(TerminalView view) {
        return view.getCurrentDirectory();
    }
}
