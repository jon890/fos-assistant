package com.bifos.assistant.chat;

import com.bifos.assistant.chat.contentdraft.application.dto.ContentDraftCreationContext;
import com.bifos.assistant.chat.contentdraft.domain.entity.ContentDraftCreationRequest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.bifos.assistant.testsupport.MysqlTestDatabase;
import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** H2와 같은 실제 서비스 회귀를 Flyway MySQL 스키마에서도 실행한다. */
@Tag("mysql")
class ContentDraftIdempotencyMysqlTest extends ContentDraftIdempotencyTest {
    @Test
    @DisplayName("사용자 잠금을 우회한 사전 조회 두 개는 둘 다 부재를 보고 DB UNIQUE만 중복 저장을 차단한다")
    void provesUniqueBarrierWithSeparateConnections() throws Exception {
        var context = new ContentDraftCreationContext(
            owner.id(), conversation.id(), "profile", 99L, 99L, "root", "root", "call_1");
        var requestId = UUID.randomUUID();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var connections = ConcurrentHashMap.<Long>newKeySet();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = List.of(1, 2).stream().map(index -> pool.submit(() -> {
                var transaction = new TransactionTemplate(manager);
                transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                try {
                    transaction.executeWithoutResult(status -> {
                        connections.add(jdbc.queryForObject("select connection_id()", Long.class));
                        Assertions.assertThat(creationRequests.findByCreationKey(context.creationKey(requestId))).isEmpty();
                        ready.countDown();
                        try {
                            Assertions.assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException ex) {
                            throw new IllegalStateException(ex);
                        }
                        creationRequests.saveAndFlush(new ContentDraftCreationRequest(
                            context.creationKey(requestId), context.creationIdentityKey(requestId), owner.id(), conversation.id(),
                            "profile", 99L, 99L, "root", requestId.toString(), new byte[32], UUID.randomUUID().toString()));
                    });
                    return "accepted";
                } catch (DataIntegrityViolationException ex) {
                    return "duplicate";
                }
            })).toList();
            Assertions.assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            Assertions.assertThat(List.of(pending.get(0).get(10, TimeUnit.SECONDS),
                pending.get(1).get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder("accepted", "duplicate");
        }
        Assertions.assertThat(connections).hasSize(2);
        Assertions.assertThat(creationRequests.count()).isEqualTo(1);
        Assertions.assertThat(draftRows.count()).isZero();
    }


    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        var database = MysqlTestDatabase.create();
        registry.add("spring.datasource.url", database::url);
        registry.add("spring.datasource.username", database::username);
        registry.add("spring.datasource.password", database::password);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }
}
