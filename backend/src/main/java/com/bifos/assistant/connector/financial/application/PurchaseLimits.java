package com.bifos.assistant.connector.financial.application;

import com.bifos.assistant.connector.action.domain.type.ActionStatus;
import com.bifos.assistant.connector.action.infra.ConnectorActionExecutionRepository;
import com.bifos.assistant.connector.action.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.connection.domain.type.ToolRisk;
import com.bifos.assistant.connector.financial.domain.PurchaseLimit;
import com.bifos.assistant.connector.financial.domain.PurchaseLimitExceeded;
import com.bifos.assistant.connector.financial.domain.type.PurchaseLimitKind;
import com.bifos.assistant.connector.financial.infra.PurchaseLimitRepository;
import com.bifos.assistant.crypto.domain.TextCipher;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 구매 상한 판정과 오늘 승인한 합계를 계산하는 한 곳이다(ADR-20261113 / purchase-checkout).
 *
 * <p>판정하는 때(카드를 만들 때, 승인 순간, 채우기 직전, 결과 판정)는 {@code docs/adr/ADR-20261113-purchase-checkout.md} 가 가리키는
 * 기능 설계가 갖는다. 부르는 쪽은 같은 트랜잭션에서 그 사용자의 {@code app_user} 행을 먼저 잠근다. 상한을 바꾸는 쪽도
 * 같은 행을 잠그므로 판정은 그 순간에 커밋된 상한을 본다. 트랜잭션 표시를 두지 않는다. 승인과 결과 판정이 거절을 받아
 * 같은 트랜잭션에서 줄을 {@code FAILED} 로 끝내야 하는데, 표시가 있으면 거절이 그 트랜잭션을 롤백 전용으로 만든다. 토스 주문 상한({@link FinancialOrderLimits})과 줄도 합계도 섞지 않는다.
 */
@Component
@RequiredArgsConstructor
public class PurchaseLimits {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 결과를 모르는 UNKNOWN 도 센다. 결제 화면을 넘기지 못한 FAILED 는 세지 않는다(spec DECISION17, DECISION30).
    private static final Set<ActionStatus> SPENT = Set.of(ActionStatus.EXECUTING, ActionStatus.SUCCEEDED, ActionStatus.UNKNOWN);
    private final PurchaseLimitRepository limits;
    private final ConnectorActionRepository actions;
    private final ConnectorActionExecutionRepository contents;
    private final TextCipher cipher;
    private final FinancialDigest digest;


    /**
     * 한 건 상한보다 크거나, 오늘 합계에 더해 하루 상한보다 크면 거절한다. 같으면 지난다.
     *
     * @param ownActionId 하루 합계에서 뺄 자기 승인 줄. 없으면 null
     * @param now 하루 합계를 셀 KST 날짜의 시각
     * @throws PurchaseLimitExceeded 걸린 상한과 오늘 남은 한도를 싣는다. 상한이 0 이면 그 상한이다
     */
    public void requireWithin(Long userId, Long ownActionId, long amountKrw, Instant now) {
        PurchaseLimit limit = limitOf(userId);
        long remaining = Math.max(0, limit.dailyKrw() - spentToday(userId, ownActionId, now));
        if (limit.orderKrw() == 0 || amountKrw > limit.orderKrw()) {
            throw new PurchaseLimitExceeded(PurchaseLimitKind.ORDER, limit.orderKrw(), remaining);
        }
        if (limit.dailyKrw() == 0 || amountKrw > remaining) {
            throw new PurchaseLimitExceeded(PurchaseLimitKind.DAILY, limit.dailyKrw(), remaining);
        }
    }


    /** 그 사용자의 지금 상한이다. 사용자가 없으면 닫힌 쪽(0)이다. */
    public PurchaseLimit limitOf(Long userId) {
        return limits.findByUserId(userId).orElse(new PurchaseLimit(0, 0));
    }


    /**
     * 그 사용자가 KST 그날 승인한 {@code PURCHASE} 승인 줄의 예상 합계 합이다. 날짜는 승인 시각({@code decided_at})으로 가른다.
     * 실행 원문을 열지 못한 줄이 있으면 합계를 내지 않고 닫는다.
     *
     * @param ownActionId 합계에서 뺄 자기 승인 줄. 없으면 null
     */
    public long spentToday(Long userId, Long ownActionId, Instant now) {
        Instant from = now.atZone(KST).toLocalDate().atStartOfDay(KST).toInstant();
        long spent = 0;
        for (var action : actions.findByUserIdAndRiskAndDecidedAtGreaterThanEqual(userId, ToolRisk.PURCHASE, from)) {
            if (Objects.equals(action.id(), ownActionId) || !SPENT.contains(action.status())) {
                continue;
            }
            var row = contents.findById(action.id()).orElseThrow(ConnectorFinancialGate::closed);
            spent += ConnectorCheckoutSnapshot.expectedTotal(ConnectorExecutionSnapshot.openExecutionArgs(action, row, cipher, digest));
        }
        return spent;
    }
}
