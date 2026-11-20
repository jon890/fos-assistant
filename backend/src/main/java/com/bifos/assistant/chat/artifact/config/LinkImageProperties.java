package com.bifos.assistant.chat.artifact.config;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 사용자가 대화에 보낸 링크의 사진을 받을 때의 제한이다(ADR-20261120 / link-image-source).
 *
 * <p>허용 주소 목록은 없다. 금지 목록은 공개 IP 라도 받지 않을 호스트 이름과 IP 대역이다. 홈서버 자신에게 되돌아오는 요청을 막는 값이라
 * 운영 저장소가 갖고, 이 저장소에는 빈 기본값만 둔다.
 *
 * @param blockedHosts 받지 않을 소문자 DNS 이름. 그 아래 이름도 막는다
 * @param blockedCidrs 받지 않을 IP 대역(예: {@code 203.0.113.0/24})
 */
@Validated
@ConfigurationProperties(prefix = "assistant.artifact.link-image")
public record LinkImageProperties(
    Duration connectTimeout,
    Duration readTimeout,
    Duration totalTimeout,
    Integer maxRedirects,
    Integer maxPageBytes,
    Integer maxImageBytes,
    Integer maxHeaderBytes,
    List<String> blockedHosts,
    List<String> blockedCidrs) {


    private static final String PREFIX = "assistant.artifact.link-image.";
    private static final String DNS_NAME = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+";

    public LinkImageProperties {
        connectTimeout = positive(connectTimeout, Duration.ofSeconds(3), "connect-timeout");
        readTimeout = positive(readTimeout, Duration.ofSeconds(5), "read-timeout");
        totalTimeout = positive(totalTimeout, Duration.ofSeconds(10), "total-timeout");
        maxRedirects = atLeast(maxRedirects, 5, 0, "max-redirects");
        maxPageBytes = atLeast(maxPageBytes, 2 * 1024 * 1024, 1, "max-page-bytes");
        maxImageBytes = atLeast(maxImageBytes, 20 * 1024 * 1024, 1, "max-image-bytes");
        maxHeaderBytes = atLeast(maxHeaderBytes, 64 * 1024, 1024, "max-header-bytes");
        blockedHosts = blockedHosts == null ? List.of() : List.copyOf(blockedHosts);
        for (String host : blockedHosts) {
            if (host == null || !host.equals(host.toLowerCase(Locale.ROOT)) || !host.matches(DNS_NAME)) {
                throw new IllegalStateException(PREFIX + "blocked-hosts must contain lowercase ASCII DNS names");
            }
        }
        blockedCidrs = blockedCidrs == null ? List.of() : List.copyOf(blockedCidrs);
        for (String cidr : blockedCidrs) {
            if (cidr == null || !cidr.matches("[0-9a-fA-F:.]+/[0-9]{1,3}")) {
                throw new IllegalStateException(PREFIX + "blocked-cidrs must contain CIDR ranges");
            }
        }
    }


    private static Duration positive(Duration value, Duration defaultValue, String name) {
        Duration result = value == null ? defaultValue : value;
        if (result.isZero() || result.isNegative()) {
            throw new IllegalStateException(PREFIX + name + " must be positive");
        }
        return result;
    }


    private static int atLeast(Integer value, int defaultValue, int minimum, String name) {
        int result = value == null ? defaultValue : value;
        if (result < minimum) {
            throw new IllegalStateException(PREFIX + name + " must be at least " + minimum);
        }
        return result;
    }
}
