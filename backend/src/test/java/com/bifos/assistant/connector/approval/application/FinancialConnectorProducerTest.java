package com.bifos.assistant.connector.approval.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.connector.action.domain.type.ActionStatus;
import com.bifos.assistant.connector.approval.application.ConnectorFinancialProducer.Rejected;
import com.bifos.assistant.connector.approval.application.ConnectorFinancialProducer.Stage;
import com.bifos.assistant.crypto.domain.TextCipher;
import com.bifos.assistant.hermes.application.dto.CallResult;
import com.bifos.assistant.hermes.application.dto.ConnectorCallError;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient.ConnectorState;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.testsupport.OverridableLiveProperties;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.node.ObjectNode;

/** 테스트의 호출부만 실제 내부 업무 규칙에 연결한다. 공개 진입점은 {@link FinancialOrderOpeningTest} 가 본다. */
class FinancialConnectorProducerTest extends FinancialConnectorTestBase {
    @Test
    @DisplayName("PENDING 승인과 암호화 snapshot을 같은 트랜잭션으로 저장한다")
    void storesPendingAndEncryptedSnapshotAtomically() {
        var answer = request();
        var action = action(answer);
        var row = contents.findById(action.id()).orElseThrow();
        assertThat(action.status()).isEqualTo(ActionStatus.PENDING);
        assertThat(row.contentKeyId()).isNotNull();
        assertThat(row.executionArgsJson()).doesNotContain("clientOrderId", "ABC");
        assertThat(row.supersedesUnknownActionId()).isNull();
        assertThat(counters().path("prepare").intValue()).isEqualTo(1);
        assertThat(counters().path("financial").intValue()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from notification where user_id = ?", Long.class, user.id())).isEqualTo(1);
    }


    @Test
    @DisplayName("snapshot 저장 실패는 승인과 알림도 롤백한다")
    void snapshotSaveFailureRollsBackPendingAndNotification() {
        long before = actions.count();
        jdbc.execute("alter table connector_action_execution add constraint producer_snapshot_failure check (binding_id <> " + bindingId + ")");
        try {
            assertThatThrownBy(this::request).isInstanceOf(RuntimeException.class);
            assertThat(actions.count()).isEqualTo(before);
            assertThat(jdbc.queryForObject("select count(*) from notification where user_id = ?", Long.class, user.id())).isZero();
            assertThat(counters().path("financial").intValue()).isZero();
        } finally {
            dropCheck("connector_action_execution", "producer_snapshot_failure");
        }
    }


    @Test
    @DisplayName("알림 저장 실패는 snapshot과 승인을 함께 롤백한다")
    void notificationSaveFailureRollsBackSnapshotAndPending() {
        long before = actions.count();
        long snapshots = contents.count();
        jdbc.execute("alter table notification add constraint producer_notification_failure check (user_id <> " + user.id() + ")");
        try {
            assertThatThrownBy(this::request).isInstanceOf(RuntimeException.class);
            assertThat(actions.count()).isEqualTo(before);
            assertThat(contents.count()).isEqualTo(snapshots);
            assertThat(counters().path("financial").intValue()).isZero();
        } finally {
            dropCheck("notification", "producer_notification_failure");
        }
    }


    @Test
    @DisplayName("prepare 실패는 승인과 snapshot을 남기지 않는다")
    void preparationFailureLeavesNoActionOrSnapshot() {
        long count = actions.count();
        long snapshots = contents.count();
        doReturn(CallResult.failure(ConnectorCallError.UNAVAILABLE)).when(hermes).prepare(anyString(), anyString(), anyString(), anyString());
        assertRejectedAt(Stage.PREPARE_HTTP);
        assertThat(actions.count()).isEqualTo(count);
        assertThat(contents.count()).isEqualTo(snapshots);
        assertThat(counters().path("financial").intValue()).isZero();
    }


    @Test
    @DisplayName("새 clientOrderId로도 같은 금융 의도를 중복 생성하지 못한다")
    void rejectsDuplicateIntentWithAnotherGeneratedClientOrderId() {
        var first = request();
        assertThatThrownBy(this::request).isInstanceOf(RuntimeException.class);
        assertThat(action(first).status()).isEqualTo(ActionStatus.PENDING);
        assertThat(counters().path("prepare").intValue()).isEqualTo(2);
        assertThat(counters().path("financial").intValue()).isZero();
    }


    @Test
    @DisplayName("keyed hash key 파일이 없으면 prepare 전에 거절하고 아무것도 저장하지 않는다")
    void missingDigestKeyRejectsBeforePrepare() {
        long count = actions.count();
        OverridableLiveProperties.during(gate, financial(false, owned("7"), "1000000"));
        assertRejectedAt(Stage.GATE);
        assertThat(actions.count()).isEqualTo(count);
        assertThat(counters().path("prepare").intValue()).isZero();
        assertThat(counters().path("financial").intValue()).isZero();
    }


    @Test
    @DisplayName("허용 목록 밖의 계좌는 prepare 전에 거절한다")
    void accountOutsideAllowListRejectsBeforePrepare() {
        long count = actions.count();
        OverridableLiveProperties.during(gate, financial(true, owned("9"), "1000000"));
        assertRejectedAt(Stage.ACCOUNTS);
        assertThat(actions.count()).isEqualTo(count);
        assertThat(counters().path("prepare").intValue()).isZero();
    }


    @Test
    @DisplayName("한 건 금액 상한을 넘는 주문은 승인 줄과 snapshot 을 남기지 않는다")
    void orderOverLimitStoresNothing() {
        long count = actions.count();
        long snapshots = contents.count();
        OverridableLiveProperties.during(gate, financial(true, owned("7"), "1"));
        assertThatThrownBy(this::request).isInstanceOf(ApiException.class);
        assertThat(actions.count()).isEqualTo(count);
        assertThat(contents.count()).isEqualTo(snapshots);
        assertThat(counters().path("financial").intValue()).isZero();
    }


    @Test
    @DisplayName("승인 전에 설정에서 key 파일을 비우면 승인을 거절하고 PENDING 으로 남긴다")
    void approvalAfterDigestKeyUnsetKeepsPending() {
        var requested = request();
        OverridableLiveProperties.during(gate, financial(false, owned("7"), "1000000"));
        assertThatThrownBy(() -> approval.approve(user, requested.actionId())).isInstanceOf(ApiException.class);
        assertThat(action(requested).status()).isEqualTo(ActionStatus.PENDING);
        assertThat(counters().path("financial").intValue()).isZero();
    }


    @Test
    @DisplayName("prepare 뒤 암호화가 꺼지면 평문 snapshot과 승인과 알림을 저장하지 않는다")
    void rejectsDisabledEncryptionAfterPrepareWithoutSaving() {
        Object original = ReflectionTestUtils.getField(producer, "cipher");
        var changing = mock(TextCipher.class);
        when(changing.enabled()).thenReturn(true, false);
        long count = actions.count();
        long snapshots = contents.count();
        ReflectionTestUtils.setField(producer, "cipher", changing);
        try {
            assertThatThrownBy(this::request).isInstanceOf(ApiException.class);
        } finally {
            ReflectionTestUtils.setField(producer, "cipher", original);
        }
        assertThat(actions.count()).isEqualTo(count);
        assertThat(contents.count()).isEqualTo(snapshots);
        assertThat(jdbc.queryForObject("select count(*) from notification where user_id = ?", Long.class, user.id())).isZero();
        assertThat(counters().path("prepare").intValue()).isEqualTo(1);
        assertThat(counters().path("financial").intValue()).isZero();
    }


    @Test
    @DisplayName("prepare 뒤 바뀐 revision은 저장 전에 거부된다")
    void rejectsChangedRevisionAfterPrepareWithoutSaving() {
        doAnswer(invocation -> {
            var prepared = client.prepare(profile, "tossinvest", "mcp__tossinvest__create_order", ARGS);
            tx().executeWithoutResult(status -> bindings.findById(bindingId).orElseThrow().pending(now.plusSeconds(1)));
            return prepared;
        }).when(hermes).prepare(anyString(), anyString(), anyString(), anyString());
        long count = actions.count();
        // 바인딩이 READY 가 아니게 되면 Hermes 의 지원 확인도 verified 가 아니어서 준비 뒤 다시 확인에서 걸린다.
        assertRejectedAt(Stage.GUARD_STATE);
        assertThat(actions.count()).isEqualTo(count);
        assertThat(counters().path("financial").intValue()).isZero();
    }


    @ParameterizedTest
    @DisplayName("권한과 선언이 맞지 않으면 prepare 전에 거부하고 아무것도 저장하지 않는다")
    @ValueSource(strings = {"cipher-disabled", "user-not-allowed", "read-only-check", "agent-disabled", "agent-owner",
        "connection-owner", "undeclared-tool"})
    void rejectsBeforePreparationWhenAuthorityOrDeclarationDiffers(String change) {
        Stage expected = switch (change) {
            case "cipher-disabled" -> Stage.CIPHER;
            case "read-only-check" -> Stage.CHECK;
            case "undeclared-tool" -> Stage.MANIFEST;
            default -> Stage.OWNER;
        };
        long count = actions.count();
        long snapshots = contents.count();
        String toolName = "create_order";
        Object original = ReflectionTestUtils.getField(producer, "cipher");
        Long check = null;
        boolean disallowed = false;
        try {
            switch (change) {
                case "cipher-disabled" -> ReflectionTestUtils.setField(producer, "cipher", mock(TextCipher.class));
                case "user-not-allowed" -> disallowed = jdbc.update("delete from allowed_person where email = ?", user.email()) == 1;
                case "read-only-check" -> check = checkTree(false);
                case "agent-disabled" -> jdbc.update("update agent set enabled = false where id = " + agentSql(), bindingId);
                case "agent-owner" -> jdbc.update("update agent set owner_user_id = ? where id = " + agentSql(), otherUser(), bindingId);
                case "connection-owner" -> jdbc.update("update connector_connection set user_id = ? where id = ?", otherUser(), connectionId);
                default -> toolName = "get_orders";
            }
            String name = toolName;
            assertThatThrownBy(() -> producer.request(profile, session, session, UUID.randomUUID().toString(),
                "mcp__tossinvest__" + name, name, ARGS)).isInstanceOf(ApiException.class)
                    .isInstanceOfSatisfying(Rejected.class, rejected -> assertThat(rejected.stage()).isEqualTo(expected));
        } finally {
            ReflectionTestUtils.setField(producer, "cipher", original);
            // 다른 테스트가 RUNNING 살펴보기 줄을 보지 않도록 지운다.
            if (check != null) {
                checks.deleteById(check);
            }
            // 허용 목록 행을 지운 채 두면 같은 context를 쓰는 살펴보기 테스트가 전체 실행에서 실패한다.
            if (disallowed) {
                people.saveAndFlush(AllowedPerson.of(user.email(), "검사 주인", profile));
            }
        }
        assertThat(actions.count()).isEqualTo(count);
        assertThat(contents.count()).isEqualTo(snapshots);
        assertThat(counters().path("prepare").intValue()).isZero();
        assertThat(counters().path("financial").intValue()).isZero();
    }


    @ParameterizedTest(name = "{0}")
    @DisplayName("Hermes 의 커넥터 상태나 지원 확인이 맞지 않으면 준비 HTTP 전에 그 검사의 단계 코드로 거절한다")
    @ValueSource(strings = {"not-configured", "no-policy-hook", "guard-pending", "guard-revision", "guard-connection", "guard-manifest"})
    void rejectsSupportMismatchWithStage(String change) {
        long count = actions.count();
        Stage expected = switch (change) {
            case "not-configured", "no-policy-hook" -> Stage.CONNECTOR_STATE;
            case "guard-pending" -> Stage.GUARD_STATE;
            case "guard-revision", "guard-connection" -> Stage.GUARD_REVISION;
            default -> Stage.GUARD_MANIFEST;
        };
        stateWith(state -> switch (change) {
            case "not-configured" -> new ConnectorState(state.profile(), state.enabled(), false, state.restartRequired(),
                state.policyHook(), state.mode(), state.executionGuard());
            case "no-policy-hook" -> new ConnectorState(state.profile(), state.enabled(), state.configured(), state.restartRequired(),
                false, state.mode(), state.executionGuard());
            case "guard-pending" -> guard(state, "state", "pending");
            case "guard-revision" -> guard(state, "connectionUpdatedAt", connectionRevision.plusSeconds(1).toString());
            case "guard-connection" -> guard(state, "connectionId", Long.toString(connectionId + 1));
            default -> guard(state, "manifestSha256", "0".repeat(64));
        });
        assertRejectedAt(expected);
        assertThat(actions.count()).isEqualTo(count);
        assertThat(counters().path("prepare").intValue()).isZero();
    }


    @Test
    @DisplayName("준비 결과가 v, executionArgs, summary 셋이 아니면 prepare_shape 로 거절하고 아무것도 저장하지 않는다")
    void rejectsPreparedResultShapeWithStage() {
        long count = actions.count();
        doAnswer(invocation -> {
            var prepared = client.prepare(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2),
                invocation.getArgument(3));
            return CallResult.success(((ObjectNode)prepared.result()).put("extra", true));
        }).when(hermes).prepare(anyString(), anyString(), anyString(), anyString());
        assertRejectedAt(Stage.PREPARE_SHAPE);
        assertThat(actions.count()).isEqualTo(count);
        assertThat(counters().path("financial").intValue()).isZero();
    }


    @Test
    @DisplayName("단계 코드는 소문자 낱말이고 계좌, 메일, 인자 값을 담지 않는다")
    void stageCodesAreFixedWords() {
        for (Stage stage : Stage.values()) {
            assertThat(stage.code()).matches("[a-z_]+");
        }
        assertThat(new Rejected(Stage.ACCOUNTS).getMessage()).isEqualTo(ConnectorFinancialProducer.rejected().getMessage());
    }


    private void assertRejectedAt(Stage stage) {
        assertThatThrownBy(this::request).isInstanceOfSatisfying(Rejected.class,
            rejected -> assertThat(rejected.stage()).isEqualTo(stage));
    }


    /** 실제 plugin 이 답한 커넥터 상태를 바꿔 돌려준다. */
    private void stateWith(UnaryOperator<ConnectorState> change) {
        doAnswer(invocation -> change.apply(client.readConnector(invocation.getArgument(0), invocation.getArgument(1))))
            .when(hermes).readConnector(anyString(), anyString());
    }


    private static ConnectorState guard(ConnectorState state, String field, String value) {
        ObjectNode node = ((ObjectNode)state.executionGuard()).deepCopy().put(field, value);
        return new ConnectorState(state.profile(), state.enabled(), state.configured(), state.restartRequired(), state.policyHook(),
            state.mode(), node);
    }
}
