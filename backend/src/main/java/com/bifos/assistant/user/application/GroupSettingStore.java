package com.bifos.assistant.user.application;

import com.bifos.assistant.user.domain.GroupSetting;
import com.bifos.assistant.user.domain.GroupSettingId;
import com.bifos.assistant.user.infra.GroupSettingRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code group_setting} 줄을 읽고, 잠그고 읽고, 쓰는 공통 부분이다(ADR-20261102 / table-reduce-r2).
 *
 * <p>값은 문자열로만 다룬다. JSON 의 모양과 버전, 검사는 설정 종류를 쓰는 기능이 갖는다. 줄 전체를 다시 쓰는 저장은
 * {@link #readForUpdate} 로 잠그고 읽은 뒤 {@link #write} 로 쓴다. 그룹에 줄이 아직 없으면 잠글 줄이 없어, 첫 저장 둘이
 * 동시에 오면 한쪽이 중복 key 나 deadlock 오류로 실패한다. 값을 조용히 잃지는 않는다.
 */
@Service
@RequiredArgsConstructor
public class GroupSettingStore {

    private final GroupSettingRepository settings;
    private final Clock clock;


    /** 줄이 없으면 비어 있다. */
    @Transactional(readOnly = true)
    public Optional<String> read(Long groupId, String settingKey) {
        return settings.findById(new GroupSettingId(groupId, settingKey)).map(GroupSetting::settingValue);
    }


    /** 줄을 잠그고 읽는다. 부분만 바꾸는 저장이 동시에 온 다른 저장을 덮지 않게 한다. */
    @Transactional
    public Optional<String> readForUpdate(Long groupId, String settingKey) {
        return settings.findForUpdate(new GroupSettingId(groupId, settingKey)).map(GroupSetting::settingValue);
    }


    /** 줄이 있으면 값을 고치고 없으면 넣는다. */
    @Transactional
    public void write(Long groupId, String settingKey, String settingValue) {
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        settings.findById(new GroupSettingId(groupId, settingKey)).ifPresentOrElse(
            row -> row.change(settingValue, now),
            () -> settings.save(GroupSetting.of(groupId, settingKey, settingValue, now)));
    }
}
