package com.bifos.assistant.crypto.domain;

import java.util.Optional;

/**
 * 글 하나를 주인(사용자나 그룹)의 데이터 key 로 암호화하고 푼다(ADR-20261103 / memory-encryption).
 *
 * <p>모양과 {@code aad} 의 뜻은 {@link TextCipher} 와 같다. 사용자 주인으로 암호화한 글은 {@link TextCipher} 로도 풀린다.
 */
public interface OwnedTextCipher {

    /** KEK 가 설정돼 새 글을 암호화하는가 */
    boolean enabled();


    /**
     * 그 주인의 데이터 key 로 암호화한다. 주인에게 데이터 key 가 아직 없으면 만든다.
     *
     * @return 암호화가 꺼져 있으면 빈 값이다
     * @throws IllegalStateException 데이터 key 를 풀지 못할 때. 부르는 쪽은 평문으로 내려 저장하지 않는다
     */
    Optional<SealedText> seal(KeyOwner owner, String aad, String plain);


    /**
     * 암호문을 푼다.
     *
     * @return 풀지 못하면 빈 값이다. 데이터 key 의 주인이 {@code owner} 와 다를 때도 같다
     */
    Optional<String> open(Long keyId, KeyOwner owner, String aad, String sealed);


    /**
     * 그 주인의 데이터 key 에서 쓰임마다 따로 뽑은 key 로 글의 HMAC-SHA256 을 낸다. 같은 글이 같은 값이 되어야 하는 칸(제안 중복 키, 임베딩
     * 지문)에 평문의 해시를 남기지 않으려고 쓴다(ADR-20261103 / memory-encryption). 주인에게 데이터 key 가 아직 없으면 만든다.
     *
     * @param purpose 쓰임의 이름. 쓰임이 다르면 같은 글도 다른 값이 된다
     * @return 32바이트. 암호화가 꺼져 있으면 빈 값이다
     * @throws IllegalStateException 데이터 key 를 풀지 못할 때
     */
    Optional<byte[]> mac(KeyOwner owner, String purpose, String text);
}
