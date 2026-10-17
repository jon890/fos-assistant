package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.context.application.dto.ContextSelection;
import com.bifos.assistant.memory.application.dto.MemorySearchCursor;
import com.bifos.assistant.memory.application.MemoryRelevance;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MemorySearchCursorTest {

    @Test
    @DisplayName("커서를 글로 옮겼다가 다시 읽으면 같은 지문과 순위가 나오고 같은 질문에만 맞는다")
    void roundTripsAndMatchesOnlyItsQuestion() {
        MemorySearchCursor cursor = new MemorySearchCursor(MemorySearchCursor.fingerprintOf("타이어 언제 갈았지"), 50);

        String encoded = cursor.encode();

        assertThat(encoded).doesNotContain("=", "+", "/", "타이어");
        assertThat(MemorySearchCursor.parse(encoded)).hasValue(cursor);
        assertThat(cursor.matches("타이어 언제 갈았지")).isTrue();
        assertThat(cursor.matches("타이어 언제 바꿨지")).isFalse();
        assertThat(MemorySearchCursor.first("질문")).isEqualTo(new MemorySearchCursor(MemorySearchCursor.fingerprintOf("질문"), 0));
    }


    @Test
    @DisplayName("합쳐 매긴 검색의 커서는 규칙을 담고 옛 v1 커서는 키워드로 읽는다")
    void carriesRankingRuleAndReadsOldCursorsAsKeyword() {
        String fingerprint = MemorySearchCursor.fingerprintOf("질문");
        MemorySearchCursor hybrid = new MemorySearchCursor(fingerprint, 10, true);

        assertThat(MemorySearchCursor.parse(hybrid.encode())).hasValue(hybrid);
        assertThat(MemorySearchCursor.parse(encode("v1:" + fingerprint + ":10"))).hasValue(new MemorySearchCursor(fingerprint, 10, false));
        assertThat(MemorySearchCursor.parse(encode("v2:" + fingerprint + ":10:x"))).isEmpty();
        assertThat(hybrid.needsQueryVector()).isTrue();
        assertThat(new MemorySearchCursor(fingerprint, 10, false).needsQueryVector()).isFalse();
        assertThat(MemorySearchCursor.first("질문").needsQueryVector()).isTrue();
    }


    @Test
    @DisplayName("지문은 SHA-256 앞 16 hex 이다")
    void fingerprintsWithSixteenHex() {
        assertThat(MemorySearchCursor.fingerprintOf("")).isEqualTo("e3b0c44298fc1c14");
        assertThat(MemorySearchCursor.fingerprintOf("타이어")).hasSize(16).matches("[0-9a-f]{16}");
    }


    @Test
    @DisplayName("버전이 다르거나 순위가 1 미만이거나 200 이상이거나 base64 가 깨진 커서는 읽지 않는다")
    void rejectsBrokenCursors() {
        String fingerprint = MemorySearchCursor.fingerprintOf("질문");

        assertThat(MemorySearchCursor.parse(new MemorySearchCursor(fingerprint, 1).encode())).isPresent();
        assertThat(MemorySearchCursor.parse(new MemorySearchCursor(fingerprint, 199).encode())).isPresent();
        assertThat(MemorySearchCursor.parse(new MemorySearchCursor(fingerprint, 0).encode())).isEmpty();
        assertThat(MemorySearchCursor.parse(new MemorySearchCursor(fingerprint, 200).encode())).isEmpty();
        assertThat(MemorySearchCursor.parse(encode("v2:" + fingerprint + ":1"))).isEmpty();
        assertThat(MemorySearchCursor.parse(encode("v1:" + fingerprint.substring(1) + ":1"))).isEmpty();
        assertThat(MemorySearchCursor.parse(encode("v1:" + fingerprint + ":-1"))).isEmpty();
        assertThat(MemorySearchCursor.parse("%%%")).isEmpty();
        assertThat(MemorySearchCursor.parse("")).isEmpty();
        assertThat(MemorySearchCursor.parse(null)).isEmpty();
        assertThat(MemorySearchCursor.parse("a".repeat(201))).isEmpty();
    }


    @Test
    @DisplayName("검색 결과의 규칙 이름은 조립의 근거와 같은 값이다")
    void sharesSelectorWithContextSelection() {
        assertThat(MemoryRelevance.SEARCH_KEYWORD).isEqualTo(ContextSelection.SEARCH_KEYWORD);
    }


    private static String encode(String plain) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }
}
