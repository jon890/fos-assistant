package com.bifos.assistant.orchestration.infra;

import com.bifos.assistant.crypto.domain.SealedText;
import com.bifos.assistant.crypto.domain.TextCipher;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 하위 에이전트 등록 줄의 글 칸(맡긴 일 앞부분과 결과 글)을 저장할 때 암호화하고 읽을 때 푼다(ADR-20261117 / native-delegation-wake).
 *
 * <p>AAD 는 {@code hermes_session_binding:<줄 번호>:<칸>:user:<사용자>} 다. 두 칸이 key 칸 하나({@code result_key_id})를 함께 쓴다.
 * 암호화가 꺼져 있으면 봉하기가 빈 값이고 부르는 쪽이 평문과 빈 key 로 저장한다. key 가 빈 글은 평문으로 읽는다.
 */
@Component
@RequiredArgsConstructor
public class SubagentResultTexts {

    /** 맡긴 일 앞부분의 칸 이름이다. */
    public static final String GOAL_PREVIEW = "goal_preview";

    /** 결과 글의 칸 이름이다. */
    public static final String RESULT_TEXT = "result_text";

    private final TextCipher cipher;


    /**
     * 그 줄의 칸 글을 그 사용자의 데이터 key 로 봉한다.
     *
     * @return 암호화가 꺼져 있으면 빈 값이다. 부르는 쪽은 평문으로 저장한다
     */
    public Optional<SealedText> seal(Long bindingId, Long userId, String column, String plain) {
        return cipher.seal(userId, aad(bindingId, userId, column), plain);
    }


    /**
     * 저장된 칸 글을 푼다. key 가 비었으면 평문이다.
     *
     * @return 글이 비었거나 풀지 못하면 빈 값이다
     */
    public Optional<String> open(Long bindingId, Long userId, String column, Long keyId, String stored) {
        if (stored == null) {
            return Optional.empty();
        }
        if (keyId == null) {
            return Optional.of(stored);
        }
        return cipher.open(keyId, userId, aad(bindingId, userId, column), stored);
    }


    private static String aad(Long bindingId, Long userId, String column) {
        return "hermes_session_binding:" + bindingId + ":" + column + ":user:" + userId;
    }
}
