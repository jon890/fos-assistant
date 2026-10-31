package com.bifos.assistant.crypto.domain;

import java.util.Optional;

/**
 * 사용자 바이트 본문 하나를 그 사용자의 데이터 key 로 암호화하고 푼다(ADR-20261008 / data-encryption).
 *
 * <p>사진처럼 글이 아닌 본문을 위한 것이다. 모양과 key, {@code aad} 의 뜻은 {@link TextCipher} 와 같다. 글로 암호화한
 * 본문은 그 글의 UTF-8 바이트로 풀린다. 부르는 쪽은 key 원문을 받지 않고 암호문과 key 번호만 받는다.
 */
public interface BinaryCipher {

    /** KEK 가 설정돼 새 본문을 암호화하는가 */
    boolean enabled();


    /**
     * 그 사용자의 데이터 key 로 바이트를 암호화한다. 그 사용자에게 데이터 key 가 아직 없으면 만든다.
     *
     * @return 암호화가 꺼져 있으면 빈 값이다
     */
    Optional<SealedText> sealBytes(Long ownerUserId, String aad, byte[] plain);


    /**
     * 암호문을 바이트로 푼다.
     *
     * @return 풀지 못하면 빈 값이다. 까닭은 {@link TextCipher#open} 과 같다
     */
    Optional<byte[]> openBytes(Long keyId, Long ownerUserId, String aad, String sealed);
}
