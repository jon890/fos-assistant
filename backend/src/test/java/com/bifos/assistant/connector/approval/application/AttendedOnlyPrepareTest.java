package com.bifos.assistant.connector.approval.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

import com.bifos.assistant.connector.action.domain.ConnectorActionKey;
import com.bifos.assistant.connector.action.domain.type.ActionDecision;
import com.bifos.assistant.connector.action.domain.type.ActionDenyReason;
import com.bifos.assistant.connector.action.domain.type.ActionStatus;
import com.bifos.assistant.connector.connection.application.ConnectorCatalogCache;
import com.bifos.assistant.connector.connection.application.dto.ConnectorPolicyAnswer;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.hermes.application.dto.ConnectorTool;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 사람이 연 대화에서만 부르라고 선언한 준비 도구를 사람이 없는 실행이 부르면 금융 준비로 넘기지 않고 일반 판정이 거절한다
 * (spec REQ13, ADR-20261113 / purchase-checkout).
 *
 * <p>대역 토스의 준비 도구는 {@code attended_only} 가 아니다. 선언만 바꾼 카탈로그로 판정 순서를 본다.
 */
class AttendedOnlyPrepareTest extends FinancialConnectorTestBase {
    static final String PREPARE_TOOL = "mcp__tossinvest__prepare_trade";
    @Autowired
    ConnectorPolicyService publicPolicy;
    @Autowired
    ConnectorCatalogCache cache;


    @AfterEach
    void forgetCatalog() {
        resetCache();
    }


    @Test
    @DisplayName("사람이 없는 실행이 attended_only 준비 도구를 부르면 ATTENDED_ONLY 거절 줄만 남고 준비 HTTP 를 보내지 않는다")
    void unattendedTreeIsDeniedBeforeFinancialPreparation() {
        attendedOnlyPrepareTool();
        markUnattended();
        String toolCallId = UUID.randomUUID().toString();

        var answer = prepare(toolCallId);

        assertThat(answer.allowed()).isFalse();
        assertThat(answer.actionId()).as("승인 카드가 없다").isNull();
        var recorded = actions.findByDedupeKey(ConnectorActionKey.of(profile, session, session, toolCallId).value()).orElseThrow();
        assertThat(recorded.decision()).isEqualTo(ActionDecision.DENIED);
        assertThat(recorded.denyReason()).isEqualTo(ActionDenyReason.ATTENDED_ONLY);
        assertThat(counters().path("prepare").intValue()).isZero();
    }


    @Test
    @DisplayName("사람이 연 대화의 attended_only 준비 도구 호출은 지금처럼 금융 준비로 넘어가 승인 카드를 만든다")
    void attendedConversationStillPrepares() {
        attendedOnlyPrepareTool();

        var answer = prepare(UUID.randomUUID().toString());

        assertThat(answer.actionId()).isNotNull();
        assertThat(actions.findByPublicId(answer.actionId()).orElseThrow().status()).isEqualTo(ActionStatus.PENDING);
        assertThat(counters().path("prepare").intValue()).isEqualTo(1);
    }


    @Test
    @DisplayName("attended_only 가 아닌 준비 도구는 사람이 없는 실행에서도 지금처럼 금융 준비로 넘어간다")
    void undeclaredPrepareToolKeepsCurrentOrder() {
        markUnattended();

        var answer = prepare(UUID.randomUUID().toString());

        assertThat(answer.actionId()).isNotNull();
        assertThat(actions.findByPublicId(answer.actionId()).orElseThrow().status()).isEqualTo(ActionStatus.PENDING);
        assertThat(counters().path("prepare").intValue()).isEqualTo(1);
    }


    private ConnectorPolicyAnswer prepare(String toolCallId) {
        return publicPolicy.decide(profile, session, session, toolCallId, PREPARE_TOOL, "prepare_trade",
            "{\"v\":1,\"tool\":\"create_order\",\"args\":" + ARGS + "}");
    }


    /** 이 실행을 예약 작업 turn 처럼 사람이 없는 실행으로 표시한다. turn 을 여는 쪽이 적는 칸이다. */
    private void markUnattended() {
        assertThat(jdbc.update("update agent_execution set unattended = true where hermes_session_id = ?", session)).isEqualTo(1);
    }


    /** 대역 플러그인의 카탈로그를 그대로 읽되 준비 도구에만 {@code attended_only} 를 단다. 나머지 선언과 보호 해시는 같다. */
    private void attendedOnlyPrepareTool() {
        doAnswer(invocation -> client.readCatalog().stream().map(AttendedOnlyPrepareTest::markPrepare).toList()).when(hermes).readCatalog();
        resetCache();
    }


    private static ConnectorManifest markPrepare(ConnectorManifest manifest) {
        var tools = manifest.tools().stream().map(tool -> !"prepare_trade".equals(tool.name()) ? tool
            : new ConnectorTool(tool.name(), tool.risk(), tool.approval(), tool.title(), tool.grant(), tool.identifiers(),
                tool.description(), tool.argLabels(), tool.summary(), tool.outbound(), tool.conversationGrant(), true))
            .toList();
        return new ConnectorManifest(manifest.id(), manifest.title(), manifest.description(), manifest.fields(), manifest.verifyTool(),
            manifest.mcpServer(), manifest.schema(), tools, manifest.skills(), manifest.appearance(), manifest.singleBinding(),
            manifest.ownerBrowser(), manifest.ownerBrowserLoginUrl(), manifest.executionGuard(), manifest.executionGuardManifestSha256(),
            manifest.snapshot(), manifest.unknownErrors(), manifest.defaultToolPolicy(), manifest.eventWatch());
    }


    /** 정책 판정의 카탈로그 기억을 비워 다음 판정이 바꾼 카탈로그를 읽게 한다. */
    private void resetCache() {
        try {
            var field = cache.getClass().getDeclaredField("readAt");
            field.setAccessible(true);
            field.set(cache, null);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
