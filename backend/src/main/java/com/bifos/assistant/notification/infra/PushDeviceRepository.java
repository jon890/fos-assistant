package com.bifos.assistant.notification.infra;

import com.bifos.assistant.user.domain.AppUser;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * {@code app_user.push_devices} 를 읽고 쓰는 전용 쿼리다(ADR-20261120 / web-push). 쓰는 쪽이 먼저 사용자 행을 잠근다.
 *
 * <p>{@link AppUser} 는 이 칸을 넣기와 고치기에서 뺀다. 일반 사용자 저장이 먼저 읽어 둔 옛 값으로 기기를 덮지 않게 하려는 것이다. 칸을 바꿔도
 * 일반 감사 {@code updated_at} 은 바꾸지 않는다. 백그라운드 정리가 사용자의 마지막 변경 시각을 바꾸지 않게 한다.
 */
public interface PushDeviceRepository extends Repository<AppUser, Long> {

    /** 그 사용자 행을 잠근다. 잠근 행의 번호를 돌려주고 사용자가 없으면 빈 값이다. */
    @Query(nativeQuery = true, value = "SELECT id FROM app_user WHERE id = :userId FOR UPDATE")
    Optional<Long> lockUser(@Param("userId") Long userId);


    /** DB 의 지금 글을 읽는다. 사용자가 없거나 칸이 비면 빈 값이다. */
    @Query(nativeQuery = true, value = "SELECT push_devices FROM app_user WHERE id = :userId")
    Optional<String> findDevicesJson(@Param("userId") Long userId);


    /** 그 주소 해시를 가진 사용자의 번호를 번호 순으로 찾는다. 해시는 hex 라 LIKE 의 특수 문자가 없다. */
    @Query(nativeQuery = true, value = "SELECT id FROM app_user WHERE push_devices LIKE CONCAT('%', :hash, '%') ORDER BY id")
    List<Long> findUserIdsHoldingHash(@Param("hash") String hash);


    /** 글을 쓴다. null 이면 칸을 비운다. 고친 줄 수를 돌려준다. */
    @Modifying(flushAutomatically = true)
    @Query(nativeQuery = true, value = "UPDATE app_user SET push_devices = :json WHERE id = :userId")
    int storeDevices(@Param("userId") Long userId, @Param("json") String json);
}
