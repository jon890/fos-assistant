package com.bifos.assistant.credential.domain;

/**
 * Whose AI account a profile's turns actually run on.
 *
 * <p>Hermes isolates an API key placed in a profile's own {@code .env}, but a profile without its
 * own {@code auth.json} reads the host's global OAuth store. Two profiles can therefore look
 * separate and still bill one subscription. This tells the Control Plane which situation a binding
 * was created under, so the sharing is a recorded decision rather than something nobody noticed.
 */
public enum CredentialScope {
    /** Runs on the household's shared login. Spend cannot be attributed to this member. */
    SHARED_HOUSEHOLD,
    /** Runs on this member's own credential, isolated by Hermes. */
    DEDICATED
}
