package com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model;

/** Distinguishable outcomes of evaluating a flag for a subject. */
public enum EvaluationStatus {
    /** Flag exists, is enabled and the subject is selected. */
    ENABLED,
    /** Flag exists and is enabled, but the subject is not selected by its rollout rules. */
    DISABLED,
    /** No flag with the requested key exists in the current version. */
    UNKNOWN_FLAG,
    /** The flag existed in an earlier version but has been removed (rolled back) since. */
    ROLLED_BACK
}
