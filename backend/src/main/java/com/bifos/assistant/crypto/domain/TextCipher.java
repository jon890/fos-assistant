package com.bifos.assistant.crypto.domain;

import java.util.Optional;

/**
 * 사용자 본문 칸 하나를 그 사용자의 데이터 key 로 암호화하고 푼다(ADR-20261008 / data-encryption).
 *
 * <p>{@code aad} 는 그 암호문이 놓일 자리를 나타내는 글이다. 이 인터페이스는 그 뜻을 모른다. 부르는 쪽이 표와 줄 번호,
 * 주인을 담아 정한다. 다른 줄로 옮긴 암호문은 열리지 않는다.
 */
public interface TextCipher {

    /** KEK 가 설정돼 새 본문을 암호화하는가 */
    boolean enabled();

    /**
     * 그 사용자의 데이터 key 로 암호화한다. 그 사용자에게 데이터 key 가 아직 없으면 만든다.
     *
     * @return 암호화가 꺼져 있으면 빈 값이다. 부르는 쪽은 평문으로 저장한다
     */
    Optional<SealedText> seal(Long ownerUserId, String aad, String plain);

    /**
     * 암호문을 푼다.
     *
     * @return 풀지 못하면 빈 값이다. key 가 없거나, 데이터 key 의 주인이 {@code ownerUserId} 와 다르거나, 태그가 맞지 않는
     *     경우다. 까닭은 식별자만 담아 경고 로그로 남긴다
     */
    Optional<String> open(Long keyId, Long ownerUserId, String aad, String sealed);
}
