package com.bifos.assistant.connector.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.connector.domain.ConnectorAction;
import com.bifos.assistant.connector.domain.ConnectorActionExecution;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.connector.domain.type.ToolRisk;
import com.bifos.assistant.connector.infra.ConnectorActionExecutionRepository;
import com.bifos.assistant.connector.infra.ConnectorActionRepository;
import com.bifos.assistant.crypto.domain.TextCipher;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.util.Sha256;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class ConnectorFinancialGateTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String KEY = "src/test/resources/financial/test-digest.key";
    private static final String ROTATED_KEY = "src/test/resources/financial/test-digest-rotated.key";
    private static final String OWNER = "owner@example.test";
    private static final FinancialDigest DIGEST = new FinancialDigest(LiveProperties.fixed(ConnectorFinancialProperties.class, open("1", "1", "1")));
    // KST 2026-10-13 10:00 이다. 하루의 시작은 UTC 2026-10-12T15:00 이다.
    private static final Instant NOW = Instant.parse("2026-10-13T01:00:00Z");
    private static final String LIMIT_BUY = "{\"symbol\":\"A\",\"market\":\"KOSPI\",\"currency\":\"KRW\",\"side\":\"BUY\",\"quantity\":\"2\",\"price\":\"1000\","
        + "\"orderType\":\"LIMIT\",\"clientOrderId\":\"c\",\"account_seq\":\"7\"}";
    private final ConnectorActionRepository actions = mock(ConnectorActionRepository.class);
    private final ConnectorActionExecutionRepository contents = mock(ConnectorActionExecutionRepository.class);
    private final TextCipher cipher = mock(TextCipher.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final List<ConnectorAction> today = new ArrayList<>();


    @Test
    @DisplayName("설정 기본값은 key 파일 없음, 빈 허용 목록, 상한 0 이다")
    void defaultsAreClosed() {
        var closed = new ConnectorFinancialProperties(null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, "");
        var gate = gate(closed);
        assertThatThrownBy(gate::requireOpen).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> gate.requireAccounts(9L, List.of("7"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ConnectorFinancialGate.amount(JSON.readTree(LIMIT_BUY), closed))
            .isInstanceOf(ApiException.class);
    }


    @Test
    @DisplayName("keyed hash key 가 없거나 이전 protocol, 이전 key 의 열린 줄이 남았으면 허용 목록과 상한을 채워도 닫는다")
    void closesWithoutDigestKeyOrWithOpenRowsOfOtherKey() {
        var noKey = open("1", "1", "1");
        noKey = new ConnectorFinancialProperties(noKey.allowedAccounts(), noKey.maxOrderAmountKrw(),
            noKey.maxOrderAmountUsd(), noKey.maxOrderQuantity(), noKey.maxDailyAmountKrw(), noKey.maxDailyAmountUsd(), "");
        assertThatThrownBy(gate(noKey)::requireOpen).isInstanceOf(ApiException.class);
        assertThatCode(gate(open("1", "1", "1"))::requireOpen).doesNotThrowAnyException();
        when(contents.existsOpenOutside(eq(ConnectorExecutionSnapshot.PROTOCOL), eq(DIGEST.currentPrefix()),
            eq(Set.of(ActionStatus.PENDING, ActionStatus.EXECUTING, ActionStatus.UNKNOWN)))).thenReturn(true);
        assertThatThrownBy(gate(open("1", "1", "1"))::requireOpen).isInstanceOf(ApiException.class);
    }


    @Test
    @DisplayName("keyed hash 는 칸마다 다르고 평문 SHA 와 겹치지 않는 64글자다")
    void digestsArePurposeBoundAndDistinguishable() {
        String value = "{\"account_seq\":\"7\"}";
        assertThat(DIGEST.of("scope_json", value)).hasSize(64).startsWith(FinancialDigest.PREFIX)
            .isNotEqualTo(DIGEST.of("execution_args_json", value));
        assertThat(FinancialDigest.keyed(DIGEST.of("scope_json", value))).isTrue();
        // 앞 10글자는 key 마다 같은 식별자다. 관문이 이전 key 로 만든 열린 줄을 DB 조회로 가른다.
        assertThat(DIGEST.of("scope_json", value)).startsWith(DIGEST.currentPrefix());
        assertThat(DIGEST.of("request_key", value)).startsWith(DIGEST.currentPrefix());
        assertThat(DIGEST.currentPrefix()).hasSize(10).startsWith(FinancialDigest.PREFIX);
        assertThat(FinancialDigest.keyed(Sha256.hex(value))).isFalse();
    }


    @Test
    @DisplayName("기동 로그는 0 인 상한의 설정 이름만 남긴다")
    void namesZeroLimitsForStartupLog() {
        var limits = new ConnectorFinancialProperties(List.of("7:" + OWNER), BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE,
            BigDecimal.ZERO, BigDecimal.ONE, KEY);
        assertThat(ConnectorFinancialGate.zeroLimits(limits)).containsExactly("max-order-amount-usd", "max-daily-amount-krw");
        assertThat(ConnectorFinancialGate.zeroLimits(open("1", "1", "1"))).isEmpty();
    }


    @Test
    @DisplayName("음수 상한과 빈 계좌 식별자는 기동을 멈춘다")
    void rejectsInvalidSettings() {
        assertThatThrownBy(() -> new ConnectorFinancialProperties(List.of("7:" + OWNER), new BigDecimal("-1"), BigDecimal.ONE,
            BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, KEY)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> accounts(" ")).isInstanceOf(IllegalStateException.class);
    }


    @ParameterizedTest
    @ValueSource(strings = {"7", "7:", ":" + OWNER, "a7:" + OWNER, "12345678901234567890:" + OWNER,
        "7:owner", "7:@", "7:a@", "7:@example.test", "7:own er@example.test", "7:owner@example.test:x", "7:a@b@example.test"})
    @DisplayName("허용 목록 항목이 계좌 순번과 메일 모양이 아니면 값 원문 없이 기동을 멈춘다")
    void rejectsMalformedAllowListEntry(String entry) {
        assertThatThrownBy(() -> accounts(entry)).isInstanceOf(IllegalStateException.class)
            .hasMessageNotContaining(entry);
    }


    @Test
    @DisplayName("같은 계좌를 두 항목에 적으면 메일이 달라도 기동을 멈춘다")
    void rejectsDuplicatedAccount() {
        assertThatThrownBy(() -> accounts("7:" + OWNER, "7:other@example.test")).isInstanceOf(IllegalStateException.class);
        assertThat(accounts("7:" + OWNER, "8:" + OWNER).ownerOf("8")).contains(OWNER);
        // 중복은 글자 그대로 본다. 앞의 0 만 다른 표기는 다른 계좌다.
        assertThat(accounts("7:" + OWNER, "000007:other@example.test").ownerOf("000007")).contains("other@example.test");
    }


    @Test
    @DisplayName("허용 목록은 계좌의 주인 메일을 돌려주고 목록에 없는 계좌는 빈 값이다")
    void findsOwnerOfAccount() {
        var listed = accounts("7:" + OWNER, "1234567890123456789:Other@Example.Test");
        assertThat(listed.ownerOf("7")).contains(OWNER);
        assertThat(listed.ownerOf("1234567890123456789")).contains("Other@Example.Test");
        assertThat(listed.ownerOf("8")).isEmpty();
        assertThat(listed.ownerOf("07")).isEmpty();
        assertThat(listed.ownerOf("")).isEmpty();
        assertThat(listed.ownerOf(null)).isEmpty();
    }


    @Test
    @DisplayName("scope 계좌의 항목 메일이 줄 주인의 메일과 대소문자 없이 같을 때만 지나고 다른 사용자와 없는 사용자는 닫는다")
    void requiresAccountOwnedByUser() {
        when(users.findById(9L)).thenReturn(Optional.of(AppUser.of("Owner@Example.Test", "주인", 1L, UserRole.MEMBER, NOW)));
        when(users.findById(10L)).thenReturn(Optional.of(AppUser.of("other@example.test", "다른 사람", 1L, UserRole.MEMBER, NOW)));
        var gate = gate(open("1", "1", "1"));
        assertThatCode(() -> gate.requireAccounts(9L, List.of("7"))).doesNotThrowAnyException();
        assertThatCode(() -> gate.requireScope(9L, "{\"account_seq\":\"7\"}")).doesNotThrowAnyException();
        assertThatThrownBy(() -> gate.requireAccounts(10L, List.of("7"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> gate.requireScope(10L, "{\"account_seq\":\"7\"}")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> gate.requireAccounts(11L, List.of("7"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> gate.requireAccounts(9L, List.of("8"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> gate.requireAccounts(9L, List.of())).isInstanceOf(ApiException.class);
    }


    @Test
    @DisplayName("000007 항목이 있어도 scope 7 은 맞지 않고 scope 000007 만 지난다")
    void comparesAccountTextExactly() {
        when(users.findById(9L)).thenReturn(Optional.of(AppUser.of(OWNER, "주인", 1L, UserRole.MEMBER, NOW)));
        var limits = new ConnectorFinancialProperties(List.of("000007:" + OWNER), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
            BigDecimal.ONE, BigDecimal.ONE, KEY);
        var gate = gate(limits);
        assertThat(limits.ownerOf("7")).isEmpty();
        assertThatThrownBy(() -> gate.requireAccounts(9L, List.of("7"))).isInstanceOf(ApiException.class);
        assertThatCode(() -> gate.requireAccounts(9L, List.of("000007"))).doesNotThrowAnyException();
    }


    @Test
    @DisplayName("지정가 생성은 수량과 가격의 곱을 한 건 금액으로 보고 상한을 넘으면 거절한다")
    void limitsLimitOrderAmountAndQuantity() {
        var amount = ConnectorFinancialGate.amount(JSON.readTree(LIMIT_BUY), open("2000", "2", "1000000"));
        assertThat(amount).hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo("2000"));
        assertThatThrownBy(() -> ConnectorFinancialGate.amount(JSON.readTree(LIMIT_BUY), open("1999", "2", "1000000")))
            .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ConnectorFinancialGate.amount(JSON.readTree(LIMIT_BUY), open("2000", "1", "1000000")))
            .isInstanceOf(ApiException.class);
    }


    @ParameterizedTest
    @ValueSource(strings = {
        // 시장가 수량 주문은 금액을 미리 알 수 없다.
        "{\"currency\":\"KRW\",\"quantity\":\"1\",\"orderType\":\"MARKET\"}",
        // 일반 주문 정정은 첫 개방 대상이 아니다.
        "{\"orderId\":\"o\",\"currency\":\"KRW\",\"quantity\":\"1\",\"price\":\"1\"}",
        // 조건주문 생성과 정정은 첫 개방 대상이 아니다.
        "{\"currency\":\"KRW\",\"quantity\":\"1\",\"first\":{}}",
        "{\"conditionalOrderId\":\"c\",\"quantity\":\"1\",\"first\":{}}",
        // 모르는 통화와 숫자 decimal 은 닫는다.
        "{\"currency\":\"JPY\",\"orderAmount\":\"1\"}",
        "{\"currency\":\"KRW\",\"quantity\":1,\"price\":\"1\"}",
        "{\"currency\":\"KRW\",\"quantity\":\"0\",\"price\":\"1\"}"})
    @DisplayName("금액을 정할 수 없거나 첫 개방 대상이 아닌 주문은 닫는다")
    void closesUnmeasurableOrders(String args) {
        assertThatThrownBy(() -> ConnectorFinancialGate.amount(JSON.readTree(args), open("1000000", "1000", "1000000")))
            .isInstanceOf(ApiException.class);
    }


    @Test
    @DisplayName("취소는 새로 쓰는 금액이 없어 상한과 무관하게 지난다")
    void cancelHasNoAmount() {
        var limits = open("0", "0", "0");
        assertThat(ConnectorFinancialGate.amount(JSON.readTree("{\"orderId\":\"o\",\"currency\":\"KRW\"}"), limits)).isEmpty();
        assertThat(ConnectorFinancialGate.amount(JSON.readTree("{\"conditionalOrderId\":\"c\"}"), limits)).isEmpty();
    }


    @Test
    @DisplayName("상한을 주문 하나만 겨우 담게 둬도 같은 날 그 주문의 취소는 지나고 하루 합계에 들어가지 않는다")
    void cancelPassesTightLimitAndIsNotCountedTwice() {
        String cancel = "{\"orderId\":\"o\",\"market\":\"KOSPI\",\"currency\":\"KRW\",\"account_seq\":\"7\"}";
        spent(1L, ActionStatus.SUCCEEDED, LIMIT_BUY);
        assertThatCode(() -> gate(open("2000", "2", "2000")).requireWithinLimits(9L, null, cancel, NOW))
            .doesNotThrowAnyException();
        spent(2L, ActionStatus.SUCCEEDED, cancel);
        // 원주문 2000 과 새 주문 2000 이다. 취소 줄을 원주문 금액으로 세면 6000 이 되어 막힌다.
        assertThatCode(() -> gate(open("2000", "2", "4000")).requireWithinLimits(9L, null, LIMIT_BUY, NOW))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> gate(open("2000", "2", "3999")).requireWithinLimits(9L, null, LIMIT_BUY, NOW))
            .isInstanceOf(ApiException.class);
    }


    @Test
    @DisplayName("하루 합계는 승인으로 넘어간 같은 통화 주문을 UNKNOWN 까지 세고 자기 줄과 끝난 실패는 뺀다")
    void sumsDailyAmountIncludingUnknown() {
        spent(1L, ActionStatus.SUCCEEDED, LIMIT_BUY);
        spent(2L, ActionStatus.UNKNOWN, LIMIT_BUY);
        spent(3L, ActionStatus.FAILED, LIMIT_BUY);
        spent(4L, ActionStatus.EXECUTING, LIMIT_BUY);
        spent(5L, ActionStatus.SUCCEEDED, LIMIT_BUY.replace("KRW", "USD"));
        // 1, 2 와 새 주문을 더하면 6000 이다. 4 는 자기 줄이라 뺀다.
        assertThatCode(() -> gate(open("2000", "2", "6000")).requireWithinLimits(9L, 4L, LIMIT_BUY, NOW))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> gate(open("2000", "2", "5999")).requireWithinLimits(9L, 4L, LIMIT_BUY, NOW))
            .isInstanceOf(ApiException.class);
        // 자기 줄이 아니면 EXECUTING 도 센다.
        assertThatThrownBy(() -> gate(open("2000", "2", "6000")).requireWithinLimits(9L, null, LIMIT_BUY, NOW))
            .isInstanceOf(ApiException.class);
    }


    @Test
    @DisplayName("같은 날 keyed hash 이전 protocol 로 성공한 주문은 평문 SHA 로 대조해 하루 합계에 넣고 새 주문을 막지 않는다")
    void sumsLegacyRowsWithPlainSha() {
        spent(1L, ActionStatus.SUCCEEDED, LIMIT_BUY, ConnectorExecutionSnapshot.LEGACY_PROTOCOL, Sha256.hex(LIMIT_BUY));
        assertThatCode(() -> gate(open("2000", "2", "4000")).requireWithinLimits(9L, null, LIMIT_BUY, NOW))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> gate(open("2000", "2", "3999")).requireWithinLimits(9L, null, LIMIT_BUY, NOW))
            .isInstanceOf(ApiException.class);
    }


    @Test
    @DisplayName("이전 protocol 줄이라도 평문 SHA 가 원문과 다르면 하루 합계를 내지 않고 닫는다")
    void rejectsLegacyRowWithMismatchedSha() {
        spent(1L, ActionStatus.SUCCEEDED, LIMIT_BUY, ConnectorExecutionSnapshot.LEGACY_PROTOCOL, Sha256.hex(LIMIT_BUY + " "));
        assertThatThrownBy(() -> gate(open("2000", "2", "4000")).requireWithinLimits(9L, null, LIMIT_BUY, NOW))
            .isInstanceOf(RuntimeException.class);
    }


    @Test
    @DisplayName("key 를 바꾼 같은 날 이전 key 로 성공한 주문도 암호문을 열어 하루 합계에 넣는다")
    void sumsRowsOfPreviousKey() {
        var rotated = new FinancialDigest(LiveProperties.fixed(ConnectorFinancialProperties.class,
            new ConnectorFinancialProperties(List.of("7:" + OWNER), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, ROTATED_KEY)));
        var row = spent(1L, ActionStatus.SUCCEEDED, "sealed", ConnectorExecutionSnapshot.PROTOCOL,
            rotated.of("execution_args_json", LIMIT_BUY));
        when(row.contentKeyId()).thenReturn(3L);
        when(cipher.open(eq(3L), any(), any(), eq("sealed"))).thenReturn(Optional.of(LIMIT_BUY));
        assertThatCode(() -> gate(open("2000", "2", "4000")).requireWithinLimits(9L, null, LIMIT_BUY, NOW))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> gate(open("2000", "2", "3999")).requireWithinLimits(9L, null, LIMIT_BUY, NOW))
            .isInstanceOf(ApiException.class);
        // 암호화하지 않은 줄은 해시로도 AAD 로도 원문을 확인할 수 없어 닫는다.
        when(row.contentKeyId()).thenReturn(null);
        when(row.executionArgsJson()).thenReturn(LIMIT_BUY);
        assertThatThrownBy(() -> gate(open("2000", "2", "4000")).requireWithinLimits(9L, null, LIMIT_BUY, NOW))
            .isInstanceOf(RuntimeException.class);
    }


    private void spent(long id, ActionStatus status, String args) {
        spent(id, status, args, ConnectorExecutionSnapshot.PROTOCOL, DIGEST.of("execution_args_json", args));
    }


    private ConnectorActionExecution spent(long id, ActionStatus status, String args, String protocol, String executionArgsSha256) {
        var action = mock(ConnectorAction.class);
        when(action.id()).thenReturn(id);
        when(action.status()).thenReturn(status);
        var row = mock(ConnectorActionExecution.class);
        when(row.actionId()).thenReturn(id);
        when(row.protocol()).thenReturn(protocol);
        // key 가 없으면 원문을 그대로 읽는다. 하루 합계의 복호화 경로는 통합 테스트가 본다.
        when(row.contentKeyId()).thenReturn(null);
        when(row.executionArgsJson()).thenReturn(args);
        when(row.executionArgsSha256()).thenReturn(executionArgsSha256);
        when(contents.findById(id)).thenReturn(Optional.of(row));
        today.add(action);
        return row;
    }


    private ConnectorFinancialGate gate(ConnectorFinancialProperties properties) {
        when(actions.findByUserIdAndRiskAndDecidedAtGreaterThanEqual(eq(9L), eq(ToolRisk.FINANCIAL),
            eq(Instant.parse("2026-10-12T15:00:00Z")))).thenReturn(today);
        var live = LiveProperties.fixed(ConnectorFinancialProperties.class, properties);
        return new ConnectorFinancialGate(live, actions, contents, cipher, new FinancialDigest(live), users);
    }


    private static ConnectorFinancialProperties open(String maxOrder, String maxQuantity, String maxDaily) {
        return new ConnectorFinancialProperties(List.of("7:" + OWNER), new BigDecimal(maxOrder), new BigDecimal(maxOrder),
            new BigDecimal(maxQuantity), new BigDecimal(maxDaily), new BigDecimal(maxDaily), KEY);
    }


    private static ConnectorFinancialProperties accounts(String... entries) {
        return new ConnectorFinancialProperties(List.of(entries), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
            BigDecimal.ONE, KEY);
    }
}
