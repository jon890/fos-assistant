package com.bifos.assistant.chat.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.ServletWebRequest;

/**
 * 결과물 파일의 조건부 요청 판정이 Spring 의 판정과 같은지 본다.
 *
 * <p>200 으로 돌려준 응답에 {@code ETag} 나 {@code Last-Modified} 가 있으면 Spring 이 조건부 요청을 다시 판정해
 * 304 로 바꾼다. 우리 판정이 거짓인데 Spring 이 304 로 바꾸면 이미 연 파일 스트림이 닫히지 않는다.
 * 그래서 경우마다 우리 판정의 기대값을 단언하고, 같은 머리글로 {@link ServletWebRequest#checkNotModified} 를
 * 불러 결과가 같은지도 본다. {@code If-None-Match: *} 하나만 Spring 과 다르고, 그 까닭은 그 검사에 적었다.
 */
class ArtifactControllerNotModifiedTest {

    /** 결과물 파일이 붙이는 것과 같은 모양의 약한 검증자다. */
    private static final String ETAG = "W/\"1f-19a2b3c4d5e\"";
    private static final Instant SECOND = Instant.parse("2026-09-01T10:00:00Z");
    private static final String SECOND_AS_HEADER = DateTimeFormatter.RFC_1123_DATE_TIME
            .withZone(ZoneOffset.UTC).format(SECOND);

    /**
     * 이 경우만 Spring 과 다르다. Spring 7 은 GET 의 {@code If-None-Match: *} 를 맞지 않은 것으로 보아 200 을 준다.
     *
     * <p>어긋나는 방향이 안전하다. 우리가 304 로 답하면 Spring 은 200 이 아닌 응답을 다시 판정하지 않으므로 파일을
     * 열지도, 연 채로 두지도 않는다. 위험한 쪽은 우리가 거짓인데 Spring 이 참인 경우이고, 여기서는 그 반대다.
     * Spring 의 판정이 바뀌면 이 검사가 알린다.
     */
    @Test
    void If_None_Match_가_별표면_참이고_Spring_은_거짓이다() {
        assertThat(ours(ETAG, SECOND, "If-None-Match", "*")).isTrue();
        assertThat(spring(ETAG, SECOND, "If-None-Match", "*")).isFalse();
    }

    @Test
    void 쉼표로_나열한_값_가운데_하나가_맞으면_참이다() {
        assertThat(judged(ETAG, SECOND, "If-None-Match", "\"other\", " + ETAG + ", W/\"another\"")).isTrue();
    }

    @Test
    void 요청에만_W_가_있어도_참이다() {
        assertThat(judged("\"1f-19a2b3c4d5e\"", SECOND, "If-None-Match", "W/\"1f-19a2b3c4d5e\"")).isTrue();
    }

    @Test
    void 현재_값에만_W_가_있어도_참이다() {
        assertThat(judged(ETAG, SECOND, "If-None-Match", "\"1f-19a2b3c4d5e\"")).isTrue();
    }

    @Test
    void 어느_값도_맞지_않으면_거짓이다() {
        assertThat(judged(ETAG, SECOND, "If-None-Match", "\"other\", W/\"another\"")).isFalse();
    }

    @Test
    void If_None_Match_가_맞지_않으면_If_Modified_Since_가_맞아도_거짓이다() {
        assertThat(judged(ETAG, SECOND,
                "If-None-Match", "\"other\"",
                "If-Modified-Since", SECOND_AS_HEADER)).isFalse();
    }

    @Test
    void If_None_Match_가_맞으면_If_Modified_Since_가_옛날이어도_참이다() {
        String yearBefore = DateTimeFormatter.RFC_1123_DATE_TIME
                .withZone(ZoneOffset.UTC).format(SECOND.minusSeconds(365L * 24 * 60 * 60));

        assertThat(judged(ETAG, SECOND,
                "If-None-Match", ETAG,
                "If-Modified-Since", yearBefore)).isTrue();
    }

    @Test
    void 수정_시각이_If_Modified_Since_와_같은_초의_999ms_면_참이다() {
        assertThat(judged(ETAG, SECOND.plusMillis(999), "If-Modified-Since", SECOND_AS_HEADER)).isTrue();
    }

    @Test
    void 수정_시각이_If_Modified_Since_의_다음_초면_거짓이다() {
        assertThat(judged(ETAG, SECOND.plusSeconds(1), "If-Modified-Since", SECOND_AS_HEADER)).isFalse();
    }

    @Test
    void 조건_머리글이_둘_다_없으면_거짓이다() {
        assertThat(judged(ETAG, SECOND)).isFalse();
    }

    /**
     * 이 머리글로 우리 판정을 내리고, 같은 머리글의 GET 요청에 Spring 이 내리는 판정과 같은지 확인한 뒤 돌려준다.
     *
     * @param headerPairs 머리글 이름과 값을 번갈아 둔다
     */
    private static boolean judged(String etag, Instant lastModified, String... headerPairs) {
        boolean ours = ours(etag, lastModified, headerPairs);
        boolean spring = spring(etag, lastModified, headerPairs);

        assertThat(ours)
                .as("머리글 %s, ETag %s, 수정 시각 %s 에서 우리 판정 %s 가 Spring 판정 %s 와 다르다",
                        String.join(" ", headerPairs), etag, lastModified, ours, spring)
                .isEqualTo(spring);
        return ours;
    }

    private static boolean ours(String etag, Instant lastModified, String... headerPairs) {
        HttpHeaders headers = new HttpHeaders();
        for (int i = 0; i < headerPairs.length; i += 2) {
            headers.add(headerPairs[i], headerPairs[i + 1]);
        }
        return ArtifactController.notModified(headers, etag, lastModified);
    }

    /** 200 으로 돌려준 GET 응답을 Spring 이 다시 판정할 때와 같은 호출이다. */
    private static boolean spring(String etag, Instant lastModified, String... headerPairs) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/chat/files/index.html");
        for (int i = 0; i < headerPairs.length; i += 2) {
            request.addHeader(headerPairs[i], headerPairs[i + 1]);
        }
        return new ServletWebRequest(request, new MockHttpServletResponse())
                .checkNotModified(etag, lastModified.toEpochMilli());
    }
}
