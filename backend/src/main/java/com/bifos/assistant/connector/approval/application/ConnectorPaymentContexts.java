package com.bifos.assistant.connector.approval.application;

import com.bifos.assistant.connector.action.domain.ConnectorAction;
import com.bifos.assistant.connector.action.domain.type.ActionStatus;
import com.bifos.assistant.connector.action.domain.type.HandoffNeed;
import com.bifos.assistant.connector.action.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.approval.application.dto.PaymentHandoffResult;
import com.bifos.assistant.connector.connection.application.dto.ConnectorPaymentContext;
import com.bifos.assistant.connector.connection.application.dto.ConnectorPolicyAnswer;
import com.bifos.assistant.connector.connection.domain.ToolPolicy;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * 주문 확인 도구({@code payment_context} 를 선언한 도구)에 줄 결제 넘기기 맥락을 고른다.
 *
 * <p>호출한 실행의 대화와 사용자, 그 플러그인이 같은 승인 줄 가운데 결제 넘기기({@code PAYMENT})를 적은 {@code SUCCEEDED} 줄을 넘긴
 * 시각이 늦은 것부터 하나 고른다. 넘긴 시각과 금액, 상품 주소는 모델 인자가 아니라 이 줄에서만 온다. 규칙은
 * {@code docs/features/connector-policy.md} 의 「도구 호출 판정」 이 갖는다. 서비스 이름을 모른다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorPaymentContexts {
    private final ConnectorActionRepository actions;
    private final ConnectorFinancialSummaries summaries;


    /** 허용한 답이고 도구가 {@code payment_context} 를 선언했으면 맥락을 싣는다. 찾지 못하면 맥락 없이 그대로 허용한다. */
    public ConnectorPolicyAnswer attach(
        ConnectorPolicyAnswer answered, Optional<ToolPolicy> declared, AgentExecution origin, String connectorId) {
        if (!answered.allowed() || !declared.map(ToolPolicy::paymentContext).orElse(false)) {
            return answered;
        }
        return of(origin, connectorId).map(answered::withPaymentContext).orElse(answered);
    }


    /**
     * 대화가 없는 실행, 줄이 없음, 결과 글이나 snapshot 을 열지 못함이면 빈 값이다. 예외는 밖으로 내지 않는다. 판정은 그대로 허용이다.
     *
     * <p>트랜잭션 표시를 두지 않는다. 저장소 호출이 던진 예외를 여기서 잡아도 감싼 트랜잭션이 롤백 전용이 되어 커밋에서 다시 터지면,
     * 판정 줄은 허용인데 hook 은 막는 어긋남이 생긴다. 저장소 호출마다 자기 읽기 트랜잭션으로 돈다.
     */
    public Optional<ConnectorPaymentContext> of(AgentExecution origin, String connectorId) {
        if (origin.conversationId() == null || origin.userId() == null) {
            return Optional.empty();
        }
        try {
            return actions.findHandedOff(origin.conversationId(), origin.userId(), connectorId, HandoffNeed.PAYMENT,
                ActionStatus.SUCCEEDED, PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .flatMap(this::contextOf);
        } catch (RuntimeException ex) {
            log.warn("결제 넘기기 맥락을 고르지 못했다 kind={}", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }


    private Optional<ConnectorPaymentContext> contextOf(ConnectorAction action) {
        var amounts = PaymentHandoffResult.fromStored(action.resultText());
        var url = summaries.checkoutUrlOf(action);
        if (amounts.isEmpty() || url.isEmpty() || action.handoff() == null || action.handoff().at() == null) {
            return Optional.empty();
        }
        return Optional.of(new ConnectorPaymentContext(action.handoff().at(), amounts.get().finalAmountKrw(),
            amounts.get().expectedTotalKrw(), url.get()));
    }
}
