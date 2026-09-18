## ADR-002: 사용자마다 profile 을 나누되 AI 계정은 가족이 함께 쓴다

- Status: Accepted
- Date: 2026-09-17
- 개정: 2026-09-17, OAuth credential 이 profile 로 격리되지 않는 사실을 확인하고 공유를 채택

### 맥락

사용자마다 자기 AI credential 을 연결할 수 있는 구조를 두려 했다.
그런데 홈서버의 Hermes v0.21.0 을 확인해 보니 형태에 따라 격리 수준이 다르다.

| credential 형태 | 저장 위치 | 격리 |
| --- | --- | --- |
| provider API key | 그 profile 의 `.env` | 런타임이 보장한다 |
| OAuth 로그인 | `auth.json` | 보장하지 않는다 |

profile 에 자기 `auth.json` 이 없으면 루트의 `~/.hermes/auth.json` 을 읽는다.
`tui_gateway/methods_profiles.py` 의 주석이 그것을 직접 말한다.

> profile reads fall back to the global store, and token refreshes write THROUGH to it

홈서버가 쓰는 것은 OAuth 쪽이다.
`provider: openai-codex` 와 `base_url: https://chatgpt.com/backend-api/codex` 로 ChatGPT 구독에 붙는다.
기존 profile 넷은 모두 자기 `auth.json` 이 없어 이미 한 로그인을 함께 쓰고 있다.

사용자마다 계정을 나누려면 사람 수만큼 구독을 사야 한다.
가족 규모에서 그 비용은 얻는 것에 견줘 크다.

### 결정

**AI 계정은 가족이 함께 쓴다.**
구독 하나로 모든 사용자가 돌고, 누가 얼마나 썼는지는 계정이 아니라 실행 기록으로 구분한다.

profile 은 그래도 사용자마다 하나씩 둔다.
계정을 나누지 않아도 아래는 profile 이 나눈다.

- session 과 메시지 기록
- `SOUL.md` 로 정하는 말투와 역할
- `memories/` 와 `skills/`
- API server 를 지키는 `API_SERVER_KEY`

즉 한 사용자의 대화 내용이 다른 사용자의 profile 로 새지 않는다.
공유하는 것은 요금을 내는 계정뿐이다.

공유를 모르고 하는 일이 없도록 두 곳에서 명시하게 한다.

- `hermes_profile_binding.credential_scope` 에 `SHARED_HOUSEHOLD` 나 `DEDICATED` 를 적는다.
  기본값이 없으므로 바인딩을 만드는 사람이 반드시 고른다.
- `configure-default-profile.sh verify` 는 profile 에 자기 credential 이 없으면 실패한다.
  `--allow-shared-credential` 을 줄 때만 넘어간다.

한 사람이 자기 계정을 쓰기로 하면 그 profile 에만 credential 을 넣고
`credential_scope` 를 `DEDICATED` 로 적는다.
그때 `cost_mode` 를 `API` 로 두면 그 사람 몫 비용을 계산할 수 있다.
나머지 사용자는 그대로 공유를 쓴다. 둘이 한 시스템에 함께 있을 수 있다.

### 거절한 대안

- 사용자마다 구독을 따로 사는 방식은 가족 규모에서 비용이 얻는 것보다 크므로 지금은 쓰지 않는다.
- 루트 `auth.json` 을 각 profile 로 복사해 격리한 것처럼 보이게 하는 방식은 쓰지 않는다.
  Hermes 주석이 이유를 적어 두었다. 복사하면 갱신 토큰이 둘로 갈라지고
  한쪽에서 갱신하는 순간 다른 쪽이 무효가 된다.
  같은 계정을 나눠 쓸 것이면 복사하지 말고 fallback 을 그대로 두어야 한다.
- 공유한다는 사실을 문서에만 적는 방식은 쓰지 않는다.
  시간이 지나면 어느 바인딩이 어떤 상태였는지 알 수 없게 된다.
  그래서 `credential_scope` 를 데이터로 남긴다.

### 결과

지금 상태에서 얻는 것은 아래와 같다.

- 구독 하나로 가족 전체가 쓴다.
- 누가 얼마나 썼는지는 `agent_execution` 의 사용자별 토큰 기록으로 본다.
- 대화와 기억은 여전히 사람마다 갈린다.

받아들이는 것은 아래와 같다.

- 구독의 요청 한도를 가족이 나눠 쓴다. 한 사람이 많이 쓰면 다른 사람이 기다린다.
- 구독형이라 실행 하나의 금액을 계산할 수 없다. 토큰 수까지만 남는다.
- 계정 수준에서 보면 모든 대화가 한 사람 명의다. 제공자 쪽 기록은 사용자를 구분하지 않는다.

한도를 나눠 쓰는 것이 불편해지면 그때 `DEDICATED` 로 한 명씩 옮긴다.
그 전환에 구조 변경은 필요하지 않다.
