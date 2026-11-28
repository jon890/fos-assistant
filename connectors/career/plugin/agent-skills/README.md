# agent-skills

에이전트에 설치하는 스킬을 둔다([ADR-20261120 / plugin-agent-skills](../../../../hermes/docs/adr/ADR-20261120-plugin-agent-skills.md)).
`assistant.json` 의 `agent_skills` 가 이 디렉터리를 가리키고, 플러그인을 붙인 에이전트의 셸 설정이 `sandbox_env` 의 env 를 모두 넘길 때만 그 스킬이 설치된다.

스킬은 모두 이 디렉터리가 원본이다. 여기서 직접 고친다.
스킬마다 `SKILL.md` 와 `references/`, `templates/` 만 둔다. 대시보드 plugin 이 그 밖의 파일을 복사하지 않는다.
다른 스킬의 문서는 링크 대신 스킬 이름과 경로로 적고, 에이전트가 `skill_view` 로 읽게 한다.

`connectors/career/plugin/scripts/agent-skills.ts` 가 스킬 목록(`AGENT_SKILLS`)과 디렉터리가 같은지, 다른 실행 환경 전용 표현(실행 환경 문장, 별도 검토 에이전트, 그 환경의 도구 이름)이 남았는지 검사한다(`HOST_ONLY_PATTERNS`).
`connectors/career/plugin/scripts/agent-skills.test.ts` 는 스킬이 지운 MCP 도구와 실행기 명령을 가리키지 않는지, 맥락 문서를 기억에서 읽는지 본다.
