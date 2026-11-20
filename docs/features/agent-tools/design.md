# 터미널 기본 기술 설계

스펙: [`spec.md`](spec.md). PR 순서와 요구사항 묶음은 스펙의 「범위」 가 갖는다.
코드 경로는 2026-11-20 의 main(`13e9d1e7d`) 기준이다.

## 요구사항 연결

| ID | 해결 방법 |
| --- | --- |
| REQ1 | 대시보드 plugin 의 도구 저장에서 local 분기를 없앤다. 정책에 없는 profile 이 실행 공간 도구를 켜면 언제나 409 `sandbox_unavailable` 이다. 거절 이유를 `reason` 필드으로 나눈다. Control Plane 은 기동할 때 한 번 셸 계열이 켜진 에이전트의 도구를 다시 저장하고, `reason: unlisted` 로 거절되면 실행 공간 도구를 빼고 다시 쓴다 |
| REQ2 | 정책에 최상위 칸 `isolate_unlisted` 를 더한다. 참이면 목록에 없는 named profile 이 공통 설정과 빈 profile 칸으로 격리된다. plugin 의 「정책이 이 profile 을 덮는가」 판정을 한 함수로 모은다 |
| REQ5 | `AgentToolPolicy` 에서 셸 계열을 주인 등급으로 옮긴다. 사용자가 만드는 에이전트와 관리자가 등록하는 에이전트도 기본 비서와 같은 `AgentDefaultToolsets` 로 기본 도구를 켠다. profile 틀의 `disabled_toolsets` 에서 셸 계열을 빼고 만들기의 「셸이 켜져 있으면 거둔다」 검사를 실행 공간 밖 도구로 좁힌다 |
| REQ8 | 기존 에이전트의 `pending_default_toolsets` 에 셸 계열을 채우는 데이터 마이그레이션을 둔다. 지금의 5분 주기 `AgentPendingToolsets` 가 켠다. 사람이 저장하면 저장 전후로 켜짐이 바뀐 이름만 칸에서 지운다(`settle`). 켜진 뒤 주인이 끄면 그 이름이 지워져 다시 켜지지 않는다. 그룹 공개 에이전트는 PR4 의 마이그레이션이 채운다 |
| REQ9 | 셸 계열의 관리자 등급이 없어지면 화면의 확인 창과 「관리자만」 표시는 `tier` 로 그려지므로 저절로 사라진다. 확인 창과 연결 위험 문구에서 「안전하게 분리되지 않은 에이전트」 경우를 지운다 |
| REQ3 | `Agent.sandboxOwner()` 가 그룹 공개면 `a<에이전트 번호>` 를 낸다. 셸 계열을 `PRIVATE_ONLY_TOOLSETS` 에서 뺀다. 공개 범위가 바뀌는 경로에서 실행 공간 도구가 켜져 있으면 새 주인 키로 도구를 다시 저장한다. 「내 파일」 로 내주기는 이미 `agent.sandboxOwner()` 에서 읽고 요청자의 「내 파일」 에 넣는다 |
| REQ7 | 주인 바꾸기 거절을 없애고, 주인이 바뀌는 같은 잠금 안에서 새 `sandboxOwner()` 로 도구와 스크립트 스킬을 다시 저장한다 |
| REQ4 | 먼저 살펴보기의 시작 판정 허용 목록에 셸 계열을 더하고, 매일 깨우기의 격리 스위치를 지운다. 사건 실행의 도구 판정(`EventToolGuard`)에 셸 계열 Hermes 도구 이름을 안전 목록으로 더한다 |
| REQ6 | 사람이 없는 실행이 끝날 때 그 실행 트리의 `execution_event` 에서 셸 계열 도구 이름의 `TOOL_STARTED` 를 센다. 결과가 있으면 보고와 알림에 수와 실행 기록 링크를 붙이고, 결과가 없으면 `notification` 에 알림 하나를 남긴다 |

## 책임과 경계

| 무엇 | 소유 | 바뀌는 것 |
| --- | --- | --- |
| profile 의 `terminal:` 과 실행 공간 정책 해석 | 대시보드 plugin(`hermes/plugins/dashboard-profile-api/sandbox.py`, `toolconfig.py`) | local 분기 삭제(PR1), `isolate_unlisted`(PR2) |
| 누가 어느 도구를 켜는가 | Control Plane `AgentToolPolicy` | 셸 계열의 등급(PR3)과 그룹 공개 금지(PR4) |
| 실행 공간 주인 키 | `Agent.sandboxOwner()` 한 곳 | 그룹 공개면 에이전트 키(PR4) |
| 기본 도구 켜기와 다시 시도 | `AgentDefaultToolsets`, `AgentPendingToolsets` | 모든 에이전트로 넓힌다(PR3), 그룹과 주인 없는 에이전트 허용(PR4) |
| 사람이 없는 실행의 도구 허용 | `ProactiveCheckReadiness`, `ProactiveScheduleService`, `EventToolGuard` | 셸 계열 허용(PR5) |
| 실행 공간 운영 값 | `fos-home-infra` | 정책에 `isolate_unlisted` 필드(PR2 배포 뒤) |

새 서비스나 테이블은 만들지 않는다. 의존 방향은 그대로다.

## 인터페이스와 데이터

### PR1: local 없음과 다시 저장

- plugin `PUT /api/config`: 실행 공간 도구를 켜는 저장에서 profile 이 정책에 없으면 `require_sandbox` 와 상관없이 409 `{"code": "sandbox_unavailable", "reason": "unlisted"}` 다. 정책이 없거나 틀리거나 디렉터리를 만들지 못한 409 는 `reason` 없이 지금과 같다.
  `require_sandbox` 필드은 옛 Control Plane 과의 호환을 위해 계속 받고, 스크립트 스킬 게시의 `skill_root` 검사에만 쓴다.
  실행 공간 도구를 켜지 않는 저장은 `terminal:` 을 건드리지 않는다. 이미 local 인 설정은 셸이 꺼진 동안 쓰이지 않는다.
- Control Plane `HermesCallFailure`: `reason: unlisted` 를 읽어 `HermesRequestRejected` 에 표시를 남긴다. 옛 plugin 처럼 `reason` 이 없으면 표시가 없다.
- Control Plane 기동 작업(`ApplicationReadyEvent`, 백그라운드): 지워지지 않은 에이전트마다 행을 잠그고 Hermes 의 켜진 목록을 읽는다. 실행 공간 도구가 켜져 있으면 지금 목록 그대로(Control Plane MCP, 붙은 플러그인 서버 이름, 원본 재조회 도구 포함) `writeApiServerInSandbox` 로 다시 쓴다.
  `unlisted` 로 거절되면 실행 공간 도구를 빼고 `require_sandbox` 없이 다시 쓰고 경고 로그를 남긴다. 그 밖의 실패(Hermes 무응답, `reason` 없는 409)는 그 에이전트를 메모리의 「끝내지 못한 목록」 에 두고 다음 에이전트로 간다. 끝내지 못한 목록은 `assistant.agents.pending-toolsets-cron` 주기에 다시 보고, 남은 수를 매 차례 로그로 남긴다.
  목록 계산은 `AgentDefaultToolsets` 의 private `desired()` 를 공용으로 꺼내 쓴다. `enablePending` 은 그룹 공개, 주인 없음, 비밀값 요청 스킬로 멈추므로 그대로 쓰지 않는다. 기동 작업은 공개 범위나 스킬로 제외하지 않는다.
  정책이 바뀌지 않았으면 다시 써도 결과가 같다. 운영이 정책(이미지, 마운트, 망)을 바꾼 뒤의 기동은 셸 에이전트의 `terminal:` 을 새 정책으로 다시 써서 컨테이너가 바뀐다. 운영의 일괄 반영을 대신하는 의도된 동작이다.
  REQ1 의 「어떤 경로의 도구 저장」 은 Control Plane 토큰 경로(`_check_config_update`)다. 운영자가 Hermes 대시보드의 쿠키 경로로 직접 켜는 것은 이 plugin 이 검사하지 않으며 운영 규칙이 갖는다.

### PR2: 정책 기본 줄

- 정책 최상위 칸 `isolate_unlisted`(선택, 참 거짓, 기본 거짓). 참이면 `profiles` 에 없는 named profile 이 `{"read_only_mounts": [], "env": {}, "network": <공통 network>}` 를 받는다. `default` 는 지금처럼 격리하지 않는다.
- plugin 이 정책의 profile 칸을 읽는 곳(`toolconfig.py` 의 도구 저장 판정, `connector_install.py` 의 실행 공간 필요 플러그인과 출력 디렉터리 판정 둘, `sandbox.py` 의 `_sandbox_terminal` 의 `policy["profiles"][profile]`)을 `_sandbox_profile_settings(policy, profile)` 하나로 바꾼다. 없으면 None 이다. `_sandbox_terminal` 을 빠뜨리면 목록 밖 profile 의 저장이 KeyError 로 500 이 된다.
- 운영 요청 범위와 순서는 「실패와 일관성」 의 배포 순서를 따른다.

### PR3: 주인 등급과 기본 켜기

- `AgentToolPolicy`: `terminal`, `file`, `code_execution` 을 `ADMIN_TOOLSETS` 에서 `OWNER_TOOLSETS` 로 옮긴다. `allowedAsDefault` 는 그대로 통과한다.
- 사용자가 만드는 에이전트(`AgentLifecycleService.provisionAgent`)와 관리자 등록(`AgentAdminService.create`)이 커밋 뒤 `AgentDefaultToolsets.apply(agent, peopleProperties.defaultToolsets())` 를 부른다. `image_gen` 다시 시도 표시는 기본 비서만 그대로다.
- profile 틀: `disabled_toolsets` 에서 셸 계열을 뺀다. 만들기 직후 검사는 `hasPrivateOnlyToolset` 대신 실행 공간 밖의 위험 도구(`browser`, `computer_use`, `session_search`)만 본다. 셸 계열은 실행 공간 정책이 없으면 기본 도구 쓰기가 빼므로 local 로 켜지지 않는다.
- 데이터 마이그레이션: `PRIVATE` 이고 주인이 있는 에이전트의 `pending_default_toolsets` 에 `terminal`, `file`, `code_execution` 을 더한다(기존 값과 합친다). 선례는 `V20261105074418__agent_pending_default_toolsets_backfill.sql` 이다. 비밀값 요청 스킬은 파일이라 SQL 이 보지 못한다. 지금 `AgentPendingToolsets` 는 `SKILL_REQUESTS_SECRETS` 에서 칸을 비우지 않고 관리자에게 알려 스킬을 지우면 저절로 켠다. 셸 계열 이름에 한해 그 결과에서 칸을 비우고 알리지 않게 바꾼다(스펙 DECISION10 의 「건너뛴다」). 알림 본문의 「기본 비서」 도 에이전트 이름으로 바꾼다. 옛 커넥터 에이전트는 남아 있지 않다(ADR-083 의 2026-10-15 줄, `agent.connector_managed` 삭제).
- 화면: `agent-tools-section.tsx` 의 셸 계열 확인 문구, `agent-connections-section.tsx` 의 `SHELL_RISK_MESSAGE` 에서 「안전하게 분리되지 않은 에이전트」 문장을 지운다. `error-message.ts` 의 「도구에서 셸을 켜 주세요」 는 주인이 켤 수 있으므로 그대로 둔다.

### PR4: 그룹 공개 공간과 주인 바꾸기

- `Agent.sandboxOwner()`: 주인이 없거나 `GROUP` 이면 `a<id>`, 아니면 `u<주인>`.
- `PRIVATE_ONLY_TOOLSETS` 에서 셸 계열을 뺀다. 사진 계열과 실행 공간 밖 도구는 남는다(스펙 DECISION14).
- 공개 범위 변경(`changeVisibilityLocked`), 관리자 수정(`AgentAdminService.update`), 사용자 정리의 그룹 에이전트 넘기기(`AgentUserPurge.transferGroupAgents`)가 `sandboxOwner()` 를 바꾸면, 같은 잠금 안에서 실행 공간 도구가 켜진 경우 새 키로 `writeApiServerInSandbox` 를 부르고 `scripts/` 스킬이 있으면 `SkillPublisher` 로 다시 게시한다. `requireOwnerChangeSafe` 는 지운다.
- 「내 파일」 로 내주기(`McpFileSpaceTools`)는 이미 `agent.sandboxOwner()` 에서 읽어 요청자 `userId` 의 「내 파일」 에 넣는다. 작업 폴더 화면은 `u<사용자>` 로 고정이라 에이전트 공간을 보이지 않는다. 둘 다 바꾸지 않는다.
- `AgentPendingToolsets.enablePending` 의 「그룹이거나 주인이 없으면 `NOT_ALLOWED`」 를 셸 계열에 한해 푼다. 데이터 마이그레이션으로 그룹 공개 에이전트의 `pending_default_toolsets` 에 셸 계열을 더한다.

### PR5: 사람이 없는 실행

- `ProactiveCheckReadiness.ALLOWED_TOOLSETS` 에 셸 계열을 더한다. `ISOLATION_REQUIRED_TOOLSETS` 판정과 `ProactiveScheduleService` 의 `isolated-execution-enabled` 설정, 화면의 `ISOLATED_EXECUTION_REQUIRED` 문구를 지운다.
- 사건 실행의 도구 판정: fos-ctx 의 `SAFE_TOOLS`(`hermes/plugins/fos-ctx/tool_guard.py`) 밖의 호출은 Control Plane `EventToolGuard.judge` 가 정하고, 지금은 사건 트리이면 이름과 상관없이 막는다. `isEventTree` 차단 앞에서 셸 계열 Hermes 도구 이름(`terminal`, `process`, `read_file`, `write_file`, `patch`, `search_files`, `execute_code`. 원본은 `hermes/tests/hermes_contract.py` 의 도구 이름 계약)을 허용한다. 위임과 `image_generate` 차단은 그 뒤에 그대로 둔다. fos-ctx 의 승인 관문 막기와 격리 바인딩 profile 의 `execute_code` 차단도 그대로다.
- 셸 사용 수: 실행 트리가 끝날 때 `execution_event` 에서 트리의 실행들의 `TOOL_STARTED` 가운데 위 도구 이름을 센다(`ExecutionEventRepository` 에 집계 쿼리 하나).
  `execute_code` 안의 스크립트가 부르는 일은 `TOOL_STARTED` 로 따로 남지 않을 수 있어 N 은 「모델이 부른 셸 계열 도구 수」 다.
  보고(`CheckReportFactory`)와 사건 알림(`EventWatchNotices`)에 수와 루트 실행의 실행 기록 링크를 붙인다. 결과를 남기지 않는 실행(`silentWhenEmpty`, 알릴 것 없는 사건)은 수가 0 보다 크면 `notification` 에 새 종류 하나를 남긴다. 조용한 시간 미루기는 그 알림 종류에 지금 규칙을 적용한다.

## 실패와 일관성

**배포 순서**

| 순서 | 무엇 | 까닭 |
| --- | --- | --- |
| 1 | PR1 의 plugin | 옛 Control Plane 은 사람의 저장에 `require_sandbox` 를 보내지 않는다. plugin 이 먼저 바뀌면 그 저장이 local 대신 409 가 되고 화면은 「격리된 실행 공간이 준비되지 않아…」 를 보인다. **2 의 기동 작업이 끝날 때까지 정책 밖에서 셸이 켜진 에이전트는 지금 목록을 그대로 쓰는 다른 경로도 409 다.** 사진이 든 대화 turn(원본 재조회 도구를 붙이는 쓰기, `ATTACHMENT_INSPECTION_UNAVAILABLE`), `skills` 가 꺼진 에이전트의 스킬 첫 게시, 셸과 상관없는 도구 하나를 바꾸는 저장이다. 그래서 1 과 2 는 붙여서 배포한다 |
| 2 | PR1 의 Control Plane | 기동 작업이 local 셸을 바로잡는다. 옛 plugin 위에서 돌면 정책 밖 profile 의 다시 쓰기가 `reason` 없는 409 라 건너뛰고 아무것도 바꾸지 않는다. 해롭지 않지만 빈틈이 남으므로 plugin 이 먼저다 |
| 3 | PR2 의 plugin | 옛 plugin 은 모르는 최상위 칸이 있으면 정책 전체를 무효로 본다 |
| 4 | 운영이 정책에 `isolate_unlisted: true` 를 넣는다 | 3 보다 먼저면 모든 셸 저장이 409 다. 되돌리기는 칸을 지우는 것이다 |
| 5 | PR3 | 4 전에 배포하면 정책 밖 새 에이전트는 셸 없이 만들어진다(DECISION15). 기존 에이전트의 다시 시도 칸이 남아 4 뒤 5분 주기로 켜진다 |

**경쟁**: 기동 작업과 다시 시도 작업은 에이전트 행을 잠그고 Hermes 를 부른다. 사람의 저장과는 지금의 `AGENT_BUSY` 규칙으로 차례가 정해진다.

**부분 실패**: 기동 작업의 한 에이전트 실패는 다른 에이전트를 막지 않는다. 정책이 틀려 모든 저장이 `reason` 없는 409 면 아무것도 끄지 않는다. 이것이 `reason` 을 나누는 까닭이다.

**호환**: plugin 은 `require_sandbox` 를 계속 받는다. Control Plane 은 `reason` 이 없는 409 를 지금처럼 다룬다.

## 검증 전략

| ID | 경계와 환경 |
| --- | --- |
| REQ1 | plugin 테스트(`test_dashboard_profile_api_sandbox_policy.py`: 정책 밖 profile 의 셸 저장이 `require_sandbox` 없이도 409 `unlisted`, `terminal:` 이 바뀌지 않음, 정책 무효와 디렉터리 실패는 `reason` 없음). Control Plane 테스트(`AgentSandboxSweepTest`: 기동 작업이 `unlisted` 에서만 셸 계열을 빼고 다시 쓰고, `reason` 없는 409 와 다른 실패는 다시 시도. `HermesToolsetRequestTest`: 409 의 `reason` 해석). 운영 배포 뒤에는 코디네이터가 Control Plane 기동 로그의 「실행 공간 도구를 다시 저장했다 outcomes=… unfinished=…」 를 읽고, unfinished 가 0 이 아니면 다음 주기의 「다시 봤다 unfinished=0」 까지 기다린 뒤, 모든 profile 의 `terminal.backend` 와 `platform_toolsets.api_server` 를 읽기 전용으로 대조한다. 셸 계열이 켜진 profile 은 모두 docker 이고 local 인 profile 은 셸 계열이 꺼져 있어야 한다. 읽는 명령은 운영 저장소가 갖는다 |
| REQ2 | plugin 단위 테스트(칸이 참이면 목록 밖 profile 이 공통 설정의 docker, 거짓이거나 없으면 409 `unlisted`, `default` 는 격리하지 않음, 목록의 profile 은 자기 칸). 운영 반영 뒤 왕복 확인 |
| REQ5, REQ9 | Control Plane 단위 테스트(`MEMBER` 주인이 셸 계열을 켜고 끔, 만들기와 관리자 등록 뒤 기본 도구 적용), 화면 단위 테스트와 브라우저 테스트(확인 창 없음) |
| REQ8 | MySQL 마이그레이션 테스트와 다시 시도 작업 단위 테스트 |
| REQ3, REQ7 | Control Plane 단위 테스트(키와 다시 쓰기 호출). 실제 격리는 운영 왕복 확인 |
| REQ4, REQ6 | Control Plane 단위 테스트(시작 판정, 도구 판정, 수 세기와 알림), fos-ctx 의 사건 판정 왕복 테스트 |

## 결정

| ID | 결정 | 답 | 누가 | 근거 |
| --- | --- | --- | --- | --- |
| DECISION1 | 정책 밖 거절 표시 | 409 본문에 `reason: unlisted` 를 더한다 | 작성자 | 기동 작업이 정책 무효의 409 를 목록 밖으로 오해하면 모든 에이전트의 셸을 끈다 |
| DECISION2 | 다시 저장의 자리 | Control Plane 기동 작업, 매 기동 | 작성자 | plugin 은 `sandbox_owner` 를 모른다. 정책에 있으나 local 로 남은 profile 은 주인 키가 있어야 docker 로 바꾼다. 결과가 같아 표지 없이 돌려도 된다 |
| DECISION3 | 정책 기본 줄의 모양 | 최상위 참 거짓 칸 `isolate_unlisted` | 작성자 | 공통 설정이 이미 기본 줄의 값을 갖는다. 칸 하나라 되돌리기 쉽다. `profiles` 에 와일드카드 키를 두면 지금 plugin 의 profile 이름 검사가 정책 전체를 무효로 본다 |
| DECISION4 | 기존 에이전트 한 번 켜기의 장치 | `pending_default_toolsets` 데이터 마이그레이션과 지금의 다시 시도 작업 | 작성자 | 켜진 뒤 주인이 끄면 `settle` 이 그 이름을 칸에서 지워 다시 켜지 않는다. 새 테이블이나 표지가 필요 없다 |
| DECISION5 | 역할 profile 의 기본 켜기 | 관리자 등록(`AgentAdminService.create`) 때 기본 도구를 켠다 | 작성자 | 역할 profile 은 운영이 만들고 Control Plane 은 등록만 한다(`docs/code-architecture.md`) |

## 결정 기록 연결

| 결정 | 종류 | ADR 또는 필요 없음의 이유 | 현재 규칙의 원본 |
| --- | --- | --- | --- |
| 스펙 DECISION1, DECISION17, 이 문서 DECISION1~2 | architecture, areas: security | [ADR-20261120 / no-local-shell](../../adr/ADR-20261120-no-local-shell.md)(PR1). ADR-086 의 local 허용을 대체한다 | `hermes/README.md` 의 「셸 실행 공간」, `toolconfig.py` |
| 스펙 DECISION3, 이 문서 DECISION3 | architecture, areas: operations | PR2 에서 새 ADR | `hermes/README.md` 의 「셸 실행 공간」 |
| 스펙 DECISION6, DECISION10 | domain-policy, areas: security | PR3 에서 새 ADR. ADR-029 의 등급 표와 ADR-20261008 / default-toolsets 의 기본 비서 한정을 대체한다 | `AgentToolPolicy`, `docs/features/agent-skill.md` 의 「에이전트 도구」 |
| 스펙 DECISION4, DECISION9, DECISION18 | architecture, areas: security | PR4 에서 새 ADR. ADR-086 의 주인 바꾸기 금지와 ADR-029 의 그룹 공개 금지(셸 계열)를 대체한다 | `Agent.sandboxOwner()` |
| 스펙 DECISION5, DECISION7, DECISION19 | domain-policy, areas: security | PR5 에서 새 ADR. ADR-082, ADR-085, ADR-20261105 / event-watch 의 셸 계열 차단을 대체한다 | [`proactive.md`](../proactive.md) |
