package com.bifos.assistant.chat.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.chat.conversation.application.dto.FoundPage;
import com.bifos.assistant.chat.conversation.application.dto.LinkTarget;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.chat.turn.application.ChatService;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 비서가 찾은 주소를 대화마다 해시로 남기고 판정하는 규칙을 확인한다(스펙 agent-found-image REQ2, DECISION16, DECISION19, DECISION25). */
@BackendIntegrationTest
class FoundLinksTest {
    private static final String PAGE = "https://shop.example.com/goods/1";

    @Autowired
    FoundLinks links;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    AppUserRepository users;

    @Autowired
    ChatService chat;

    @Autowired
    JdbcTemplate jdbc;

    private CurrentUser user;
    private Long conversationId;


    @BeforeEach
    void setUp() {
        AppUser saved = users.save(AppUser.of(UUID.randomUUID() + "@example.test", "사용자", 1L, UserRole.MEMBER));
        user = new CurrentUser(saved.id(), saved.email(), saved.displayName(), 1L, UserRole.MEMBER);
        conversationId = conversations.save(Conversation.startedBy(user.id(), "찾은 주소", null, Instant.now())).id();
    }


    @Test
    @DisplayName("결과에 적힌 절대 주소는 scheme 과 호스트 대소문자, 퍼센트 인코딩이 달라도 찾은 주소다")
    void findsAbsoluteLinksWithSameAddressRules() {
        links.record(conversationId, List.of(new FoundPage(null, List.of("https://Shop.Example.com/상품/1?c=red"))), List.of());

        assertThat(links.find(conversationId, "shop.example.com/상품/1?c=red"))
            .contains(URI.create("https://shop.example.com/%EC%83%81%ED%92%88/1?c=red"));
        assertThat(links.find(conversationId, "https://shop.example.com/%EC%83%81%ED%92%88/1?c=red")).isPresent();
    }


    @Test
    @DisplayName("글자를 바꾸거나 쿼리를 더한 주소와 알리지 않은 주소는 찾은 주소가 아니다")
    void rejectsAlteredLinks() {
        links.record(conversationId, List.of(new FoundPage(null, List.of("https://shop.example.com/goods/1?c=red"))), List.of());

        assertThat(links.find(conversationId, "https://shop.example.com/goods/2?c=red")).isEmpty();
        assertThat(links.find(conversationId, "https://shop.example.com/goods/1?c=red&secret=x")).isEmpty();
        assertThat(links.find(conversationId, "https://shop.example.com/goods/1")).isEmpty();
        assertThat(links.find(conversationId, "https://evil.example.com/goods/1?c=red")).isEmpty();
        assertThat(links.find(conversationId, null)).isEmpty();
    }


    @Test
    @DisplayName("엔티티가 든 주소는 적힌 꼴과 푼 꼴이 모두 통과하고 받을 주소는 푼 꼴이다")
    void decodesEntities() {
        links.record(conversationId, List.of(new FoundPage(PAGE, List.of("https://img.example.com/a.png?w=1&amp;h=2"))), List.of());

        URI decoded = URI.create("https://img.example.com/a.png?w=1&h=2");
        assertThat(links.find(conversationId, "https://img.example.com/a.png?w=1&amp;h=2")).contains(decoded);
        assertThat(links.find(conversationId, "https://img.example.com/a.png?w=1&h=2")).contains(decoded);
    }


    @Test
    @DisplayName("// 로 시작하는 주소와 상대 주소는 페이지 주소로 푼 완전한 주소로 통과한다")
    void resolvesRelativeLinks() {
        links.record(conversationId, List.of(new FoundPage(PAGE, List.of("//cdn.example.com/b.png", "/img/c.jpg", "d.webp"))), List.of());

        assertThat(links.find(conversationId, "https://cdn.example.com/b.png")).isPresent();
        assertThat(links.find(conversationId, "https://shop.example.com/img/c.jpg")).isPresent();
        assertThat(links.find(conversationId, "https://shop.example.com/goods/d.webp")).isPresent();
        assertThat(links.find(conversationId, "/img/c.jpg")).as("상대 꼴 그대로는 가리키지 못한다").isEmpty();
    }


    @Test
    @DisplayName("기준 주소가 없는 상대 주소와 http(s) 가 아닌 주소, 너무 긴 주소는 남기지 않는다")
    void dropsUnresolvableLinks() {
        int kept = links.record(
            conversationId,
            List.of(
                new FoundPage(null, List.of("/img/c.jpg", "", "has space", "x".repeat(FoundLinks.MAX_LINK_CHARS + 1))),
                new FoundPage(PAGE, List.of("javascript:alert(1)", "mailto:a@example.com"))),
            List.of());

        assertThat(kept).isZero();
        assertThat(conversations.findFoundLinkHashes(conversationId)).isEmpty();
    }


    @Test
    @DisplayName("읽은 페이지 자신의 주소는 본문에 되풀이돼도 찾은 주소가 아니다")
    void excludesThePageItself() {
        links.record(conversationId, List.of(new FoundPage(PAGE, List.of(PAGE, "/goods/1", "HTTP://SHOP.EXAMPLE.COM/goods/1", "/goods/2"))), List.of());

        assertThat(links.find(conversationId, PAGE)).isEmpty();
        assertThat(links.find(conversationId, "https://shop.example.com/goods/2")).isPresent();
    }


    @Test
    @DisplayName("읽기 서비스가 넘어간 뒤의 주소를 돌려줘도 비서가 적어 넣은 주소는 찾은 주소가 아니다")
    void excludesTypedUrlsEvenAfterRedirect() {
        links.record(
            conversationId,
            List.of(new FoundPage("https://final.example.com/landed", List.of("https://typed.example.com/go?x=1", "/img/a.png"))),
            List.of("typed.example.com/go?x=1"));

        assertThat(links.find(conversationId, "https://typed.example.com/go?x=1")).isEmpty();
        assertThat(links.find(conversationId, "https://final.example.com/img/a.png")).isPresent();
    }


    @Test
    @DisplayName("쿼리만 있는 상대 주소는 페이지 경로에 그대로 붙여 푼다")
    void resolvesQueryOnlyLinks() {
        links.record(conversationId, List.of(new FoundPage("https://shop.example.com/a/b?q=1#top", List.of("?page=2"))), List.of());

        assertThat(links.find(conversationId, "https://shop.example.com/a/b?page=2")).isPresent();
        assertThat(links.find(conversationId, "https://shop.example.com/a/?page=2")).isEmpty();
    }


    @Test
    @DisplayName("두 해시의 경계에 걸친 글자는 일치로 보지 않는다")
    void matchesOnlyAtHashBoundaries() {
        String first = LinkTarget.of("https://a.example.com/1").orElseThrow().hash();
        String second = LinkTarget.of("https://a.example.com/2").orElseThrow().hash();
        String target = LinkTarget.of("https://a.example.com/straddle").orElseThrow().hash();
        // 찾을 해시가 두 해시에 걸쳐 나타나게 앞뒤를 채운다.
        String stored = first.substring(0, 16) + target + second.substring(0, 16);
        jdbc.update("UPDATE conversation SET found_link_hashes = ? WHERE id = ?", stored, conversationId);

        assertThat(links.find(conversationId, "https://a.example.com/straddle")).isEmpty();
    }


    @Test
    @DisplayName("상한을 넘으면 오래된 주소부터 밀려나고 쓴 주소는 뒤로 옮겨져 남는다")
    void evictsOldestAndKeepsUsedLinks() {
        links.record(conversationId, List.of(new FoundPage(null, List.of("https://old.example.com/first", "https://old.example.com/used"))), List.of());
        fill(0, FoundLinks.MAX_HASHES - 2_000);
        assertThat(links.find(conversationId, "https://old.example.com/used")).as("쓴 주소는 뒤에 다시 붙는다").isPresent();

        fill(FoundLinks.MAX_HASHES, 4_000);

        assertThat(links.find(conversationId, "https://old.example.com/first")).isEmpty();
        assertThat(links.find(conversationId, "https://old.example.com/used")).isPresent();
        assertThat(links.find(conversationId, "https://fill.example.com/" + (FoundLinks.MAX_HASHES + 3_999))).isPresent();
        assertThat(conversations.findFoundLinkHashes(conversationId).orElseThrow())
            .hasSize(FoundLinks.MAX_HASHES * FoundLinks.HASH_CHARS);
    }


    @Test
    @DisplayName("대화를 지우면 찾은 주소를 비우고 지운 대화에는 더 남기지 않는다")
    void clearsOnDelete() {
        links.record(conversationId, List.of(new FoundPage(null, List.of("https://shop.example.com/goods/9"))), List.of());

        chat.delete(user, conversationId);

        assertThat(conversations.findFoundLinkHashes(conversationId)).isEmpty();
        links.record(conversationId, List.of(new FoundPage(null, List.of("https://shop.example.com/goods/9"))), List.of());
        assertThat(conversations.findFoundLinkHashes(conversationId)).isEmpty();
    }


    /** H2 의 문자열 함수 결과 길이 상한을 넘지 않게 2,000개씩 나눠 알린다. */
    private void fill(int from, int count) {
        for (int start = from; start < from + count; start += 2_000) {
            List<String> batch = new ArrayList<>();
            for (int index = start; index < Math.min(start + 2_000, from + count); index++) {
                batch.add("https://fill.example.com/" + index);
            }
            links.record(conversationId, List.of(new FoundPage(null, batch)), List.of());
        }
    }
}
