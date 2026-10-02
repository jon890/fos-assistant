package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.domain.ModelTierDefinition;
import com.bifos.assistant.model.domain.type.ModelTier;
import com.bifos.assistant.chat.infra.ModelTierDefinitionRepository;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 환경 변수로 주던 단계 초기값을 기동할 때 DB 로 한 번 옮긴다(ADR-054).
 *
 * <p>정의 행이 하나도 없는 그룹에만 쓴다. 관리자가 저장한 정의는 건드리지 않으므로 여러 번 기동해도 결과가
 * 같다. 환경 변수에 모델이 하나도 없으면 아무것도 하지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelTierSeedImporter implements ApplicationRunner {

    private final ModelTierProperties properties;
    private final ModelTierDefinitionRepository definitions;
    private final AppUserRepository users;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.hasAnyMapping()) {
            return;
        }
        for (Long groupId : users.findDistinctGroupIds()) {
            if (!definitions.findByGroupIdOrderByTier(groupId).isEmpty()) {
                continue;
            }
            definitions.saveAll(Arrays.stream(ModelTier.values())
                    .map(tier -> {
                        ModelTierProperties.Tier mapping = properties.forTier(tier);
                        return ModelTierDefinition.of(
                                groupId, tier, mapping.provider(), mapping.model(), mapping.reasoningEffort());
                    })
                    .toList());
            log.info("단계 초기값을 설정에서 DB 로 옮겼다 groupId={}", groupId);
        }
    }
}
