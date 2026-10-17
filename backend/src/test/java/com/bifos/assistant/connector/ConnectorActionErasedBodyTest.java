package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bifos.assistant.connector.financial.application.ConnectorExecutionSnapshot;
import com.bifos.assistant.connector.financial.application.FinancialDigest;
import com.bifos.assistant.connector.action.application.dto.ConnectorActionView;
import com.bifos.assistant.connector.action.domain.ConnectorAction;
import com.bifos.assistant.connector.action.domain.ConnectorActionBodyOpener;
import com.bifos.assistant.connector.action.domain.ConnectorActionExecution;
import com.bifos.assistant.connector.connection.domain.ConnectorConnection;
import com.bifos.assistant.connector.action.domain.ToolPolicyDecision;
import com.bifos.assistant.connector.action.domain.type.ActionDecision;
import com.bifos.assistant.connector.connection.domain.type.ToolApproval;
import com.bifos.assistant.connector.connection.domain.type.ToolRisk;
import com.bifos.assistant.crypto.domain.TextCipher;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ConnectorActionErasedBodyTest {
    private static final Instant NOW = Instant.parse("2026-10-16T00:00:00Z");


    @Test
    @DisplayName("본문을 지운 줄은 암호문을 열지 않고 삭제 표시를 내며 실행 인자를 주지 않는다")
    void returnsDeletionNoticeWithoutOpeningBodies() {
        ConnectorAction action = erased();
        var opener = mock(ConnectorActionBodyOpener.class);
        action.attachOpener(opener);

        assertThat(action.argsJson()).isEqualTo(ConnectorAction.BODY_ERASED);
        assertThat(action.resultText()).isEqualTo(ConnectorAction.BODY_ERASED);
        assertThat(action.executableArgs()).isEmpty();
        assertThat(action.executableArgs(plain -> action.argsSha256())).isEmpty();
        verifyNoInteractions(opener);
    }


    @Test
    @DisplayName("본문이 지워진 승인 카드도 삭제 표시로 조회할 수 있다")
    void displaysErasedApprovalCard() {
        ConnectorActionView view = ConnectorActionView.from(erased(), Optional.empty(), true, false);

        assertThat(view.argsJson()).isEqualTo(ConnectorAction.BODY_ERASED);
        assertThat(view.resultText()).isEqualTo(ConnectorAction.BODY_ERASED);
        assertThat(view.grantAllowed()).isFalse();
        assertThat(view.argLabels()).isEmpty();
    }


    @Test
    @DisplayName("본문을 지운 금융 snapshot은 복호화와 해시 계산 전에 거절한다")
    void rejectsErasedSnapshotBeforeDecryption() {
        ConnectorAction action = erased();
        var row = mock(ConnectorActionExecution.class);
        var cipher = mock(TextCipher.class);
        var digest = mock(FinancialDigest.class);
        when(row.actionId()).thenReturn(action.id());

        assertThatThrownBy(() -> ConnectorExecutionSnapshot.open(action, row, cipher, digest))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(cipher, digest);
    }


    private ConnectorAction erased() {
        ConnectorAction action = ConnectorAction.decided(mock(ConnectorConnection.class), 1L, mock(AgentExecution.class),
            "mcp__demo__write", "write", new ToolPolicyDecision(ActionDecision.NEEDS_APPROVAL, null, ToolRisk.WRITE, ToolApproval.REQUIRED),
            false, "dedupe", "hash", NOW.minusSeconds(60));
        // 삭제 시각이 있으면 과거에 복호화한 캐시나 잘못 남은 저장 값도 제공하지 않는다.
        action.sealArgs("ciphertext-args", 1L);
        action.sealResult("ciphertext-result", 1L);
        ReflectionTestUtils.setField(action, "plainArgs", "이전 인자");
        ReflectionTestUtils.setField(action, "plainResult", "이전 결과");
        ReflectionTestUtils.setField(action, "bodyErasedAt", NOW);
        ReflectionTestUtils.setField(action, "id", 1L);
        return action;
    }
}
