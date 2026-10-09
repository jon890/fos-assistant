package com.bifos.assistant.followup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.followup.application.FollowUpService;
import com.bifos.assistant.followup.application.model.FollowUpPatch;
import com.bifos.assistant.followup.application.model.FollowUpSnapshot;
import com.bifos.assistant.followup.application.model.NewFollowUp;
import com.bifos.assistant.followup.domain.FollowUp;
import com.bifos.assistant.followup.domain.type.FollowUpStatus;
import com.bifos.assistant.followup.infra.FollowUpRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 할 일의 만들기, 상태 전이, 고치기, 주인 판정을 실제 DB 로 본다. 규칙은 {@code backend/docs/flow.md} 의 「상태」 와 「API(할 일)」 다.
 *
 * <p>사용자는 검사마다 새로 만들어 다른 검사의 줄과 섞이지 않게 하고, 끝나면 그 사용자의 줄을 지운다.
 */
@BackendIntegrationTest
class FollowUpServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-05T09:00:00Z");
    private static final String TITLE = "할 일 검사 7391";

    @Autowired
    FollowUpService followUps;

    @Autowired
    FollowUpRepository repository;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    JdbcTemplate jdbc;

    private final List<Long> createdUsers = new ArrayList<>();
    private CurrentUser dad;
    private CurrentUser kid;

    @BeforeEach
    void setUp() {
        dad = member();
        kid = member();
    }

    @AfterEach
    void tearDown() {
        for (Long userId : createdUsers) {
            jdbc.update("DELETE FROM follow_up WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM conversation WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM agent WHERE owner_user_id = ?", userId);
        }
        createdUsers.clear();
    }

    @Test
    @DisplayName("제목 열쇠는 앞뒤 공백, 연속 공백, 대소문자를 가리지 않는 64자 16진수다")
    void normalizesTitleKey() {
        assertThat(FollowUpService.titleKey("  할 일   검사 ")).isEqualTo(FollowUpService.titleKey("할 일 검사"));
        assertThat(FollowUpService.titleKey("ABC")).isEqualTo(FollowUpService.titleKey("abc"));
        assertThat(FollowUpService.titleKey("할 일 검사")).matches("[0-9a-f]{64}");
        assertThat(FollowUpService.titleKey("할 일 검사")).isNotEqualTo(FollowUpService.titleKey("할 일 검사 2"));
    }

    @Test
    @DisplayName("사람이 더한 할 일은 바로 OPEN 이고 받아들인 시각이 있으며 제안이 아니다")
    void createsOpenFollowUp() {
        FollowUpSnapshot created = followUps.create(dad, input(TITLE));

        assertThat(created.status()).isEqualTo(FollowUpStatus.OPEN);
        assertThat(created.acceptedAt()).isNotNull();
        assertThat(created.proposed()).isFalse();
        assertThat(created.title()).isEqualTo(TITLE);
        assertThat(followUps.list(dad)).extracting(FollowUpSnapshot::publicId).containsExactly(created.publicId());
    }

    @Test
    @DisplayName("같은 제목을 공백만 다르게 다시 더하면 새 줄 없이 같은 할 일을 돌려준다")
    void returnsSameRowForSameTitle() {
        FollowUpSnapshot first = followUps.create(dad, input(TITLE));

        FollowUpSnapshot again = followUps.create(dad, input("  할 일   검사 7391 "));

        assertThat(again.publicId()).isEqualTo(first.publicId());
        assertThat(repository.findByUserIdAndStatusInOrderByIdAsc(dad.id(), List.of(FollowUpStatus.values())))
                .hasSize(1);
    }

    @Test
    @DisplayName("끝낸 할 일과 같은 제목을 다시 더하면 새 OPEN 줄이 생기고 앞 줄은 DONE 으로 남는다")
    void opensNewRowAfterDone() {
        FollowUpSnapshot first = followUps.create(dad, input(TITLE));
        followUps.done(dad, first.publicId());

        FollowUpSnapshot second = followUps.create(dad, input(TITLE));

        assertThat(second.publicId()).isNotEqualTo(first.publicId());
        assertThat(second.status()).isEqualTo(FollowUpStatus.OPEN);
        FollowUp closed =
                repository.findByPublicIdAndUserId(first.publicId(), dad.id()).orElseThrow();
        assertThat(closed.status()).isEqualTo(FollowUpStatus.DONE);
        assertThat(closed.closedAt()).isNotNull();
        assertThat(closed.openMarker()).isNull();
    }

    @Test
    @DisplayName("제안된 할 일과 같은 제목을 사람이 더하면 그 줄을 받아들여 OPEN 으로 돌려준다")
    void acceptsProposedRowOnCreate() {
        FollowUp proposed = repository.saveAndFlush(
                FollowUp.proposed(dad.id(), null, 7391L, TITLE, FollowUpService.titleKey(TITLE), null, false, NOW));

        FollowUpSnapshot created = followUps.create(dad, input(TITLE));

        assertThat(created.publicId()).isEqualTo(proposed.publicId());
        assertThat(created.status()).isEqualTo(FollowUpStatus.OPEN);
        assertThat(created.acceptedAt()).isNotNull();
        assertThat(created.proposed()).isTrue();
    }

    @Test
    @DisplayName("같은 사용자의 같은 제목 열쇠로 열린 줄을 하나 더 저장하면 유일 제약에 걸린다")
    void rejectsSecondOpenRowByUniqueConstraint() {
        String key = FollowUpService.titleKey(TITLE);
        repository.saveAndFlush(FollowUp.opened(dad.id(), null, TITLE, key, null, false, NOW));

        assertThatThrownBy(() -> repository.saveAndFlush(FollowUp.opened(dad.id(), null, TITLE, key, null, false, NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 제목을 동시에 여러 번 더해도 줄은 하나이고 모두 같은 할 일을 돌려받는다")
    void keepsOneRowForConcurrentCreates() throws Exception {
        int callers = 4;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<FollowUpSnapshot>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                Callable<FollowUpSnapshot> create = () -> {
                    start.await();
                    return followUps.create(dad, input(TITLE));
                };
                results.add(pool.submit(create));
            }
            start.countDown();
            List<UUID> ids = new ArrayList<>();
            for (Future<FollowUpSnapshot> result : results) {
                ids.add(result.get().publicId());
            }

            assertThat(ids).as("동시에 더한 결과의 번호").containsOnly(ids.getFirst());
            assertThat(followUps.list(dad)).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("OPEN 인 할 일을 받아들이면 409 다")
    void rejectsAcceptOnOpen() {
        FollowUpSnapshot created = followUps.create(dad, input(TITLE));

        assertCode(() -> followUps.accept(dad, created.publicId()), ErrorCode.FOLLOW_UP_STATE_CONFLICT);
    }

    @Test
    @DisplayName("DONE 인 할 일을 고치면 409 다")
    void rejectsUpdateOnDone() {
        FollowUpSnapshot created = followUps.create(dad, input(TITLE));
        followUps.done(dad, created.publicId());

        assertCode(
                () -> followUps.update(dad, created.publicId(), new FollowUpPatch("다른 제목", false, null, null)),
                ErrorCode.FOLLOW_UP_STATE_CONFLICT);
    }

    @Test
    @DisplayName("열린 할 일의 제목을 다른 열린 할 일의 제목으로 바꾸면 409 다")
    void rejectsRenameToAnotherOpenTitle() {
        followUps.create(dad, input(TITLE));
        FollowUpSnapshot other = followUps.create(dad, input("할 일 검사 8402"));

        assertCode(
                () -> followUps.update(dad, other.publicId(), new FollowUpPatch(" 할 일 검사 7391", false, null, null)),
                ErrorCode.FOLLOW_UP_STATE_CONFLICT);
    }

    @Test
    @DisplayName("기한 칸이 없으면 기한을 그대로 두고, 기한을 null 로 보내면 지운다")
    void keepsOrClearsDueAt() {
        Instant due = Instant.parse("2026-10-06T09:00:00Z");
        FollowUpSnapshot created = followUps.create(dad, new NewFollowUp(TITLE, due, false, null));

        FollowUpSnapshot kept = followUps.update(dad, created.publicId(), new FollowUpPatch(null, false, null, null));
        assertThat(kept.dueAt()).isEqualTo(due);
        assertThat(kept.title()).isEqualTo(TITLE);

        FollowUpSnapshot cleared = followUps.update(dad, created.publicId(), new FollowUpPatch(null, true, null, null));
        assertThat(cleared.dueAt()).isNull();
    }

    @Test
    @DisplayName("남의 할 일을 끝내려 하면 없는 할 일과 같은 404 다")
    void hidesOthersFollowUp() {
        FollowUpSnapshot created = followUps.create(dad, input(TITLE));

        assertCode(() -> followUps.done(kid, created.publicId()), ErrorCode.FOLLOW_UP_NOT_FOUND);
        assertCode(() -> followUps.done(kid, UUID.randomUUID()), ErrorCode.FOLLOW_UP_NOT_FOUND);
        assertThat(followUps.list(kid)).isEmpty();
    }

    @Test
    @DisplayName("남의 대화에 할 일을 연결하려 하면 404 CONVERSATION_NOT_FOUND 다")
    void rejectsOthersConversation() {
        Conversation kids = conversationOf(kid);

        assertCode(
                () -> followUps.create(dad, new NewFollowUp(TITLE, null, false, kids.publicId())),
                ErrorCode.CONVERSATION_NOT_FOUND);
    }

    @Test
    @DisplayName("제목이 없거나 공백뿐이거나 201자면 400 이고 200자는 받는다")
    void validatesTitleLength() {
        assertCode(() -> followUps.create(dad, input(null)), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> followUps.create(dad, input("   ")), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> followUps.create(dad, input("가".repeat(201))), ErrorCode.VALIDATION_FAILED);

        assertThat(followUps.create(dad, input("가".repeat(200))).title()).hasSize(200);
    }

    @Test
    @DisplayName("연결한 대화를 지우면 목록의 그 할 일에 대화 공개 식별자가 없다")
    void hidesDeletedConversation() {
        Conversation conversation = conversationOf(dad);
        FollowUpSnapshot created = followUps.create(dad, new NewFollowUp(TITLE, null, false, conversation.publicId()));
        assertThat(created.conversationPublicId()).isEqualTo(conversation.publicId());

        jdbc.update("UPDATE conversation SET deleted_at = ? WHERE id = ?", Timestamp.from(NOW), conversation.id());

        assertThat(followUps.list(dad)).singleElement().satisfies(row -> {
            assertThat(row.publicId()).isEqualTo(created.publicId());
            assertThat(row.conversationPublicId()).isNull();
        });
    }

    private static NewFollowUp input(String title) {
        return new NewFollowUp(title, null, false, null);
    }

    private static void assertCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).code())
                .isEqualTo(expected);
    }

    private CurrentUser member() {
        String email = "follow-up-" + UUID.randomUUID() + "@example.com";
        AppUser user = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER, NOW));
        createdUsers.add(user.id());
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }

    private Conversation conversationOf(CurrentUser owner) {
        String code = "follow-up-" + UUID.randomUUID().toString().substring(0, 8);
        Agent agent = agents.save(Agent.of(
                code,
                "할 일 검사 도우미",
                code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                owner.id(),
                NOW));
        return conversations.save(Conversation.startedBy(owner.id(), "할 일 검사 대화", agent.id(), NOW));
    }
}
