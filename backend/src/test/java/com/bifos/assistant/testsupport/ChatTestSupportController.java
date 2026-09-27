package com.bifos.assistant.testsupport;

import jakarta.persistence.EntityManager;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 브라우저 검사에서 옛 대화 주소를 만들 수 있게 대화 번호를 알려 준다.
 *
 * <p>대화 번호는 주소와 API 에 나오지 않아 브라우저 검사가 얻을 길이 없다.
 */
@RestController
@RequestMapping("/api/v1/test-support/chat")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "assistant.test-support.enabled", havingValue = "true")
public class ChatTestSupportController {

    private final EntityManager entityManager;

    /**
     * 공개 식별자로 대화 번호를 찾는다.
     *
     * <p>부르는 사람의 대화인지 보지 않는다. 남의 대화로 옛 주소를 여는 검사도 이 번호를 쓴다.
     * 운영 저장소에는 사용자를 함께 받는 조회만 있어, 운영 코드에 조회를 더하지 않고 여기서 JPQL 로 찾는다.
     */
    @GetMapping("/conversations/{conversationId}/number")
    public ResponseEntity<ConversationNumber> number(@PathVariable UUID conversationId) {
        return entityManager
                .createQuery("select c.id from Conversation c where c.publicId = :id", Long.class)
                .setParameter("id", conversationId)
                .getResultList()
                .stream()
                .findFirst()
                .map(number -> ResponseEntity.ok(new ConversationNumber(number)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 대화 표의 번호다. */
    public record ConversationNumber(Long number) {
    }
}
