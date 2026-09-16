package com.bifos.assistant.hermes;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Reads the per-profile API server key from host configuration.
 *
 * <p>Keys are never stored in our database. A missing key is an error for that one profile: we do
 * not substitute another profile's key, because that would run one family member's request on
 * another member's AI credential.
 */
@Component
public class HermesProfileKeyStore {

    private static final Pattern PROFILE_NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

    private final Path keyDir;

    public HermesProfileKeyStore(HermesProperties properties) {
        this.keyDir = Path.of(properties.profileKeyDir());
    }

    public String resolve(String profileName) {
        if (profileName == null || !PROFILE_NAME.matcher(profileName).matches()) {
            throw new ApiException(
                    ErrorCode.HERMES_PROFILE_KEY_MISSING, "profile name is not a valid Hermes profile");
        }
        Path keyFile = keyDir.resolve(profileName);
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
}
