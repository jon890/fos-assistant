package com.bifos.assistant.mcp.application;

/**
 * {@code /mcp} 요청의 토큰을 인증한 결과다. 사용자가 아니라 부르는 profile 을 증명한다(ADR-032).
 *
 * <p>요청자는 이 값으로 정하지 않는다. 사용자가 걸린 도구는 {@link McpCallerResolver} 가 서명한
 * {@code _fos_ctx} 로 origin 실행을 찾아 그 실행의 사용자를 쓴다(ADR-037).
 *
 * @param tokenId 인증한 토큰 번호
 * @param profileName 토큰이 묶인 profile. 비었으면 옛 토큰이다
 * @param legacyUserId 옛 토큰을 발급한 사용자. profile 이 묶인 토큰은 늘 null 이다
 * @param tokenHash 토큰의 SHA-256 소문자 16진수. {@code _fos_ctx} 서명의 key 다
 */
public record McpPrincipal(Long tokenId, String profileName, Long legacyUserId, String tokenHash) {

    /** profile 이 묶인 토큰인지. 묶인 토큰에는 옛 경로가 없다. */
    public boolean bound() {
        return profileName != null;
    }

    /** 인증 주체는 로그에 찍힐 수 있다. 서명의 key 인 토큰 해시를 빼고 적는다. */
    @Override
    public String toString() {
        return "McpPrincipal[tokenId=" + tokenId + ", profileName=" + profileName + ", legacyUserId=" + legacyUserId + "]";
    }
}
