package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.bifos.assistant.memory.application.MemoryLinkService;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.entity.MemoryLink;
import com.bifos.assistant.memory.domain.type.MemoryLinkOrigin;
import com.bifos.assistant.memory.domain.type.MemoryLinkStatus;
import com.bifos.assistant.memory.domain.type.MemoryLinkType;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.infra.MemoryLinkRepository;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.memory.infra.MemoryRevisionRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 기억 연결의 규칙과 받아들이기, 거절, 되돌리기를 확인한다. */
@BackendIntegrationTest
class MemoryLinkServiceTest {

    private static final CurrentUser ADMIN = user(1L, 10L, UserRole.ADMIN);
    private static final CurrentUser MEMBER = user(2L, 10L, UserRole.MEMBER);

    @Autowired
    MemoryLinkService linkService;

    @Autowired
    MemoryService memories;

    @Autowired
    MemoryLinkRepository links;

    @Autowired
    MemoryRepository repository;

    @Autowired
    MemoryRevisionRepository revisions;

    @Autowired
    JdbcTemplate jdbc;


    @BeforeEach
    void setUp() {
        links.deleteAll();
        repository.deleteAll();
        revisions.deleteAll();
    }


    @Test
    @DisplayName("대체는 제안으로 남고 주인이 받아들이면 효력이 생기며 되돌리면 끊긴다")
    void proposesAcceptsAndRevokesSupersede() {
        Memory old = memories.create(ADMIN, MemoryScope.USER, "옛 주소", "서울", false);
        Memory fresh = memories.create(ADMIN, MemoryScope.USER, "새 주소", "부산", false);

        MemoryLink proposed = linkService.proposeSupersede(ADMIN, fresh.id(), old.id(), MemoryLinkOrigin.AGENT, 7L);

        assertThat(proposed.status()).isEqualTo(MemoryLinkStatus.PROPOSED);
        assertThat(proposed.fromMemoryId()).isEqualTo(fresh.id());
        assertThat(proposed.toMemoryId()).isEqualTo(old.id());
        assertThat(proposed.createdByExecutionId()).isEqualTo(7L);
        assertThat(linkService.isSuperseded(old.id())).as("제안만으로는 대체되지 않는다").isFalse();
        assertThat(linkService.pendingSupersedesFor(ADMIN)).extracting(p -> p.link().id()).containsExactly(proposed.id());

        linkService.accept(ADMIN, proposed.id());

        assertThat(linkService.isSuperseded(old.id())).isTrue();
        assertThat(linkService.supersededBy(List.of(old.id(), fresh.id())))
            .containsOnlyKeys(old.id())
            .extractingByKey(old.id())
            .extracting(MemoryLink::fromMemoryId)
            .isEqualTo(fresh.id());
        assertThat(linkService.pendingSupersedesFor(ADMIN)).isEmpty();

        MemoryLink revoked = linkService.revoke(ADMIN, proposed.id());

        assertThat(revoked.status()).isEqualTo(MemoryLinkStatus.REVOKED);
        assertThat(revoked.revokedAt()).isNotNull();
        assertThat(linkService.isSuperseded(old.id())).isFalse();
    }


    @Test
    @DisplayName("거절하면 끊긴 줄로 남고 같은 쌍을 다시 맺으면 그 줄을 다시 연다")
    void rejectsAndReopensSamePair() {
        Memory old = memories.create(ADMIN, MemoryScope.USER, "옛", "1", false);
        Memory fresh = memories.create(ADMIN, MemoryScope.USER, "새", "2", false);
        MemoryLink proposed = linkService.proposeSupersede(ADMIN, fresh.id(), old.id(), MemoryLinkOrigin.AGENT, 1L);

        linkService.reject(ADMIN, proposed.id());
        MemoryLink again = linkService.proposeSupersede(ADMIN, fresh.id(), old.id(), MemoryLinkOrigin.AGENT, 2L);

        assertThat(again.id()).isEqualTo(proposed.id());
        assertThat(again.status()).isEqualTo(MemoryLinkStatus.PROPOSED);
        assertThat(again.revokedAt()).isNull();
        assertThat(again.createdByExecutionId()).isEqualTo(2L);
        assertThat(links.count()).isEqualTo(1);
    }


    @Test
    @DisplayName("이미 있는 제안이나 연결을 다시 맺으면 그 줄을 그대로 돌려준다")
    void returnsExistingLinkForSamePair() {
        Memory a = memories.create(ADMIN, MemoryScope.USER, "가", "1", false);
        Memory b = memories.create(ADMIN, MemoryScope.USER, "나", "2", false);

        MemoryLink related = linkService.relate(ADMIN, b.id(), a.id(), "같은 여행", MemoryLinkOrigin.AGENT, null, 1L);
        MemoryLink same = linkService.relate(ADMIN, a.id(), b.id(), "다른 글", MemoryLinkOrigin.AGENT, null, 2L);

        assertThat(related.type()).isEqualTo(MemoryLinkType.RELATED);
        assertThat(related.status()).isEqualTo(MemoryLinkStatus.ACTIVE);
        assertThat(related.fromMemoryId()).as("번호가 작은 쪽이 from 이다").isEqualTo(a.id());
        assertThat(related.label()).isEqualTo("같은 여행");
        assertThat(same.id()).isEqualTo(related.id());
        assertThat(same.label()).isEqualTo("같은 여행");
    }


    @Test
    @DisplayName("범위가 다르거나 주인이 다르거나 승인 전이거나 출처 원문이거나 자기 자신이면 없는 항목과 같이 거절한다")
    void rejectsLinksThatBreakTheRules() {
        Memory mine = memories.create(ADMIN, MemoryScope.USER, "내 것", "1", false);
        Memory other = memories.create(ADMIN, MemoryScope.USER, "내 다른 것", "2", false);
        Memory group = memories.create(ADMIN, MemoryScope.GROUP, "그룹", "3", false);
        Memory membersOwn = memories.create(MEMBER, MemoryScope.USER, "남의 것", "4", false);
        Memory proposal = memories.proposeUser(ADMIN, "제안", "5", 1L);
        Memory source = memories.create(ADMIN, MemoryScope.USER, "출처", "6", false);
        jdbc.update("UPDATE memory SET entry_type = 'SOURCE' WHERE id = ?", source.id());

        assertNotFound(() -> linkService.relate(ADMIN, mine.id(), group.id(), null, MemoryLinkOrigin.AGENT, null, 1L));
        assertNotFound(() -> linkService.relate(ADMIN, mine.id(), membersOwn.id(), null, MemoryLinkOrigin.AGENT, null, 1L));
        assertNotFound(() -> linkService.proposeSupersede(ADMIN, proposal.id(), mine.id(), MemoryLinkOrigin.AGENT, 1L));
        assertNotFound(() -> linkService.proposeSupersede(ADMIN, source.id(), mine.id(), MemoryLinkOrigin.AGENT, 1L));
        assertNotFound(() -> linkService.relate(ADMIN, mine.id(), mine.id(), null, MemoryLinkOrigin.AGENT, null, 1L));
        assertNotFound(() -> linkService.relate(ADMIN, mine.id(), 999_999L, null, MemoryLinkOrigin.AGENT, null, 1L));
        assertThatThrownBy(() -> linkService.relate(ADMIN, mine.id(), other.id(), "가".repeat(201), MemoryLinkOrigin.AGENT, null, 1L))
            .isInstanceOfSatisfying(
                ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThat(links.count()).isZero();
    }


    @Test
    @DisplayName("남의 줄을 받아들이면 없는 줄과 같은 404 이고 그룹 항목의 대체는 MEMBER 가 받아들이지 못한다")
    void onlyOwnerOrAdminDecides() {
        Memory old = memories.create(ADMIN, MemoryScope.USER, "옛", "1", false);
        Memory fresh = memories.create(ADMIN, MemoryScope.USER, "새", "2", false);
        MemoryLink personal = linkService.proposeSupersede(ADMIN, fresh.id(), old.id(), MemoryLinkOrigin.AGENT, 1L);
        Memory groupOld = memories.create(ADMIN, MemoryScope.GROUP, "그룹 옛", "3", false);
        Memory groupNew = memories.create(ADMIN, MemoryScope.GROUP, "그룹 새", "4", false);
        MemoryLink group = linkService.proposeSupersede(MEMBER, groupNew.id(), groupOld.id(), MemoryLinkOrigin.AGENT, 1L);

        assertNotFound(() -> linkService.accept(MEMBER, personal.id()));
        assertNotFound(() -> linkService.accept(MEMBER, 999_999L));
        assertThatThrownBy(() -> linkService.accept(MEMBER, group.id()))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(linkService.pendingSupersedesFor(MEMBER)).isEmpty();
        assertThat(linkService.pendingSupersedesFor(ADMIN))
            .extracting(p -> p.link().id(), p -> p.newMemory().title(), p -> p.oldMemory().title())
            .containsExactly(tuple(personal.id(), "새", "옛"), tuple(group.id(), "그룹 새", "그룹 옛"));

        linkService.accept(ADMIN, group.id());

        assertThat(linkService.isSuperseded(groupOld.id())).isTrue();
    }


    @Test
    @DisplayName("이미 받아들인 줄을 다시 받아들이거나 제안이 아닌 줄을 거절하면 상태 충돌이다")
    void rejectsTransitionsFromWrongState() {
        Memory old = memories.create(ADMIN, MemoryScope.USER, "옛", "1", false);
        Memory fresh = memories.create(ADMIN, MemoryScope.USER, "새", "2", false);
        MemoryLink link = linkService.proposeSupersede(ADMIN, fresh.id(), old.id(), MemoryLinkOrigin.AGENT, 1L);
        linkService.accept(ADMIN, link.id());

        assertConflict(() -> linkService.accept(ADMIN, link.id()));
        assertConflict(() -> linkService.reject(ADMIN, link.id()));

        Memory related = memories.create(ADMIN, MemoryScope.USER, "관련", "3", false);
        MemoryLink relatedLink = linkService.relate(ADMIN, old.id(), related.id(), null, MemoryLinkOrigin.AGENT, null, 1L);
        assertConflict(() -> linkService.accept(ADMIN, relatedLink.id()));
    }


    @Test
    @DisplayName("한 항목을 두 줄이 대신하면 하나를 되돌려도 다른 줄이 있는 동안 대체된 채다")
    void keepsSupersededWhileAnotherActiveLinkRemains() {
        Memory old = memories.create(ADMIN, MemoryScope.USER, "옛", "1", false);
        Memory first = memories.create(ADMIN, MemoryScope.USER, "새 1", "2", false);
        Memory second = memories.create(ADMIN, MemoryScope.USER, "새 2", "3", false);
        MemoryLink a = linkService.proposeSupersede(ADMIN, first.id(), old.id(), MemoryLinkOrigin.AGENT, 1L);
        MemoryLink b = linkService.proposeSupersede(ADMIN, second.id(), old.id(), MemoryLinkOrigin.AGENT, 1L);
        linkService.accept(ADMIN, a.id());
        linkService.accept(ADMIN, b.id());

        linkService.revoke(ADMIN, a.id());

        assertThat(linkService.isSuperseded(old.id())).isTrue();
        assertThat(linkService.supersededBy(List.of(old.id())).get(old.id()).id()).isEqualTo(b.id());
    }


    @Test
    @DisplayName("새 항목이 제안 뒤에 지워지면 받아들이지 못한다")
    void refusesAcceptWhenNewMemoryIsGone() {
        Memory old = memories.create(ADMIN, MemoryScope.USER, "옛", "1", false);
        Memory fresh = memories.create(ADMIN, MemoryScope.USER, "새", "2", false);
        MemoryLink link = linkService.proposeSupersede(ADMIN, fresh.id(), old.id(), MemoryLinkOrigin.AGENT, 1L);
        jdbc.update("UPDATE memory SET status = 'REJECTED' WHERE id = ?", fresh.id());

        assertConflict(() -> linkService.accept(ADMIN, link.id()));
        assertThat(linkService.isSuperseded(old.id())).isFalse();
    }


    @Test
    @DisplayName("서로를 대체하는 두 제안 가운데 하나를 받아들이면 다른 하나는 목록에서 빠지고 받아들일 수 없다")
    void refusesSupersedeCycle() {
        Memory a = memories.create(ADMIN, MemoryScope.USER, "가", "1", false);
        Memory b = memories.create(ADMIN, MemoryScope.USER, "나", "2", false);
        MemoryLink aOverB = linkService.proposeSupersede(ADMIN, a.id(), b.id(), MemoryLinkOrigin.AGENT, 1L);
        MemoryLink bOverA = linkService.proposeSupersede(ADMIN, b.id(), a.id(), MemoryLinkOrigin.AGENT, 2L);

        linkService.accept(ADMIN, aOverB.id());

        assertThat(linkService.pendingSupersedesFor(ADMIN)).isEmpty();
        assertConflict(() -> linkService.accept(ADMIN, bOverA.id()));
        assertThat(linkService.isSuperseded(a.id())).isFalse();
        assertThat(linkService.isSuperseded(b.id())).isTrue();
    }


    @Test
    @DisplayName("같은 제안에 받아들이기와 거절이 함께 오면 하나만 이기고 다른 하나는 상태 충돌이다")
    void decidesConcurrentAcceptAndRejectOnce() throws Exception {
        for (int round = 0; round < 5; round++) {
            Memory old = memories.create(ADMIN, MemoryScope.USER, "옛 " + round, "1", false);
            Memory fresh = memories.create(ADMIN, MemoryScope.USER, "새 " + round, "2", false);
            MemoryLink link = linkService.proposeSupersede(ADMIN, fresh.id(), old.id(), MemoryLinkOrigin.AGENT, null);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Future<String>> results = List.of(
                    pool.submit(() -> decide(start, () -> linkService.accept(ADMIN, link.id()))),
                    pool.submit(() -> decide(start, () -> linkService.reject(ADMIN, link.id()))));
                start.countDown();
                List<String> outcomes = new ArrayList<>();
                for (Future<String> result : results) {
                    outcomes.add(result.get(20, TimeUnit.SECONDS));
                }

                assertThat(outcomes).as("라운드 %s", round).containsExactlyInAnyOrder("ok", "conflict");
                MemoryLinkStatus stored = links.findById(link.id()).orElseThrow().status();
                assertThat(linkService.isSuperseded(old.id())).isEqualTo(stored == MemoryLinkStatus.ACTIVE);
            } finally {
                pool.shutdownNow();
            }
        }
    }


    private static String decide(CountDownLatch start, ThrowingCallable action) throws Exception {
        start.await();
        try {
            action.call();
            return "ok";
        } catch (ApiException ex) {
            return ex.code() == ErrorCode.MEMORY_LINK_STATE_CONFLICT ? "conflict" : ex.code().name();
        } catch (Throwable ex) {
            return ex.getClass().getSimpleName();
        }
    }


    private static CurrentUser user(Long id, Long groupId, UserRole role) {
        return new CurrentUser(id, "user" + id + "@example.com", "user" + id, groupId, role);
    }


    private static void assertNotFound(ThrowingCallable action) {
        assertThatThrownBy(action)
            .isInstanceOfSatisfying(
                ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.MEMORY_NOT_FOUND));
    }


    private static void assertConflict(ThrowingCallable action) {
        assertThatThrownBy(action)
            .isInstanceOfSatisfying(
                ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.MEMORY_LINK_STATE_CONFLICT));
    }
}
