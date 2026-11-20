package com.bifos.assistant.memory.application;

import com.bifos.assistant.memory.application.dto.MemoryContextBudget;
import com.bifos.assistant.memory.application.port.MemoryContextBudgets;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.application.GroupSettingStore;
import com.bifos.assistant.user.domain.GroupSetting;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 그룹의 기억 요약 구역 예산과 기억 전체 상한을 {@code group_setting} 의 {@code MEMORY_CONTEXT} 줄 하나로 읽고 쓴다
 * ({@code docs/features/memory/design.md} 의 「그룹 설정 {@code MEMORY_CONTEXT}」).
 *
 * <p>JSON 은 {@code {"version":1,"summaryMaxChars":4000,"maxChars":10000}} 이다. 줄이 없는 그룹은 {@link MemoryContextBudget#DEFAULTS}
 * 다. 읽을 때 모르는 버전이거나 형식이 깨졌으면 기본값으로 돌고 경고를 남긴다(스펙 DECISION19). 오류로 올리면 그 그룹의 모든 실행이 멈추고, 기본값으로
 * 돌아도 권한이 풀리지 않는다.
 */
@Slf4j
@Service
public class MemoryContextSettings implements MemoryContextBudgets {

    /** 지금 쓰는 JSON 의 버전이다. 모양을 바꾸면 올리고, 옛 버전을 읽는 길을 함께 둔다. */
    static final int VERSION = 1;

    /** 관리자가 정할 수 있는 요약 구역 예산의 범위다. */
    public static final long SUMMARY_MIN = 1_000;

    public static final long SUMMARY_MAX = 20_000;

    /** 관리자가 정할 수 있는 기억 전체 상한의 범위다. */
    public static final long TOTAL_MIN = 4_000;

    public static final long TOTAL_MAX = 40_000;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final GroupSettingStore settings;

    /** 조립이 색인 몫으로 먼저 떼어 두는 비율이다. 조립과 같은 설정 키를 읽는다. */
    private final int indexBudgetRatio;


    public MemoryContextSettings(
        GroupSettingStore settings, @Value("${assistant.context.index-budget-ratio:4}") int indexBudgetRatio) {
        this.settings = settings;
        this.indexBudgetRatio = indexBudgetRatio > 0 ? indexBudgetRatio : 4;
    }


    @Override
    @Transactional(readOnly = true)
    public MemoryContextBudget budgetFor(Long groupId) {
        if (groupId == null) {
            return MemoryContextBudget.DEFAULTS;
        }
        return settings.read(groupId, GroupSetting.MEMORY_CONTEXT)
            .map(value -> decode(groupId, value))
            .orElse(MemoryContextBudget.DEFAULTS);
    }


    /**
     * 요청자 그룹의 예산을 바꾼다. 줄을 잠그고 읽은 뒤 줄 전체를 쓴다.
     *
     * <p>요약 구역은 색인 몫을 뗀 나머지 안에 담기므로 요약 예산이 그보다 크면 실제로 담기지 않는다(설계 DECISION10).
     *
     * @throws ApiException 관리자가 아니면 FORBIDDEN, 범위를 벗어나면 VALIDATION_FAILED
     */
    @Transactional
    public MemoryContextBudget save(CurrentUser user, long summaryMaxChars, long maxChars) {
        if (!user.isAdmin()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "this action is limited to the group admin");
        }
        requireRange(summaryMaxChars, maxChars);
        settings.readForUpdate(user.groupId(), GroupSetting.MEMORY_CONTEXT);
        String value = JSON.writeValueAsString(new Stored(VERSION, summaryMaxChars, maxChars));
        settings.write(user.groupId(), GroupSetting.MEMORY_CONTEXT, value);
        log.info(
            "memory context budget saved groupId={} summaryMaxChars={} maxChars={}",
            user.groupId(),
            summaryMaxChars,
            maxChars);
        return new MemoryContextBudget(summaryMaxChars, maxChars);
    }


    /** 요약 구역 예산의 상한이다. 전체 상한에서 색인 몫을 뺀 자리다. */
    public long summaryCeiling(long maxChars) {
        return Math.min(SUMMARY_MAX, maxChars - maxChars / indexBudgetRatio);
    }


    private void requireRange(long summaryMaxChars, long maxChars) {
        if (maxChars < TOTAL_MIN || maxChars > TOTAL_MAX) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "maxChars is out of range");
        }
        if (summaryMaxChars < SUMMARY_MIN || summaryMaxChars > summaryCeiling(maxChars)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "summaryMaxChars is out of range");
        }
    }


    private static MemoryContextBudget decode(Long groupId, String value) {
        try {
            Stored stored = JSON.readValue(value, Stored.class);
            if (stored != null && stored.version() != null && stored.version() == VERSION
                && stored.summaryMaxChars() != null && stored.maxChars() != null
                && stored.summaryMaxChars() >= 0 && stored.maxChars() >= 0) {
                return new MemoryContextBudget(stored.summaryMaxChars(), stored.maxChars());
            }
        } catch (JacksonException ex) {
            // 아래에서 경고를 남기고 기본값으로 돈다
        }
        log.warn("memory context setting is not readable, defaults are used groupId={}", groupId);
        return MemoryContextBudget.DEFAULTS;
    }


    /** 저장하는 JSON 의 모양이다. 칸 이름이 저장되므로 바꾸려면 버전을 올린다. */
    private record Stored(Integer version, Long summaryMaxChars, Long maxChars) {
    }
}
