package com.github.ryantateamp.codexsessiontabs;

import com.intellij.openapi.Disposable;
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab;
import com.intellij.terminal.frontend.view.TerminalView;
import kotlin.Unit;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.terminal.startup.TerminalProcessType;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.function.Consumer;

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

    static void addApplicationTitleListener(
            TerminalView view,
            Disposable parentDisposable,
            Consumer<String> listener
    ) {
        view.getTitle().addTitleListener(
                title -> listener.accept(title.getApplicationTitle()),
                parentDisposable
        );
    }

    static @Nullable String applicationTitle(TerminalView view) {
        return view.getTitle().getApplicationTitle();
    }

    static @Nullable String currentDirectory(TerminalView view) {
        return view.getCurrentDirectory();
    }

    static @Nullable String requestedDirectory(TerminalToolWindowTab tab) {
        Object value = invokeProcessOption(tab, "getWorkingDirectory");
        return value instanceof String ? (String) value : null;
    }

    static List<String> requestedCommand(TerminalToolWindowTab tab) {
        Object value = invokeProcessOption(tab, "getShellCommand");
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    static @Nullable TerminalProcessType requestedProcessType(TerminalToolWindowTab tab) {
        Object value = invokeProcessOption(tab, "getProcessType");
        return value instanceof TerminalProcessType ? (TerminalProcessType) value : null;
    }

    /** Process options became public on TerminalToolWindowTab in a 2026.2 patch release. */
    private static @Nullable Object invokeProcessOption(TerminalToolWindowTab tab, String getter) {
        try {
            Object options = tab.getClass().getMethod("getProcessOptions").invoke(tab);
            return options == null ? null : options.getClass().getMethod(getter).invoke(options);
        }
        catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ignored) {
            return null;
        }
    }
}
