package com.bifos.assistant.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.error.GlobalExceptionHandler;
import com.bifos.assistant.skill.application.SkillDetail;
import com.bifos.assistant.skill.application.SkillFileInfo;
import com.bifos.assistant.skill.application.SkillFileInput;
import com.bifos.assistant.skill.application.SkillList;
import com.bifos.assistant.skill.application.SkillListItem;
import com.bifos.assistant.skill.application.SkillService;
import com.bifos.assistant.skill.application.SkillSource;
import com.bifos.assistant.skill.presentation.SkillAdminController;
import com.bifos.assistant.skill.presentation.SkillController;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 스킬 경로가 HTTP 경계에서 돌려주는 상태 코드와 응답 모양을 본다. */
class SkillControllerTest {

    private static final CurrentUser OWNER = new CurrentUser(7L, "dad@example.com", "아빠", 1L, UserRole.MEMBER);

    private final SkillService skills = mock(SkillService.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new SkillController(skills, currentUser), new SkillAdminController(skills, currentUser))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @BeforeEach
    void setUp() {
        when(currentUser.require()).thenReturn(OWNER);
    }

    @Test
    @DisplayName("목록은 출처와 편집 여부와 도구 상태와 올릴 수 있는 한도를 주고 사용량이 없으면 칸을 뺀다")
    void listGivesSourceEditabilityToolStatusAndUploadLimit() throws Exception {
        when(skills.list(OWNER, "dad"))
                .thenReturn(new SkillList(
                        List.of(new SkillListItem("weekly-plan", "이번 주 계획", SkillSource.UPLOADED, false, null)),
                        true,
                        true,
                        30));

        mvc.perform(get("/api/v1/agents/dad/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skills.length()").value(1))
                .andExpect(jsonPath("$.skills[0].name").value("weekly-plan"))
                .andExpect(jsonPath("$.skills[0].source").value("UPLOADED"))
                .andExpect(jsonPath("$.skills[0].usage").doesNotExist())
                .andExpect(jsonPath("$.skills[0].enabled").value(false))
                .andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.skillsToolsetEnabled").value(true))
                .andExpect(jsonPath("$.uploadLimit").value(30));
    }

    @Test
    @DisplayName("없는 스킬은 404 SKILL NOT FOUND 다")
    void missingSkillIs404SkillNotFound() throws Exception {
        when(skills.read(OWNER, "dad", "missing"))
                .thenThrow(new ApiException(ErrorCode.SKILL_NOT_FOUND, "no such uploaded skill"));

        mvc.perform(get("/api/v1/agents/dad/skills/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.SKILL_NOT_FOUND.name()));
    }

    @Test
    @DisplayName("저장은 본문을 생략한 파일을 null 본문으로 넘기고 원문과 파일 크기를 돌려준다")
    void saveSendsOmittedBodyAsNullAndReturnsOriginalAndFileSize() throws Exception {
        when(skills.save(eq(OWNER), eq("dad"), eq("weekly-plan"), anyString(), any()))
                .thenReturn(new SkillDetail(
                        "weekly-plan",
                        "이번 주 계획",
                        "---\nname: weekly-plan\n---\n",
                        List.of(new SkillFileInfo("references/guide.md", 9L, "안내문"))));

        mvc.perform(
                        write(
                                "weekly-plan",
                                "{\"skillMd\":\"---\\nname: weekly-plan\\n---\\n\","
                                        + "\"files\":[{\"path\":\"references/guide.md\"},{\"path\":\"templates/t.md\",\"content\":\"x\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("weekly-plan"))
                .andExpect(jsonPath("$.description").value("이번 주 계획"))
                .andExpect(jsonPath("$.body").value("---\nname: weekly-plan\n---\n"))
                .andExpect(jsonPath("$.files[0].path").value("references/guide.md"))
                .andExpect(jsonPath("$.files[0].size").value(9))
                .andExpect(jsonPath("$.files[0].content").value("안내문"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SkillFileInput>> files = ArgumentCaptor.forClass(List.class);
        verify(skills)
                .save(eq(OWNER), eq("dad"), eq("weekly-plan"), eq("---\nname: weekly-plan\n---\n"), files.capture());
        assertThat(files.getValue())
                .containsExactly(
                        new SkillFileInput("references/guide.md", null), new SkillFileInput("templates/t.md", "x"));
    }

    @Test
    @DisplayName("경로가 빈 파일과 본문이 없는 요청은 서비스에 닿기 전에 거절한다")
    void rejectsBlankPathFileAndBodylessRequestBeforeService() throws Exception {
        mvc.perform(write("weekly-plan", "{\"skillMd\":\"x\",\"files\":[{\"path\":\" \",\"content\":\"x\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
        mvc.perform(write("weekly-plan", "{\"files\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));

        verifyNoInteractions(skills);
    }

    @Test
    @DisplayName("Hermes 와 같은 이름은 409 SKILL NAME TAKEN 다")
    void nameSameAsHermesIs409SkillNameTaken() throws Exception {
        when(skills.save(eq(OWNER), eq("dad"), eq("hermes-help"), anyString(), any()))
                .thenThrow(new ApiException(ErrorCode.SKILL_NAME_TAKEN, "taken"));

        mvc.perform(write("hermes-help", "{\"skillMd\":\"---\\nname: hermes-help\\n---\\n\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.SKILL_NAME_TAKEN.name()));
    }

    @Test
    @DisplayName("지우기와 켜고 끄기는 본문 없이 204 다")
    void deleteAndToggleReturn204WithoutBody() throws Exception {
        mvc.perform(delete("/api/v1/agents/dad/skills/weekly-plan")).andExpect(status().isNoContent());
        verify(skills).delete(OWNER, "dad", "weekly-plan");

        mvc.perform(put("/api/v1/agents/dad/skills/weekly-plan/enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isNoContent());
        verify(skills).toggle(OWNER, "dad", "weekly-plan", false);

        mvc.perform(put("/api/v1/agents/dad/skills/weekly-plan/enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
    }

    @Test
    @DisplayName("켜고 끄기는 점과 밑줄이 든 Hermes 스킬 이름을 그대로 넘긴다")
    void toggleSendsHermesSkillNameWithDotAndUnderscoreAsIs() throws Exception {
        mvc.perform(put("/api/v1/agents/dad/skills/note_taking.v2/enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isNoContent());
        verify(skills).toggle(OWNER, "dad", "note_taking.v2", true);
    }

    @Test
    @DisplayName("관리자 목록과 기본 스킬 토글은 관리자 인증을 쓰고 일반 역할을 거절한다")
    void adminRoutesRequireAdminAndUseAdminService() throws Exception {
        when(currentUser.requireAdmin()).thenThrow(new ApiException(ErrorCode.FORBIDDEN, "admin only"));
        mvc.perform(get("/api/v1/admin/agents/dad/skills")).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/admin/agents/dad/skills/hermes-help/enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(skills);
        CurrentUser admin = new CurrentUser(7L, "dad@example.com", "아빠", 1L, UserRole.ADMIN);
        when(currentUser.requireAdmin()).thenReturn(admin);
        when(skills.adminList(admin, "dad"))
                .thenReturn(new SkillList(
                        List.of(new SkillListItem("hermes-help", "기본", SkillSource.HERMES, true, null)),
                        true,
                        true,
                        30));
        mvc.perform(get("/api/v1/admin/agents/dad/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skills[0].source").value("HERMES"));
        mvc.perform(put("/api/v1/admin/agents/dad/skills/hermes-help/enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isNoContent());
        verify(skills).adminToggle(admin, "dad", "hermes-help", false);
    }

    private static RequestBuilder write(String name, String json) {
        return put("/api/v1/agents/{code}/skills/{name}", "dad", name)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }
}
