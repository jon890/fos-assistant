package com.bifos.assistant.connector.application;

import java.util.UUID;

public interface AccountbookTokenVerifier {
    void verify(String token, UUID familyUuid);
}
