package com.bifos.assistant.skill.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 올린 스킬을 어느 공유 디렉터리에 두고 옛 버전을 몇 개 남기며 에이전트마다 몇 개까지 받을지 정한다.
 *
 * <p>두 경로에 기본값을 두지 않는 까닭은 결과물 폴더와 같다. 기본값이 있으면 공유 디렉터리를 붙이지
 * 않은 채 배포해도 기동이 성공하고, Control Plane 이 쓴 스킬을 Hermes 가 보지 못한다. 근거는 ADR-034
 * 에 있다.
 *
 * @param root 스킬 버전 디렉터리의 루트. Control Plane 컨테이너에서 보이는 경로다. 비어 있으면 기동을
 *     멈춘다
 * @param agentRoot 같은 디렉터리를 Hermes 컨테이너에서 보는 경로. {@code skills.external_dirs} 에 이것을
 *     적는다. 비어 있으면 기동을 멈춘다
 * @param keepVersions 게시에 성공한 버전 가운데 남겨 두는 수. 되돌릴 때 쓴다
 * @param maxPerAgent 에이전트 하나에 올릴 수 있는 스킬 수. 새 스킬을 만들 때만 본다. Hermes 색인이 커지지
 *     않게 하려는 것이다. 근거는 ADR-034 에 있다
 */
@Validated
@ConfigurationProperties(prefix = "assistant.skill")
public record SkillProperties(String root, String agentRoot, Integer keepVersions, Integer maxPerAgent) {

    private static final int DEFAULT_KEEP_VERSIONS = 3;

    private static final int DEFAULT_MAX_PER_AGENT = 30;

    public SkillProperties {
        // 설정의 자리표시자는 환경 변수가 아예 없을 때만 기동을 멈춘다. 빈 문자열은 여기서 막는다.
        if (root == null || root.isBlank()) {
            throw new IllegalStateException("assistant.skill.root is required");
        }
        if (agentRoot == null || agentRoot.isBlank()) {
            throw new IllegalStateException("assistant.skill.agent-root is required");
        }
        keepVersions = keepVersions == null ? DEFAULT_KEEP_VERSIONS : keepVersions;
        if (keepVersions < 1) {
            throw new IllegalStateException("assistant.skill.keep-versions must be at least 1");
        }
        maxPerAgent = maxPerAgent == null ? DEFAULT_MAX_PER_AGENT : maxPerAgent;
        if (maxPerAgent < 1) {
            throw new IllegalStateException("assistant.skill.max-per-agent must be at least 1");
        }
    }
}
