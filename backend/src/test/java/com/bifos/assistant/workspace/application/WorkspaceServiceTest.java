package com.bifos.assistant.workspace.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.application.UserExecutionLimiter;
import com.bifos.assistant.workspace.domain.WorkspaceCursor;
import com.bifos.assistant.workspace.domain.WorkspaceEntry;
import com.bifos.assistant.workspace.domain.WorkspaceEntryKind;
import com.bifos.assistant.workspace.domain.WorkspaceListing;
import com.bifos.assistant.workspace.domain.WorkspacePath;
import com.bifos.assistant.workspace.infra.WorkspaceProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceServiceTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("다른 사용자의 cursor를 받아도 매 요청의 사용자 디렉터리만 읽는다")
    void usesRequesterDirectoryForEveryPage() throws IOException {
        Path first = Files.createDirectory(root.resolve("u1"));
        Path second = Files.createDirectory(root.resolve("u2"));
        Files.writeString(first.resolve("z-private"), "private");
        Files.writeString(second.resolve("z-own"), "own");
        String cursor = cursor();
        WorkspaceService service = service(root.toString());
        WorkspaceListing page = service.list(user(2), "", cursor);
        assertThat(page.entries()).extracting(WorkspaceEntry::name).containsExactly("z-own");
        assertThat(page.truncated()).isFalse();
        assertThat(page.nextCursor()).isNull();
        Files.delete(second.resolve("z-own"));
        Files.delete(second);
        Files.createSymbolicLink(second, first);
        assertCode(() -> service.list(user(2), "", cursor), ErrorCode.WORKSPACE_ENTRY_NOT_FOUND);
    }

    @Test
    @DisplayName("기능 설정과 주인 디렉터리와 cursor를 페이지마다 다시 검사한다")
    void rechecksConfigurationAndCursor() {
        assertCode(() -> service("").list(user(1), "", cursor()), ErrorCode.WORKSPACE_UNAVAILABLE);
        assertCode(() -> service(root.toString()).list(user(1), "", "!"), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service(root.toString()).list(user(1), "other", cursor()), ErrorCode.VALIDATION_FAILED);
    }

    private WorkspaceService service(String value) {
        return new WorkspaceService(
                LiveProperties.fixed(WorkspaceProperties.class, new WorkspaceProperties(value, "")),
                mock(AgentService.class),
                mock(UserExecutionLimiter.class),
                (owner, path, limit) -> {
                    throw new AssertionError("목록 시험은 지우지 않는다");
                });
    }

    private static CurrentUser user(long id) {
        return new CurrentUser(id, "test@example.com", "시험 사용자", 1L, UserRole.MEMBER);
    }

    private static String cursor() {
        return WorkspaceCursor.encode(
                WorkspacePath.parse(""),
                new WorkspaceEntry("a", WorkspaceEntryKind.FILE, 0L, Instant.EPOCH, true, true));
    }

    private static void assertCode(Runnable request, ErrorCode expected) {
        assertThatThrownBy(request::run)
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("지우기 로그의 경로는 줄을 끊는 제어 문자와 구분자를 이스케이프하고 나머지 글자는 그대로 둔다")
    void escapesControlCharactersForLog() {
        String raw = "보고서/a\nb\r\u0000\u007f\u0085\u009f\u2028\u2029 c.txt";

        assertThat(WorkspaceService.escapeForLog(raw))
                .isEqualTo("보고서/a\\u000Ab\\u000D\\u0000\\u007F\\u0085\\u009F\\u2028\\u2029 c.txt");
    }
}
