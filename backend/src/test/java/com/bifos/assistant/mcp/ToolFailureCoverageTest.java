package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.shared.util.ToolFailureText;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 모델에게 돌려주는 backend 실패 글이 끝 줄 하나로 끝나는지 모아 본다(docs/features/tool-fallback/spec.md 의 REQ3).
 *
 * <p>판정 글 상수가 다시 끝 줄 없는 {@code String} 으로 들어오면 실패한다. 승인 대기 글({@code [승인 대기]})은 실패가 아니라 그대로 둔다.
 */
class ToolFailureCoverageTest {
    private static final String PENDING_MARKER = "[승인 대기]";
    private static final List<String> CLASSES = List.of(
        "com.bifos.assistant.connector.approval.application.ConnectorPolicyService",
        "com.bifos.assistant.connector.approval.application.ConnectorApprovalReply",
        "com.bifos.assistant.connector.approval.application.ConnectorFinancialIntake",
        "com.bifos.assistant.connector.approval.application.ConnectorFinancialProducer",
        "com.bifos.assistant.mcp.presentation.ToolGuardController",
        "com.bifos.assistant.orchestration.application.EventToolGuard",
        "com.bifos.assistant.mcp.application.McpToolService",
        "com.bifos.assistant.mcp.application.McpConnectorListTool",
        "com.bifos.assistant.mcp.presentation.McpRpc");


    @Test
    @DisplayName("판정 글과 MCP 실패 글 상수는 끝 줄이 있는 값이고 끝 줄 없는 글 상수는 승인 대기 글뿐이다")
    void failureConstantsEndWithStep() throws Exception {
        List<String> failureTexts = new ArrayList<>();
        for (String name : CLASSES) {
            for (Field field : Class.forName(name).getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(null);
                if (value instanceof ToolFailureText text) {
                    failureTexts.add(name + "." + field.getName());
                    assertThat(endsWithStep(text.text())).as("%s.%s", name, field.getName()).isTrue();
                } else if (value instanceof String text && field.getName().endsWith("_MESSAGE")) {
                    assertThat(text).as("끝 줄 없는 판정 글 %s.%s", name, field.getName()).startsWith(PENDING_MARKER);
                }
            }
        }
        assertThat(failureTexts).as("모은 실패 글이 있어야 이 검사가 뜻이 있다").hasSizeGreaterThan(10);
    }


    /** 앞뒤 공백을 걷어 낸 마지막 줄이 다음 방법이나 바로 알림이다. */
    static boolean endsWithStep(String text) {
        String last = text.substring(text.lastIndexOf('\n') + 1).strip();
        return last.startsWith(ToolFailureText.NEXT_PREFIX) || last.equals(ToolFailureText.ASK_USER);
    }
}
