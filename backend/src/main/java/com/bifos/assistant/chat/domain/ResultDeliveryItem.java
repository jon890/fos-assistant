package com.bifos.assistant.chat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 전달 묶음에 든 결과 하나다(ADR-075).
 *
 * <p>결과 본문은 두지 않는다. 다시 전달할 때 그 결과를 낸 쪽의 줄에서 다시 읽는다. {@code (source, result_key)} 가
 * 유일해 한 결과는 한 묶음에만 든다.
 */
@Entity
@Table(
        name = "result_delivery_item",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_result_delivery_item_source_key",
                    columnNames = {"source", "result_key"})
        })
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ResultDeliveryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "delivery_id", nullable = false)
    private Long deliveryId;

    /** 결과를 낸 쪽의 이름이다. 위임 결과는 {@code DELEGATION} 이다. */
    @Column(name = "source", nullable = false, length = 40)
    private String source;

    /** 그쪽이 쓰는 결과 이름이다. */
    @Column(name = "result_key", nullable = false, length = 64)
    private String resultKey;

    private ResultDeliveryItem(Long deliveryId, String source, String resultKey) {
        this.deliveryId = deliveryId;
        this.source = source;
        this.resultKey = resultKey;
    }

    public static ResultDeliveryItem of(Long deliveryId, String source, String resultKey) {
        return new ResultDeliveryItem(deliveryId, source, resultKey);
    }
}
