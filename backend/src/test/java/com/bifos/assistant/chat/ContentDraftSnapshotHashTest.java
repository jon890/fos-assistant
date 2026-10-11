package com.bifos.assistant.chat;

import java.util.HexFormat;
import tools.jackson.databind.node.ObjectNode;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.bifos.assistant.chat.application.ContentDraftSnapshotHash;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ContentDraftSnapshotHashTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    @DisplayName("공유 golden의 비BMP slash null tuple과 두 해시가 일치한다")
    void matchesSharedGolden() throws Exception {
        var golden = json.readTree(Path.of("../hermes/connectors/naver-blog/tests/fixtures/content-snapshot-v1.json").toFile());
        var actual = ContentDraftSnapshotHash.compile(golden.get("draft"), golden.get("assets"));
        assertThat(actual).isEqualTo(golden.get("snapshot"));
        assertThat(ContentDraftSnapshotHash.payloadBytes(actual)).isEqualTo(HexFormat.of().parseHex(golden.get("payloadUtf8Hex").stringValue()));
        assertThat(ContentDraftSnapshotHash.snapshotBytes(actual)).isEqualTo(HexFormat.of().parseHex(golden.get("snapshotUtf8Hex").stringValue()));
        ContentDraftSnapshotHash.verify(golden.get("draft"), golden.get("assets"), actual);
        var fromRest = golden.path("draft").deepCopy();
        ((ObjectNode)fromRest).put("revision", 7L);
        ContentDraftSnapshotHash.verify(fromRest, golden.get("assets"), actual);
        actual.put("revision", 8);
        assertThatThrownBy(() -> ContentDraftSnapshotHash.verify(golden.get("draft"), golden.get("assets"), actual))
            .hasMessage("원고를 사용할 수 없습니다.");
    }
}
