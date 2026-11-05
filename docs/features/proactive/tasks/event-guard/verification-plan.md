# 사건 실행 도구 판정 인수 검증 계획

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- |
| REQ9 | 가짜 Control Plane HTTP 서버가 `block` 을 답한다 | 막는 도구마다(`delegate_task`, `terminal`, `process_manage`, 파일 도구 넷, `execute_code`, `manage_connections`, `browser_navigate`, 붙지 않은 MCP 도구) 실제 `pre_tool_call` 을 부르고 서버가 받은 본문과 서명을 본다 | 모두 질의가 가고 `block` 과 Control Plane 의 글로 막힌다 | `hermes/tests` unittest | 없음 | PR 전 |
| REQ9 | 같은 서버가 `allow` 를 답한다 | 같은 도구로 hook 을 부른다 | 통과(None) | `hermes/tests` unittest | 없음 | PR 전 |
| REQ9 | 서버가 5xx, 시간 초과, JSON 아님, 주소 없음, 토큰 없음 / hook 안의 예외 | hook 을 부른다 | 정해 둔 글로 막힌다. 404 만 통과 | `hermes/tests` unittest | 없음 | PR 전 |
| REQ9 | 사건 살펴보기, 단추 살펴보기, 보통 대화의 실행 줄과 자식 등록 | `EventToolGuard.decide` 를 판정 순서의 각 경우로 부른다 | 사건 트리와 그 압축 session, 점검 대화의 끝난 실행, 사건 살펴보기가 도는 profile 의 모르는 session 은 막고, 보통 대화·점검 대화의 사용자 turn·단추 살펴보기·사건이 돌지 않는 profile 의 모르는 session 은 통과 | backend 통합(H2) | 없음 | PR 전 |
| REQ9 | 서명한 판정 요청 | `/internal/hermes/tool-guard` 를 profile 토큰으로 부른다 | 사건 트리는 `block`, 보통 대화는 `allow`, 서명이 틀리면 거절 | backend 통합(MockMvc) | 없음 | PR 전 |
| REQ9 | 사건 살펴보기 트리의 등록된 자식 | 그 자식 session 으로 판정을 묻는다 | `block` | backend 통합 | 없음 | PR 전 |
| REQ9 | Hermes v0.21.6 소스 | `scripts/checks/check-hermes-contract.sh` 를 돌린다 | 내장 도구 목록과 안전 목록 toolset, `execute_code` 호출 지점이 고정한 값과 같다 | 로컬과 CI | 네트워크 | PR 전 |
| REQ10 | 셸·위임 toolset 이 켜진 에이전트, 설치 상태 `tool_guard` 참 | 켜기 API 와 확인기를 부른다 | 켜지고 사건 실행이 열린다 | backend 통합 | 없음 | PR 전 |
| REQ10 | 설치 상태 `tool_guard` 거짓 또는 Hermes 실패 | 조회·켜기와 확인기를 부른다 | 조회는 `TOOL_GUARD_MISSING`(실패면 `READINESS_UNKNOWN`), 켜기 거절, 확인은 `BLOCKED` 이고 지점이 그대로 | backend 통합 | 없음 | PR 전 |
| REQ13 | `proactive-check` 스킬이 없는 에이전트 | 상태 조회, 단추 살펴보기, 매일 깨우기를 연다 | `SKILL_MISSING` 이 없고 시작한다. 입력에 `skill_view` 가 없고 지시에 공통 지침이 있다 | backend 통합, E2E | 없음 | PR 전 |
| REQ9 | 운영 메인 에이전트와 실제 Gmail | 새 메일 하나로 사건 실행이 돌고, 실행 기록에서 막힌 도구가 없거나 거절로 남는지 본다 | 사건 실행이 끝나고 알림이 온다 | 운영 | 배포 | 배포 뒤 |
| REQ9 | 운영 Hermes 이미지 | `execute_code` 안에서 내장 도구를 부르는 보통 대화 한 번 | hook 로그에 session 없는 호출로 남고 통과 | 운영 | 배포 | 배포 뒤 |
