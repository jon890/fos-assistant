package com.bifos.assistant.skill;

import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.error.GlobalExceptionHandler;
import com.bifos.assistant.skill.application.SkillDetail;
import com.bifos.assistant.skill.application.SkillPackageFile;
import com.bifos.assistant.skill.application.SkillPackagePreview;
import com.bifos.assistant.skill.application.SkillPackageProblem;
import com.bifos.assistant.skill.application.SkillPackageService;
import com.bifos.assistant.skill.application.SkillPackageZip;
import com.bifos.assistant.skill.application.model.SkillPackageChange;
import com.bifos.assistant.skill.application.model.SkillPackageReason;
import com.bifos.assistant.skill.presentation.SkillPackageController;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 스킬 묶음 경로가 multipart 를 서비스에 넘기는 방식과 미리보기 응답의 칸을 본다. */
class SkillPackageControllerTest {

    private static final CurrentUser OWNER = new CurrentUser(7L, "dad@example.com", "아빠", 1L, UserRole.MEMBER);
    private static final String PREVIEW_PATH = "/api/v1/agents/dad/skill-packages/preview";
    private static final String UPLOAD_PATH = "/api/v1/agents/dad/skill-packages";
    private static final byte[] ZIP = {'P', 'K', 3, 4};

    private final SkillPackageService packages = mock(SkillPackageService.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new SkillPackageController(packages, currentUser))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @BeforeEach
    void setUp() {
        when(currentUser.require()).thenReturn(OWNER);
    }

    @Test
    @DisplayName("미리보기는 문제가 있어도 200 이고 문서의 칸 이름으로 null 칸까지 보낸다")
    void previewIsOkWithDocumentedFieldsIncludingNulls() throws Exception {
        when(packages.preview(eq(OWNER), eq("dad"), aryEq(ZIP)))
                .thenReturn(new SkillPackagePreview(
                        null,
                        null,
                        "---\n",
                        false,
                        null,
                        true,
                        List.of(new SkillPackageFile("SKILL.md", 4L, SkillPackageChange.ADDED)),
                        List.of("__MACOSX/._SKILL.md"),
                        List.of(
                                new SkillPackageProblem(SkillPackageReason.FRONTMATTER_INVALID, "SKILL.md"),
                                new SkillPackageProblem(SkillPackageReason.SCRIPTS_NEED_SANDBOX, null))));

        mvc.perform(multipart(PREVIEW_PATH).file(file(ZIP)))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                                {
                                  "name": null,
                                  "description": null,
                                  "skillMdHead": "---\\n",
                                  "existing": false,
                                  "baseDigest": null,
                                  "hasScripts": true,
                                  "files": [{ "path": "SKILL.md", "size": 4, "change": "ADDED" }],
                                  "ignored": ["__MACOSX/._SKILL.md"],
                                  "problems": [
                                    { "reason": "FRONTMATTER_INVALID", "path": "SKILL.md" },
                                    { "reason": "SCRIPTS_NEED_SANDBOX", "path": null }
                                  ]
                                }
                                """, JsonCompareMode.STRICT));
    }

    @Test
    @DisplayName("올리기는 multipart 의 file 과 baseDigest 를 그대로 넘기고 저장한 스킬을 준다")
    void uploadPassesFileAndBaseDigest() throws Exception {
        SkillDetail saved = new SkillDetail("weekly-plan", "이번 주 계획", "---\n", List.of(), null);
        when(packages.upload(eq(OWNER), eq("dad"), aryEq(ZIP), eq("abc123"))).thenReturn(saved);
        when(packages.upload(eq(OWNER), eq("dad"), aryEq(ZIP), isNull())).thenReturn(saved);

        mvc.perform(multipart(UPLOAD_PATH).file(file(ZIP)).param("baseDigest", "abc123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("weekly-plan"));

        mvc.perform(multipart(UPLOAD_PATH).file(file(ZIP))).andExpect(status().isOk());
        verify(packages).upload(eq(OWNER), eq("dad"), aryEq(ZIP), isNull());
    }

    @Test
    @DisplayName("2 MiB 를 넘는 파일은 바이트를 넘기지 않고 크기 초과 경로를 부른다")
    void oversizedFileTakesTooLargePath() throws Exception {
        byte[] oversized = new byte[SkillPackageZip.MAX_ZIP_BYTES + 1];
        when(packages.previewTooLarge(OWNER, "dad"))
                .thenReturn(new SkillPackagePreview(
                        null,
                        null,
                        null,
                        false,
                        null,
                        false,
                        List.of(),
                        List.of(),
                        List.of(new SkillPackageProblem(SkillPackageReason.ZIP_TOO_LARGE, null))));
        when(packages.uploadTooLarge(OWNER, "dad"))
                .thenReturn(new ApiException(ErrorCode.SKILL_PACKAGE_INVALID, "ZIP_TOO_LARGE"));

        mvc.perform(multipart(PREVIEW_PATH).file(file(oversized)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.problems[0].reason").value("ZIP_TOO_LARGE"));
        mvc.perform(multipart(UPLOAD_PATH).file(file(oversized)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SKILL_PACKAGE_INVALID"));

        verify(packages, never()).preview(any(), anyString(), any());
        verify(packages, never()).upload(any(), anyString(), any(), any());
    }

    private static MockMultipartFile file(byte[] bytes) {
        return new MockMultipartFile("file", "skill.zip", "application/zip", bytes);
    }
}
