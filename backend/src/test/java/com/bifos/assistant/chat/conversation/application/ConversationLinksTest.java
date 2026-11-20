package com.bifos.assistant.chat.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 모델이 가리킨 링크가 요청자가 보낸 메시지에 있는지 보는 규칙이다(스펙 generated-image DECISION43). */
class ConversationLinksTest {
    private static final long CONVERSATION = 7L;
    private static final CurrentUser USER = new CurrentUser(1L, "a@example.test", "사용자", 1L, UserRole.MEMBER);
    private static final long OTHER = 2L;

    private final ChatMessageRepository repository = mock(ChatMessageRepository.class);
    private final List<ChatMessage> messages = new ArrayList<>();
    private final ConversationLinks links = new ConversationLinks(repository);


    @Test
    @DisplayName("앞부분 유무, 조사, 끝의 마침표, 인코딩 차이, 한글 경로, 대문자 호스트, 조각은 같은 주소로 본다")
    void matchesSameLinkWithinAllowedDifferences() {
        userSays("이거 봐 shop.example.com/goods/123의 양말 어때?");
        userSays("두번째 https://Shop.Example.com/a/%EC%83%81%ED%92%88?x=1. 이것도");
        userSays("세번째 (http://img.example.com/b.png) 그리고 https://shop.example.com/c#리뷰");

        assertThat(find("https://shop.example.com/goods/123")).contains(URI.create("https://shop.example.com/goods/123"));
        assertThat(find("shop.example.com/a/상품?x=1")).contains(URI.create("https://shop.example.com/a/%EC%83%81%ED%92%88?x=1"));
        assertThat(find("https://SHOP.example.com/a/%EC%83%81%ED%92%88?x=1")).isPresent();
        assertThat(find("https://img.example.com/b.png")).contains(URI.create("https://img.example.com/b.png"));
        assertThat(find("http://img.example.com/b.png")).contains(URI.create("https://img.example.com/b.png"));
        assertThat(find("https://shop.example.com/c#리뷰")).contains(URI.create("https://shop.example.com/c"));
        assertThat(find("https://shop.example.com/c")).isPresent();
    }


    @Test
    @DisplayName("더 긴 주소의 앞부분, 바꾼 주소, 남의 메시지와 답에만 있는 주소는 대화에 없는 주소다")
    void rejectsLinksNotSentByRequester() {
        userSays("https://shop.example.com/goods/12345?utm=x 이거");
        messages.add(ChatMessage.fromUser(CONVERSATION, OTHER, "https://other.example.com/x", Instant.now()));
        messages.add(ChatMessage.fromAssistant(CONVERSATION, "https://answer.example.com/y", 3L, Instant.now()));

        assertThat(find("https://shop.example.com/goods/123")).isEmpty();
        assertThat(find("https://shop.example.com/goods/12345")).isEmpty();
        assertThat(find("https://other.example.com/x")).isEmpty();
        assertThat(find("https://answer.example.com/y")).isEmpty();
        assertThat(find("https://evil.example.com/shop.example.com/goods/12345?utm=x")).isEmpty();
        assertThat(find("")).isEmpty();
    }


    @Test
    @DisplayName("추적 링크 안의 다른 주소, 퍼센트 인코딩한 공백 앞부분, 한글 뒤에 붙은 주소는 맞지 않는다")
    void rejectsAddressesInsideOtherWords() {
        userSays("https://t.example.com/r?u=https://shop.example.com/x 와 shop.example.com/a%20b 와 상품shop.example.com/y");

        assertThat(find("https://shop.example.com/x")).isEmpty();
        assertThat(find("shop.example.com/a")).isEmpty();
        assertThat(find("shop.example.com/y")).isEmpty();
        assertThat(find("https://t.example.com/r?u=https://shop.example.com/x")).isPresent();
        assertThat(find("shop.example.com/a%20b")).isPresent();
    }


    @Test
    @DisplayName("잘못된 퍼센트 인코딩이 든 메시지도 예외 없이 판정한다")
    void toleratesBrokenPercentEncoding() {
        userSays("할인 100% shop.example.com/sale?p=1 여기 %ZZ shop.example.com/bad?p=%ZZ");

        assertThat(find("shop.example.com/sale?p=1")).contains(URI.create("https://shop.example.com/sale?p=1"));
        assertThat(find("shop.example.com/bad?p=%ZZ")).as("받을 주소를 만들 수 없으면 빈 값이다").isEmpty();
        assertThat(find("shop.example.com/none")).isEmpty();
    }


    private void userSays(String content) {
        messages.add(ChatMessage.fromUser(CONVERSATION, USER.id(), content, Instant.now()));
    }


    private Optional<URI> find(String link) {
        when(repository.findByConversationIdOrderByIdAsc(CONVERSATION)).thenReturn(messages);
        return links.findSentLink(USER, CONVERSATION, link);
    }
}
