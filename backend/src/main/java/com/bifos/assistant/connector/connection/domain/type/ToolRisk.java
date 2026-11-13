package com.bifos.assistant.connector.connection.domain.type;

/**
 * 커넥터 도구 하나가 외부에 끼치는 영향의 등급이다(ADR-049).
 *
 * <p>값과 기본 승인 방식과 하한은 {@code docs/features/connector-policy.md} 의 「도구 정책」 표와 같다.
 */
public enum ToolRisk {
    READ(ToolApproval.NONE, ToolApproval.NONE),
    SENSITIVE(ToolApproval.REQUIRED, ToolApproval.REQUIRED),
    WRITE(ToolApproval.REQUIRED, ToolApproval.REQUIRED),
    DESTRUCTIVE(ToolApproval.ALWAYS, ToolApproval.ALWAYS),
    FINANCIAL(ToolApproval.ALWAYS, ToolApproval.ALWAYS),
    /**
     * 돈을 쓰는 구매 단계로 가는 도구다(ADR-20261113 / purchase-checkout). 결제는 사람이 하고 도구는 주문서까지만 간다.
     * {@link #FINANCIAL} 과 같이 직접 호출을 열지 않고 사람이 승인한 준비로만 실행한다. 위험도를 나눈 까닭은 금융 하루 합계와
     * 주문 상한 칸이 위험도로 줄을 고르기 때문이다.
     */
    PURCHASE(ToolApproval.ALWAYS, ToolApproval.ALWAYS);


    private final ToolApproval defaultApproval;
    private final ToolApproval floor;


    ToolRisk(ToolApproval defaultApproval, ToolApproval floor) {
        this.defaultApproval = defaultApproval;
        this.floor = floor;
    }


    /** 선언이 승인 방식을 적지 않았을 때의 값이다. */
    public ToolApproval defaultApproval() {
        return defaultApproval;
    }


    /** 선언이 이보다 느슨해질 수 없는 승인 방식이다. 하한이 없는 등급은 {@code NONE} 이다. */
    public ToolApproval floor() {
        return floor;
    }
}
