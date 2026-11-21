package com.bifos.assistant.chat.artifact.application;

import com.bifos.assistant.chat.artifact.application.dto.GeneratedImageDetail;
import com.bifos.assistant.chat.artifact.application.dto.GeneratedImageMention;
import com.bifos.assistant.chat.artifact.application.dto.GeneratedImageSaved;
import com.bifos.assistant.chat.artifact.application.dto.GeneratedImageSource;
import com.bifos.assistant.chat.artifact.application.dto.GeneratedImageStored;
import com.bifos.assistant.chat.artifact.config.ArtifactProperties;
import com.bifos.assistant.chat.artifact.domain.ChatArtifact;
import com.bifos.assistant.chat.artifact.domain.GeneratedImageTitle;
import com.bifos.assistant.chat.artifact.domain.type.VaultOrigin;
import com.bifos.assistant.chat.artifact.infra.ArtifactStore;
import com.bifos.assistant.chat.artifact.infra.ArtifactVaultPart;
import com.bifos.assistant.chat.artifact.infra.ChatArtifactRepository;
import com.bifos.assistant.crypto.domain.SealedText;
import com.bifos.assistant.crypto.domain.TextCipher;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * 비서가 만든 그림 하나를 대화의 결과물 폴더에 쓰고 결과물 행으로 남긴다(ADR-20261119 / generated-image).
 *
 * <p>누구의 어느 대화인지는 부르는 쪽({@code mcp})이 origin 실행에서 정해 넘긴다. 이 서비스는 요청 본문에서 그것을 읽지 않는다.
 * 답에 묶는 일은 turn 이 끝날 때 {@link ArtifactService#recordTurn} 이 한다. 새로 저장하면 {@link GeneratedImageStored} 를 내 화면이
 * 답이 끝나기 전에 그림을 그리게 한다.
 *
 * <p>이름 정하기부터 행 만들기까지 대화마다 한 번에 하나만 돈다. 겹침을 그 대화의 그림 행으로 보므로, 둘이 함께 돌면 같은 이름을 고른다.
 * Control Plane 을 한 대만 띄운다는 전제다.
 */
@Service
@Slf4j
public class GeneratedImageService {

    static final String FOLDER = "generated/";
    private static final int LOCK_COUNT = 64;

    private final ArtifactStore store;
    private final ChatArtifactRepository artifacts;
    private final TextCipher cipher;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final long maxBytes;
    private final ReentrantLock[] locks = new ReentrantLock[LOCK_COUNT];


    public GeneratedImageService(
        ArtifactStore store,
        ChatArtifactRepository artifacts,
        TextCipher cipher,
        Clock clock,
        ArtifactProperties properties,
        ApplicationEventPublisher events) {
        this.store = store;
        this.artifacts = artifacts;
        this.cipher = cipher;
        this.clock = clock;
        this.events = events;
        this.maxBytes = properties.generatedImageMaxBytes();
        for (int index = 0; index < LOCK_COUNT; index++) {
            locks[index] = new ReentrantLock();
        }
    }


    /**
     * 그림을 저장한다. 같은 호출의 같은 그림이 다시 오면 처음 저장한 결과를 돌려주고 파일을 다시 쓰지 않는다.
     *
     * @param ownerUserId 그 대화의 요청자. 지시문을 이 사용자의 데이터 key 로 암호화한다
     * @param callKey 올린 호출과 그림을 묶은 값. 되풀이 요청을 한 건으로 만든다
     * @param title 모델이 붙인 제목. 없으면 null 이나 빈 글이다
     * @throws ApiException {@link ErrorCode#VALIDATION_FAILED}. 받지 않는 형식이거나 상한을 넘을 때
     */
    public GeneratedImageSaved save(
        Long conversationId, Long ownerUserId, String callKey, String title, String prompt, byte[] image) {
        return save(conversationId, ownerUserId, callKey, title, prompt, List.of(), image);
    }


    /**
     * 참고한 링크와 함께 그림을 저장한다(ADR-20261120 / link-image-source). 링크는 지시문과 따로 봉하고 key 번호를 자기 칸에 둔다.
     *
     * @param sourceLinks 그림을 만들 때 참고한 링크 주소. 쓴 순서다. 없으면 빈 목록이다
     */
    public GeneratedImageSaved save(
        Long conversationId, Long ownerUserId, String callKey, String title, String prompt, List<String> sourceLinks, byte[] image) {
        String extension = extensionOf(image);
        String path;
        String name;
        ReentrantLock lock = locks[Math.floorMod(conversationId.hashCode(), LOCK_COUNT)];
        lock.lock();
        try {
            Optional<ChatArtifact> existing = artifacts.findByGenerationCallKey(callKey);
            if (existing.isPresent()) {
                return new GeneratedImageSaved(GeneratedImageTitle.ofPath(existing.get().path()));
            }
            name = uniqueName(conversationId, GeneratedImageTitle.clean(title, clock.instant()), extension);
            path = FOLDER + name + "." + extension;
            long byteSize = store.write(conversationId, path, image);
            try {
                SealedText sealed = seal(ownerUserId, aad(ownerUserId, callKey), prompt == null ? "" : prompt);
                SealedText links = sourceLinks.isEmpty()
                    ? new SealedText(null, null)
                    : seal(ownerUserId, linksAad(ownerUserId, callKey), String.join("\n", sourceLinks));
                artifacts.save(ChatArtifact.generatedImage(
                    conversationId, path, byteSize, sealed.content(), sealed.keyId(), links.content(), links.keyId(), callKey));
            } catch (RuntimeException ex) {
                removeQuietly(conversationId, path);
                throw ex;
            }
        } finally {
            lock.unlock();
        }
        // 잠금을 푼 뒤 알린다. 받는 쪽이 SSE 로 보내는 동안 같은 대화의 다음 그림 저장을 막지 않는다.
        events.publishEvent(new GeneratedImageStored(conversationId, path));
        return new GeneratedImageSaved(name);
    }


    /**
     * 답에 묶인 그림마다 지시문과 살아 있는 보관본 번호를 읽는다(스펙 generated-image REQ5, REQ7, REQ9). 그림이 없으면 묻지 않는다.
     *
     * @param ownerUserId 대화 요청자. 지시문의 key 주인이고 보관 주인이다. 부르는 쪽이 그 대화의 주인임을 먼저 확인한다
     * @return 결과물 행 번호마다의 값. 그림이 아닌 행은 없다
     */
    public Map<Long, GeneratedImageDetail> details(Long ownerUserId, Long conversationId, Collection<List<ChatArtifact>> produced) {
        List<ChatArtifact> images = produced.stream()
            .flatMap(List::stream)
            .filter(ChatArtifact::isGeneratedImage)
            .toList();
        if (images.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> vaultIds = new HashMap<>();
        for (ArtifactVaultPart copy : artifacts.findLiveVaultCopies(
            ownerUserId, conversationId, images.stream().map(ChatArtifact::path).toList(), VaultOrigin.PUT_WORKSPACE)) {
            vaultIds.put(copy.getPath(), copy.getId());
        }
        Map<Long, GeneratedImageDetail> details = new HashMap<>();
        for (ChatArtifact image : images) {
            details.put(image.id(), new GeneratedImageDetail(
                promptOf(image, ownerUserId).orElse(null), vaultIds.get(image.path()), sourceLinksOf(image, ownerUserId)));
        }
        return details;
    }


    /**
     * 그 대화에서 만든 그림을 제목으로 찾아 원본 바이트를 읽는다(ADR-20261120 / image-source). 파일은 읽기만 한다.
     *
     * <p>부르는 쪽이 요청자가 그 대화의 주인임을 먼저 확인한다.
     *
     * @throws ApiException {@link ErrorCode#IMAGE_SOURCE_UNKNOWN} 은 그 제목의 그림이 없을 때, {@link ErrorCode#IMAGE_SOURCE_GONE} 은 보관
     * 기간 정리로 파일을 지웠을 때다
     */
    public GeneratedImageSource readByTitle(Long conversationId, String title) {
        ChatArtifact image = findByTitle(conversationId, title);
        Optional<Path> file = image.isDeleted() ? Optional.empty() : store.resolveInside(conversationId, image.path());
        if (file.isEmpty() || !Files.isRegularFile(file.get())) {
            throw gone();
        }
        try {
            if (Files.size(file.get()) > maxBytes) {
                throw gone();
            }
            byte[] bytes = Files.readAllBytes(file.get());
            return new GeneratedImageSource(contentTypeOf(extensionOf(bytes)), bytes);
        } catch (IOException ex) {
            throw gone();
        } catch (ApiException ex) {
            if (ex.code() == ErrorCode.VALIDATION_FAILED) {
                throw gone();
            }
            throw ex;
        }
    }


    /**
     * 그 대화에서 파일이 남은 그림 행의 번호를 제목으로 찾는다. 바이트는 읽지 않는다(ADR-20261120 / generated-image-draft).
     *
     * <p>부르는 쪽이 요청자가 그 대화의 주인임을 먼저 확인한다.
     *
     * @throws ApiException {@link ErrorCode#IMAGE_SOURCE_UNKNOWN} 은 그 제목의 그림이 없을 때, {@link ErrorCode#IMAGE_SOURCE_GONE} 은
     * 보관 기간 정리로 파일을 지웠을 때다
     */
    public Long liveIdByTitle(Long conversationId, String title) {
        ChatArtifact image = findByTitle(conversationId, title);
        Optional<Path> file = image.isDeleted() ? Optional.empty() : store.resolveInside(conversationId, image.path());
        if (file.isEmpty() || !Files.isRegularFile(file.get())) {
            throw gone();
        }
        return image.id();
    }


    private ChatArtifact findByTitle(Long conversationId, String title) {
        return artifacts.findGeneratedImages(conversationId).stream()
            .filter(it -> GeneratedImageTitle.ofPath(it.path()).equals(title))
            .findFirst()
            .orElseThrow(() -> new ApiException(ErrorCode.IMAGE_SOURCE_UNKNOWN, "no generated image has this title"));
    }


    /**
     * 그 대화에서 만든 그림 가운데 파일이 남은 것의 제목과 참고한 링크다. 새것부터 {@code limit} 개까지다(스펙 generated-image REQ17, REQ25).
     *
     * <p>실행 입력에 넣어, 모델이 Hermes 기록을 압축한 뒤에도 고칠 그림을 제목으로 가리키고 이어지는 장면에서 같은 링크를 다시 넣게 한다.
     *
     * @param ownerUserId 대화 주인. 링크의 key 주인이다
     */
    public List<GeneratedImageMention> liveMentions(Long conversationId, Long ownerUserId, int limit) {
        return artifacts.findGeneratedImages(conversationId).stream()
            .filter(it -> !it.isDeleted())
            .limit(limit)
            .map(it -> new GeneratedImageMention(GeneratedImageTitle.ofPath(it.path()), sourceLinksOf(it, ownerUserId)))
            .toList();
    }


    /** 저장한 지시문을 푼다. 풀지 못하면 빈 값이다. 평문으로 둔 지시문은 그대로 돌려준다. */
    public Optional<String> promptOf(ChatArtifact artifact, Long ownerUserId) {
        if (artifact.generationPrompt() == null) {
            return Optional.empty();
        }
        if (artifact.generationPromptKeyId() == null) {
            return Optional.of(artifact.generationPrompt());
        }
        return cipher.open(
            artifact.generationPromptKeyId(), ownerUserId, aad(ownerUserId, artifact.generationCallKey()), artifact.generationPrompt());
    }


    /** 참고한 링크를 푼다. 없거나 풀지 못하면 빈 목록이다. */
    public List<String> sourceLinksOf(ChatArtifact artifact, Long ownerUserId) {
        String stored = artifact.generationSourceLinks();
        if (stored == null) {
            return List.of();
        }
        Optional<String> opened = artifact.generationSourceLinksKeyId() == null
            ? Optional.of(stored)
            : cipher.open(
                artifact.generationSourceLinksKeyId(), ownerUserId, linksAad(ownerUserId, artifact.generationCallKey()), stored);
        return opened.map(text -> text.lines().filter(line -> !line.isBlank()).toList()).orElse(List.of());
    }


    /** 암호화가 꺼져 있으면 평문이다. key 를 풀지 못하면 {@code seal} 이 던지고 평문으로 내려 저장하지 않는다. */
    private SealedText seal(Long ownerUserId, String aad, String plain) {
        return cipher.seal(ownerUserId, aad, plain).orElseGet(() -> new SealedText(plain, null));
    }


    /** 행 번호는 넣을 때 정해지므로 암호문의 자리를 사용자와 호출 값으로 묶는다. 호출 값은 표 전체에서 유일하다. */
    private static String aad(Long ownerUserId, String callKey) {
        return "chat_artifact_prompt:user:" + ownerUserId + ":call:" + callKey;
    }


    /** 참고한 링크의 암호문 자리다. 지시문과 다른 칸이라 끝을 달리한다. */
    private static String linksAad(Long ownerUserId, String callKey) {
        return aad(ownerUserId, callKey) + ":links";
    }


    /**
     * 그 대화의 그림 행(지워진 것 포함)과 겹치지 않는 이름을 고른다. 확장자가 달라도 같은 제목으로 본다.
     *
     * <p>행이 없어도 그 경로에 파일이 이미 있으면 넘긴다. 에이전트가 파일 도구로 같은 이름의 파일을 써 두었을 수 있고, 덮어쓰면 안 된다.
     */
    private String uniqueName(Long conversationId, String base, String extension) {
        Set<String> taken = new HashSet<>();
        for (String path : artifacts.findGeneratedImagePaths(conversationId)) {
            taken.add(GeneratedImageTitle.ofPath(path));
        }
        String name = base;
        int number = 2;
        while (taken.contains(name) || store.resolveInside(conversationId, FOLDER + name + "." + extension).isPresent()) {
            name = base + " " + number;
            number++;
        }
        return name;
    }


    /** 파일 머리 바이트로 형식을 정한다. 올린 쪽이 붙인 이름이나 형식 값을 믿지 않는다. */
    private String extensionOf(byte[] image) {
        if (image == null || image.length == 0) {
            throw invalid("generated image is empty");
        }
        if (image.length > maxBytes) {
            throw invalid("generated image is too large");
        }
        if (startsWith(image, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "png";
        }
        if (startsWith(image, 0, 0xFF, 0xD8, 0xFF)) {
            return "jpg";
        }
        if (startsWith(image, 0, 'R', 'I', 'F', 'F') && startsWith(image, 8, 'W', 'E', 'B', 'P')) {
            return "webp";
        }
        throw invalid("generated image must be PNG, JPEG or WebP");
    }


    private static String contentTypeOf(String extension) {
        return switch (extension) {
            case "png" -> "image/png";
            case "jpg" -> "image/jpeg";
            default -> "image/webp";
        };
    }


    private static ApiException gone() {
        return new ApiException(ErrorCode.IMAGE_SOURCE_GONE, "this generated image is no longer kept");
    }


    private static boolean startsWith(byte[] bytes, int offset, int... expected) {
        if (bytes.length < offset + expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if ((bytes[offset + index] & 0xFF) != expected[index]) {
                return false;
            }
        }
        return true;
    }


    /** 행을 만들지 못한 파일을 지운다. 지우지 못해도 행이 없어 보이지 않고 보관 기간 정리가 지운다. */
    private void removeQuietly(Long conversationId, String path) {
        try {
            store.resolveInside(conversationId, path).ifPresent(file -> {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ex) {
                    log.warn("could not remove a generated image without a row conversationId={}", conversationId, ex);
                }
            });
        } catch (RuntimeException ex) {
            log.warn("could not remove a generated image without a row conversationId={}", conversationId, ex);
        }
    }


    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message);
    }
}
