package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.feedback.application.DecisionFeedbackRecorder;
import com.bifos.assistant.feedback.application.dto.FeedbackEntry;
import com.bifos.assistant.feedback.domain.FeedbackEvent;
import com.bifos.assistant.feedback.domain.type.FeedbackActor;
import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
import com.bifos.assistant.feedback.domain.type.FeedbackSubjectType;
import com.bifos.assistant.feedback.infra.FeedbackEventRepository;
import com.bifos.assistant.mcp.McpCallSigner;
import com.bifos.assistant.memory.maintenance.application.MemoryMaintenanceJob;
import com.bifos.assistant.memory.maintenance.application.MemoryMaintenanceService;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.maintenance.application.dto.MaintenanceCandidate;
import com.bifos.assistant.memory.maintenance.application.dto.MaintenanceReview;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.ExecutionContextSource;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.execution.infra.ExecutionContextSourceRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 기억 정비 루프의 제안 모드를 확인한다(ADR-20261015 / memory-maintenance). 후보와 근거만 사건으로 남고 기억과 연결은 바뀌지 않는다.
 * 계약은 {@code docs/features/memory.md} 의 「정비 루프」 가 갖는다.
 */
@BackendIntegrationTest
class MemoryMaintenanceTest {

    @Autowired
    MemoryMaintenanceJob job;

    @Autowired
    MemoryMaintenanceService maintenance;

    @Autowired
    MemoryService memoryService;

    @Autowired
    MemoryRepository memories;

    @Autowired
    FeedbackEventRepository events;

    @Autowired
    DecisionFeedbackRecorder recorder;

    @Autowired
    ExecutionContextSourceRepository contextSources;

    @Autowired
    AppUserRepository users;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    AgentRepository agents;

    @Autowired
    JdbcTemplate jdbc;

    private CurrentUser dad;
    private CurrentUser mom;
    private CurrentUser kid;
    private final Instant now = Instant.now();


    @BeforeEach
    void setUp() {
        dad = admin("dad");
        mom = admin("mom");
        AppUser kidRow = users.save(AppUser.of("kid-" + UUID.randomUUID() + "@example.com", "아이", 1L, UserRole.MEMBER));
        kid = new CurrentUser(kidRow.id(), kidRow.email(), "아이", 1L, UserRole.MEMBER);
    }


    @AfterEach
    void tearDown() {
        List<Long> ids = List.of(dad.id(), mom.id(), kid.id());
        for (Long id : ids) {
            jdbc.update("DELETE FROM decision_feedback_event WHERE user_id = ?", id);
            jdbc.update("DELETE FROM memory_revision WHERE owner_user_id = ?", id);
            jdbc.update("DELETE FROM memory WHERE owner_user_id = ?", id);
            jdbc.update("DELETE FROM app_user WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM memory WHERE group_id = 1 AND title LIKE '정비 테스트%'");
    }


    @Test
    @DisplayName("오래 실리지 않고 부정 단추가 눌린 항목은 ARCHIVE 후보가 되고, 자동 보관을 허락하지 않은 항목도 근거에 그 값을 남기며 기억은 바뀌지 않는다")
    void proposesArchiveCandidateWithoutChangingMemory() {
        Memory stale = oldMemory(dad, "정비 테스트 오래된 상태", "이번 달 출장 중이다", 100);
        marked(dad, stale, FeedbackEventType.MARKED_WRONG);
        Memory before = memories.findById(stale.id()).orElseThrow();
        long linksBefore = linkCount();

        job.run(now);

        List<FeedbackEvent> proposed = proposedOf(dad, stale);
        assertThat(proposed).hasSize(1);
        FeedbackEvent event = proposed.get(0);
        assertThat(event.reasonCode()).isEqualTo("ARCHIVE");
        assertThat(event.actor()).isEqualTo(FeedbackActor.SYSTEM);
        assertThat(event.subjectVersion()).isEqualTo("1");
        assertThat(event.detail().mode()).isEqualTo("PROPOSE");
        assertThat(event.detail().ruleVersion()).isEqualTo(1);
        assertThat(event.detail().evidence())
            .containsEntry("archivable", false)
            .containsEntry("idleDays", 90)
            .containsEntry("unwantedMarks", 1);
        assertUnchanged(before);
        assertThat(linkCount()).isEqualTo(linksBefore);
    }


    @Test
    @DisplayName("최근에 만들었거나 기간 안에 실렸거나 부정 단추가 없는 항목은 ARCHIVE 후보가 아니다")
    void skipsMemoriesThatDoNotMeetTheRule() {
        Memory fresh = oldMemory(dad, "정비 테스트 최근", "최근 항목", 10);
        marked(dad, fresh, FeedbackEventType.MARKED_IRRELEVANT);
        Memory loaded = oldMemory(dad, "정비 테스트 실린 항목", "실린 항목", 100);
        marked(dad, loaded, FeedbackEventType.MARKED_IRRELEVANT);
        Conversation conversation = conversations.save(Conversation.startedBy(dad.id(), "정비 테스트 대화", null, Instant.now()));
        AgentExecution run = McpCallSigner.running(
            executions, agents, dad.id(), conversation.id(), "memory-maintenance", "fos-" + UUID.randomUUID());
        contextSources.save(ExecutionContextSource.of(run.id(), 0, "MEMORY_INDEX", "memory:" + loaded.id(), "TITLE", "FRESH"));
        Memory quiet = oldMemory(dad, "정비 테스트 단추 없음", "단추가 없는 항목", 100);

        job.run(now);

        assertThat(proposedOf(dad, fresh)).isEmpty();
        assertThat(proposedOf(dad, loaded)).isEmpty();
        assertThat(proposedOf(dad, quiet)).isEmpty();
        jdbc.update("DELETE FROM execution_context_source WHERE execution_id = ?", run.id());
        jdbc.update("DELETE FROM agent_execution WHERE id = ?", run.id());
        jdbc.update("DELETE FROM conversation WHERE id = ?", conversation.id());
    }


    @Test
    @DisplayName("같은 제목과 본문의 항목이 둘이면 오래된 쪽을 남기고 새 쪽만 DEDUP 후보가 된다")
    void proposesDedupForNewerDuplicate() {
        Memory older = oldMemory(dad, "정비 테스트 중복", "딸은 땅콩을 먹지 않는다", 5);
        Memory newer = oldMemory(dad, "정비 테스트 중복", "딸은 땅콩을 먹지 않는다", 2);
        Memory other = oldMemory(dad, "정비 테스트 중복", "다른 글", 1);

        job.run(now);

        assertThat(proposedOf(dad, older)).isEmpty();
        assertThat(proposedOf(dad, other)).isEmpty();
        List<FeedbackEvent> proposed = proposedOf(dad, newer);
        assertThat(proposed).hasSize(1);
        assertThat(proposed.get(0).reasonCode()).isEqualTo("DEDUP");
        assertThat(proposed.get(0).detail().evidence()).containsEntry("keepMemoryId", older.id().intValue());
    }


    @Test
    @DisplayName("같은 항목과 동작의 후보가 있으면 두 번째 실행은 새 줄을 만들지 않는다")
    void secondRunDoesNotCreateDuplicateCandidates() {
        Memory stale = oldMemory(dad, "정비 테스트 반복", "오래된 상태", 100);
        marked(dad, stale, FeedbackEventType.MARKED_WRONG);

        job.run(now);
        job.run(now.plusSeconds(60));

        assertThat(proposedOf(dad, stale)).hasSize(1);
    }


    @Test
    @DisplayName("후보는 항목을 고칠 수 있는 사람만 읽고 확인하며, 남의 후보와 없는 후보는 같은 404 이고, 확인해도 기억은 바뀌지 않는다")
    void onlyEditorReadsAndReviewsCandidates() {
        Memory stale = oldMemory(dad, "정비 테스트 확인", "오래된 상태", 100);
        marked(dad, stale, FeedbackEventType.MARKED_WRONG);
        job.run(now);
        Memory before = memories.findById(stale.id()).orElseThrow();

        List<MaintenanceCandidate> mine = maintenance.candidatesFor(dad);
        assertThat(mine).extracting(MaintenanceCandidate::memoryId).containsExactly(stale.id());
        assertThat(mine.get(0).title()).isEqualTo("정비 테스트 확인");
        assertThat(mine.get(0).archivable()).isFalse();
        assertThat(maintenance.candidatesFor(kid)).isEmpty();
        Long candidateId = mine.get(0).id();

        assertNotFound(() -> maintenance.review(kid, candidateId, MaintenanceReview.CORRECT));
        assertNotFound(() -> maintenance.review(dad, candidateId + 1_000_000L, MaintenanceReview.CORRECT));
        assertNotFound(() -> maintenance.review(mom, candidateId, MaintenanceReview.CORRECT));

        maintenance.review(dad, candidateId, MaintenanceReview.CORRECT);

        assertThat(maintenance.candidatesFor(dad)).isEmpty();
        List<FeedbackEvent> reviews = events.findByUserIdAndEventTypeOrderByOccurredAtAscIdAsc(
            dad.id(), FeedbackEventType.MAINTENANCE_CONFIRMED);
        assertThat(reviews).hasSize(1);
        assertThat(reviews.get(0).actor()).isEqualTo(FeedbackActor.USER);
        assertThat(reviews.get(0).reasonCode()).isEqualTo("ARCHIVE");
        assertThat(reviews.get(0).detail().candidateId()).isEqualTo(candidateId);
        assertUnchanged(before);
        job.run(now.plusSeconds(60));
        assertThat(maintenance.candidatesFor(dad)).as("확인한 후보는 같은 판으로 다시 열리지 않는다").isEmpty();
    }


    @Test
    @DisplayName("「아님」 도 확인 결과로 남고 후보가 닫힌다")
    void deniedReviewClosesCandidate() {
        Memory stale = oldMemory(dad, "정비 테스트 아님", "오래된 상태", 100);
        marked(dad, stale, FeedbackEventType.MARKED_IRRELEVANT);
        job.run(now);
        Long candidateId = maintenance.candidatesFor(dad).get(0).id();

        maintenance.review(dad, candidateId, MaintenanceReview.INCORRECT);

        assertThat(maintenance.candidatesFor(dad)).isEmpty();
        assertThat(events.findByUserIdAndEventTypeOrderByOccurredAtAscIdAsc(dad.id(), FeedbackEventType.MAINTENANCE_DENIED))
            .hasSize(1);
    }


    @Test
    @DisplayName("그룹 항목의 후보는 그룹 관리자마다 하나씩 남고 한 사람이 확인하면 다른 관리자의 후보도 닫힌다")
    void groupCandidateIsSharedByAdmins() {
        Memory group = memoryService.create(
            dad, MemoryScope.GROUP, "정비 테스트 그룹", "오래된 그룹 상태", "core", MemoryRetrieval.SEARCH, MemorySensitivity.NORMAL);
        jdbc.update("UPDATE memory SET created_at = ? WHERE id = ?", Timestamp.from(now.minus(Duration.ofDays(100))), group.id());
        marked(dad, group, FeedbackEventType.MARKED_WRONG);

        job.run(now);

        assertThat(proposedOf(dad, group)).hasSize(1);
        assertThat(proposedOf(mom, group)).hasSize(1);
        assertThat(maintenance.candidatesFor(kid)).as("MEMBER 는 그룹 항목을 고칠 수 없다").isEmpty();
        maintenance.review(mom, maintenance.candidatesFor(mom).get(0).id(), MaintenanceReview.CORRECT);
        assertThat(maintenance.candidatesFor(dad)).isEmpty();
        assertThat(maintenance.candidatesFor(mom)).isEmpty();
    }


    @Test
    @DisplayName("화면의 수정은 자동 보관 허락을 켜고 끄며, 값을 보내지 않으면 지금 값을 그대로 둔다")
    void updateTogglesArchivableOnlyWhenGiven() {
        Memory memory = oldMemory(dad, "정비 테스트 허락", "기간이 있는 상태", 1);

        assertThat(memoryService.update(dad, memory.id(), "고친 글", false, true).archivable()).isTrue();
        assertThat(memoryService.update(dad, memory.id(), "또 고친 글", false, null).archivable()).isTrue();
        assertThat(memoryService.update(dad, memory.id(), "마지막 글", false, false).archivable()).isFalse();
        assertThat(memories.findById(memory.id()).orElseThrow().archivable()).isFalse();
    }


    private CurrentUser admin(String name) {
        AppUser row = users.save(AppUser.of(name + "-" + UUID.randomUUID() + "@example.com", name, 1L, UserRole.ADMIN));
        return new CurrentUser(row.id(), row.email(), name, 1L, UserRole.ADMIN);
    }


    private Memory oldMemory(CurrentUser owner, String title, String content, int ageDays) {
        Memory memory = memoryService.create(
            owner, MemoryScope.USER, title, content, "core", MemoryRetrieval.SEARCH, MemorySensitivity.NORMAL);
        jdbc.update("UPDATE memory SET created_at = ? WHERE id = ?", Timestamp.from(now.minus(Duration.ofDays(ageDays))), memory.id());
        return memory;
    }


    private void marked(CurrentUser user, Memory memory, FeedbackEventType type) {
        recorder.record(FeedbackEntry.of(user.id(), FeedbackSubjectType.MEMORY, memory.id(), type, FeedbackActor.USER, now));
    }


    private List<FeedbackEvent> proposedOf(CurrentUser user, Memory memory) {
        return events.findByUserIdAndEventTypeOrderByOccurredAtAscIdAsc(user.id(), FeedbackEventType.MAINTENANCE_PROPOSED).stream()
            .filter(event -> event.subjectKey().equals("memory:" + memory.id()))
            .toList();
    }


    private void assertUnchanged(Memory before) {
        Memory after = memories.findById(before.id()).orElseThrow();
        assertThat(after)
            .extracting(Memory::title, Memory::content, Memory::revision, Memory::status, Memory::archivable, Memory::retrieval)
            .containsExactly(before.title(), before.content(), before.revision(), before.status(), before.archivable(), before.retrieval());
        assertThat(after.updatedAt()).isEqualTo(before.updatedAt());
    }


    private long linkCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM memory_link", Long.class);
    }


    private static void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run)
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.MEMORY_NOT_FOUND));
    }
}
