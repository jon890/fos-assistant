package com.bifos.assistant.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.orchestration.domain.DelegationKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** 위임 키가 같은 위임은 같게, 다른 위임은 다르게 나오는 것과 정의를 고정한다. */
class DelegationKeyTest {

    private static DelegationKey key(String profile, String root, String session, String toolCall) {
        return DelegationKey.of(profile, root, session, toolCall);
    }

    @Test
    void 같은_다섯_칸의_재시도는_같은_키이고_값은_소문자_16진수_64자다() {
        DelegationKey first = key("dad", "fos-root", "fos-a", "call-1");
        DelegationKey retry = key("dad", "fos-root", "fos-a", "call-1");

        assertThat(retry).isEqualTo(first);
        assertThat(first.value()).matches("[0-9a-f]{64}");
    }

    @Test
    void session만_다르면_다른_키다() {
        assertThat(key("dad", "fos-root", "fos-a", "call-1"))
                .isNotEqualTo(key("dad", "fos-root", "fos-b", "call-1"));
    }

    @Test
    void root만_다르면_다른_키다() {
        assertThat(key("dad", "fos-root-1", "fos-a", "call-1"))
                .isNotEqualTo(key("dad", "fos-root-2", "fos-a", "call-1"));
    }

    @Test
    void profile만_다르면_다른_키다() {
        assertThat(key("dad", "fos-root", "fos-a", "call-1"))
                .isNotEqualTo(key("mom", "fos-root", "fos-a", "call-1"));
    }

    @Test
    void toolCall만_다르면_다른_키다() {
        assertThat(key("dad", "fos-root", "fos-a", "call-1"))
                .isNotEqualTo(key("dad", "fos-root", "fos-a", "call-2"));
    }

    @Test
    void 값은_v1_다섯_칸을_줄바꿈으로_이은_SHA_256이다() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest("v1\ndad\nfos-root\nfos-a\ncall-1".getBytes(StandardCharsets.UTF_8));

        assertThat(key("dad", "fos-root", "fos-a", "call-1").value())
                .isEqualTo(HexFormat.of().formatHex(digest));
    }

    @Test
    void null이나_빈_칸은_거절한다() {
        assertThatThrownBy(() -> key(null, "fos-root", "fos-a", "call-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> key("dad", "", "fos-a", "call-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> key("dad", "fos-root", "  ", "call-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> key("dad", "fos-root", "fos-a", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 해시가_아닌_문자열로는_직접_만들지_못한다() {
        assertThatThrownBy(() -> new DelegationKey("아무 값"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DelegationKey(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DelegationKey("A".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
