package com.bifos.assistant.chat.artifact.application;

import com.bifos.assistant.chat.artifact.application.dto.ArtifactFile;
import com.bifos.assistant.chat.artifact.application.dto.GeneratedImageMention;
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
     * 그림 만들기와 고치기 규칙이다(스펙 generated-image REQ4, REQ6, REQ10, REQ15~REQ17, REQ24~REQ26). 조건을 먼저 적는다. 조건 없는 지시는 싼 모델이 요청과 상관없이 따른다.
     *
     * <p>{@code aspect_ratio} 를 늘 적게 한다. 빼면 Hermes 가 {@code landscape} 로 만들어 한 사람이나 물건 하나의 정사각이 지켜지지 않는다.
     * 값 셋은 {@code hermes/tests/hermes_contract.py} 의 {@code IMAGE_ASPECT_RATIOS} 가 Hermes 소스와 대조한다.
     *
     * <p>{@code 제목:} 줄은 fos-ctx 가 떼어 제목으로 쓴다. 글자를 바꾸면 {@code hermes/plugins/fos-ctx/generated_image.py} 의
     * {@code TITLE_PREFIX} 도 함께 바꾼다. {@code attachment:} 와 {@code generated:} 는 같은 plugin 의 {@code image_source.py} 가 읽는다.
     */
    static final String IMAGE_RULES = "아래 열두 줄은 image_generate 도구가 있고 사용자가 그림을 만들거나 고쳐 달라고 했을 때만 따른다.\n"
        + "- image_generate 로 만든 그림은 이 대화에 자동으로 저장되고 사용자의 화면에 보인다."
        + " artifact_write 나 파일 도구로 옮기지 않고, 답에 파일 경로나 MEDIA: 를 쓰지 않는다.\n"
        + "- 그림 지시문(prompt)은 사용자의 부탁을 그대로 넘기지 않는다."
        + " 그릴 대상, 구도, 화풍, 조명, 바꾸지 말고 지킬 것을 담아 한국어로 자세히 쓴다.\n"
        + "- 지시문의 첫 줄에 「제목: <짧은 한국어 제목>」 을 적고 둘째 줄부터 지시문을 쓴다.\n"
        + "- 사람이 나오는 그림을 새로 그릴 때는 지시문에 「인물 N명」 처럼 인물 수를 숫자로 적고,"
        + " 사람마다 「- 인물1: 생김새(나이대, 머리, 체격), 옷, 신발」 처럼 한 줄씩 적는다.\n"
        + "- 같은 인물이 나오는 다음 장면을 그릴 때는 사용자가 「이어서」 라고 하지 않아도 앞 그림을 reference_image_urls 에 generated:<그림 제목> 으로 넣는다."
        + " 바탕(image_url)에 넣지 않고 구도는 새로 잡는다. 앞 지시문의 인물 줄을 글자 그대로 다시 적고,"
        + " 아래 「이 대화에서 만든 그림」 에서 앞 그림 제목 뒤에 참고 링크가 있으면 그 링크도 link: 로 함께 넣는다."
        + " 앞 그림이 「이 대화에서 만든 그림」 에 없거나 앞 그림을 쓸 수 없다는 결과를 받으면 참고 없이 인물 줄만으로 그리고, 앞 그림을 참고하지 못했다고 답에 적는다.\n"
        + "- aspect_ratio 는 늘 적는다. 사용자가 비율이나 구도를 말했거나 쓰임새로 분명하면 그것을 따른다"
        + "(프로필 사진은 얼굴 위주로 square, 블로그 대표 사진은 landscape)."
        + " 그렇지 않으면 사람이나 동물 여럿, 풍경, 물건 여럿은 landscape, 한 사람이나 동물 하나, 물건 하나는 square 다."
        + " 얼굴 위주로 정한 때가 아니면 사람은 머리부터 발까지 들어오고 얼굴이 잘 보이게 지시문에 적는다.\n"
        + "- 이 대화에 보낸 사진이나 이 대화에서 만든 그림을 바탕으로 고칠 때는 바탕 한 장을 image_url 에, 참고할 것을 reference_image_urls 에 넣는다."
        + " 사진은 attachment:<사진 참조 목록의 attachment_id>, 만든 그림은 generated:<그림 제목> 으로 적는다.\n"
        + "- 사용자가 이 대화에 보낸 링크(상품 페이지나 사진 주소)의 사진을 바탕이나 참고로 쓸 때는 link:<사용자가 보낸 주소 그대로> 로 적는다."
        + " 검색이나 페이지에서 찾은 주소는 쓰지 않는다.\n"
        + "- 바탕과 참고에는 attachment:, generated:, link: 가리킴만 쓰고 파일 경로나 맨 주소는 쓰지 않는다.\n"
        + "- 고치는 지시문에는 바꿀 곳, 그대로 지킬 곳, 참고 사진의 어느 부분을 쓰는지를 적는다."
        + " 올린 사진은 대화에서 몇 번째 사진인지로, 만든 그림은 제목으로 가리키고 입력 순서(바탕이 첫째)를 함께 적는다.\n"
        + "- 올린 사진이나 만든 그림을 바탕(image_url)으로 고칠 때는 인물 수와 사람별 줄을 적지 않고, 바탕에 없는 사람을 더할 때만 그 사람의 줄을 적는다."
        + " aspect_ratio 는 바탕 사진의 비율에 가까운 것을 고른다.\n"
        + "- 그림을 만들지 못했거나 쓸 수 없는 사진이나 링크라는 결과를 받으면 그림 없이 까닭(쓸 수 없는 사진이나 링크와 그 까닭)과 다시 부탁하는 방법을 답한다."
        + " 링크를 쓸 수 없으면 사진을 직접 올리는 방법도 알린다.\n";

    /** 실행 입력에 제목을 적는 만든 그림의 수다. 새것부터 센다(스펙 generated-image REQ17). */
    static final int IMAGE_TITLE_LIMIT = 20;

    /** 그림 제목 줄에 적는 링크 하나의 길이 상한이다. {@code link:} 가리킴의 상한({@code ImageSourceRef})과 같다. */
    static final int IMAGE_LINK_MAX_CHARS = 2048;

    /**
     * 파일 공간 규칙이다(스펙 workspace REQ7, REQ8). 조건을 먼저 적는다. 조건 없는 지시는 싼 모델이 요청과 상관없이 따른다.
     *
     * <p>첫 줄은 도구가 없어도 맞는 안내다. 사용자가 「파일 공간」 을 말했을 때 되묻지 않게 한다.
     */
    static final String FILE_SPACE_RULES = fileSpaceRules(false);

    /**
     * 셸·파일 도구가 실행 공간에서 도는 에이전트의 파일 공간 규칙이다.
     *
     * <p>결과물 폴더는 실행 공간에 붙어 있지 않다. 파일 도구로 그 경로에 쓰면 실행 공간 안의 같은 이름 경로에 써져 Control Plane 이 찾지
     * 못한다(2026-11-20 운영에서 확인). 실행 공간에서 Control Plane 이 읽는 자리는 {@code /workspace/} 뿐이다.
     */
    static final String SANDBOX_FILE_SPACE_RULES = fileSpaceRules(true);

    /** 실행 공간 에이전트에게 결과물 폴더 단락의 「파일 도구로 직접 쓴다」 대신 주는 줄이다. */
    static final String SANDBOX_FOLDER_LINE = "파일 도구는 실행 공간에서 돌아 위 폴더에 쓴 파일이 이 서비스에 보이지 않는다. 위 폴더의 결과물은 artifact_write 로만 저장한다.\n";

    private static final String FOLDER_FILE_TOOL_LINE = "artifact_write 도구가 없고 파일 도구가 있으면 위 폴더에 결과물 파일을 직접 쓴다.\n";


    private static String fileSpaceRules(boolean sandboxed) {
        return "[파일 공간]\n"
            + "「파일 공간」(「내 파일」)은 이 서비스가 사용자에게 준 파일을 모아 두는 화면이다."
            + " 답에 묶인 HTML 과 image_generate 로 만든 그림은 자동으로 들어간다.\n"
            + "아래 " + (sandboxed ? "다섯" : "네") + " 줄은 file_space_put 도구가 있을 때만 따른다.\n"
            + "- 사용자에게 줄 파일(엑셀, PDF, 문서, CSV 등)을 만들었거나 사용자가 파일 공간에 넣어 달라고 하면 file_space_put 으로 넣는다."
            + " 짧은 한국어 제목과 한 줄 설명을 붙인다.\n"
            + (sandboxed
                ? "- 넣을 파일은 /workspace/ 아래에 만들고 file_space_put 의 path 에 그 /workspace/ 경로를 그대로 쓴다."
                    + " 위 결과물 폴더 경로에 만든 파일은 찾지 못한다.\n"
                : "")
            + "- 이미 들어 있는 것(자동으로 들어가는 HTML 과 그림, 결과가 already 인 것)은 다시 넣지 않고 이미 있다고 답한다.\n"
            + "- 도구가 실패하면 넣었다고 답하지 않고 까닭을 알린다.\n"
            + "- 다른 에이전트에게 맡긴 일에서 만든 파일은 그 파일을 만든 에이전트가 직접 넣는다. 맡길 때 넣어 달라고 함께 적는다.\n";
    }


    private final ArtifactStore store;
    private final ChatArtifactRepository artifacts;
    private final ChatArtifactWriter writer;
    private final GeneratedImageService images;


    /**
     * 실행 입력 맨 앞에 붙일 단락들이다. 결과물 폴더 단락 뒤에 스킬 관리 단락을 둔다. 끝에 빈 줄 하나를 둔다.
     *
     * <p>매 turn 붙인다. 에이전트가 이번 turn 에 파일을 만들지 미리 알 수 없다. 사진 단락이 있으면 그 앞에 둔다.
     * 저장하는 메시지 본문에는 쓰지 않는다.
     *
     * @param sandboxed 이 에이전트의 셸·파일 도구가 실행 공간에서 도는가. 파일을 어디에 만들고 어떤 경로로 넣는지가 달라진다
     */
    public String agentPreamble(Conversation conversation, boolean sandboxed) {
        return "[결과물 폴더]\n"
            + store.agentFolder(conversation.id()) + "\n"
            + "대화 식별자: " + conversation.publicId() + "\n"
            // 조건 없이 「저장한다」 만 적으면 싼 모델이 요청과 상관없이 파일을 만든다(2026-09-29 gpt-6-luna 에서 확인).
            + "이 폴더는 사용자가 HTML 페이지나 이미지 같은 파일을 만들어 달라고 할 때만 쓴다. 그런 요청이 없으면 파일을 만들지 않고 답을 글로만 한다.\n"
            + "artifact_write 도구가 있으면 그것으로 저장한다. conversation_id 에 이 대화 식별자를 넣고 path 는 상대 경로로 쓴다.\n"
            + (sandboxed ? SANDBOX_FOLDER_LINE : FOLDER_FILE_TOOL_LINE)
            + "artifact_write 에서는 HTML 과 CSS 는 content, HTML 이 부를 인터넷의 사진은 source_url 을 쓴다. 둘 중 하나만 넣는다. 파일 하나는 5MB 까지다.\n"
            + "HTML 이 사진을 부를 때는 이 폴더 안의 상대 경로를 쓴다.\n"
            // 위에 폴더 경로가 적혀 있어 모델이 그것을 답에 옮긴다(2026-09-29 운영에서 확인). 결과물은 답 아래에 자동으로 붙는다.
            + "이 폴더 경로와 파일 경로를 답에 쓰지 않는다. 만든 결과물은 답 아래에 자동으로 붙는다.\n"
            + IMAGE_RULES
            + imageTitles(conversation)
            + (sandboxed ? SANDBOX_FILE_SPACE_RULES : FILE_SPACE_RULES)
            + "\n"
            + SkillAgentNotice.PARAGRAPH;
    }


    /**
     * 파일이 남은 만든 그림의 제목 줄이다. 없으면 빈 글이다. 모델이 고칠 그림을 제목으로 가리킬 수 있게 한다.
     *
     * <p>그림이 참고한 링크는 제목 뒤에 붙인다(스펙 generated-image REQ25). {@code link:} 가리킴의 길이 상한을 넘는 링크는 다시 받을 수 없어
     * 적지 않는다.
     */
    private String imageTitles(Conversation conversation) {
        List<GeneratedImageMention> mentions = images.liveMentions(conversation.id(), conversation.userId(), IMAGE_TITLE_LIMIT);
        if (mentions.isEmpty()) {
            return "";
        }
        return "이 대화에서 만든 그림: " + mentions.stream().map(ArtifactService::mention).collect(Collectors.joining(", ")) + "\n";
    }


    private static String mention(GeneratedImageMention image) {
        List<String> links = image.sourceLinks().stream().filter(link -> link.length() <= IMAGE_LINK_MAX_CHARS).toList();
        String title = "「" + image.title() + "」";
        return links.isEmpty() ? title : title + "(참고 링크: " + String.join(", ", links) + ")";
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
        bindChangedHtml(conversationId, messageId, turnStartedAt);
        // HTML 을 묶은 뒤에 본다. 같은 답에 같은 경로의 HTML 줄이 있으면 넣은 파일 줄을 겹쳐 보이지 않는다.
        bindPutFiles(conversationId, messageId);
    }


    /** 바뀐 HTML 을 답에 묶는다. 훑기가 실패하거나 바뀐 것이 없으면 여기서만 멈춘다. */
    private void bindChangedHtml(Long conversationId, Long messageId, Instant turnStartedAt) {
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
                // artifact_write 가 제목을 받아 둔 행이 있으면 그 행을 묶는다. 제목이 보관본으로 옮겨진다.
                if (!writer.bindTitled(conversationId, file.path(), messageId, file.byteSize())) {
                    artifacts.save(ChatArtifact.of(conversationId, messageId, file.path(), file.byteSize()));
                }
            } catch (RuntimeException ex) {
                // 한 파일이 실패해도 나머지는 묶는다. 경로가 칸 길이를 넘는 것도 여기로 온다.
                log.warn("could not record an artifact conversationId={} messageId={}", conversationId, messageId, ex);
            }
        }
    }


    /** 넣기 도구가 넣은 파일을 이 답에 묶는다(ADR-20261119 / file-space-put). 실패해도 밖으로 던지지 않는다. */
    private void bindPutFiles(Long conversationId, Long messageId) {
        try {
            writer.bindPutFiles(conversationId, messageId);
        } catch (RuntimeException ex) {
            log.warn("could not bind put files of a turn conversationId={} messageId={}", conversationId, messageId, ex);
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
