package com.bifos.assistant.crypto.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 서버 비밀값을 KEK 로 감싸 {@code server_secret} 에 두고 읽고 쓴다(ADR-20261120 / server-secret).
 *
 * <p>원문을 돌려주는 것은 {@link #read} 하나다. 관리자 화면 경로는 이 인터페이스를 부를 수 없고(구조 규칙
 * {@code ADMIN_USES_ONLY_FEATURE_APPLICATION}), 각 기능의 application 이 상태만 dto 로 낸다.
 *
 * <p>쓰기와 지우기는 부른 쪽의 트랜잭션이 있으면 그 커밋 뒤에, 없으면 저장 직후에 새 값을 읽기에 반영한다.
 */
public interface ServerSecrets {

    /** KEK 가 있어 값을 쓰고 풀 수 있는가. 거짓이면 {@link #write} 가 거절되고 {@link #read} 는 늘 비어 있다 */
    boolean available();


    /** 그 이름의 원문이다. 줄이 없거나 비었거나 풀지 못하면 비어 있다 */
    Optional<String> read(String name);


    /** 원문 없이 그 이름의 상태다 */
    Optional<ServerSecretRecord> find(String name);


    /** 이름이 그 접두로 시작하는 줄의 상태다. 이름 순이다 */
    List<ServerSecretRecord> findByPrefix(String namePrefix);


    /** 줄이 있는가. 값이 비어 있어도 줄이 있으면 참이다. 옛 설정 가져오기가 한 번만 돌게 하는 표지다 */
    boolean exists(String name);


    /**
     * 값을 감싸 쓴다. 값이 빈 줄이나 없는 줄도 덮는다.
     *
     * @param meta 비밀이 아닌 부가 값. 비어 있으면 저장하지 않는다
     * @param updatedBy 바꾼 사용자 번호. 기동 때 가져온 값이면 null
     * @throws com.bifos.assistant.shared.error.ApiException KEK 가 없으면 {@code SECRET_STORE_UNAVAILABLE}
     */
    void write(String name, String value, Map<String, Object> meta, Long updatedBy);


    /** 값을 비운다. 줄은 남긴다. 줄이 없으면 빈 줄을 만든다 */
    void clear(String name, Long updatedBy);
}
