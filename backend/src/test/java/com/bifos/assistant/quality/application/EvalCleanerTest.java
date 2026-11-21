package com.bifos.assistant.quality.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.followup.domain.FollowUp;
import com.bifos.assistant.followup.infra.FollowUpRepository;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.entity.MemoryCapture;
import com.bifos.assistant.memory.domain.type.MemoryCaptureKind;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.infra.MemoryCaptureRepository;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.task.application.TaskService;
import com.bifos.assistant.task.application.dto.ScheduleInput;
import com.bifos.assistant.task.application.dto.TaskInput;
import com.bifos.assistant.task.domain.Task;
import com.bifos.assistant.task.domain.type.ConversationMode;
import com.bifos.assistant.task.domain.type.MissedPolicy;
import com.bifos.assistant.task.domain.type.NotifyPolicy;
import com.bifos.assistant.task.domain.type.TriggerType;
import com.bifos.assistant.task.infra.TaskRepository;
import com.bifos.assistant.task.infra.TaskRunRepository;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 평가 문항 하나의 대화가 만든 것만 지우고, 다른 대화와 배포 뒤 확인, 다른 사용자의 것은 남기는지 본다. */
@BackendIntegrationTest
class EvalCleanerTest {

    private static final Instant NOW = Instant.parse("2026-11-21T01:00:00Z");

    @Autowired
    EvalCleaner cleaner;

    @Autowired
    TestClock clock;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    TaskService taskService;

    @Autowired
    TaskRepository tasks;

    @Autowired
    TaskRunRepository taskRuns;

    @Autowired
    FollowUpRepository followUps;

    @Autowired
    MemoryService memoryService;

    @Autowired
    MemoryRepository memories;

    @Autowired
    MemoryCaptureRepository captures;


    @BeforeEach
    void setUp() {
        clock.set(NOW);
        clean();
    }


    @AfterEach
    void tearDown() {
        clean();
    }


    @Test
    @DisplayName("문항 대화의 예약 작업, 위임 자식이 만든 작업, 할 일, 기억을 지우고 다른 대화와 배포 뒤 확인, 다른 사용자의 것은 남긴다")
    void cleansOnlyTheQuestionConversation() {
        CurrentUser evaluation = user();
        CurrentUser other = user();
        Agent agent = agentOf(evaluation);
        Agent otherAgent = agentOf(other);
        Long question = conversationOf(evaluation, agent);
        Long neighbour = conversationOf(evaluation, agent);
        Instant questionStartedAt = NOW.minusSeconds(60);

        Task own = taskService.create(evaluation, input("문항 작업", agent, "매일 날씨 알려 줘", ConversationMode.SINGLE), question).task();
        Task child = taskService.create(evaluation, input("위임 작업", agent, "매주 정리해 줘", ConversationMode.NEW_PER_RUN), null).task();
        Task deployCheck = taskService.create(evaluation, input("배포 뒤 확인", agent, DeployCheckItems.TASK_INSTRUCTION,
            ConversationMode.NEW_PER_RUN), null).task();
        Task kept = taskService.create(evaluation, input("다른 대화 작업", agent, "알려 줘", ConversationMode.SINGLE), neighbour).task();
        Task otherUser = taskService.create(other, input("남의 작업", otherAgent, "알려 줘", ConversationMode.SINGLE), null).task();
        followUps.save(FollowUp.opened(evaluation.id(), question, "병원 예약", "hospital", null, false, NOW));
        FollowUp neighbourFollowUp = followUps.save(FollowUp.opened(evaluation.id(), neighbour, "장보기", "groceries", null, false, NOW));
        Memory remembered = memoryService.create(evaluation, MemoryScope.USER, "강아지 이름", "초코", false);
        captures.save(MemoryCapture.of(remembered.id(), evaluation.id(), question, 501L, MemoryCaptureKind.CREATED, remembered.revision(), null, false));

        boolean all = cleaner.cleanQuestion(evaluation, question, questionStartedAt);

        assertThat(all).isTrue();
        assertThat(tasks.findById(own.id()).orElseThrow().archived()).isTrue();
        assertThat(tasks.findById(child.id()).orElseThrow().archived()).isTrue();
        assertThat(tasks.findById(deployCheck.id()).orElseThrow().archived()).isFalse();
        assertThat(tasks.findById(kept.id()).orElseThrow().archived()).isFalse();
        assertThat(tasks.findById(otherUser.id()).orElseThrow().archived()).isFalse();
        assertThat(followUps.findAll()).extracting(FollowUp::id).containsExactly(neighbourFollowUp.id());
        assertThat(memories.findById(remembered.id())).isEmpty();
    }


    @Test
    @DisplayName("문항 시작 전에 생긴 대화 칸 빈 작업은 위임 자식의 것으로 보지 않는다")
    void detachedTasksBeforeTheQuestionAreKept() {
        CurrentUser evaluation = user();
        Agent agent = agentOf(evaluation);
        Long question = conversationOf(evaluation, agent);
        Task earlier = taskService.create(evaluation, input("앞 작업", agent, "알려 줘", ConversationMode.NEW_PER_RUN), null).task();

        cleaner.cleanQuestion(evaluation, question, NOW.plusSeconds(1));

        assertThat(tasks.findById(earlier.id()).orElseThrow().archived()).isFalse();
    }


    private CurrentUser user() {
        String email = "eval-" + UUID.randomUUID() + "@example.com";
        AppUser user = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER));
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }


    private Agent agentOf(CurrentUser owner) {
        String code = "eval-" + UUID.randomUUID().toString().substring(0, 8);
        return agents.save(Agent.of(code, code, code, "http://agent-runtime.test/p/" + code, CostMode.SUBSCRIPTION,
            CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.PRIVATE, owner.id()));
    }


    private Long conversationOf(CurrentUser owner, Agent agent) {
        return conversations.save(Conversation.startedBy(owner.id(), "평가", agent.id(), NOW)).id();
    }


    private static TaskInput input(String title, Agent agent, String instruction, ConversationMode mode) {
        return new TaskInput(title, agent.code(), instruction, new ScheduleInput(TriggerType.CRON, "0 9 * * *", null, "Asia/Seoul"),
            mode, MissedPolicy.SKIP, NotifyPolicy.NEVER, null);
    }


    private void clean() {
        captures.deleteAll();
        memories.deleteAll();
        followUps.deleteAll();
        taskRuns.deleteAll();
        tasks.deleteAll();
    }
}
