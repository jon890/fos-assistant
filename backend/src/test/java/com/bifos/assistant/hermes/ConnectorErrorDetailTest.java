package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.hermes.dto.ConnectorErrorDetail;
import com.bifos.assistant.hermes.dto.ConnectorRecovery;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ConnectorErrorDetailTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode answer(String details) {
        return JSON.readTree(
                "{\"ok\":false,\"error\":\"invalid_input\",\"code\":\"DEMO_STALE\",\"details\":" + details + "}");
    }

    @Test
    @DisplayName("세부 값은 절댓값 10억 이하의 정수와 boolean 만 남기고 글, 실수, 배열, 객체, null 은 버린다")
    void detailsKeepOnlyBoundedIntegersAndBooleans() {
        ConnectorErrorDetail detail = ConnectorErrorDetail.fromAnswer(
                        answer("{\"count\":17,\"ok\":true,\"text\":\"17\",\"ratio\":0.5}"))
                .orElseThrow();
        assertThat(detail.details()).isEqualTo(Map.of("count", 17L, "ok", true));

        assertThat(ConnectorErrorDetail.fromAnswer(answer("{\"a\":1000000000,\"b\":-1000000000,\"c\":1000000001}"))
                        .orElseThrow()
                        .details())
                .isEqualTo(Map.of("a", 1_000_000_000L, "b", -1_000_000_000L));
        assertThat(ConnectorErrorDetail.fromAnswer(
                                answer("{\"a\":17.0,\"b\":1e3,\"c\":-9223372036854775808,\"d\":92233720368547758070}"))
                        .orElseThrow()
                        .details())
                .as("실수, 지수 표기, long 의 가장 작은 값, long 을 넘는 정수")
                .isEmpty();
        assertThat(ConnectorErrorDetail.fromAnswer(answer("{\"a\":{\"n\":1},\"b\":null,\"c\":[1],\"Upper\":1}"))
                        .orElseThrow()
                        .details())
                .isEmpty();
    }

    @Test
    @DisplayName("직접 만드는 오류 계약도 상한 안의 Long 과 Boolean 세부만 받는다")
    void constructorRejectsOtherDetailValues() {
        assertThatThrownBy(() -> new ConnectorErrorDetail("DEMO_STALE", Map.of("count", 17), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConnectorErrorDetail("DEMO_STALE", Map.of("count", Long.MIN_VALUE), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new ConnectorErrorDetail("DEMO_STALE", null, null).details()).isEmpty();
    }

    @Test
    @DisplayName("세부 칸은 넷까지 받고 다섯이면 모두 버린다")
    void detailsAreLimitedToFour() {
        assertThat(ConnectorErrorDetail.fromAnswer(answer("{\"a\":1,\"b\":2,\"c\":3,\"d\":false}"))
                        .orElseThrow()
                        .details())
                .hasSize(4);
        assertThat(ConnectorErrorDetail.fromAnswer(answer("{\"a\":1,\"b\":2,\"c\":3,\"d\":4,\"e\":5}"))
                        .orElseThrow()
                        .details())
                .isEmpty();
    }

    @Test
    @DisplayName("저장 글은 kind 를 붙여 쓰고, kind 가 없거나 다른 글은 오류 계약으로 읽지 않는다")
    void storedTextNeedsTheKind() {
        ConnectorErrorDetail detail = new ConnectorErrorDetail(
                "GMAIL_TARGET_COUNT_CHANGED", Map.of("actual_count", 17L), ConnectorRecovery.RECHECK);

        assertThat(ConnectorErrorDetail.fromStored(detail.toStored())).contains(detail);
        assertThat(ConnectorErrorDetail.fromStored("{\"code\":\"GMAIL_FORBIDDEN\"}"))
                .isEmpty();
        assertThat(ConnectorErrorDetail.fromStored("{\"kind\":\"result\",\"code\":\"GMAIL_FORBIDDEN\"}"))
                .isEmpty();
        assertThat(ConnectorErrorDetail.fromStored("not json")).isEmpty();
        assertThat(ConnectorErrorDetail.fromStored(null)).isEmpty();
    }
}
