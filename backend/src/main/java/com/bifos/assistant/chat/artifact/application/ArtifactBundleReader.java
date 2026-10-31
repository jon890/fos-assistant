package com.bifos.assistant.chat.artifact.application;

import com.bifos.assistant.chat.artifact.application.dto.ArtifactBundle;
import com.bifos.assistant.chat.artifact.application.dto.ArtifactBundleFile;
import com.bifos.assistant.chat.artifact.application.dto.ArtifactFile;
import com.bifos.assistant.chat.artifact.config.ArtifactVaultProperties;
import com.bifos.assistant.chat.artifact.domain.ArtifactBundleRejectedException;
import com.bifos.assistant.chat.artifact.domain.ArtifactReferences;
import com.bifos.assistant.chat.artifact.infra.ArtifactStore;
import com.bifos.assistant.chat.artifact.infra.ArtifactVaultBodies;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 보관할 파일과 그 파일이 부르는 파일을 대화 폴더에서 찾아 묶음으로 훑고, 저장할 때 다시 읽는다(#530 PR 2).
 *
 * <p>HTML 과 CSS 는 {@link ArtifactReferences} 로 부르는 주소를 찾아 따라간다. 사진은 더 따라가지 않는다. 외부 주소,
 * {@code data:} 주소, 빠진 파일, 받지 않는 형식, 상한 초과는 묶음 전체를 거절한다. 일부만 보관하지 않는다.
 *
 * <p>한 번에 파일 하나만 메모리에 올린다. 파일 하나는 {@code maxFileBytes} 를 넘지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ArtifactBundleReader {

    private final ArtifactService artifacts;


    /**
     * 대화 폴더의 파일 하나에서 시작해 묶음을 훑는다. 본문은 남기지 않고 크기와 해시만 남긴다.
     *
     * @param rootPath 고른 상대 경로({@link ArtifactReferences#normalize} 를 거친 것)
     */
    public ArtifactBundle scan(Long conversationId, String rootPath, ArtifactVaultProperties limits) {
        requireVaultable(rootPath);
        byte[] rootBytes = readRoot(conversationId, rootPath, limits.maxFileBytes());
        String rootFingerprint = ArtifactVaultBodies.fingerprint(conversationId, rootPath, rootBytes);
        ArtifactBundleFile root = new ArtifactBundleFile(rootPath, rootBytes.length, ArtifactVaultBodies.sha256Hex(rootBytes));

        Set<String> seen = new HashSet<>();
        seen.add(rootPath);
        Deque<String> queue = new ArrayDeque<>(references(rootPath, rootBytes));
        List<ArtifactBundleFile> parts = new ArrayList<>();
        long total = root.byteSize();
        while (!queue.isEmpty()) {
            String path = queue.removeFirst();
            if (!seen.add(path)) {
                continue;
            }
            if (parts.size() + 2 > limits.maxBundleFiles()) {
                throw new ArtifactBundleRejectedException("this bundle has too many files");
            }
            if (ArtifactStore.contentTypeOf(path).isEmpty()) {
                throw new ArtifactBundleRejectedException("a file of this bundle is of a kind that is not kept");
            }
            byte[] bytes = readPart(conversationId, path, limits.maxFileBytes());
            total += bytes.length;
            if (total > limits.maxBundleBytes()) {
                throw new ArtifactBundleRejectedException("this bundle is larger than the vault limit");
            }
            parts.add(new ArtifactBundleFile(path, bytes.length, ArtifactVaultBodies.sha256Hex(bytes)));
            queue.addAll(references(path, bytes));
        }
        parts.sort(Comparator.comparing(ArtifactBundleFile::path));
        List<String> entries = parts.stream().map(part -> part.path() + "\0" + part.sha256()).toList();
        return new ArtifactBundle(
            conversationId, root, List.copyOf(parts), ArtifactVaultBodies.bundleFingerprint(rootFingerprint, entries));
    }


    /**
     * 훑은 파일 하나를 저장하려고 다시 읽는다. 사이에 바뀌었거나 지워졌으면 묶음 전체를 거절한다.
     *
     * <p>원본 정리가 보관 도중에 파일을 지워도 일부만 남지 않는 근거다. 부르는 쪽은 이 거절로 트랜잭션을 되돌린다.
     */
    public byte[] reread(Long conversationId, ArtifactBundleFile file, long maxFileBytes) {
        byte[] bytes = readPart(conversationId, file.path(), maxFileBytes);
        if (bytes.length != file.byteSize() || !ArtifactVaultBodies.sha256Hex(bytes).equals(file.sha256())) {
            throw new ArtifactBundleRejectedException("a file of this bundle changed while it was being kept");
        }
        return bytes;
    }


    /** 보관할 수 있는 형식(HTML, CSS, 사진)인지 본다. 형식은 결과물을 내주는 형식과 같다. */
    static void requireVaultable(String path) {
        if (path == null || ArtifactStore.contentTypeOf(path).isEmpty()) {
            throw new ApiException(ErrorCode.ARTIFACT_VAULT_UNSUPPORTED, "only html, css and images are kept");
        }
    }


    /** 확장자로 정한 형식이다. 글자 집합은 뗀다. */
    static String contentTypeOf(String path) {
        String full = ArtifactStore.contentTypeOf(path).orElseThrow();
        int semicolon = full.indexOf(';');
        return (semicolon < 0 ? full : full.substring(0, semicolon)).strip();
    }


    private List<String> references(String path, byte[] bytes) {
        String type = contentTypeOf(path);
        if (!type.startsWith("text/")) {
            return List.of();
        }
        String text = decode(bytes);
        return type.equals("text/css") ? ArtifactReferences.ofCss(path, text) : ArtifactReferences.ofHtml(path, text);
    }


    /** 뿌리는 PR 1 과 같이 없으면 없는 결과물로 답한다. */
    private byte[] readRoot(Long conversationId, String path, long limit) {
        ArtifactFile file = artifacts.find(conversationId, path);
        if (file.byteSize() > limit) {
            throw new ApiException(ErrorCode.ARTIFACT_VAULT_UNSUPPORTED, "this file is larger than the vault limit");
        }
        return read(file, limit);
    }


    /** 부르는 파일이 없거나 보관 기간이 지나 지워졌으면 묶음 전체를 거절한다. */
    private byte[] readPart(Long conversationId, String path, long limit) {
        ArtifactFile file;
        try {
            file = artifacts.find(conversationId, path);
        } catch (ApiException ex) {
            throw missingOr(ex);
        }
        if (file.byteSize() > limit) {
            throw new ArtifactBundleRejectedException("a file of this bundle is larger than the vault limit");
        }
        try {
            return read(file, limit);
        } catch (ApiException ex) {
            throw missingOr(ex);
        }
    }


    private byte[] read(ArtifactFile file, long limit) {
        try (InputStream in = artifacts.open(file)) {
            byte[] bytes = in.readNBytes((int)limit + 1);
            if (bytes.length > limit) {
                throw new ApiException(
                    ErrorCode.ARTIFACT_VAULT_UNSUPPORTED, "this file is larger than the vault limit");
            }
            return bytes;
        } catch (IOException ex) {
            throw new UncheckedIOException("could not read an artifact to vault", ex);
        }
    }


    private static ApiException missingOr(ApiException ex) {
        if (ex.code() == ErrorCode.ARTIFACT_NOT_FOUND || ex.code() == ErrorCode.ARTIFACT_GONE) {
            return new ArtifactBundleRejectedException("a file of this bundle is missing");
        }
        return ex;
    }


    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException ex) {
            throw new ApiException(ErrorCode.ARTIFACT_VAULT_UNSUPPORTED, "this file is not valid UTF-8 text");
        }
    }
}
