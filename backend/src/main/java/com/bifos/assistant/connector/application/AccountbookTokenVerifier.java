package com.bifos.assistant.connector.application;

import java.util.UUID;
import java.util.List;

public interface AccountbookTokenVerifier {
    void verify(String token, UUID familyUuid);
    List<FamilyOption> readFamilies(String token);
    record FamilyOption(UUID uuid, String name) {}
}
