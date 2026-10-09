package com.bifos.assistant.workspace.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WorkspacePathTest {

    @Test
    @DisplayName("상대 경로를 조각으로 나누고 빈 값은 주인 디렉터리다")
    void parsesSegmentsAndTreatsEmptyAsRoot() {
        WorkspacePath path = WorkspacePath.parse("a/b.txt");

        assertThat(path.segments()).containsExactly("a", "b.txt");
        assertThat(path.value()).isEqualTo("a/b.txt");
        assertThat(path.name()).isEqualTo("b.txt");
        assertThat(path.isRoot()).isFalse();
        assertThat(WorkspacePath.parse("").isRoot()).isTrue();
        assertThat(WorkspacePath.parse(null).isRoot()).isTrue();
    }

    @Test
    @DisplayName("조각 64개는 받고 65개는 거절한다")
    void acceptsSixtyFourSegmentsAndRejectsSixtyFive() {
        String sixtyFour = String.join("/", Collections.nCopies(64, "a"));

        assertThat(WorkspacePath.parse(sixtyFour).segments()).hasSize(64);
        assertRejected(sixtyFour + "/a");
    }

    @ParameterizedTest
    @ValueSource(strings = {"../u2", "/etc/passwd", "a//b", "a/./b", "a\u0000b", "a/", "a\u001fb", "a\u007fb"})
    @DisplayName("상위 조각, 맨 앞 슬래시, 빈 조각, 점 조각, 제어 문자를 거절한다")
    void rejectsTraversalAndControlCharacters(String raw) {
        assertRejected(raw);
    }

    @Test
    @DisplayName("255바이트를 넘는 조각과 4,096바이트를 넘는 경로를 거절한다")
    void rejectsTooLongSegmentAndPath() {
        assertThat(WorkspacePath.parse("가".repeat(85)).name()).hasSize(85);
        assertRejected("a".repeat(256));
        assertRejected(String.join("/", Collections.nCopies(17, "b".repeat(250))));
    }

    @Test
    @DisplayName("본문 경로의 조각도 같은 규칙으로 거절한다")
    void rejectsInvalidSegmentsFromPathVariable() {
        assertThat(WorkspacePath.ofSegments(List.of("보고서.csv")).value()).isEqualTo("보고서.csv");
        assertThatThrownBy(() -> WorkspacePath.ofSegments(List.of("..", "u2")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("퍼센트, 세미콜론, 역슬래시가 든 이름은 주소로 쓰지 못한다")
    void judgesAddressableNames() {
        assertThat(WorkspacePath.addressable("보고서.csv")).isTrue();
        assertThat(WorkspacePath.addressable("50%.txt")).isFalse();
        assertThat(WorkspacePath.addressable("a;b")).isFalse();
        assertThat(WorkspacePath.addressable("a\\b")).isFalse();
        assertThat(WorkspacePath.addressable("a\nb")).isFalse();
    }

    private static void assertRejected(String raw) {
        assertThatThrownBy(() -> WorkspacePath.parse(raw))
                .as("거절해야 하는 경로: %s", raw)
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
