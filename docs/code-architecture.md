# 구조

## 경계

```
브라우저
  └ Next.js (web/)            세션은 여기까지만 산다
      └ 서버 라우트에서 짧은 수명의 토큰을 발급해 호출
          └ Spring Boot (backend/)   Control Plane
              └ Hermes API server    /p/<profile>/v1/runs
```

브라우저는 Control Plane 토큰을 갖지 않는다.
Next.js 서버 라우트가 세션에서 메일 주소를 꺼내 매 요청마다 토큰을 새로 만든다.
그래서 사용자가 요청 본문을 고쳐 남의 자료를 달라고 할 수 없다.

**위 그림은 요청이 나가는 쪽만 그린 것이다.**
Hermes 가 Control Plane 을 부르는 반대 방향도 있고 토큰이 서로 다르다.
그 두 방향은 [`flow.md`](flow.md) 의 「두 방향과 두 토큰」 절이 그림으로 갖는다.

## 사용자와 profile, 에이전트, 대화

층이 넷이고 서로 다르다.

```
사용자 (app_user)
 └ 그 사용자의 profile 들
     └ 각 profile 을 가리키는 에이전트 (agent 표)
         └ 그 에이전트로 시작한 대화 (conversation)
```

**한 사용자가 profile 을 여럿 가질 수 있다.**
기본 profile 하나에 역할 profile 을 더한다.
둘은 만드는 방법이 달라 설정도 다르다.
`fos-home-infra` 가 그 차이를 소유한다.

## Memory 에서 아직 만들지 않은 것

아래는 아직 만들지 않았다. 스키마와 판정은 이미 받을 수 있게 되어 있다.

- collection 탭, 문서의 판 이력 화면, 출처 표시
- 신원 항목의 들이기. 암호화와 문서 읽기 경계와 `identity` 권한을 운영에서 확인한 뒤에 연다. 조건은 [ADR-058](adr/archive/ADR-058-기존-개인-지식-저장소는-주인이-검토한-묶음을-화면에서-올려-들여온다.md) 이 정했다
- 민감 항목 본문의 완전 삭제
- `always_inject` 칸 제거

Memory 의 기본 근거는 [`backend/docs/adr/ADR-003-memory-권한은-주입으로-강제한다.md`](../backend/docs/adr/ADR-003-memory-권한은-주입으로-강제한다.md) 와
[`backend/docs/adr/ADR-012-memory-는-사람이-승인한-것만-남는다.md`](../backend/docs/adr/ADR-012-memory-는-사람이-승인한-것만-남는다.md) 에 있다.
층을 나누는 근거는
[`backend/docs/adr/ADR-015-memory-는-층을-나눠-싣는다.md`](../backend/docs/adr/ADR-015-memory-는-층을-나눠-싣는다.md) 에 있다.

## 화면을 검증하는 방법

테스트는 확인하는 대상을 나눠 둔다.

| 위치 | 확인하는 것 | 띄우는 것 |
| --- | --- | --- |
| `test/e2e/` | Control Plane 의 응답과 권한과 기록 | 가짜 Hermes, 백엔드, 데이터베이스 |
| `test/browser/` | 화면의 배치와 동작 | 위에 더해 웹과 Chromium |
| `test/unit/` | 화면이 쓰는 순수 함수 | 없음 |

브라우저 테스트는 `mobile` 과 `desktop` 두 폭에서 돈다. 폭의 값은 `test/browser/playwright.config.ts` 가 갖는다.

**운영 코드에 시험용 문을 만들지 않는다.**
로그인은 테스트가 NextAuth 세션 쿠키를 직접 만들어 넣는다.
테스트일 때만 켜지는 우회를 두면 그 문이 운영에도 남는다.

## 비밀값을 두는 곳

| 값 | 두는 곳 |
| --- | --- |
| 사용자의 AI credential | Hermes 쪽. provider API key 는 profile `.env` 에 둔다. OAuth 로그인은 profile 에 자기 `auth.json` 이 없으면 여러 profile 이 Hermes 루트의 것을 함께 쓴다([`hermes/README.md`](../hermes/docs/hermes-contract.md)) |
| profile 의 API server key | 홈서버의 mode 600 파일. 파일 이름이 profile 이름이다 |
| Hermes 대시보드를 부를 토큰 | Control Plane 의 환경 변수와 그 plugin 의 환경 변수 |
| 웹과 Control Plane 이 나눠 가지는 HMAC 비밀값 | 두 서비스의 환경 변수 |
| 사용자 본문을 감싸는 KEK | 홈서버 파일. 데이터베이스 백업과 다른 자리에 둔다([ADR-20261008 / data-encryption](../backend/docs/adr/ADR-20261008-data-encryption.md)) |
| 사용자별 데이터 key | `user_data_key` 에 KEK 로 감싼 채로 둔다. 푼 key 는 Control Plane 메모리에만 있다 |

데이터베이스에는 어떤 비밀값도 원문으로 넣지 않는다.
`agent` 는 profile 이름과 주소만 적는다.
profile key 와 AI credential 은 계속 홈서버 파일에 둔다.

## 아직 만들지 않은 것

- Hermes 안의 `delegate_task` 하위 에이전트가 자기 실행 줄을 남기는 경로.
  그 하위 에이전트는 Hermes 안에서만 돌고 사건으로만 보인다.
  우리 실행 줄이 생기는 자식은 `agent_delegate`, 흐름의 하위 실행, Memory 제안이다.
  사용량과 금액은 실행 줄 없이 `subagent_usage_job` 줄에 남겨 합계에 더한다([ADR-062](../backend/docs/adr/ADR-062-native-하위-에이전트-사용량은-재조회-작업-줄을-원장으로-넓혀-합계에-더한다.md)).
  그 자식의 provider 는 대시보드 plugin 의 읽기 경로로 받는다([ADR-067](../backend/docs/adr/ADR-067-native-하위-에이전트의-provider-는-대시보드-plugin-이-session-저장소에서-읽어-준다.md))
- `agent_stop` 이 그 실행 아래의 실행까지 멈추는 것. 지금은 그 실행만 멈춘다
- 사용자가 turn 을 중지할 때 Hermes `delegate_task` 하위 에이전트를 실제로 멈추는 것.
  지금은 origin 실행이나 그 루트 실행이 `CANCELLED` 인 하위 에이전트의 Control Plane MCP 호출만 거절한다([ADR-037](../backend/docs/adr/ADR-037-hermes-하위-에이전트-session-의-주인은-만들-때-등록한-줄로-정한다.md)).
  루트와 origin 사이의 중간 실행만 중지된 경우는 보지 않는다.
  멈출 수 있는 길은 profile 플러그인 쪽에 있고, 부모 run 이 끝난 뒤의 자식은 그 길로도 멈추지 못한다([`hermes/docs/hermes-contract.md`](../hermes/docs/hermes-contract.md#native-하위-에이전트를-멈추는-길))
- 여러 Control Plane 이 함께 세는 사용자 실행 한도. 지금은 한 프로세스 안의 사용자 잠금으로 세고 만든다([`backend/execution-limit.md`](backend/execution-limit.md) 의 「서버 한 대 전제」)
- Hermes native 하위 에이전트와 cron 을 사용자 실행 한도에 넣는 것. Control Plane 이 제출하지 않아 세지 못한다
- `connector_action` 줄의 보관 기한과 정리. 지금은 도구 호출마다 남긴 줄을 지우지 않는다
- 커넥터 연결에 다시 인증이 필요하다는 알림. 연결 상태에 재인증 상태가 없고, 토큰이 거절된 것을 연결 상태로 옮기는 지점도 없다. 그 상태를 정한 뒤 알림 종류를 더한다([`backend/notification.md`](backend/notification.md))
- 예약 작업의 실패 다시 하기와 일시 정지, 도구 미리 허락, 작업 제안. 목록과 넣지 않기로 한 것은 [`backend/task.md`](backend/task.md) 의 「다음 단계」 가 갖는다
- 알림을 웹 밖으로 보내는 채널. 첫 채널은 브라우저 웹 푸시로 정했다. 지금은 웹 안의 알림 단추와 목록뿐이다
- 먼저 살펴보기의 목표별 변화 판정. 외부 변화를 Control Plane 이 알지 못해 `NO_CHANGE` 로 모델을 건너뛰지 않는다. 남은 것은 [ADR-080](adr/ADR-080-먼저-살펴보기는-점검-대화의-turn-하나로-돌고-읽기-경계를-control-plane-이-강제한다.md) 의 「다음 단계」 가 갖는다
- 커넥터 plugin 이 일반 에이전트용 `proactive-check` 스킬을 선언하는 manifest 칸. 지금은 그 스킬을 에이전트에 따로 둔다
