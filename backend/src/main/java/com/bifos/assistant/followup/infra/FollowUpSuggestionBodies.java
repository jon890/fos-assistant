package com.bifos.assistant.followup.infra;

import com.bifos.assistant.crypto.domain.SealedText;
import com.bifos.assistant.crypto.domain.TextCipher;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 제안 본문({@code follow_up.detail_json})을 사용자 단위로 암호화하고 푼다(ADR-20261008 / data-encryption).
 *
 * <p>AAD 는 사용자와 중복 열쇠로 정한다. 공개 번호는 줄을 넣을 때 정해지므로 암호화할 때는 아직 없고, 중복 열쇠는 사용자 안에서 유일하다.
 * 다른 줄로 옮긴 암호문은 열리지 않는다.
 */
@Component
@RequiredArgsConstructor
public class FollowUpSuggestionBodies {

    private final TextCipher cipher;


    /** 암호화가 꺼져 있으면 key 가 빈 평문을 돌려준다. */
    public SealedText seal(Long userId, String dedupeKey, String plain) {
        return cipher.seal(userId, aad(userId, dedupeKey), plain).orElseGet(() -> new SealedText(plain, null));
    }


    /** 풀지 못하면 빈 값이다. 평문으로 저장한 본문은 그대로 돌려준다. */
    public Optional<String> open(Long userId, String dedupeKey, String stored, Long keyId) {
        if (stored == null) {
            return Optional.empty();
        }
        if (keyId == null) {
            return Optional.of(stored);
        }
        return cipher.open(keyId, userId, aad(userId, dedupeKey), stored);
    }


    private static String aad(Long userId, String dedupeKey) {
        return "follow_up_detail:user:" + userId + ":dedupe:" + dedupeKey;
    }
}
