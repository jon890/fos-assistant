package com.bifos.assistant.agent.application;

import com.bifos.assistant.agent.domain.ProviderState;
import com.bifos.assistant.agent.infra.ProviderStateRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 지금 막힌 provider 가 무엇인지 안다.
 *
 * <p>여기서 다루는 것은 **그 provider 의 계정이 전부 막힌 상태**다. 계정 하나가 막힌 것은 Hermes 가
 * 같은 provider 의 다음 계정으로 옮겨 처리하므로 우리에게 오지 않는다. 계정을 돌려 쓰는 층을 여기에
 * 만들지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ProviderBlocklist {

    private static final Logger log = LoggerFactory.getLogger(ProviderBlocklist.class);

    private final ProviderStateRepository states;
    private final ModelSelectionProperties properties;

    /** 지금 막혀 있는 provider 이름들. */
    public Set<String> blockedProviders() {
        return blocked().stream().map(ProviderState::provider).collect(Collectors.toSet());
    }

    /** 지금 막혀 있는 줄을 provider 이름 순서로 준다. 막힌 것이 없으면 빈 목록이다. */
    public List<ProviderState> blocked() {
        return states.findByBlockedUntilAfterOrderByProviderAsc(Instant.now());
    }

    /** 이 provider 를 식는 시간만큼 막는다. */
    public void block(String provider, String reason) {
        Instant until = Instant.now().plus(properties.providerCooldown());
        ProviderState state = states.findById(provider)
                .map(existing -> {
                    existing.blockUntil(until, reason);
                    return existing;
                })
                .orElseGet(() -> ProviderState.blocked(provider, until, reason));
        states.save(state);
        log.info("provider 가 막힌 것으로 보인다 provider={} until={}", provider, until);
    }

    /**
     * 이 provider 의 막힘을 푼다.
     *
     * <p>식는 시간이 남아 있어도 실제로 실행이 성공했으면 막히지 않은 것이다.
     */
    public void release(String provider) {
        states.findById(provider).ifPresent(state -> {
            if (state.blockedAt(Instant.now())) {
                state.release(Instant.now());
                states.save(state);
                log.info("provider 의 막힘이 풀렸다 provider={}", provider);
            }
        });
    }
}
