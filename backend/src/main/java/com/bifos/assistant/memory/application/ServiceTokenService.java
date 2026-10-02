package com.bifos.assistant.memory.application;

import com.bifos.assistant.memory.application.model.IssuedServiceToken;
import com.bifos.assistant.memory.application.model.ServiceTokenGrant;
import com.bifos.assistant.memory.application.model.ServiceTokenSnapshot;
import com.bifos.assistant.memory.domain.MemoryPlacement;
import com.bifos.assistant.memory.domain.ServiceToken;
import com.bifos.assistant.memory.domain.ServiceTokenCollection;
import com.bifos.assistant.memory.infra.ServiceTokenCollectionRepository;
import com.bifos.assistant.memory.infra.ServiceTokenRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.UserAccessRevoked;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.util.Sha256;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 다른 서비스가 사용자의 문서를 읽을 때 쓰는 토큰을 발급하고 폐기한다(ADR-056).
 *
 * <p>발급과 폐기는 늘 요청자 자신의 토큰만 다룬다. 관리자 경로는 없다. 만료는 발급할 때 1일에서 365일 사이로 정하고
 * 고치지 않는다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ServiceTokenService {

    private static final String PREFIX = "fos_svc_";
    private static final int TOKEN_BYTES = 32;
    private static final int MAX_EXPIRES_IN_DAYS = 365;
    private static final int MAX_GRANTS = 20;

    private final ServiceTokenRepository tokens;
    private final ServiceTokenCollectionRepository tokenCollections;
    private final MemoryService memories;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    /**
     * 토큰을 발급한다. 원문은 이 응답에만 있고 저장하는 것은 해시뿐이다.
     *
     * @throws ApiException 만료 일수나 collection 이 틀리면 VALIDATION_FAILED
     */
    @Transactional
    public IssuedServiceToken issue(CurrentUser user, String label, int expiresInDays, List<ServiceTokenGrant> grants) {
        requireValid(user, expiresInDays, grants);
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String raw = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        ServiceToken token = tokens.save(
                ServiceToken.issue(user.id(), Sha256.hex(raw), label, now.plus(Duration.ofDays(expiresInDays)), now));
        tokenCollections.saveAll(grants.stream()
                .map(grant -> ServiceTokenCollection.of(token.id(), grant.collection(), grant.allowSensitive()))
                .toList());
        log.info("service token issued userId={} tokenId={} expiresInDays={}", user.id(), token.id(), expiresInDays);
        return new IssuedServiceToken(new ServiceTokenSnapshot(token, List.copyOf(grants)), raw);
    }

    /** 요청자의 토큰을 최근 것부터 낸다. 폐기되거나 만료된 것도 낸다. */
    public List<ServiceTokenSnapshot> listOf(CurrentUser user) {
        List<ServiceToken> owned = tokens.findByUserIdOrderByIdDesc(user.id());
        Map<Long, List<ServiceTokenGrant>> grants =
                grantsOf(owned.stream().map(ServiceToken::id).toList());
        return owned.stream()
                .map(token -> new ServiceTokenSnapshot(token, grants.getOrDefault(token.id(), List.of())))
                .toList();
    }

    /** 요청자 자신의 토큰을 폐기한다. 줄은 지우지 않는다. 없는 토큰과 남의 토큰은 같은 응답이다. */
    @Transactional
    public void revoke(CurrentUser user, Long id) {
        ServiceToken token = tokens.findById(id)
                .filter(found -> found.userId().equals(user.id()))
                .orElseThrow(() -> new ApiException(ErrorCode.SERVICE_TOKEN_NOT_FOUND, "no such service token"));
        token.revoke(clock.instant());
        tokens.save(token);
        log.info("service token revoked userId={} tokenId={}", user.id(), token.id());
    }

    /**
     * 허용 목록에서 꺼진 사용자의 토큰을 모두 폐기한다. 다시 켜도 되살리지 않는다(ADR-056).
     *
     * <p>끈 요청 안에서 동기로 돈다. 폐기가 실패하면 그 요청이 실패로 보인다.
     */
    @EventListener
    @Transactional
    public void revokeAllOf(UserAccessRevoked event) {
        Instant now = clock.instant();
        List<ServiceToken> live = tokens.findByUserIdAndRevokedAtIsNull(event.userId());
        live.forEach(token -> token.revoke(now));
        tokens.saveAll(live);
        log.info("service tokens revoked on access removal userId={} count={}", event.userId(), live.size());
    }

    private void requireValid(CurrentUser user, int expiresInDays, List<ServiceTokenGrant> grants) {
        if (expiresInDays < 1 || expiresInDays > MAX_EXPIRES_IN_DAYS) {
            throw invalid("expiresInDays must be between 1 and " + MAX_EXPIRES_IN_DAYS);
        }
        if (grants == null || grants.isEmpty() || grants.size() > MAX_GRANTS) {
            throw invalid("collections must have 1 to " + MAX_GRANTS + " entries");
        }
        Set<String> available = memories.collectionsFor(user).stream()
                .map(collection -> collection.key())
                .collect(Collectors.toSet());
        Set<String> seen = new HashSet<>();
        for (ServiceTokenGrant grant : grants) {
            if (!MemoryPlacement.isCollectionKey(grant.collection())
                    || !available.contains(grant.collection())
                    || !seen.add(grant.collection())) {
                throw invalid("a collection is invalid, unavailable or repeated");
            }
        }
    }

    private Map<Long, List<ServiceTokenGrant>> grantsOf(List<Long> tokenIds) {
        if (tokenIds.isEmpty()) {
            return Map.of();
        }
        return tokenCollections.findByIdTokenIdIn(tokenIds).stream()
                .collect(Collectors.groupingBy(
                        row -> row.id().tokenId(),
                        Collectors.mapping(
                                row -> new ServiceTokenGrant(row.collection(), row.allowSensitive()),
                                Collectors.toList())));
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message);
    }
}
