package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.domain.ChatArtifact;
import com.bifos.assistant.chat.infra.ArtifactStore;
import com.bifos.assistant.chat.infra.ArtifactStore.FoundFile;
import com.bifos.assistant.chat.infra.ChatArtifactRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 에이전트가 대화 폴더에 만든 결과물을 답에 묶고, 파일을 줄 때 판정한다.
 *
 * <p><b>모델이 답에 적은 경로를 읽지 않는다.</b> turn 이 끝나면 폴더를 훑어 그 turn 이 시작한 뒤 바뀐 HTML 만
 * 묶는다. 모델이 적는 형식은 흔들리지만 폴더에서 바뀐 파일을 찾는 것은 말투와 무관하다. 근거는 ADR-027 에
 * 있다.
 */
@Service
@RequiredArgsConstructor
public class ArtifactService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactService.class);

    private final ConversationAccess access;
    private final ArtifactStore store;
    private final ChatArtifactRepository artifacts;

    /**
     * 실행 입력 맨 앞에 붙일 결과물 폴더 단락이다. 끝에 빈 줄 하나를 둔다.
     *
     * <p>매 turn 붙인다. 에이전트가 이번 turn 에 파일을 만들지 미리 알 수 없다. 사진 단락이 있으면 그 앞에 둔다.
     * 저장하는 메시지 본문에는 쓰지 않는다.
     */
    public String agentPreamble(Long conversationId) {
        return "[결과물 폴더]\n"
                + store.agentFolder(conversationId) + "\n"
                + "파일로 결과물을 만들면 이 폴더에 둔다. HTML 이 사진을 부를 때는 이 폴더 안의 상대 경로를 쓴다.\n"
                + "\n";
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
        List<FoundFile> found;
        try {
            found = store.changedHtmlSince(conversationId, turnStartedAt);
        } catch (RuntimeException ex) {
            log.warn("could not scan artifacts of a turn conversationId={} messageId={}",
                    conversationId, messageId, ex);
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
        for (FoundFile file : found) {
            if (already.contains(file.path())) {
                continue;
            }
            try {
                artifacts.save(ChatArtifact.of(conversationId, messageId, file.path(), file.byteSize()));
            } catch (RuntimeException ex) {
                // 한 파일이 실패해도 나머지는 묶는다. 경로가 칸 길이를 넘는 것도 여기로 온다.
                log.warn("could not record an artifact conversationId={} messageId={}",
                        conversationId, messageId, ex);
            }
        }
    }

    /**
     * 대화 폴더 안의 파일 하나를 연다.
     *
     * <p>행을 찾지 않는다. HTML 이 부르는 사진은 행이 없다. 폴더 안에 있고 확장자가 허용되면 준다. 행은 없는 파일이
     * 보관 기간이 지나 지워진 것인지 가릴 때만 본다. 부르는 순서에 기대지 않도록 여기서도 대화 주인을 확인한다.
     */
    public ArtifactContent open(CurrentUser user, Long conversationId, String relativePath) {
        access.requireOwn(user, conversationId);
        Path file = store.resolveInside(conversationId, relativePath)
                .orElseThrow(() -> missing(conversationId, relativePath));
        String contentType = ArtifactStore.contentTypeOf(relativePath)
                .orElseThrow(() -> missing(conversationId, relativePath));
        try {
            long size = Files.size(file);
            InputStream body = Files.newInputStream(file);
            return new ArtifactContent(contentType, size, body);
        } catch (NoSuchFileException ex) {
            // 판정과 여는 사이에 정리 작업이 지운 경우다.
            throw missing(conversationId, relativePath);
        } catch (IOException ex) {
            throw new UncheckedIOException("could not open an artifact of conversation " + conversationId, ex);
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
                && artifacts.existsByConversationIdAndPathAndDeletedAtIsNotNull(conversationId, relativePath)) {
            return new ApiException(ErrorCode.ARTIFACT_GONE, "this file is no longer kept");
        }
        return new ApiException(ErrorCode.ARTIFACT_NOT_FOUND, "this file does not exist");
    }
}
