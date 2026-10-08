package com.bifos.assistant.crypto.domain;

/**
 * 사용자별 데이터 key 를 감싸고 푸는 마스터 key(KEK)다(ADR-20261008 / data-encryption).
 *
 * <p>KEK 원문은 이 인터페이스 밖으로 나가지 않는다. 감싸기와 풀기만 연다. 지금 구현은 서버 파일에서 읽는
 * {@code FileKeyEncryptionKeys} 하나이고, 클라우드 KMS 구현도 같은 두 동작으로 들어올 수 있다.
 */
public interface KeyEncryptionKeys {

    /** KEK 가 설정됐는가. 아니면 새 데이터 key 를 만들지 못하고 감싼 key 를 풀지 못한다. */
    boolean available();

    /** 새로 감쌀 때 쓰는 KEK 의 id. {@link #available()} 이 거짓이면 빈 글이다 */
    String activeKeyId();

    /** 그 id 의 KEK 를 갖고 있는가. 옛 KEK 를 목록에서 뺐으면 거짓이다 */
    boolean has(String keyId);

    /** 데이터 key 를 활성 KEK 로 감싼다. {@code aad} 는 그 key 의 주인을 묶는다 */
    String wrap(byte[] dataKey, byte[] aad);

    /** 감싼 데이터 key 를 푼다. KEK 가 없거나 주인이 다르면 {@link IllegalStateException} 이다 */
    byte[] unwrap(String keyId, String wrapped, byte[] aad);
}
