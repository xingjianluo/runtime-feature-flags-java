package com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model;

/**
 * Configuration layers in increasing priority order.
 * A higher layer overrides a lower one for the same key.
 */
public enum ConfigLayer {
    /** Built-in defaults declared in code; always present. */
    DEFAULT(0),
    /** Loaded once at startup (e.g. application.properties); frozen for the process lifetime. */
    STARTUP(1),
    /** Atomically published at runtime; can be rolled back. */
    RUNTIME(2);

    private final int precedence;

    ConfigLayer(int precedence) {
        this.precedence = precedence;
    }

    public int precedence() {
        return precedence;
    }
}
