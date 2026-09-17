package com.bifos.assistant.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.workspace.application.WorkspaceService;
import com.bifos.assistant.workspace.domain.Workspace;
import com.bifos.assistant.workspace.domain.WorkspaceVisibility;
import com.bifos.assistant.workspace.infra.WorkspaceRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 영역의 안내문이 실행에 실리는 조건과, 남의 개인 영역이 존재를 드러내지 않고 거절되는 것을 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
class WorkspaceServiceTest {

    @TempDir static Path root;

    @DynamicPropertySource
    static void pointAtTheMountRoot(DynamicPropertyRegistry registry) {
        registry.add("assistant.workspace.root", () -> root.toString());
    }

    @Autowired WorkspaceService workspaces;
    @Autowired WorkspaceRepository repository;

    private static final Long DAD_ID = 1L;
    private static final Long MOM_ID = 2L;

    private final CurrentUser dad = new CurrentUser(DAD_ID, "dad@example.com", "dad", UserRole.MEMBER);
    private final CurrentUser mom = new CurrentUser(MOM_ID, "mom@example.com", "mom", UserRole.MEMBER);

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    private Workspace familyWorkspace(String code, String guideBody) throws IOException {
        writeGuide(code, guideBody);
        return repository.save(
                Workspace.of(code, code, code, WorkspaceVisibility.FAMILY, null));
    }

    private Workspace privateWorkspace(String code, Long ownerUserId, String guideBody) throws IOException {
        writeGuide(code, guideBody);
        return repository.save(
                Workspace.of(code, code, code, WorkspaceVisibility.PRIVATE, ownerUserId));
    }

    private void writeGuide(String dir, String body) throws IOException {
        Path target = root.resolve(dir);
        Files.createDirectories(target);
        Files.writeString(target.resolve("AGENTS.md"), body);
    }

    @Test
    void 영역을_주면_그_영역의_AGENTSMD_가_instructions_로_넘어간다() throws IOException {
        Workspace home = familyWorkspace("home", "이 영역의 규칙: 반말을 쓰지 않는다.");

        String briefing = workspaces.briefing(home);

        assertThat(briefing).contains("이 영역의 규칙: 반말을 쓰지 않는다.");
    }

    @Test
    void 안내문을_읽지_못하면_null_을_돌려주고_예외를_던지지_않는다() {
        Workspace ghost = repository.save(
                Workspace.of("ghost", "ghost", "no-such-directory", WorkspaceVisibility.FAMILY, null));

        assertThat(workspaces.briefing(ghost)).isNull();
    }

    @Test
    void source_path_가_마운트_밖을_가리키면_지식을_읽지_않는다() throws IOException {
        Path outside = Files.createTempDirectory("workspace-outside-");
        Files.writeString(outside.resolve("AGENTS.md"), "바깥의 비밀");
        Workspace escaping =
                repository.save(
                        Workspace.of(
                                "escape",
                                "escape",
                                root.relativize(outside).toString(),
                                WorkspaceVisibility.FAMILY,
                                null));

        assertThat(workspaces.briefing(escaping)).isNull();
    }

    @Test
    void 남의_개인_영역은_조회할_때_WORKSPACE_NOT_FOUND_로_응답한다() throws IOException {
        privateWorkspace("mom-journal", MOM_ID, "엄마만 보는 것");

        assertThatThrownBy(() -> workspaces.requireReadable(dad, "mom-journal"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.WORKSPACE_NOT_FOUND);
    }

    @Test
    void 가족_공개_영역은_다른_구성원도_쓸_수_있다() throws IOException {
        Workspace home = familyWorkspace("home-2", "가족이 함께 보는 규칙");

        Workspace readByMom = workspaces.requireReadable(mom, "home-2");

        assertThat(readByMom.id()).isEqualTo(home.id());
    }

    @Test
    void 없는_영역_코드도_같은_WORKSPACE_NOT_FOUND_로_응답한다() {
        assertThatThrownBy(() -> workspaces.requireReadable(dad, "no-such-code"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.WORKSPACE_NOT_FOUND);
    }
}
