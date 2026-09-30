package com.bifos.assistant.connector.application;

import java.time.Duration;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "assistant.accountbook")
public record AccountbookProperties(String apiBaseUrl, Duration connectTimeout, Duration readTimeout) {
    public AccountbookProperties {
        apiBaseUrl = apiBaseUrl == null ? "" : apiBaseUrl;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
    }
    public boolean configured() {
        try {
            URI uri = URI.create(apiBaseUrl);
            String host = uri.getHost();
            return "https".equals(uri.getScheme()) && host != null && uri.getUserInfo() == null
                    && uri.getQuery() == null && uri.getFragment() == null && !isPrivateHost(host);
        } catch (IllegalArgumentException ex) { return false; }
    }
    private static boolean isPrivateHost(String host) {
        String value = host.toLowerCase(java.util.Locale.ROOT).replace("[", "").replace("]", "");
        return value.equals("localhost") || value.endsWith(".localhost") || value.endsWith(".local")
                || value.equals("::1") || value.equals("::") || value.startsWith("fc") && value.contains(":")
                || value.startsWith("fd") && value.contains(":") || value.startsWith("fe80:")
                || value.startsWith("0.") || value.startsWith("127.")
                || value.startsWith("10.") || value.startsWith("192.168.") || value.startsWith("169.254.")
                || value.matches("172\\.(1[6-9]|2[0-9]|3[01])\\..*");
    }
}
