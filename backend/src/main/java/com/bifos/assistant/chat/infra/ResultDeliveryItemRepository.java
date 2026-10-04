package com.bifos.assistant.chat.infra;

import com.bifos.assistant.chat.domain.ResultDeliveryItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 트랜잭션은 부르는 서비스가 연다. */
public interface ResultDeliveryItemRepository extends JpaRepository<ResultDeliveryItem, Long> {

    /** 묶음에 넣은 순서로 읽는다. */
    List<ResultDeliveryItem> findByDeliveryIdOrderByIdAsc(Long deliveryId);
}
