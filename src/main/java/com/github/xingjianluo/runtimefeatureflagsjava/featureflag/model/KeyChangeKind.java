package com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model;

/** What would happen / what happened to a single key between two states. */
public enum KeyChangeKind {
    /** Key absent before, present after. */
    ADDED,
    /** Key present on both sides with a different value/definition. */
    OVERWRITTEN,
    /** Key present on both sides, identical value/definition. */
    UNCHANGED,
    /** Key present before, absent after. */
    REMOVED
}
