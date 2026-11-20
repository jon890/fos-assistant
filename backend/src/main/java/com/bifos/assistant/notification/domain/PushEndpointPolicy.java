package com.bifos.assistant.notification.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 웹 푸시를 보낼 주소가 허용한 푸시 서비스인지 정한다(ADR-20261120 / web-push).
 *
 * <p>Control Plane 은 사용자가 준 주소로 요청을 보낸다. 막지 않으면 내부망 주소로 요청을 보내는 길이 된다. 그래서 등록할 때와 보낼 때
 * 모두 이 규칙을 지나야 한다.
 *
 * <ul>
 * <li>스킴은 {@code https}, 포트는 없거나 443
 * <li>userinfo 가 없고 호스트가 IP 리터럴이 아니다
 * <li>호스트가 허용 항목과 정확히 같거나, 항목이 {@code .} 으로 시작하면 그 접미사로 끝난다
 * </ul>
 */
public final class PushEndpointPolicy {

    /** 주소 길이 상한이다. 알려진 푸시 서비스 주소는 수백 자다. */
    public static final int MAX_LENGTH = 1024;

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final int HTTPS_PORT = 443;

    private final List<String> allowedHosts;


    public PushEndpointPolicy(List<String> allowedHosts) {
        this.allowedHosts = allowedHosts.stream().map(host -> host.strip().toLowerCase(Locale.ROOT)).toList();
    }


    /** 보내도 되는 주소인가. */
    public boolean allows(String endpoint) {
        if (endpoint == null || endpoint.isEmpty() || endpoint.length() > MAX_LENGTH) {
            return false;
        }
        URI uri;
        try {
            uri = new URI(endpoint);
        } catch (URISyntaxException ex) {
            return false;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null) {
            return false;
        }
        if (uri.getPort() != -1 && uri.getPort() != HTTPS_PORT) {
            return false;
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty() || host.startsWith("[") || IPV4.matcher(host).matches()) {
            return false;
        }
        String lower = host.toLowerCase(Locale.ROOT);
        return allowedHosts.stream().anyMatch(allowed -> matches(lower, allowed));
    }


    private static boolean matches(String host, String allowed) {
        if (allowed.startsWith(".")) {
            return host.endsWith(allowed) && host.length() > allowed.length();
        }
        return host.equals(allowed);
    }
}
