package com.bifos.assistant.chat.artifact.application;

import com.bifos.assistant.chat.artifact.application.dto.ArtifactFile;
import com.bifos.assistant.chat.artifact.domain.ChatArtifact;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.artifact.infra.ArtifactFoundFile;
import com.bifos.assistant.chat.artifact.infra.ArtifactStore;
import com.bifos.assistant.chat.artifact.infra.ChatArtifactRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.skill.upload.application.SkillAgentNotice;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 에이전트가 대화 폴더에 만든 결과물을 답에 묶고, 파일을 줄 때 판정한다.
 *
 * <p><b>모델이 답에 적은 경로를 읽지 않는다.</b> turn 이 끝나면 폴더를 훑어 그 turn 이 시작한 뒤 바뀐 HTML 만
 * 묶는다. 모델이 적는 형식은 흔들리지만 폴더에서 바뀐 파일을 찾는 것은 말투와 무관하다. 근거는 ADR-027 에
 * 있다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ArtifactService {

    /**
     * 그림 만들기 규칙이다(스펙 generated-image REQ4, REQ6, REQ10). 조건을 먼저 적는다. 조건 없는 지시는 싼 모델이 요청과 상관없이 따른다.
     *
     * <p>{@code 제목:} 줄은 fos-ctx 가 떼어 제목으로 쓴다. 글자를 바꾸면 {@code hermes/plugins/fos-ctx/generated_image.py} 의
     * {@code TITLE_PREFIX} 도 함께 바꾼다.
     */
    static final String IMAGE_RULES = "아래 네 줄은 image_generate 도구가 있고 사용자가 그림을 만들어 달라고 했을 때만 따른다.\n"
        + "- image_generate 로 만든 그림은 이 대화에 자동으로 저장되고 사용자의 화면에 보인다."
        + " artifact_write 나 파일 도구로 옮기지 않고, 답에 파일 경로나 MEDIA: 를 쓰지 않는다.\n"
        + "- 그림 지시문(prompt)은 사용자의 부탁을 그대로 넘기지 않는다."
        + " 그릴 대상, 구도, 화풍, 조명, 바꾸지 말고 지킬 것을 담아 자세히 쓴다.\n"
        + "- 지시문의 첫 줄에 「제목: <짧은 한국어 제목>」 을 적고 둘째 줄부터 지시문을 쓴다.\n"
        + "- 그림을 만들지 못했으면 그림 없이 까닭과, 잠시 뒤 다시 부탁하면 된다는 것을 답한다.\n";

    private final ArtifactStore store;
    private final ChatArtifactRepository artifacts;
    private final ChatArtifactWriter writer;


    /**
     * 실행 입력 맨 앞에 붙일 단락들이다. 결과물 폴더 단락 뒤에 스킬 관리 단락을 둔다. 끝에 빈 줄 하나를 둔다.
     *
     * <p>매 turn 붙인다. 에이전트가 이번 turn 에 파일을 만들지 미리 알 수 없다. 사진 단락이 있으면 그 앞에 둔다.
     * 저장하는 메시지 본문에는 쓰지 않는다.
     */
    public String agentPreamble(Conversation conversation) {
        return "[결과물 폴더]\n"
            + store.agentFolder(conversation.id()) + "\n"
            + "대화 식별자: " + conversation.publicId() + "\n"
            // 조건 없이 「저장한다」 만 적으면 싼 모델이 요청과 상관없이 파일을 만든다(2026-09-29 gpt-6-luna 에서 확인).
            + "이 폴더는 사용자가 HTML 페이지나 이미지 같은 파일을 만들어 달라고 할 때만 쓴다. 그런 요청이 없으면 파일을 만들지 않고 답을 글로만 한다.\n"
            + "artifact_write 도구가 있으면 그것으로 저장한다. conversation_id 에 이 대화 식별자를 넣고 path 는 상대 경로로 쓴다.\n"
            + "artifact_write 도구가 없고 파일 도구가 있으면 위 폴더에 결과물 파일을 직접 쓴다.\n"
            + "artifact_write 에서는 HTML 과 CSS 는 content, HTML 이 부를 인터넷의 사진은 source_url 을 쓴다. 둘 중 하나만 넣는다. 파일 하나는 5MB 까지다.\n"
            + "HTML 이 사진을 부를 때는 이 폴더 안의 상대 경로를 쓴다.\n"
            // 위에 폴더 경로가 적혀 있어 모델이 그것을 답에 옮긴다(2026-09-29 운영에서 확인). 결과물은 답 아래에 자동으로 붙는다.
            + "이 폴더 경로와 파일 경로를 답에 쓰지 않는다. 만든 결과물은 답 아래에 자동으로 붙는다.\n"
            + IMAGE_RULES
            + "\n"
            + SkillAgentNotice.PARAGRAPH;
    }


    /**
     * turn 이 시작한 뒤 바뀐 HTML 을 그 turn 의 답 메시지에 묶는다.
     *
     * <p>답 메시지가 없는 turn(빈 답으로 중지)은 묶지 않는다. 같은 답에 같은 경로가 이미 묶였으면 건너뛴다.
     * 사진과 CSS 는 행을 만들지 않는다. HTML 이 부르는 것일 뿐이다.
     *
     * <p>훑거나 행을 만들다 실패해도 밖으로 던지지 않는다. 답은 이미 저장됐고 turn 은 성공으로 끝나야 한다.
     * 경고 로그만 남긴다.
     */
    public void recordTurn(Long conversationId, Long messageId, Instant turnStartedAt) {
        if (messageId == null) {
            return;
        }
        bindGeneratedImages(conversationId, messageId);
        List<ArtifactFoundFile> found;
        try {
            found = store.changedHtmlSince(conversationId, turnStartedAt);
        } catch (RuntimeException ex) {
            log.warn(
                "could not scan artifacts of a turn conversationId={} messageId={}", conversationId, messageId, ex);
            return;
        }
        if (found.isEmpty()) {
            return;
        }
        Set<String> already;
        try {
            already = artifacts.findByMessageId(messageId).stream()
                .map(ChatArtifact::path)
                .collect(Collectors.toSet());
        } catch (RuntimeException ex) {
            log.warn("could not read artifacts of a message messageId={}", messageId, ex);
            return;
        }
        for (ArtifactFoundFile file : found) {
            if (already.contains(file.path())) {
                continue;
            }
            try {
                artifacts.save(
                    ChatArtifact.of(conversationId, messageId, file.path(), file.byteSize()));
            } catch (RuntimeException ex) {
                // 한 파일이 실패해도 나머지는 묶는다. 경로가 칸 길이를 넘는 것도 여기로 온다.
                log.warn("could not record an artifact conversationId={} messageId={}", conversationId, messageId, ex);
            }
        }
    }


    /**
     * 그 대화에서 아직 답에 묶이지 않은 그림을 이 답에 묶는다(ADR-20261119 / generated-image).
     *
     * <p>그림 행은 fos-ctx 가 올릴 때 이미 만들어져 있다. 폴더를 훑지 않는다. 실패해도 밖으로 던지지 않는다.
     */
    private void bindGeneratedImages(Long conversationId, Long messageId) {
        try {
            writer.bindGeneratedImages(conversationId, messageId);
        } catch (RuntimeException ex) {
            log.warn(
                "could not bind generated images of a turn conversationId={} messageId={}", conversationId, messageId, ex);
        }
    }


    /**
     * 지워졌거나 아직 있는 결과물의 살아 있는 보관본 번호를 경로마다 준다. 같은 경로에 여럿이면 가장 새것이다.
     *
     * @param ownerUserId 보관 주인. 대화 요청자다
     */
    public Map<String, Long> vaultItemIds(Long ownerUserId, Long conversationId, Collection<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> ids = new HashMap<>();
        for (ChatArtifact copy : artifacts.findLiveVaultCopies(ownerUserId, conversationId, paths)) {
            ids.put(copy.path(), copy.id());
        }
        return ids;
    }


    /**
     * 대화 폴더 안의 파일 하나를 열지 않고 판정한다.
     *
     * <p>행을 찾지 않는다. HTML 이 부르는 사진은 행이 없다. 폴더 안에 있고 확장자가 허용되면 준다. 행은 없는 파일이
     * 보관 기간이 지나 지워진 것인지 가릴 때만 본다. 대화 주인 확인은 컨트롤러가 대화 번호를 얻을 때 이미 했다.
     *
     * <p>형식은 링크를 따라간 실제 파일의 확장자로 정한다. 요청한 이름으로 정하면 {@code a.html} 이름의 링크가
     * 사진을 가리킬 때 사진을 HTML 로 내준다.
     *
     * <p>파일을 열지 않고 크기와 수정 시각만 읽는다. 조건부 요청이 304 로 끝나면 열 것이 없다.
     */
    public ArtifactFile find(Long conversationId, String relativePath) {
        Path file = store.resolveInside(conversationId, relativePath)
            .orElseThrow(() -> missing(conversationId, relativePath));
        String contentType = ArtifactStore.contentTypeOf(file.getFileName().toString())
            .orElseThrow(() -> missing(conversationId, relativePath));
        try {
            long size = Files.size(file);
            Instant lastModified = Files.getLastModifiedTime(file).toInstant();
            return new ArtifactFile(conversationId, relativePath, file, contentType, size, lastModified);
        } catch (NoSuchFileException ex) {
            // 판정과 읽는 사이에 정리 작업이 지운 경우다.
            throw missing(conversationId, relativePath);
        } catch (IOException ex) {
            throw new UncheckedIOException("could not read an artifact of conversation " + conversationId, ex);
        }
    }


    /** {@link #find} 가 판정한 파일의 본문을 연다. 받은 쪽이 스트림을 닫는다. */
    public InputStream open(ArtifactFile file) {
        try {
            return Files.newInputStream(file.path());
        } catch (NoSuchFileException ex) {
            // 판정과 여는 사이에 정리 작업이 지운 경우다.
            throw missing(file.conversationId(), file.relativePath());
        } catch (IOException ex) {
            throw new UncheckedIOException("could not open an artifact of conversation " + file.conversationId(), ex);
        }
    }


    /** 답 메시지마다 묶인 결과물을 한 번에 읽는다. 지워진 것도 담아 지난 대화에 자리를 남긴다. */
    public Map<Long, List<ChatArtifact>> byMessage(Collection<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return Map.of();
        }
        return artifacts.findByMessageIdInOrderByIdAsc(messageIds).stream()
            .collect(Collectors.groupingBy(ChatArtifact::messageId));
    }


    private ApiException missing(Long conversationId, String relativePath) {
        if (relativePath != null
            && artifacts.existsByConversationIdAndPathAndDeletedAtIsNotNullAndVaultUserIdIsNull(conversationId, relativePath)) {
            return new ApiException(ErrorCode.ARTIFACT_GONE, "this file is no longer kept");
        }
        return new ApiException(ErrorCode.ARTIFACT_NOT_FOUND, "this file does not exist");
    }
}
