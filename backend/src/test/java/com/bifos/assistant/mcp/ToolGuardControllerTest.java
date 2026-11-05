package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.mcp.application.dto.McpPrincipal;
import com.bifos.assistant.mcp.presentation.McpDtos.ToolGuardResponse;
import com.bifos.assistant.mcp.presentation.ToolGuardController;
import com.bifos.assistant.orchestration.application.EventToolGuard;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 도구 판정 경로가 판정 중 예외를 404 로 내지 않고 200 의 block 으로 답하는지 본다(ADR-20261105 / event-watch).
 *
 * <p>fos-ctx 는 404 를 이 경로가 없는 옛 Control Plane 으로 읽고 통과시킨다. 판정이 던지는 {@code *_NOT_FOUND} 가 404 로 나가면 사건
 * 실행의 위험한 도구가 판정 없이 나간다.
 */
class ToolGuardControllerTest {

    private static final String TOKEN_HASH = "2ce07fe9da9032a6ba2d14ea44adf290f530110a4b5d2ed67bb72384342abf8b";

    private final EventToolGuard guard = mock(EventToolGuard.class);
    private final ToolGuardController controller = new ToolGuardController(guard);
    private final McpPrincipal principal = new McpPrincipal(1L, "tool-guard-unit", TOKEN_HASH);


    @Test
    @DisplayName("판정이 NOT_FOUND 를 던져도 404 가 아니라 block 으로 답한다")
    void exceptionDuringDecisionBlocks() {
        when(guard.decide(anyString(), anyString(), anyString()))
            .thenThrow(new ApiException(ErrorCode.EXECUTION_NOT_FOUND, "no such execution"));

        ToolGuardResponse response = controller.decide(principal, body());

        assertThat(response.decision()).isEqualTo("block");
        assertThat(response.message()).isNotBlank();
    }


    @Test
    @DisplayName("판정이 다른 런타임 예외를 던져도 block 으로 답한다")
    void runtimeExceptionBlocks() {
        when(guard.decide(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("boom"));

        assertThat(controller.decide(principal, body()).decision()).isEqualTo("block");
    }


    private static String body() {
        String signed = String.join("\n", "v1-tool-guard", "terminal", "fos-root-1", "fos-session-1", "call_1");
        return "{\"v\":1,\"root_session_id\":\"fos-root-1\",\"session_id\":\"fos-session-1\",\"tool_call_id\":\"call_1\","
            + "\"hermes_tool\":\"terminal\",\"sig\":\"" + hmac(signed) + "\"}";
    }


    private static String hmac(String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(TOKEN_HASH.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
