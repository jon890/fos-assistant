package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.bifos.assistant.memory.application.MemoryLinkService;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.application.EmbeddingText;
import com.bifos.assistant.memory.config.MemoryEmbeddingProperties;
import com.bifos.assistant.memory.application.MemoryVectorCache;
import com.bifos.assistant.memory.application.MemoryLinkDiscovery;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.entity.MemoryEmbedding;
import com.bifos.assistant.memory.domain.entity.MemoryLink;
import com.bifos.assistant.memory.domain.type.MemoryLinkOrigin;
import com.bifos.assistant.memory.domain.type.MemoryLinkStatus;
import com.bifos.assistant.memory.domain.type.MemoryLinkType;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.infra.MemoryEmbeddingRepository;
import com.bifos.assistant.memory.infra.MemoryLinkRepository;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 새로 임베딩한 항목에서 대체 제안과 자동 RELATED 가 같은 주인 안에서만 생기는지 확인한다(ADR-20261015 / memory-link). */
@BackendIntegrationTest
class MemoryLinkDiscoveryTest {

    private static final CurrentUser ADMIN = new CurrentUser(1L, "owner@example.com", "주인", 10L, UserRole.ADMIN);
    private static final CurrentUser OTHER = new CurrentUser(2L, "other@example.com", "가족", 10L, UserRole.MEMBER);
    private static final float[] PARKING = {1f, 0f, 0f};
    private static final float[] TRIP = {0f, 1f, 0f};
    private static final float[] HOTEL = {0f, 0.95f, 0.3f};

    @Autowired
    MemoryLinkDiscovery discovery;

    @Autowired
    MemoryService memories;

    @Autowired
    MemoryLinkService linkService;

    @Autowired
    MemoryRepository repository;

    @Autowired
    MemoryLinkRepository links;

    @Autowired
    MemoryEmbeddingRepository embeddings;

    @Autowired
    MemoryEmbeddingProperties embeddingProperties;

    @Autowired
    MemoryVectorCache vectorCache;


    @BeforeEach
    void setUp() {
        vectorCache.clear();
        links.deleteAll();
        embeddings.deleteAll();
        repository.deleteAll();
    }


    @AfterEach
    void tearDown() {
        links.deleteAll();
        embeddings.deleteAll();
    }


    @Test
    @DisplayName("같은 주인의 비슷하고 값이 다른 항목은 코드가 만든 대체 제안이 되고 다른 주인과 그룹 항목은 견주지 않는다")
    void proposesSupersedeWithinOwner() {
        Memory old = embedded(ADMIN, MemoryScope.USER, "주차 자리", "차는 3층에 세운다", PARKING);
        Memory fresh = embedded(ADMIN, MemoryScope.USER, "주차 자리", "차는 5층에 세운다", PARKING);
        embedded(OTHER, MemoryScope.USER, "주차 자리", "차는 7층에 세운다", PARKING);
        embedded(ADMIN, MemoryScope.GROUP, "주차 자리", "차는 9층에 세운다", PARKING);

        discovery.discover(List.of(fresh.id()));

        assertThat(links.findAll())
            .extracting(MemoryLink::fromMemoryId, MemoryLink::toMemoryId, MemoryLink::type, MemoryLink::origin, MemoryLink::status)
            .containsExactly(tuple(fresh.id(), old.id(), MemoryLinkType.SUPERSEDES, MemoryLinkOrigin.AUTO, MemoryLinkStatus.PROPOSED));
        assertThat(links.findAll().getFirst().score()).isGreaterThanOrEqualTo(0.85);
        assertThat(linkService.isSuperseded(old.id())).as("제안만으로는 대체되지 않는다").isFalse();
    }


    @Test
    @DisplayName("사람이 거절한 대체 제안은 다시 찾아도 만들지 않고 같은 제안을 두 번 만들지 않는다")
    void doesNotReproposeRejectedPair() {
        embedded(ADMIN, MemoryScope.USER, "주차 자리", "차는 3층에 세운다", PARKING);
        Memory fresh = embedded(ADMIN, MemoryScope.USER, "주차 자리", "차는 5층에 세운다", PARKING);
        discovery.discover(List.of(fresh.id()));
        discovery.discover(List.of(fresh.id()));
        assertThat(links.count()).isEqualTo(1);

        linkService.reject(ADMIN, links.findAll().getFirst().id());
        discovery.discover(List.of(fresh.id()));

        assertThat(links.findAll()).extracting(MemoryLink::status).containsExactly(MemoryLinkStatus.REVOKED);
    }


    @Test
    @DisplayName("항목당 대체 제안 상한은 만든 제안 수로 세어 거절한 쌍이 상한을 채우지 않는다")
    void countsSupersedeLimitByCreatedProposals() {
        Memory first = embedded(ADMIN, MemoryScope.USER, "학원", "월요일 4시", PARKING);
        Memory second = embedded(ADMIN, MemoryScope.USER, "학원", "화요일 4시", PARKING);
        Memory third = embedded(ADMIN, MemoryScope.USER, "학원", "수요일 4시", PARKING);
        Memory fresh = embedded(ADMIN, MemoryScope.USER, "학원", "목요일 4시", PARKING);
        for (Memory old : List.of(first, second)) {
            MemoryLink rejected = linkService.proposeSupersede(ADMIN, fresh.id(), old.id(), MemoryLinkOrigin.HUMAN, null);
            linkService.reject(ADMIN, rejected.id());
        }

        discovery.discover(List.of(fresh.id()));

        assertThat(links.findAll())
            .filteredOn(link -> link.status() == MemoryLinkStatus.PROPOSED)
            .extracting(MemoryLink::fromMemoryId, MemoryLink::toMemoryId)
            .containsExactly(tuple(fresh.id(), third.id()));
    }


    @Test
    @DisplayName("사람이 관련으로 이은 쌍은 값이 달라도 대체 제안을 만들지 않는다")
    void doesNotProposeSupersedeForHumanRelatedPair() {
        Memory first = embedded(ADMIN, MemoryScope.USER, "제주 여행 1일차", "1일차는 성산에 간다", PARKING);
        Memory second = embedded(ADMIN, MemoryScope.USER, "제주 여행 2일차", "2일차는 애월에 간다", PARKING);
        links.save(MemoryLink.related(first.id(), second.id(), "같은 여행", MemoryLinkOrigin.HUMAN, null, null));

        discovery.discover(List.of(second.id()));

        assertThat(links.findAll()).extracting(MemoryLink::type).containsExactly(MemoryLinkType.RELATED);
    }


    @Test
    @DisplayName("서로 가장 가까운 같은 범위의 항목은 바로 효력 있는 RELATED 로 잇고 끊은 쌍은 다시 잇지 않는다")
    void relatesMutualNeighbors() {
        Memory trip = embedded(ADMIN, MemoryScope.USER, "여름 여행", "제주에 간다", TRIP);
        Memory hotel = embedded(ADMIN, MemoryScope.USER, "숙소", "제주 숙소를 잡았다", HOTEL);
        embedded(ADMIN, MemoryScope.USER, "주차 자리", "차는 3층에 세운다", PARKING);

        discovery.discover(List.of(hotel.id()));

        assertThat(links.findAll())
            .extracting(MemoryLink::fromMemoryId, MemoryLink::toMemoryId, MemoryLink::type, MemoryLink::origin, MemoryLink::status)
            .containsExactly(tuple(trip.id(), hotel.id(), MemoryLinkType.RELATED, MemoryLinkOrigin.AUTO, MemoryLinkStatus.ACTIVE));

        linkService.revoke(ADMIN, links.findAll().getFirst().id());
        discovery.discover(List.of(hotel.id()));

        assertThat(links.findAll()).extracting(MemoryLink::status).containsExactly(MemoryLinkStatus.REVOKED);
    }


    @Test
    @DisplayName("한 주기에 같은 주인의 여러 항목을 넘겨도 항목마다 연결을 찾고 주인이 다른 항목은 따로 견준다")
    void discoversSeveralItemsGroupedByOwner() {
        Memory oldParking = embedded(ADMIN, MemoryScope.USER, "주차 자리", "차는 3층에 세운다", PARKING);
        Memory newParking = embedded(ADMIN, MemoryScope.USER, "주차 자리", "차는 5층에 세운다", PARKING);
        Memory trip = embedded(ADMIN, MemoryScope.USER, "여름 여행", "제주에 간다", TRIP);
        Memory hotel = embedded(ADMIN, MemoryScope.USER, "숙소", "제주 숙소를 잡았다", HOTEL);
        Memory otherOld = embedded(OTHER, MemoryScope.USER, "주차 자리", "차는 7층에 세운다", PARKING);
        Memory otherNew = embedded(OTHER, MemoryScope.USER, "주차 자리", "차는 8층에 세운다", PARKING);

        discovery.discover(List.of(newParking.id(), hotel.id(), otherNew.id()));

        assertThat(links.findAll())
            .extracting(MemoryLink::fromMemoryId, MemoryLink::toMemoryId, MemoryLink::type)
            .containsExactlyInAnyOrder(
                tuple(newParking.id(), oldParking.id(), MemoryLinkType.SUPERSEDES),
                tuple(trip.id(), hotel.id(), MemoryLinkType.RELATED),
                tuple(otherNew.id(), otherOld.id(), MemoryLinkType.SUPERSEDES));
    }


    @Test
    @DisplayName("다른 모델의 벡터와 본문을 고친 뒤 다시 채우기 전의 벡터는 견주지 않는다")
    void ignoresVectorsOfOtherModelOrOldText() {
        embedded(ADMIN, MemoryScope.USER, "주차 자리", "차는 3층에 세운다", PARKING, "old-model");
        Memory edited = embedded(ADMIN, MemoryScope.USER, "주차 위치", "차는 4층에 세운다", PARKING);
        memories.update(ADMIN, edited.id(), "차는 지하 1층에 세운다", false);
        Memory fresh = embedded(ADMIN, MemoryScope.USER, "주차 자리", "차는 5층에 세운다", PARKING);

        discovery.discover(List.of(fresh.id()));

        assertThat(links.count()).isZero();
    }


    @Test
    @DisplayName("자동 RELATED 는 항목마다 상한을 넘지 않는다")
    void keepsRelatedLimitPerItem() {
        Memory hub = memories.create(ADMIN, MemoryScope.USER, "중심", "본문", false);
        Memory first = memories.create(ADMIN, MemoryScope.USER, "가지 1", "본문", false);
        Memory second = memories.create(ADMIN, MemoryScope.USER, "가지 2", "본문", false);

        assertThat(linkService.relateAuto(hub.id(), first.id(), 0.9, 1)).isPresent();
        assertThat(linkService.relateAuto(hub.id(), second.id(), 0.9, 1)).isEmpty();
    }


    private Memory embedded(CurrentUser owner, MemoryScope scope, String title, String content, float[] vector) {
        return embedded(owner, scope, title, content, vector, embeddingProperties.model());
    }


    /** 지금 글의 지문과 주어진 모델 이름으로 벡터 줄을 넣는다. */
    private Memory embedded(CurrentUser owner, MemoryScope scope, String title, String content, float[] vector, String model) {
        Memory memory = memories.create(owner, scope, title, content, false);
        String text = EmbeddingText.of(title, content, embeddingProperties.maxTextChars());
        embeddings.save(MemoryEmbedding.of(memory.id(), model, EmbeddingText.fingerprint(model, text), vector));
        return memory;
    }
}
