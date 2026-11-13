package com.bifos.assistant.connector.financial.domain.type;

import java.util.Arrays;
import java.util.Optional;

/** 구매 상한의 종류다(ADR-20261113 / purchase-checkout). 화면 API 는 소문자 {@link #apiName} 으로 부른다. */
public enum PurchaseLimitKind {
    ORDER("order", "한 건 구매 상한"),
    DAILY("daily", "하루 구매 상한");


    private final String apiName;
    private final String label;


    PurchaseLimitKind(String apiName, String label) {
        this.apiName = apiName;
        this.label = label;
    }


    public String apiName() {
        return apiName;
    }


    /** 사용자와 모델에게 보이는 이름이다. */
    public String label() {
        return label;
    }


    /** API 이름으로 찾는다. 모르는 이름이면 비어 있다. */
    public static Optional<PurchaseLimitKind> fromApiName(String name) {
        return Arrays.stream(values()).filter(kind -> kind.apiName.equals(name)).findFirst();
    }
}
