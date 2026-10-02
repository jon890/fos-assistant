package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.domain.ModelHidden;
import com.bifos.assistant.chat.infra.ModelHiddenRepository;
import com.bifos.assistant.model.domain.ModelChoice;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 그룹이 숨긴 provider 와 모델을 읽고 저장한다.
 *
 * <p>숨김은 고를 수 있는 목록, 저장 검증, 실행 직전 검증에 같은 규칙으로 걸린다. 숨긴 모델을 가리키는
 * 대화와 정의는 다른 모델로 바꾸지 않고 {@code MODEL_HIDDEN} 으로 거절한다(ADR-054).
 */
@Service
@RequiredArgsConstructor
public class ModelVisibilityService {

    private final ModelHiddenRepository hidden;

    @Transactional(readOnly = true)
    public HiddenModels hiddenFor(Long groupId) {
        return new HiddenModels(hidden.findByGroupIdOrderByProviderAscModelAsc(groupId).stream()
                .map(row -> new HiddenModels.Entry(row.provider(), row.wholeProvider() ? null : row.model()))
                .toList());
    }

    /** 명시한 provider 와 모델이 숨겨졌으면 거절한다. 모델을 고르지 않은 선택은 통과한다. */
    @Transactional(readOnly = true)
    public void requireVisible(Long groupId, ModelChoice choice) {
        if (choice == null || choice.usesDefaultModel()) {
            return;
        }
        if (hiddenFor(groupId).hides(choice.provider(), choice.model())) {
            throw new ApiException(ErrorCode.MODEL_HIDDEN, "the selected model is hidden for this group");
        }
    }

    /**
     * 숨김 목록 전체를 바꾼다.
     *
     * <p>형식만 본다. 아직 목록에 나오지 않은 provider 와 모델도 미리 숨길 수 있어야 하므로 catalog 와
     * 견주지 않는다. 같은 항목이 두 번 오면 하나로 합친다. 판정은 대소문자를 구분한다.
     */
    @Transactional
    public void save(CurrentUser user, List<HiddenModels.Entry> entries) {
        if (!user.isAdmin()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "this action is limited to the group admin");
        }
        // 유일 제약은 DB 의 비교 규칙을 따라 대소문자를 가리지 않을 수 있다. 대소문자만 다른 항목은 먼저 온 것만 남긴다.
        Map<String, HiddenModels.Entry> normalized = new LinkedHashMap<>();
        for (HiddenModels.Entry entry : entries) {
            HiddenModels.Entry clean = normalize(entry);
            String key =
                    (clean.provider() + "\n" + (clean.model() == null ? "" : clean.model())).toLowerCase(Locale.ROOT);
            normalized.putIfAbsent(key, clean);
        }
        hidden.deleteByGroupId(user.groupId());
        hidden.flush();
        hidden.saveAll(normalized.values().stream()
                .map(entry -> ModelHidden.of(user.groupId(), entry.provider(), entry.model()))
                .toList());
    }

    private static HiddenModels.Entry normalize(HiddenModels.Entry entry) {
        String provider = entry == null || entry.provider() == null
                ? ""
                : entry.provider().strip();
        String model =
                entry == null || entry.model() == null ? "" : entry.model().strip();
        if (provider.isEmpty()
                || provider.length() > ModelChoice.PROVIDER_MAX_LENGTH
                || model.length() > ModelChoice.MODEL_MAX_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "invalid hidden model entry");
        }
        return new HiddenModels.Entry(provider, model.isEmpty() ? null : model);
    }
}
