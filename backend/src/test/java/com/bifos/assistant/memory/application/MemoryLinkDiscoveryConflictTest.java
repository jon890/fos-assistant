package com.bifos.assistant.memory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import com.bifos.assistant.memory.application.embedding.EmbeddingText;
import com.bifos.assistant.memory.application.embedding.MemoryEmbeddingProperties;
import com.bifos.assistant.memory.application.relevance.MemoryVectorCache;
import com.bifos.assistant.memory.config.MemoryLinkProperties;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.entity.MemoryEmbedding;
import com.bifos.assistant.memory.domain.entity.MemoryLink;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/** 한 쌍이 유일 제약에 걸려도 같은 항목의 나머지 쌍을 이어 만드는지 확인한다. */
@BackendIntegrationTest
class MemoryLinkDiscoveryConflictTest {

    private static final CurrentUser ADMIN = new CurrentUser(1L, "owner@example.com", "주인", 10L, UserRole.ADMIN);

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

    @Autowired
    MemoryLinkProperties properties;

    @Autowired
    SupersedeCandidates supersedes;

    @Autowired
    RelatedLinker related;

    @Autowired
    PlatformTransactionManager transactionManager;


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
    @DisplayName("대체 제안이 유일 제약에 걸려도 같은 항목의 관련 연결은 이어 만든다")
    void keepsGoingAfterUniqueConflict() {
        embedded("주차 자리", "차는 3층에 세운다", new float[] {1f, 0f, 0f});
        Memory target = embedded("주차 자리", "차는 5층에 세운다", new float[] {1f, 0f, 0f});
        Memory neighbor = embedded("주차 요금", "주차는 동네 공영 주차장이 싸다", new float[] {0.8f, 0.6f, 0f});
        // 트랜잭션 proxy 는 spy 로 감쌀 수 없어 대상 객체를 감싼다. 저장소 메서드는 저마다 트랜잭션을 연다
        MemoryLinkService conflicting = spy(AopTestUtils.<MemoryLinkService>getUltimateTargetObject(linkService));
        doThrow(new DataIntegrityViolationException("같은 쌍이 먼저 들어왔다"))
            .when(conflicting).proposeAutoSupersede(anyLong(), anyLong(), anyDouble());
        MemoryLinkDiscovery discovery = new MemoryLinkDiscovery(
            properties, embeddings, repository, conflicting, supersedes, related, vectorCache, transactionManager);

        discovery.discover(List.of(target.id()));

        assertThat(links.findAll())
            .extracting(MemoryLink::type, MemoryLink::fromMemoryId, MemoryLink::toMemoryId)
            .containsExactly(tuple(
                MemoryLinkType.RELATED, Math.min(target.id(), neighbor.id()), Math.max(target.id(), neighbor.id())));
    }


    private Memory embedded(String title, String content, float[] vector) {
        Memory memory = memories.create(ADMIN, MemoryScope.USER, title, content, false);
        String model = embeddingProperties.model();
        String text = EmbeddingText.of(title, content, embeddingProperties.maxTextChars());
        embeddings.save(MemoryEmbedding.of(memory.id(), model, EmbeddingText.fingerprint(model, text), vector));
        return memory;
    }
}
