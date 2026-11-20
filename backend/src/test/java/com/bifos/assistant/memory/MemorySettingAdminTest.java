package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.admin.memory.presentation.MemoryAdminDtos.MemoryContextSettingView;
import com.bifos.assistant.admin.memory.presentation.MemoryAdminDtos.ReplaceMemoryContextRequest;
import com.bifos.assistant.admin.memory.presentation.MemorySettingAdminController;
import com.bifos.assistant.memory.application.MemoryContextSettings;
import com.bifos.assistant.memory.application.dto.MemoryContextBudget;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 관리자가 그룹의 기억 요약 구역 예산과 기억 전체 상한을 읽고 바꾸는 API 를 확인한다(docs/features/memory/spec.md 의 REQ4). */
@BackendIntegrationTest
class MemorySettingAdminTest {

    /** 다른 테스트가 쓰지 않는 그룹 번호에서 시작한다. */
    private static final AtomicLong NEXT_GROUP = new AtomicLong(7_412_000_000L);

    @Autowired
    MemoryContextSettings settings;

    @Autowired
    JdbcTemplate jdbc;

    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private Long groupId;
    private CurrentUser admin;
    private MemorySettingAdminController controller;


    @BeforeEach
    void setUp() {
        groupId = NEXT_GROUP.incrementAndGet();
        admin = new CurrentUser(1L, "admin@example.com", "admin", groupId, UserRole.ADMIN);
        when(currentUser.requireAdmin()).thenReturn(admin);
        controller = new MemorySettingAdminController(settings, currentUser);
    }


    @AfterEach
    void tearDown() {
        MemorySummaryRows.clearBudget(jdbc, groupId);
    }


    @Test
    @DisplayName("설정 줄이 없는 그룹은 요약 4,000자와 전체 10,000자이고 허용 범위를 함께 낸다")
    void returnsDefaultsWithoutRow() {
        MemoryContextSettingView view = controller.get();

        assertThat(view.summaryMaxChars()).isEqualTo(4_000);
        assertThat(view.maxChars()).isEqualTo(10_000);
        assertThat(view.summaryMin()).isEqualTo(1_000);
        assertThat(view.summaryCeiling()).isEqualTo(7_500);
        assertThat(view.maxCharsMin()).isEqualTo(4_000);
        assertThat(view.maxCharsMax()).isEqualTo(40_000);
    }


    @Test
    @DisplayName("관리자가 바꾼 값을 다시 읽으면 그 값이고 조립이 읽는 예산도 바뀐다")
    void savesAndReadsBack() {
        controller.replace(new ReplaceMemoryContextRequest(6_000L, 12_000L));

        assertThat(controller.get().summaryMaxChars()).isEqualTo(6_000);
        assertThat(settings.budgetFor(groupId)).isEqualTo(new MemoryContextBudget(6_000, 12_000));
    }


    @Test
    @DisplayName("관리자가 아니면 FORBIDDEN 이고 범위 밖이거나 요약이 전체에서 색인 몫을 뺀 자리를 넘으면 VALIDATION_FAILED 다")
    void rejectsNonAdminAndOutOfRange() {
        CurrentUser member = new CurrentUser(2L, "member@example.com", "member", groupId, UserRole.MEMBER);

        assertCode(() -> settings.save(member, 4_000, 10_000), ErrorCode.FORBIDDEN);
        assertCode(() -> controller.replace(new ReplaceMemoryContextRequest(999L, 10_000L)), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> controller.replace(new ReplaceMemoryContextRequest(4_000L, 3_999L)), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> controller.replace(new ReplaceMemoryContextRequest(4_000L, 40_001L)), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> controller.replace(new ReplaceMemoryContextRequest(7_501L, 10_000L)), ErrorCode.VALIDATION_FAILED);
        assertThat(settings.budgetFor(groupId)).isEqualTo(MemoryContextBudget.DEFAULTS);
    }


    @Test
    @DisplayName("깨진 JSON 과 모르는 버전의 설정 줄은 기본값으로 돈다")
    void fallsBackToDefaultsOnBrokenRow() {
        MemorySummaryRows.useBudget(jdbc, groupId, "{");
        assertThat(settings.budgetFor(groupId)).isEqualTo(MemoryContextBudget.DEFAULTS);

        MemorySummaryRows.useBudget(jdbc, groupId, "{\"version\":9,\"summaryMaxChars\":2000,\"maxChars\":8000}");
        assertThat(settings.budgetFor(groupId)).isEqualTo(MemoryContextBudget.DEFAULTS);
    }


    private static void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
