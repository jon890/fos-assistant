package com.bifos.assistant.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.bifos.assistant.crypto.domain.KeyEncryptionKeys;
import com.bifos.assistant.crypto.infra.DatabaseServerSecrets;
import com.bifos.assistant.crypto.infra.ServerSecretRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** KEK 가 없는 설치에서 서버 비밀값을 원문으로 두는 길이 없는지 확인한다. 공유 컨텍스트에서는 KEK 를 끌 수 없어 직접 만든다. */
class DatabaseServerSecretsWithoutKekTest {

    @Test
    @DisplayName("KEK 가 없으면 쓰기를 SECRET_STORE_UNAVAILABLE 로 거절하고 줄을 만들지 않는다")
    void rejectsWriteWithoutKek() {
        ServerSecretRepository rows = mock(ServerSecretRepository.class);
        KeyEncryptionKeys keks = mock(KeyEncryptionKeys.class);
        DatabaseServerSecrets secrets = new DatabaseServerSecrets(rows, keks);

        assertThat(secrets.available()).isFalse();
        assertThatThrownBy(() -> secrets.write("browser_gateway", "value-without-kek-000", Map.of(), 1L))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.SECRET_STORE_UNAVAILABLE));
        verify(rows, never()).save(any());
        assertThat(secrets.rewrapStale()).isZero();
    }
}
