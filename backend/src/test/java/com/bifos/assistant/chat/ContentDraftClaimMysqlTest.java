package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.testsupport.MysqlTestDatabase;
import com.bifos.assistant.shared.error.ApiException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 저장 요청·승인·claim 과 실행 취소·사용자 끄기의 행 잠금 경합을 실제 Flyway MySQL 과 ddl-auto validate 에서 실행한다. */
@Tag("mysql")
class ContentDraftClaimMysqlTest extends ContentDraftJobsTest {
    @Test
    @DisplayName("실제 MySQL에서 두 start 허가는 경합해도 claim 하나만 커밋한다")
    void commitsOneStartClaimUnderConcurrentAuthorization() throws Exception {
        var snapshotId = snapshots.preview(owner, conversation.id(), draftId, 1, binding.id(), origin.id()).snapshotId();
        var request = saves.requestSave(owner, origin.id(), snapshotId);
        contentApproval.approve(owner, request.actionId(), null);
        String ticket = starter.started().getLast().ticket();
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = List.of(1, 2).stream().map(index -> pool.submit(() -> {
                assertThat(gate.await(5, TimeUnit.SECONDS)).isTrue();
                try {
                    authorizer.authorize(ticket, "start", null);
                    return "ALLOWED";
                } catch (ApiException ex) {
                    return ex.code().name();
                }
            })).toList();
            gate.countDown();
            assertThat(List.of(pending.get(0).get(10, TimeUnit.SECONDS), pending.get(1).get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("ALLOWED", "FORBIDDEN");
        }
        assertThat(jobRows.findAll().getFirst().startClaimedAt()).isNotNull();
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
