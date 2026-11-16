package com.bifos.assistant.connector.connection.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.connector.connection.application.dto.ConnectorPaymentContext;
import com.bifos.assistant.connector.connection.application.dto.ConnectorPolicyAnswer;
import com.bifos.assistant.connector.connection.presentation.ConnectionDtos.ConnectorPolicyResponse;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 도구 호출 판정 응답의 JSON 모양이다. fos-ctx 는 결제 넘기기 맥락을 정확한 snake_case 키로만 읽는다. */
class ConnectorPolicyResponseTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();


    @Test
    @DisplayName("결제 넘기기 맥락이 있는 허용 답은 payment_context 를 v, handed_off_at_ms, final_amount_krw, expected_total_krw, url 로 낸다")
    void writesPaymentContextInSnakeCase() {
        var context = new ConnectorPaymentContext(Instant.ofEpochMilli(1_763_269_200_000L), 3300, 3400, "https://shop.example/p/1");
        var answer = new ConnectorPolicyAnswer(true, "", null).withPaymentContext(context);

        var written = JSON.readTree(JSON.writeValueAsString(ConnectorPolicyResponse.from(answer)));

        assertThat(written.path("decision").asString()).isEqualTo("allow");
        assertThat(written.path("payment_context")).isEqualTo(JSON.readTree(
            "{\"v\":1,\"handed_off_at_ms\":1763269200000,\"final_amount_krw\":3300,\"expected_total_krw\":3400,"
                + "\"url\":\"https://shop.example/p/1\"}"));
    }


    @Test
    @DisplayName("맥락이 없는 답은 칸을 내지 않고, 막는 답에는 맥락을 싣지 않는다")
    void omitsMissingContextAndNeverAttachesToBlock() {
        var context = new ConnectorPaymentContext(Instant.EPOCH, 1, 1, "https://shop.example/p/1");
        var plain = JSON.readTree(JSON.writeValueAsString(ConnectorPolicyResponse.from(new ConnectorPolicyAnswer(true, "", null))));
        var blocked = new ConnectorPolicyAnswer(false, "막음", null).withPaymentContext(context);

        assertThat(plain.has("payment_context")).isFalse();
        assertThat(blocked.paymentContext()).isNull();
    }
}
