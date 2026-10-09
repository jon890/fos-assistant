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

## 커넥터 비밀값을 두는 곳

`connector_connection` 은 사용자, 커넥터, 상태, 칸 값, 마지막 확인 시각, 값을 보관 파일에 두었는지, 선언하지 않은 도구 수를 저장한다.
에이전트에 붙인 것은 `agent_connector_binding` 이 바인딩마다 상태와 재시작 대기를 저장한다([`backend/docs/data-schema.md`](../backend/docs/data-schema.md)).
비밀 칸은 원문과 해시를 저장하지 않고, 값이 충분히 길 때만 앞부분을 남긴다. 길이 기준과 저장 칸은 [`backend/docs/data-schema.md`](../backend/docs/data-schema.md) 의 「connector_connection」 이 갖는다.
화면은 연결된 상태에서 앞부분이 없는 필수 비밀 칸을 「입력됨」 으로만 보인다. 앞부분이 없는 선택 비밀 칸은 입력 여부를 응답으로 알 수 없어 보이지 않는다.
브라우저는 등록을 제출한 직후 비밀 칸 입력을 비우고 다시 표시하지 않는다. 선택지를 고르는 동안은 작성 중인 입력을 쓰고, 조회가 실패해도 입력을 비운다.
요청 record 의 문자열 표현, 외부 오류, 로그와 응답에 비밀 원문을 남기지 않는다.
`connector_action` 은 도구 호출마다의 판정과 승인 줄을 저장한다. 인자 원문은 승인 줄에만 두고 주인에게만 보인다. 관리자 목록과 로그에는 싣지 않는다.

**칸 값의 원문은 Hermes 쪽 두 곳에만 있다.**

| 어디 | 무엇 | 언제 지워지는가 |
| --- | --- | --- |
| 보관 파일 | 연결마다 하나인 원본. 대시보드 plugin 이 둔다 | 연결을 해제할 때 |
| 붙인 에이전트 profile 의 `.env` | 붙일 때 보관 파일에서 복사한 값. 서버 정의의 `env` 는 `${이름}` 참조만 갖고 그 profile 의 secret scope 에서 풀린다 | 그 에이전트에서 뗄 때. 연결 해제는 붙은 바인딩을 모두 떼므로 함께 지워진다 |

값을 바꾸면 보관 파일을 다시 쓰고 붙은 profile 마다 복사본을 다시 쓴다. 떠 있는 MCP 프로세스는 옛 값을 쥐고 있어 그 바인딩들이 재시작 대기가 된다.
설정 백업은 `.env` 와 보관 파일을 담지 않는다. 그래서 떼거나 해제한 뒤에는 그 profile 디렉터리의 어느 파일에도 칸 값의 원문이 남지 않는다.
옛 커넥터 에이전트는 남아 있는 동안 자기 profile 의 `.env` 에 값을 갖는다. 연결 확인이 그 값을 보관 파일로 옮기고, 그 에이전트를 지우면 그 profile 이 거둬진다.
