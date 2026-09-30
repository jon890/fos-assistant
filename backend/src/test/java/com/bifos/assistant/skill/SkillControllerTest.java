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
import com.bifos.assistant.skill.presentation.SkillController;
import com.bifos.assistant.user.domain.UserRole;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
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

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new SkillController(skills, currentUser))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @BeforeEach
    void 준비한다() {
        when(currentUser.require()).thenReturn(OWNER);
    }

    @Test
    void 목록은_출처와_편집_여부와_도구_상태를_주고_사용량이_없으면_칸을_뺀다() throws Exception {
        when(skills.list(OWNER, "dad")).thenReturn(new SkillList(List.of(
                new SkillListItem("hermes-help", "Hermes 기본", SkillSource.HERMES, true, null),
                new SkillListItem("weekly-plan", "이번 주 계획", SkillSource.UPLOADED, false, null)),
                true, true));

        mvc.perform(get("/api/v1/agents/dad/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skills.length()").value(2))
                .andExpect(jsonPath("$.skills[0].name").value("hermes-help"))
                .andExpect(jsonPath("$.skills[0].source").value("HERMES"))
                .andExpect(jsonPath("$.skills[0].usage").doesNotExist())
                .andExpect(jsonPath("$.skills[1].source").value("UPLOADED"))
                .andExpect(jsonPath("$.skills[1].enabled").value(false))
                .andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.skillsToolsetEnabled").value(true));
    }

    @Test
    void 없는_스킬은_404_SKILL_NOT_FOUND_다() throws Exception {
        when(skills.read(OWNER, "dad", "missing"))
                .thenThrow(new ApiException(ErrorCode.SKILL_NOT_FOUND, "no such uploaded skill"));

        mvc.perform(get("/api/v1/agents/dad/skills/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.SKILL_NOT_FOUND.name()));
    }

    @Test
    void 저장은_본문을_생략한_파일을_null_본문으로_넘기고_원문과_파일_크기를_돌려준다() throws Exception {
        when(skills.save(eq(OWNER), eq("dad"), eq("weekly-plan"), anyString(), any()))
                .thenReturn(new SkillDetail("weekly-plan", "이번 주 계획", "---\nname: weekly-plan\n---\n",
                        List.of(new SkillFileInfo("references/guide.md", 9L))));

        mvc.perform(write("weekly-plan", "{\"skillMd\":\"---\\nname: weekly-plan\\n---\\n\","
                        + "\"files\":[{\"path\":\"references/guide.md\"},{\"path\":\"templates/t.md\",\"content\":\"x\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("weekly-plan"))
                .andExpect(jsonPath("$.description").value("이번 주 계획"))
                .andExpect(jsonPath("$.body").value("---\nname: weekly-plan\n---\n"))
                .andExpect(jsonPath("$.files[0].path").value("references/guide.md"))
                .andExpect(jsonPath("$.files[0].size").value(9));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SkillFileInput>> files = ArgumentCaptor.forClass(List.class);
        verify(skills).save(eq(OWNER), eq("dad"), eq("weekly-plan"), eq("---\nname: weekly-plan\n---\n"), files.capture());
        assertThat(files.getValue()).containsExactly(
                new SkillFileInput("references/guide.md", null),
                new SkillFileInput("templates/t.md", "x"));
    }

    @Test
    void 경로가_빈_파일과_본문이_없는_요청은_서비스에_닿기_전에_거절한다() throws Exception {
        mvc.perform(write("weekly-plan", "{\"skillMd\":\"x\",\"files\":[{\"path\":\" \",\"content\":\"x\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
        mvc.perform(write("weekly-plan", "{\"files\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));

        verifyNoInteractions(skills);
    }

    @Test
    void Hermes_와_같은_이름은_409_SKILL_NAME_TAKEN_다() throws Exception {
        when(skills.save(eq(OWNER), eq("dad"), eq("hermes-help"), anyString(), any()))
                .thenThrow(new ApiException(ErrorCode.SKILL_NAME_TAKEN, "taken"));

        mvc.perform(write("hermes-help", "{\"skillMd\":\"---\\nname: hermes-help\\n---\\n\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.SKILL_NAME_TAKEN.name()));
    }

    @Test
    void 지우기와_켜고_끄기는_본문_없이_204_다() throws Exception {
        mvc.perform(delete("/api/v1/agents/dad/skills/weekly-plan"))
                .andExpect(status().isNoContent());
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
    void 켜고_끄기는_점과_밑줄이_든_Hermes_스킬_이름을_그대로_넘긴다() throws Exception {
        mvc.perform(put("/api/v1/agents/dad/skills/note_taking.v2/enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isNoContent());
        verify(skills).toggle(OWNER, "dad", "note_taking.v2", true);
    }

    private static RequestBuilder write(String name, String json) {
        return put("/api/v1/agents/{code}/skills/{name}", "dad", name)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }
}
