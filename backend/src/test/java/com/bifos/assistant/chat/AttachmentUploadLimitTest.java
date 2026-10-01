package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.chat.application.AttachmentProperties;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ChatAttachmentRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Date;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 서버와 HTTP 클라이언트로 사진 크기 상한을 확인한다.
 *
 * <p>MockMvc 의 multipart 요청은 Tomcat 과 multipart resolver 의 크기 상한을 거치지 않아, 설정의
 * {@code max-file-size} 를 빠뜨려도 통과한다. 그래서 실제 서버를 띄운다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AttachmentUploadLimitTest {

    private static final String JWT_SECRET = "test-secret-test-secret-test-secret-test-secret";
    private static final String EMAIL = "uploader@example.com";
    private static final int MB = 1024 * 1024;
    private static final int KB = 1024;

    @LocalServerPort
    int port;

    @Autowired
    AppUserRepository users;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ChatAttachmentRepository attachments;

    @Autowired
    AttachmentProperties properties;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private AppUser user;
    private Long conversationId;
    private UUID conversationPublicId;

    @BeforeEach
    void setUp() throws IOException {
        attachments.deleteAll();
        // 이 서버는 따로 뜬 메모리 데이터베이스를 써서 번호가 1부터 다시 시작한다. 앞선 실행이 남긴 파일과
        // 겹치지 않게 비운다.
        deleteTree(Path.of(properties.root()).toAbsolutePath());
        user = users.findByEmail(EMAIL).orElseGet(() -> users.save(AppUser.of(EMAIL, "올리는 사람", 1L, UserRole.MEMBER)));
        Conversation conversation = conversations.save(Conversation.startedBy(user.id(), "사진 대화", null));
        conversationId = conversation.id();
        conversationPublicId = conversation.publicId();
    }

    @Test
    @DisplayName("기본 상한 1MB 를 넘는 2MB 사진이 올라간다")
    void uploads2MbPhotoOverDefault1MbLimit() throws Exception {
        HttpResponse<String> response = upload(2 * MB);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("byteSize").asLong()).isEqualTo(2L * MB);
        assertThat(attachments.findByConversationIdOrderByIdAsc(conversationId)).hasSize(1);
    }

    @Test
    @DisplayName("한 장 상한을 조금 넘으면 서비스가 입력 오류로 거절한다")
    void serviceRejectsAsInputErrorWhenSlightlyOverPerImageLimit() throws Exception {
        HttpResponse<String> response = upload(10 * MB + 100 * KB);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(code(response)).isEqualTo("VALIDATION_FAILED");
        assertThat(attachments.findByConversationIdOrderByIdAsc(conversationId)).isEmpty();
    }

    @Test
    @DisplayName("요청 상한을 넘어도 500 이 아니라 입력 오류다")
    void overRequestLimitIsInputErrorNot500() throws Exception {
        // 넘는 양을 수백 KB 로 둔다. 많이 넘기면 서버가 남은 본문을 읽지 않고 연결을 끊어 응답을 받지 못한다.
        HttpResponse<String> response = upload(12 * MB + 300 * KB);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(code(response)).isEqualTo("VALIDATION_FAILED");
        assertThat(attachments.findByConversationIdOrderByIdAsc(conversationId)).isEmpty();
    }

    private HttpResponse<String> upload(int size) throws Exception {
        String boundary = "attachment-boundary-" + size;
        ByteArrayOutputStream body = new ByteArrayOutputStream(size + 512);
        body.write(("--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"file\"; filename=\"photo.jpg\"\r\n"
                        + "Content-Type: image/jpeg\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.write(new byte[size]);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/api/v1/chat/conversations/" + conversationPublicId + "/attachments"))
                .header("Authorization", "Bearer " + jwt())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String code(HttpResponse<String> response) {
        JsonNode node = json.readTree(response.body());
        return node.path("code").asString();
    }

    private String jwt() {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.email())
                .claim("name", user.displayName())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path each : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(each);
            }
        }
    }
}
