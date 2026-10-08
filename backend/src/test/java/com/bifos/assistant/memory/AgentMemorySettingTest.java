package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentMemoryCollection;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentMemoryCollectionRepository;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.memory.application.AgentMemorySettingService;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.domain.MemoryCollection;
import com.bifos.assistant.memory.domain.MemoryPlacement;
import com.bifos.assistant.memory.domain.StoredContent;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.memory.presentation.AgentMemorySettingController;
import com.bifos.assistant.memory.presentation.MemoryDtos.AgentMemoryChangeView;
import com.bifos.assistant.memory.presentation.MemoryDtos.AgentMemoryCollectionView;
import com.bifos.assistant.memory.presentation.MemoryDtos.AgentMemoryGrantBody;
import com.bifos.assistant.memory.presentation.MemoryDtos.AgentMemorySettingView;
import com.bifos.assistant.memory.presentation.MemoryDtos.ReplaceAgentMemoryRequest;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 관리자가 에이전트의 Memory collection 설정을 읽고 바꾸는 API 를 확인한다(ADR-20261008 / agent-memory-grants-admin).
 *
 * <p>통합 시험은 H2 하나를 함께 쓴다. 다른 시험이 남긴 항목이 수를 흔들지 않게 시험마다 다른 그룹 번호를 쓰고, 넣은 줄은
 * 끝에 지운다.
 */
@BackendIntegrationTest
class AgentMemorySettingTest {

    private static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");

    /** 다른 시험이 쓰지 않는 그룹 번호에서 시작한다. */
    private static final AtomicLong NEXT_GROUP = new AtomicLong(7_295_000_000L);

    @Autowired
    AgentMemorySettingService service;

    @Autowired
    MemoryRepository memories;

    @Autowired
    AgentRepository agents;

    @Autowired
    AgentMemoryCollectionRepository grants;

    @Autowired
    AppUserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final List<Long> memoryIds = new ArrayList<>();
    private final List<Long> agentIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    private AgentMemorySettingController controller;
    private Long groupId;
    private AppUser admin;
    private AppUser owner;
    private AppUser other;

    @BeforeEach
    void setUp() {
        controller = new AgentMemorySettingController(service, currentUser);
        groupId = NEXT_GROUP.incrementAndGet();
        admin = user("관리자A", UserRole.ADMIN);
        owner = user("사용자A", UserRole.MEMBER);
        other = user("사용자B", UserRole.MEMBER);
        as(admin);
    }

    @AfterEach
    void tearDown() {
        memories.deleteAllById(memoryIds);
        for (Long agentId : agentIds) {
            jdbc.update("DELETE FROM agent_memory_collection_change WHERE agent_id = ?", agentId);
            jdbc.update("DELETE FROM agent_memory_collection WHERE agent_id = ?", agentId);
            jdbc.update("DELETE FROM agent WHERE id = ?", agentId);
        }
        jdbc.update("DELETE FROM memory_collection WHERE group_id = ?", groupId);
        users.deleteAllById(userIds);
    }

    @Test
    @DisplayName("주인의 항목과 그룹 항목을 collection 마다 세고 받지 않는 collection 도 낸다")
    void countsOwnerAndGroupItemsPerCollectionIncludingUngrantedOnes() {
        Agent agent = agent(AgentVisibility.PRIVATE, owner.id());
        save(Memory.document(
                owner.id(), "career", key(), "이력", StoredContent.plain("본문"), MemorySensitivity.NORMAL, NOW));
        save(Memory.document(
                owner.id(), "career", key(), "연봉", StoredContent.plain("본문"), MemorySensitivity.SENSITIVE, NOW));
        save(Memory.accepted(
                MemoryScope.USER,
                owner.id(),
                null,
                "보관한 항목",
                StoredContent.plain("본문"),
                new MemoryPlacement("career", MemoryRetrieval.ARCHIVE, MemorySensitivity.NORMAL),
                owner.id(),
                NOW));
        save(Memory.proposed(
                owner.id(),
                "제안만 된 항목",
                StoredContent.plain("본문"),
                new MemoryPlacement("career", MemoryRetrieval.SEARCH, MemorySensitivity.NORMAL),
                null,
                null,
                NOW));
        save(Memory.document(
                other.id(), "career", key(), "남의 이력", StoredContent.plain("본문"), MemorySensitivity.NORMAL, NOW));
        save(groupItem("core"));

        AgentMemorySettingView view = controller.get(agent.code());

        assertThat(view.countedFor()).isEqualTo("OWNER");
        assertThat(view.ownerName()).isEqualTo("사용자A");
        assertThat(view.collections())
                .extracting(AgentMemoryCollectionView::key)
                .as("그룹의 collection 목록 순서로 받지 않는 collection 까지 낸다")
                .containsExactlyElementsOf(MemoryCollection.DEFAULT_KEYS);
        assertThat(collection(view, "career"))
                .extracting(
                        AgentMemoryCollectionView::displayName,
                        AgentMemoryCollectionView::listed,
                        AgentMemoryCollectionView::granted,
                        AgentMemoryCollectionView::entryCount,
                        AgentMemoryCollectionView::sensitiveEntryCount)
                .as("ARCHIVE, PROPOSED, 다른 사용자의 항목은 세지 않는다")
                .containsExactly("커리어", true, false, 2L, 1L);
        assertThat(collection(view, "core"))
                .extracting(
                        AgentMemoryCollectionView::granted,
                        AgentMemoryCollectionView::allowSensitive,
                        AgentMemoryCollectionView::entryCount)
                .containsExactly(true, false, 1L);
        assertThat(collection(view, "health").entryCount()).isZero();
        assertThat(view.changes()).isEmpty();
    }

    @Test
    @DisplayName("바꾸면 응답이 바뀐 값과 기록을 낸다")
    void returnsChangedValuesAndRecordsAfterReplacing() {
        Agent agent = agent(AgentVisibility.PRIVATE, owner.id());

        AgentMemorySettingView view =
                controller.replace(agent.code(), request(new AgentMemoryGrantBody("career", true)));

        assertThat(collection(view, "career"))
                .extracting(AgentMemoryCollectionView::granted, AgentMemoryCollectionView::allowSensitive)
                .containsExactly(true, true);
        assertThat(collection(view, "core").granted()).isFalse();
        assertThat(view.changes())
                .extracting(
                        AgentMemoryChangeView::collection,
                        AgentMemoryChangeView::changeType,
                        AgentMemoryChangeView::allowSensitive,
                        AgentMemoryChangeView::changedByName)
                .containsExactlyInAnyOrder(
                        tuple("career", "GRANTED", true, "관리자A"), tuple("core", "REVOKED", false, "관리자A"));
        assertThat(view.changes().get(0).changedByName()).isEqualTo("관리자A");
        assertThat(grants.findByIdAgentId(agent.id()))
                .extracting(AgentMemoryCollection::collection, AgentMemoryCollection::allowSensitive)
                .containsExactly(tuple("career", true));

        AgentMemorySettingView emptied = controller.replace(agent.code(), request());
        assertThat(emptied.collections()).noneMatch(AgentMemoryCollectionView::granted);
        assertThat(grants.findByIdAgentId(agent.id())).isEmpty();
    }

    @Test
    @DisplayName("목록에 없는 받는 collection 은 key 이름으로 뒤에 붙이고 그대로 둘 수 있다")
    void appendsGrantedCollectionsOutsideTheListAndKeepsThem() {
        Agent agent = agent(AgentVisibility.PRIVATE, owner.id());
        grants.save(AgentMemoryCollection.of(agent.id(), "legacy-notes", false, NOW));

        AgentMemorySettingView view = controller.replace(
                agent.code(),
                request(new AgentMemoryGrantBody("core", false), new AgentMemoryGrantBody("legacy-notes", true)));

        assertThat(view.collections().get(view.collections().size() - 1))
                .extracting(
                        AgentMemoryCollectionView::key,
                        AgentMemoryCollectionView::displayName,
                        AgentMemoryCollectionView::listed,
                        AgentMemoryCollectionView::granted,
                        AgentMemoryCollectionView::allowSensitive)
                .containsExactly("legacy-notes", "legacy-notes", false, true, true);
    }

    @Test
    @DisplayName("그룹 목록에 없는 collection 과 겹치는 collection 과 64개 넘는 목록은 거절한다")
    void rejectsUnknownDuplicateAndTooManyCollections() {
        Agent agent = agent(AgentVisibility.PRIVATE, owner.id());

        assertCode(
                () -> controller.replace(agent.code(), request(new AgentMemoryGrantBody("no-such", false))),
                ErrorCode.VALIDATION_FAILED);
        assertCode(
                () -> controller.replace(
                        agent.code(),
                        request(new AgentMemoryGrantBody("career", false), new AgentMemoryGrantBody("career", true))),
                ErrorCode.VALIDATION_FAILED);
        AgentMemoryGrantBody[] tooMany = IntStream.range(0, 65)
                .mapToObj(i -> new AgentMemoryGrantBody("c" + i, false))
                .toArray(AgentMemoryGrantBody[]::new);
        assertCode(() -> controller.replace(agent.code(), request(tooMany)), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> controller.get("no-such-agent-" + key()), ErrorCode.AGENT_NOT_FOUND);

        assertThat(grants.findByIdAgentId(agent.id()))
                .extracting(AgentMemoryCollection::collection)
                .as("거절한 요청은 받는 줄을 바꾸지 않는다")
                .containsExactly("core");
        assertThat(controller.get(agent.code()).changes()).isEmpty();
    }

    @Test
    @DisplayName("ADMIN 이 아니면 읽지도 바꾸지도 못한다")
    void forbidsNonAdmins() {
        Agent agent = agent(AgentVisibility.PRIVATE, owner.id());
        as(owner);

        assertCode(() -> controller.get(agent.code()), ErrorCode.FORBIDDEN);
        assertCode(
                () -> controller.replace(agent.code(), request(new AgentMemoryGrantBody("career", true))),
                ErrorCode.FORBIDDEN);
        assertThat(grants.findByIdAgentId(agent.id()))
                .extracting(AgentMemoryCollection::collection)
                .containsExactly("core");
    }

    @Test
    @DisplayName("주인이 없는 그룹 에이전트는 그룹 항목만 센다")
    void countsOnlyGroupItemsForAgentsWithoutOwner() {
        Agent agent = agent(AgentVisibility.GROUP, null);
        save(groupItem("core"));
        save(Memory.document(
                admin.id(), "core", key(), "관리자 개인 문서", StoredContent.plain("본문"), MemorySensitivity.NORMAL, NOW));

        AgentMemorySettingView view = controller.get(agent.code());

        assertThat(view.countedFor()).isEqualTo("GROUP");
        assertThat(view.ownerName()).isNull();
        assertThat(collection(view, "core").entryCount())
                .as("관리자의 USER 항목은 세지 않는다")
                .isEqualTo(1L);
    }

    private void as(AppUser user) {
        CurrentUser current = new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
        if (user.isAdmin()) {
            when(currentUser.requireAdmin()).thenReturn(current);
        } else {
            when(currentUser.requireAdmin())
                    .thenThrow(new ApiException(ErrorCode.FORBIDDEN, "this action is limited to the group admin"));
        }
    }

    private AppUser user(String displayName, UserRole role) {
        AppUser saved =
                users.save(AppUser.of("user-" + UUID.randomUUID() + "@example.com", displayName, groupId, role, NOW));
        userIds.add(saved.id());
        return saved;
    }

    private Agent agent(AgentVisibility visibility, Long ownerUserId) {
        String code = "memory-setting-" + key();
        Agent saved = agents.save(Agent.of(
                code,
                "설정 검사",
                code,
                "http://runtime.test/p/" + code,
                CostMode.API,
                CredentialScope.DEDICATED,
                visibility,
                ownerUserId,
                NOW));
        agentIds.add(saved.id());
        return saved;
    }

    private void save(Memory memory) {
        memoryIds.add(memories.save(memory).id());
    }

    private Memory groupItem(String collection) {
        return Memory.accepted(
                MemoryScope.GROUP,
                null,
                groupId,
                "그룹 항목",
                StoredContent.plain("본문"),
                new MemoryPlacement(collection, MemoryRetrieval.SEARCH, MemorySensitivity.NORMAL),
                admin.id(),
                NOW);
    }

    private static ReplaceAgentMemoryRequest request(AgentMemoryGrantBody... bodies) {
        return new ReplaceAgentMemoryRequest(List.of(bodies));
    }

    private static AgentMemoryCollectionView collection(AgentMemorySettingView view, String key) {
        return view.collections().stream()
                .filter(collection -> collection.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("collection " + key + " is missing from " + view.collections()));
    }

    private static String key() {
        return UUID.randomUUID().toString().substring(0, 12);
    }

    private static void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
