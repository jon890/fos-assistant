package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.chat.application.ContentDraftAuthorizationHash;
import com.bifos.assistant.chat.application.model.ContentDraftBindingStamp;
import com.bifos.assistant.chat.application.model.ContentDraftSnapshotInput;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ContentDraftAuthorizationHashTest {
    private static final Instant CONNECTION = Instant.parse("2026-10-10T10:00:00.123456789Z");
    private static final Instant BINDING = Instant.parse("2026-10-10T10:00:00Z");
    private static final String PROFILE = "profile/한글😀";
    private static final String CONNECTOR = "connector/한글😀";
    private static final String POLICY = "a".repeat(64);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void fixesNamespaceUtf8TupleAndMicroSerialization() {
        var stamp = stamp(15L, 16L, 17L, CONNECTION, BINDING, PROFILE, CONNECTOR, POLICY);
        var expected = "17ff103d3efb84d414c016c53fb0db9c59bc8cc7fb97f81442b758f5db29d3eb";
        assertThat(hash(11L, 12L, 13L, 14L, stamp)).isEqualTo(expected);
        assertThat(hash(11L, 12L, 13L, 14L, stamp(15L, 16L, 17L,
            CONNECTION.minusNanos(789), BINDING, PROFILE, CONNECTOR, POLICY))).isEqualTo(expected);
        assertThat(hash(11L, 12L, 13L, 14L, stamp(15L, 16L, 17L,
            CONNECTION.plusNanos(1000), BINDING, PROFILE, CONNECTOR, POLICY))).isNotEqualTo(expected);
    }

    @Test
    void distinguishesEveryAuthorizationCaptureField() {
        var original = stamp(15L, 16L, 17L, CONNECTION, BINDING, PROFILE, CONNECTOR, POLICY);
        var expected = hash(11L, 12L, 13L, 14L, original);
        for (int changed = 0; changed < 12; changed++) {
            var stamp = stamp(changed == 4 ? 99L : 15L, changed == 5 ? 99L : 16L, changed == 6 ? 99L : 17L,
                changed == 7 ? CONNECTION.plusSeconds(1) : CONNECTION, changed == 8 ? BINDING.plusSeconds(1) : BINDING,
                changed == 9 ? PROFILE + "😀" : PROFILE, changed == 10 ? CONNECTOR + "😀" : CONNECTOR,
                changed == 11 ? "b".repeat(64) : POLICY);
            assertThat(hash(changed == 0 ? 99L : 11L, changed == 1 ? 99L : 12L,
                changed == 2 ? 99L : 13L, changed == 3 ? 99L : 14L, stamp)).isNotEqualTo(expected);
        }
    }

    @Test
    void copiesInputAndOutputAndRedactsDiagnostic() {
        var draft = JSON.createObjectNode().put("secret", "원문");
        var assets = JSON.createArrayNode().add(JSON.createObjectNode().put("path", "private-path"));
        var input = new ContentDraftSnapshotInput(null, 13L, 14L,
            stamp(15L, 16L, 17L, CONNECTION, BINDING, PROFILE, CONNECTOR, POLICY), draft, assets, BINDING);
        draft.put("secret", "changed");
        assets.removeAll();
        ((tools.jackson.databind.node.ObjectNode)input.canonicalDraft()).put("secret", "changed-output");
        ((tools.jackson.databind.node.ArrayNode)input.resolvedAssets()).removeAll();
        assertThat(input.canonicalDraft().path("secret").stringValue()).isEqualTo("원문");
        assertThat(input.resolvedAssets().size()).isEqualTo(1);
        assertThat(input.toString()).isEqualTo("ContentDraftSnapshotInput[redacted]");
    }

    private static ContentDraftBindingStamp stamp(Long agent, Long connection, Long binding, Instant connectionTime,
        Instant bindingTime, String profile, String connector, String policy) {
        return new ContentDraftBindingStamp(agent, connection, binding, connectionTime, bindingTime, profile, connector, policy);
    }

    private static String hash(Long owner, Long conversation, Long revision, Long origin, ContentDraftBindingStamp stamp) {
        return ContentDraftAuthorizationHash.fingerprint(owner, conversation,
            new ContentDraftSnapshotInput(UUID.randomUUID(), revision, origin, stamp, JSON.createObjectNode(), JSON.createArrayNode(), BINDING));
    }
}
