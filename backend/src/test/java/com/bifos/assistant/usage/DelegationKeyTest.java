package com.bifos.assistant.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.usage.domain.DelegationKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 위임 키가 같은 위임은 같게, 다른 위임은 다르게 나오는 것과 정의를 고정한다. */
class DelegationKeyTest {

    private static DelegationKey key(String profile, String root, String session, String toolCall) {
        return DelegationKey.of(profile, root, session, toolCall);
    }

    @Test
    @DisplayName("같은 다섯 칸의 재시도는 같은 키이고 값은 소문자 16진수 64자다")
    void retryOfSameFiveFieldsIsSameKeyAndValueIsLowerHex64() {
        DelegationKey first = key("dad", "fos-root", "fos-a", "call-1");
        DelegationKey retry = key("dad", "fos-root", "fos-a", "call-1");

        assertThat(retry).isEqualTo(first);
        assertThat(first.value()).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("session만 다르면 다른 키다")
    void differsWhenOnlySessionDiffers() {
        assertThat(key("dad", "fos-root", "fos-a", "call-1")).isNotEqualTo(key("dad", "fos-root", "fos-b", "call-1"));
    }

    @Test
    @DisplayName("root만 다르면 다른 키다")
    void differsWhenOnlyRootDiffers() {
        assertThat(key("dad", "fos-root-1", "fos-a", "call-1"))
                .isNotEqualTo(key("dad", "fos-root-2", "fos-a", "call-1"));
    }

    @Test
    @DisplayName("profile만 다르면 다른 키다")
    void differsWhenOnlyProfileDiffers() {
        assertThat(key("dad", "fos-root", "fos-a", "call-1")).isNotEqualTo(key("mom", "fos-root", "fos-a", "call-1"));
    }

    @Test
    @DisplayName("toolCall만 다르면 다른 키다")
    void differsWhenOnlyToolCallDiffers() {
        assertThat(key("dad", "fos-root", "fos-a", "call-1")).isNotEqualTo(key("dad", "fos-root", "fos-a", "call-2"));
    }

    @Test
    @DisplayName("값은 v1 다섯 칸을 줄바꿈으로 이은 SHA 256이다")
    void valueIsSha256OfV1FiveFieldsJoinedByNewline() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest("v1\ndad\nfos-root\nfos-a\ncall-1".getBytes(StandardCharsets.UTF_8));

        assertThat(key("dad", "fos-root", "fos-a", "call-1").value())
                .isEqualTo(HexFormat.of().formatHex(digest));
    }

    @Test
    @DisplayName("고정 입력 넷의 키는 64글자 고정 값과 같다")
    void fixedInputsProduceFixedKeys() {
        assertThat(key("dad", "fos-root", "fos-a", "call-1").value())
                .isEqualTo("d585e62e29b01440571487c71bb84ea4c611f33d4861f3c9ab5b5b4393970fe5");
        assertThat(key("mom", "fos-root-2", "fos-b", "call-2").value())
                .isEqualTo("6a91578d1e8f44ae5ceb19fdca31c46dd2eb170371429e3de505e7226cf7cd39");
        assertThat(key("가", "나", "다", "라").value())
                .isEqualTo("abbbd64fd1a5fd555dc7e31d9d3a72b86b0ac96cf2821094e4be4cce84d1a2c1");
        assertThat(key("x", "y", "z", "w").value())
                .isEqualTo("ac30f9e596b772bab0e91e57e5663aee34d4c005f957a4ebccae87c0cc3e8761");
    }

    @Test
    @DisplayName("null이나 빈 칸은 거절한다")
    void rejectsNullOrBlankFields() {
        assertThatThrownBy(() -> key(null, "fos-root", "fos-a", "call-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> key("dad", "", "fos-a", "call-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> key("dad", "fos-root", "  ", "call-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> key("dad", "fos-root", "fos-a", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("해시가 아닌 문자열로는 직접 만들지 못한다")
    void cannotBeCreatedDirectlyFromNonHashString() {
        assertThatThrownBy(() -> new DelegationKey("아무 값")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DelegationKey(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DelegationKey("A".repeat(64))).isInstanceOf(IllegalArgumentException.class);
    }
}
