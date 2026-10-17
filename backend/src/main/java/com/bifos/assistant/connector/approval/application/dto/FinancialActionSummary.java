package com.bifos.assistant.connector.approval.application.dto;

import com.bifos.assistant.connector.financial.application.dto.FinancialLimitStatus;
import java.util.List;

/**
 * 금융 승인 카드에 보일 요약이다. 저장한 summary 의 칸을 글 그대로 옮기고 계좌는 실행 scope 의 값을 싣는다.
 *
 * <p>summary 의 계좌 끝 네 자리는 싣지 않는다. 실제 전송 계좌는 scope 의 값이고 승인과 claim 이 그 값을 허용 목록에 대조한다.
 *
 * @param available 저장한 내용을 열어 검증했는가. 거짓이면 나머지 칸은 null 이고 화면은 승인 단추를 그리지 않는다
 * @param accountSeq 실행 scope 의 계좌 순번
 * @param limits 생성 주문의 그 통화 상한과 오늘 남은 한도. 취소와 열지 못한 줄은 null 이다
 */
public record FinancialActionSummary(
    boolean available,
    String accountSeq,
    String operation,
    String symbol,
    String market,
    String currency,
    String side,
    String quantity,
    String orderAmount,
    String price,
    String orderType,
    String timeInForce,
    String orderId,
    List<Normalization> normalization,
    FinancialLimitStatus limits) {

    public FinancialActionSummary {
        normalization = List.copyOf(normalization);
    }


    /** 저장한 내용을 열지 못한 줄이다. */
    public static FinancialActionSummary unavailable() {
        return new FinancialActionSummary(false, null, null, null, null, null, null, null, null, null, null, null, null,
            List.of(), null);
    }


    /** prepare 가 바꾼 칸 하나다. 없던 값은 null 이다. */
    public record Normalization(String field, String before, String after) {
    }
}
