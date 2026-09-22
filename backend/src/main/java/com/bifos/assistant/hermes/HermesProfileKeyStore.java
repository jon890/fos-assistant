package com.bifos.assistant.hermes;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * profile 별 API server key 를 호스트 설정에서 읽고 쓴다.
 *
 * <p>key 는 데이터베이스에 두지 않는다. 어느 profile 의 key 가 없는 것은 그 profile 하나의 오류다.
 * 다른 profile 의 key 로 대신하지 않는다. 대신하면 한 가족 구성원의 요청이 다른 구성원의 AI
 * credential 로 돌아간다.
 *
 * <p>읽는 규칙과 쓰는 규칙을 한 파일에 둔다. 파일 이름 규칙이 두 곳으로 갈리면 쓴 자리를 읽는 쪽이
 * 찾지 못한다.
 */
@Component
public class HermesProfileKeyStore {

    /** 주인만 읽고 쓴다. 이 컨테이너 안의 다른 프로세스에게도 열지 않는다. */
    private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

    private final Path keyDir;

    public HermesProfileKeyStore(HermesProperties properties) {
        this.keyDir = Path.of(properties.profileKeyDir());
    }

    public String resolve(String profileName) {
        Path keyFile = keyFile(profileName, ErrorCode.HERMES_PROFILE_KEY_MISSING);
        String key;
        try {
            key = Files.exists(keyFile) ? Files.readString(keyFile).trim() : "";
        } catch (IOException ex) {
            throw new ApiException(
                    ErrorCode.HERMES_PROFILE_KEY_MISSING, "cannot read the key for this profile", ex);
        }
        if (key.isEmpty()) {
            throw new ApiException(
                    ErrorCode.HERMES_PROFILE_KEY_MISSING, "no API key is provisioned for this profile");
        }
        return key;
    }

    /**
     * 그 profile 의 key 파일을 새로 만든다.
     *
     * <p>이미 있으면 덮지 않고 오류로 끝낸다. 덮으면 그 profile 로 돌던 대화가 다음 요청부터 401 을
     * 받는데, 그 원인이 이 자리에 드러나지 않는다.
     */
    public void write(String profileName, String key) {
        if (key == null || key.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "a profile key cannot be empty");
        }
        Path keyFile = keyFile(profileName, ErrorCode.VALIDATION_FAILED);
        try {
            Files.createDirectories(keyDir);
            Files.createFile(keyFile, OWNER_ONLY);
            Files.writeString(keyFile, key);
        } catch (FileAlreadyExistsException ex) {
            throw new ApiException(
                    ErrorCode.HERMES_PROVISION_FAILED, "a key file for this profile already exists", ex);
        } catch (IOException | UnsupportedOperationException ex) {
            throw new ApiException(
                    ErrorCode.HERMES_PROVISION_FAILED, "cannot write the key for this profile", ex);
        }
    }

    /** 되돌릴 때 쓴다. 파일이 없으면 그냥 지나간다. */
    public void delete(String profileName) {
        Path keyFile = keyFile(profileName, ErrorCode.VALIDATION_FAILED);
        try {
            Files.deleteIfExists(keyFile);
        } catch (IOException ex) {
            throw new ApiException(
                    ErrorCode.HERMES_PROVISION_FAILED, "cannot remove the key for this profile", ex);
        }
    }

    /**
     * profile 이름이 규칙에 맞는지 보고 key 파일 경로를 만든다.
     *
     * <p>읽을 때와 쓸 때가 같은 규칙을 쓴다. 규칙을 통과하지 않은 이름은 경로를 만들지 않으므로 key
     * 디렉터리 밖으로 나갈 수 없다. 규칙 자체는 {@link HermesProfileName} 이 갖는다. 대시보드 경로에
     * 이름을 넣는 자리와 같은 것을 쓴다.
     */
    private Path keyFile(String profileName, ErrorCode code) {
        if (!HermesProfileName.isValid(profileName)) {
            throw new ApiException(code, "profile name is not a valid Hermes profile");
        }
        return keyDir.resolve(profileName);
    }
}
