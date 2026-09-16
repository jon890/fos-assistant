package com.bifos.assistant.credential.domain;

/** How an execution on this binding should be priced. */
public enum CostMode {
    /** Flat-rate plan. Token counts are still recorded, cost stays null. */
    SUBSCRIPTION,
    /** Metered API key. Cost can be computed from token counts and a price table. */
    API
}
