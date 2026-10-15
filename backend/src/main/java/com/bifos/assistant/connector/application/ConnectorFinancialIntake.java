package com.bifos.assistant.connector.application;

import com.bifos.assistant.connector.application.execution.ConnectorExecutionGuard;
import com.bifos.assistant.connector.application.model.ConnectorExecutionGuardContract;
import com.bifos.assistant.connector.application.model.ConnectorPolicyAnswer;
import com.bifos.assistant.connector.domain.ConnectorAction;
import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.domain.HermesToolName;
import com.bifos.assistant.connector.domain.type.ToolRisk;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import com.bifos.assistant.proactive.application.ProactiveCheckGuard;
import com.bifos.assistant.usage.domain.AgentExecution;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 정책 판정이 받은 금융 준비 도구 호출을 금융 준비로 넘긴다(ADR-20261013 / financial-order-opening).
 *
 * <p>일반 판정과 일반 승인 줄을 거치지 않는다. 실행 도구를 직접 부른 호출은 이 길로 오지 않고 정책 판정에서 {@code RISK_NOT_OPEN} 이다.
 * 금융 준비는 트랜잭션 밖에서 부른다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class ConnectorFinancialIntake {
    /** 금융 준비를 거절한 답이다. 어느 검사에 걸렸는지 밝히지 않는다. 같은 의도의 다른 호출이 받는 답도 이것이다. */
    static final String FINANCIAL_UNAVAILABLE_MESSAGE = "금융 요청을 준비하지 못해 실행하지 않았다. 같은 주문의 승인 요청이 이미 있을 수 있다. "
        + "다시 부르지 말고 사용자에게 승인 카드와 그 서비스의 주문 내역을 확인해 달라고 안내한다.";
    /** 지금 여는 금융 동작이다. 정정과 조건주문은 열지 않는다. */
    private static final Set<String> OPEN_OPERATIONS = Set.of("CREATE", "CANCEL");
    /** 준비 도구 인자를 읽는다. 중복 키와 뒤에 붙은 값은 거절한다. 서명한 글과 다른 값으로 읽히지 않게 한다. */
    private static final JsonMapper STRICT_JSON = JsonMapper.builder(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ConnectorFinancialProducer producer;
    private final ConnectorExecutionGuard guard;
    private final ProactiveCheckGuard checks;


    /**
     * 같은 키로 이미 남은 줄을 금융 준비가 맡는가. 금융 줄은 준비 도구 호출이 만든 것이라 등록 이름과 인자 해시가 그 호출과 다르다.
     * 다시 금융 준비로 넘기면 금융 준비가 같은 키의 줄을 찾아 같은 승인 번호로 답한다.
     */
    static boolean owns(ConnectorAction recorded) {
        return recorded.risk() == ToolRisk.FINANCIAL;
    }


    /**
     * 같은 키의 금융 줄이 있거나 이 호출이 금융 준비 도구이면 금융 준비가 답한다. 그 밖의 호출은 빈 값이라 정책 판정이 이어서 판정한다.
     *
     * @param recorded 같은 키로 이미 남은 줄. {@link #owns} 가 참인 줄만 온다
     * @param confirmedTool 등록 이름과 맞는 것을 확인한 원래 도구 이름. 확인하지 못했으면 null
     */
    Optional<ConnectorPolicyAnswer> intercept(Optional<ConnectorAction> recorded, Optional<ConnectorManifest> manifest,
        String confirmedTool, ConnectorBinding binding, AgentExecution origin, String profileName, String rootSessionId,
        String sessionId, String toolCallId, String argsJson) {
        if (recorded.isEmpty() && manifest.filter(value -> prepares(value, confirmedTool)).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(prepare(manifest, confirmedTool, binding, origin, profileName, rootSessionId, sessionId, toolCallId, argsJson));
    }


    /** 커넥터가 금융 보호를 선언했고 이 호출이 그 준비 도구인가. 선언의 나머지 검증은 {@link #prepare} 가 한다. */
    private static boolean prepares(ConnectorManifest manifest, String confirmedTool) {
        JsonNode declared = manifest.executionGuard();
        return confirmedTool != null && declared != null && declared.isObject() && declared.path("prepare_tool").isString()
            && declared.path("prepare_tool").stringValue().equals(confirmedTool);
    }


    /**
     * 준비 도구 호출을 금융 준비로 넘긴다.
     *
     * <p>같은 키의 금융 줄이 있는데 이 호출이 준비 도구가 아니면 줄 없이 막는다. 보호 선언을 읽지 못하면 이 호출만 막는다. 그 커넥터의
     * 다른 도구는 막지 않는다. 살펴보기 트리 안의 호출은 쓰기를 허용한 살펴보기여도 넘기지 않는다. 사람이 보지 않는 실행이 외부 글에
     * 속아 주문 승인 요청을 쌓지 않게 한다.
     *
     * <p>인자는 정확히 {@code {v: 1, tool, args}} 이고, {@code tool} 은 지금 여는 일반 주문 동작이어야 한다.
     */
    private ConnectorPolicyAnswer prepare(Optional<ConnectorManifest> manifest, String confirmedTool, ConnectorBinding binding,
        AgentExecution origin, String profileName, String rootSessionId, String sessionId, String toolCallId, String argsJson) {
        if (manifest.isEmpty()) {
            return ConnectorPolicyService.blockedWithoutRecord("같은 키의 금융 줄이 있는데 커넥터 선언을 읽지 못했다");
        }
        if (!prepares(manifest.get(), confirmedTool)) {
            return ConnectorPolicyService.blockedWithoutRecord("같은 키의 금융 줄에 준비 도구가 아닌 호출이 왔다");
        }
        final ConnectorExecutionGuardContract contract;
        try {
            contract = guard.read(manifest.get());
        } catch (RuntimeException ex) {
            return ConnectorPolicyService.blockedWithoutRecord("금융 보호 선언을 읽지 못했다");
        }
        if (checks.checkOf(origin).isPresent()) {
            return ConnectorPolicyService.blockedWithoutRecord("살펴보기 트리에서 금융 준비 도구를 불렀다");
        }
        try {
            JsonNode input = STRICT_JSON.readTree(argsJson);
            boolean shaped = input != null && input.isObject() && input.propertyNames().equals(Set.of("v", "tool", "args"))
                && input.get("v").isInt() && input.get("v").intValue() == 1
                && input.get("tool").isString() && input.get("args").isObject();
            String tool = shaped ? input.get("tool").stringValue() : null;
            // 선언하지 않은 도구는 동작이 없다. Set.of 의 contains 는 null 에 예외를 던지므로 먼저 거른다.
            String operation = tool == null ? null : contract.operations().get(tool);
            if (operation == null || !OPEN_OPERATIONS.contains(operation)
                || !ConnectorExecutionSnapshot.ORDER.equals(contract.kind(tool))) {
                log.warn("금융 준비 도구의 인자가 지금 여는 주문이 아니다");
                return new ConnectorPolicyAnswer(false, FINANCIAL_UNAVAILABLE_MESSAGE, null);
            }
            return producer.request(profileName, rootSessionId, sessionId, toolCallId,
                HermesToolName.of(binding.mcpServer(), tool), tool, JSON.writeValueAsString(input.get("args")));
        } catch (FinancialLimitExceeded ex) {
            log.info("금융 준비가 상한에 걸렸다 limit={}", ex.kind());
            return new ConnectorPolicyAnswer(false, limitMessage(ex), null);
        } catch (RuntimeException ex) {
            // 예외 메시지에는 원격 응답과 인자가 섞일 수 있어 종류만 남긴다.
            log.warn("금융 준비를 거절했다 kind={}", ex.getClass().getSimpleName());
            return new ConnectorPolicyAnswer(false, FINANCIAL_UNAVAILABLE_MESSAGE, null);
        }
    }


    /**
     * 상한에 걸린 준비의 답이다. 상한은 사용자가 정한 값이라 걸린 상한과 오늘 남은 한도를 밝힌다(ADR-20261015 / financial-user-limits).
     * 수량 상한은 남은 한도가 없다.
     */
    static String limitMessage(FinancialLimitExceeded ex) {
        String unit = ex.kind().currency() == null ? "" : " " + ex.kind().currency();
        var message = new StringBuilder("주문이 상한에 걸려 실행하지 않았다. 걸린 상한: ")
            .append(ex.kind().label()).append(' ').append(plain(ex.limit())).append(unit).append('.');
        if (ex.remainingToday() != null) {
            message.append(" 오늘 남은 한도: ").append(plain(ex.remainingToday())).append(unit).append('.');
        }
        return message.append(" 사용자에게 알린다. 상한을 바꾸려면 order_limit_change 로 요청한다. 올리기는 사용자의 승인이 있어야 반영된다.")
            .toString();
    }


    private static String plain(BigDecimal value) {
        return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }
}
