# hermes 모듈의 구조

이 저장소가 Hermes 에 설치하는 plugin 과 profile 틀, 범용 커넥터를 어디에 두는지 갖는다.
설치 묶음과 운영 값은 [`hermes/README.md`](../README.md), Hermes 의 동작은 [`hermes/docs/hermes-contract.md`](hermes-contract.md) 가 갖는다.

## 모듈이 갖는 것

Control Plane 이 기대는 Hermes 쪽 코드다. Hermes 에 설치하는 plugin 두 개와 새 profile 의 설정 틀을 둔다.
이 저장소가 이것을 갖는 근거는 [ADR-041](../../docs/adr/ADR-041-hermes-에-설치하는-plugin-과-profile-틀은-이-저장소가-소유한다.md) 에 있다.

| plugin | 하는 일 | 두는 곳 | 읽는 프로세스 |
| --- | --- | --- | --- |
| `dashboard-profile-api` | 대시보드의 profile 관리와 도구 목록 경로를 `Authorization: Bearer` 로 연다 | Hermes 기본 루트의 `plugins/` | 대시보드 |
| `fos-ctx` | Control Plane MCP 호출 인자에 서명한 run 맥락 `_fos_ctx` 를 덮어쓰고, `skill_manage` 와 자식의 로컬 이미지 전달을 막고, 자식 session 을 등록하고, 커넥터를 설치한 profile 의 커넥터 도구 호출을 Control Plane 에 묻고, 바인딩 profile 의 커넥터 도구 결과를 `<external-data>` 로 감싼다 | Control Plane MCP 를 등록한 profile 과 커넥터를 설치한 profile 마다 | gateway |

## plugin 파일 안내

각 plugin 의 `__init__.py` 는 Hermes 가 부르는 `register` 와 기존 이름을 다시 내보낸다.
하위 모듈은 다른 기능 모듈을 직접 import 하며 패키지 `__init__.py` 를 import 하지 않는다.

| plugin | 모듈 | 맡는 것 |
| --- | --- | --- |
| `dashboard-profile-api` | `common.py` | 응답, 본문 읽기, profile 이름, 원자적 쓰기, 공용 표식 |
| `dashboard-profile-api` | `profiles.py`, `session.py` | profile 생성과 삭제, 스킬 검사, 모델 기본값과 판단 준비 검사, 자식 session provider |
| `dashboard-profile-api` | `sandbox.py`, `sandbox_paths.py`, `sandbox_approvals.py`, `toolconfig.py`, `env.py` | 실행 공간과 첨부 디렉터리, 실행 공간 정책의 경로 검사, 실행 공간과 함께 쓰는 승인 설정, 도구와 스킬 경로 설정, env 검사 |
| `dashboard-profile-api` | `connector_schema.py`, `connector_policy.py`, `connector_skills.py`, `connector_appearance.py` | manifest 형식 규칙, 입력 칸과 도구 정책·오류 계약, 스킬 읽기, 아이콘과 링크 검사 |
| `dashboard-profile-api` | `connector_manifest.py`, `connector_vault.py` | manifest 읽기와 카탈로그, 연결 보관 파일 |
| `dashboard-profile-api` | `connector_state.py`, `connector_status.py` | 소유 기록과 도구 이름 대응, 스킬과 정책 hook 상태 확인 |
| `dashboard-profile-api` | `connector_isolated.py`, `connector_binding.py`, `connector_install.py` | 옛 설치, 바인딩 설치, 요청 처리와 probe |
| `dashboard-profile-api` | `connector_mcp.py`, `connector_run.py` | MCP 호출과 결과 해석, call·execute 요청과 공유 동시 호출 한도 |
| `dashboard-profile-api` | `connector_output.py`, `connector_owner_env.py` | 바인딩마다 만드는 커넥터 출력 디렉터리, 주인 env 검증 |
| `dashboard-profile-api` | `routes.py` | 인증 provider, 경로 표, 토큰 미들웨어 |
| `fos-ctx` | `context.py` | 서명, 토큰 읽기, 루트 session 조회, 호출 문맥 |
| `fos-ctx` | `connector_policy.py` | 이름 대응 파일, 정책 질의, 서버 선택 |
| `fos-ctx` | `subagent.py`, `hooks.py` | 자식 session 등록, hook 진입점, 이미지 검사와 외부 데이터 감싸기 |

설치 묶음은 대시보드 plugin 과 profile plugin 의 모든 Python 모듈을 함께 담는다.
커넥터 설치와 `policy_hook` 조회도 `fos-ctx` 의 하위 모듈까지 묶음과 비교한다.

## 범용 커넥터

`hermes/connectors/<커넥터 이름>/` 은 이 저장소가 유지보수하는 커넥터다([ADR-064](adr/ADR-064-범용-커넥터는-이-저장소의-hermes-connectors-에-두고-저장소가-유지보수한다.md)).
대시보드 plugin 이 읽는 plugin 디렉터리 모양 그대로이고, 운영자가 아래 「커넥터」 의 운영 목록에 그 디렉터리를 올려야 카탈로그에 나온다.
설치 묶음에는 들어가지 않는다.

| 커넥터 | 하는 일 | 문서 |
| --- | --- | --- |
| `gmail` | Gmail 을 찾고 읽고, 승인받은 메일과 라벨, 자동 분류 필터를 쓴다 | [Gmail 커넥터](../connectors/gmail/README.md) |
| `naver-blog` | 「내 브라우저」 에 로그인해 둔 네이버 계정으로 승인받은 글을 네이버 블로그에 임시저장한다. 발행하지 않는다 | [네이버 블로그 커넥터](../connectors/naver-blog/README.md) |
| `tossinvest` | 토스증권 계좌의 보유 종목, 시세, 주문 가능 현금, 주문 내역을 읽는다. 주문하지 않는다 | [토스증권 커넥터](../connectors/tossinvest/README.md) |

커넥터의 MCP 서버는 TypeScript 로 쓰고 의존성까지 한 JavaScript 파일로 묶어 커밋한다.
운영 목록의 `command` 는 Bun 실행 파일이어야 한다. 서버 실행 중 패키지를 내려받지 않는다.
만드는 방법과 공통 검사는 [커넥터 만들기](../connectors/README.md) 가 갖는다.

## 디렉터리 배치와 배포 순서

Control Plane 이 기대는 Hermes 쪽 코드는 이 저장소가 갖는다.
근거는 [ADR-041](../../docs/adr/ADR-041-hermes-에-설치하는-plugin-과-profile-틀은-이-저장소가-소유한다.md) 에 있다.

```
hermes/
  plugins/
    dashboard-profile-api/   대시보드 plugin. profile 만들기와 지우기, env, 도구와 스킬 설정, 커넥터
    fos-ctx/                 profile plugin. Control Plane MCP 호출에 _fos_ctx 서명을 붙이고, 바인딩 profile 과 옛 설치 profile 의 커넥터 도구 호출을 Control Plane 에 물어 막는다
  connectors/
    <커넥터 이름>/            범용 커넥터 하나. connector.json, .mcp.json, MCP 서버, 스킬 (ADR-064)
  profile-template/
    config.yaml.template     새 profile 의 설정 틀. 안전한 도구 목록, Control Plane MCP 등록, fos-ctx 켜기
  bundle.sh                  설치 묶음을 만든다
  tests/                     Python unittest. Hermes 모듈은 가짜로 끼운다
```

**서비스 이름은 `hermes/connectors/` 와 그 검사와 문서에만 둔다.** `backend/src/main`, `web/src`, `hermes/plugins` 는 어느 서비스도 모른다. `test/unit/connector-neutral.test.ts` 가 본다.

**운영 값은 코드에 두지 않는다.** 설치 묶음을 만들 때와 프로세스의 환경 변수로 받는다.
묶음을 Hermes 에 넣고 대시보드를 다시 띄우는 것은 운영 저장소가 한다.
설치 묶음의 모양, 운영 값의 목록, 검사 방법은 [`hermes/README.md`](../README.md) 가 갖는다.

**plugin 은 한 배포 동안 옛 Control Plane 의 호출도 받는다.** 운영은 plugin 을 먼저 올리고 Control Plane 을 올린다.
경로나 요청 모양을 바꿀 때는 새 것을 더하고, 옛 것은 그다음 배포에서 뺀다.

## 토스증권 REST 전체 지원의 배치 (구현 전)

서비스의 REST 입력·필드·권한은 [커넥터 연결](../../docs/features/connector.md)의 「토스증권 REST 전체 지원」이 갖는다.
새 의존성, 서비스, DB 표와 profile 설정은 추가하지 않는다. 기존 Bun MCP와 범용 Java 승인, Python 실행 전달을 재사용한다.

| 자리 | 책임 |
| --- | --- |
| tossinvest/src/client.ts, api-contract.ts, rate-limit.ts | 고정 REST 주소와 schema, token, 그룹 한도, 제한된 읽기 재시도와 단일 쓰기 전송 |
| tossinvest/src/read-tools.ts, account-tools.ts | 기존 조회 이름과 표시 호환, 공식 result와 계좌 마스킹 |
| tossinvest/src/market-data-tools.ts, stock-tools.ts, stock-trend-tools.ts, market-info-tools.ts, ranking-tools.ts, indicator-tools.ts, sector-tools.ts | 기능별 READ 도구 등록. server.ts가 연결하고 manifest와 같은 이름을 선언 |
| tossinvest/src/order-options.ts, prepare-conditional-trade.ts, conditional-trading-tools.ts | 기존 금융 producer와 prepare dispatcher의 확장. execution-guard를 재사용하고 별도 약한 실행 경로를 만들지 않음 |
| backend connector/application/ConnectorExecutionSnapshot, ConnectorConditionalExecution | 범용 불변 금융 union 검증, 중첩 의도 키. 서비스 이름과 URL은 모름 |
| 기존 승인 DTO와 ApprovalCard | 서버가 저장한 전체 전략과 금액, 고액 명시 확인. 계좌 선택과 모델 문자열 요약은 사용하지 않음 |

현재 client/api-contract/rate-limit과 기존 READ 도구는 구현했다. 시장정보·일반주문·조건주문의 새 producer는 아직 구현하지 않았다. 저장과 claim, prepare·transport, 바인딩 지원과 일반주문 producer는 각 선행 구현의 책임이며 이 확장에서 중복 작성하지 않는다.
조회 기반과 조건주문 READ는 금융 후속과 별도 착수한다. 계좌 원문 마스킹은 표시뿐 아니라 공식 result를 만드는 producer의 책임이다.
금융 execution_baseline은 argsJson 원문 해시에 묶어 실행 자식에 전달하고 summary.executionBaseline과 대조한다. HTTP body·경제적 request_key·scope에는 넣지 않는다. 정확한 schema는 커넥터 도구 정책의 「예약 실행 메타데이터」가 갖는다.
계약·schema 소비자, 승인 카드와 producer는 같은 작동 단계에서 호환을 확인한다. 미지원 소비자는 새 금융 도구를 열지 않는다.
