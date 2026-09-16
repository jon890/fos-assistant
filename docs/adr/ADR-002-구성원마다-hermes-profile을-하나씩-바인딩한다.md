## ADR-002: 구성원마다 Hermes profile 을 하나씩 바인딩한다

- Status: Accepted
- Date: 2026-09-17
- 개정: 2026-09-17, OAuth credential 은 profile 로 격리되지 않는다는 사실을 홈서버에서 확인해 반영

### 맥락

가족 구성원마다 자기 AI credential 을 연결할 수 있어야 한다.
한 사람의 요청이 다른 사람의 credential 로 넘어가면 과금과 개인 자료가 함께 섞인다.

Hermes 는 profile 마다 `config.yaml`, `.env`, `SOUL.md`, memory, session 을 따로 둔다.
`config.yaml` 의 `${VAR}` 는 그 profile 의 `.env` 에서만 값을 찾는다.

여기까지는 문서가 말하는 대로다.
그런데 홈서버의 Hermes v0.21.0 소스를 읽어 보니 OAuth credential 은 다르게 동작한다.

`hermes_cli/auth.py` 는 profile 에 `auth.json` 이 없으면 루트의 `~/.hermes/auth.json` 을 읽는다.
`tui_gateway/methods_profiles.py` 의 주석이 그것을 직접 말한다.

> profile reads fall back to the global store, and token refreshes write THROUGH to it

홈서버에 이미 있는 profile 넷은 모두 자기 `auth.json` 이 없다.
`brain-api`, `career`, `stock`, `ji-yoon-blog` 가 한 로그인을 함께 쓰고 있다.

즉 profile 을 나누는 것만으로는 credential 이 갈리지 않는다.
바로 이것이 요구사항이 막으라고 한 자동 fallback 이다.

### 결정

구성원 한 사람에 Hermes profile 하나를 붙인다.
`hermes_profile_binding` 이 그 관계를 갖고 `user_id` 와 `profile_name` 에 각각 유일 제약을 둔다.

여기에 credential 격리 조건을 더한다.
profile 을 바인딩하기 전에 그 profile 이 아래 둘 중 하나를 가져야 한다.

- 자기 `auth.json`. `hermes -p <member> login` 이 만든다
- 자기 `.env` 안의 provider API key. `API_SERVER_KEY` 는 여기 해당하지 않는다

둘 다 없으면 그 profile 은 홈서버 공용 로그인을 쓴다.
`configure-member-profile.sh credential-check` 가 이것을 판정하고 실패로 끝낸다.

profile 의 API server key 는 데이터베이스가 아니라 홈서버의 디렉터리에 둔다.
파일 이름이 profile 이름이고 내용이 key 다.
`HermesProfileKeyStore` 가 요청받은 profile 이름으로만 파일을 찾고,
없으면 다른 파일로 대신하지 않고 `HERMES_PROFILE_KEY_MISSING` 으로 끝난다.

profile 마다 `fallback_providers` 를 비워 둔다.

### 거절한 대안

- profile 하나를 공유하고 요청마다 credential 을 바꿔 넣는 방식은
  Hermes 의 격리를 우리가 다시 만들어야 하고, 한 번의 실수가 남의 계정으로 과금되므로 쓰지 않는다.
- credential 을 우리 데이터베이스에 암호화해 보관하는 방식은
  Hermes 가 이미 profile 단위로 보관할 수 있어 같은 비밀을 두 곳에 두게 되므로 쓰지 않는다.
- 루트 `auth.json` 을 각 profile 로 복사하는 방식은 쓰지 않는다.
  Hermes 주석이 이유를 적어 두었다. 복사하면 갱신 토큰이 둘로 갈라지고
  한쪽에서 갱신하는 순간 다른 쪽이 무효가 된다.
  같은 계정을 나눠 쓸 것이면 복사하지 말고 fallback 을 그대로 두어야 한다.
- 사용자마다 Hermes 컨테이너를 따로 띄우는 방식은 가족 규모에 견줘 운영 부담이 크므로 쓰지 않는다.

### 결과

`.env` 로 연결한 API key 는 런타임이 격리를 보장한다.
OAuth 로 연결한 계정은 그렇지 않으므로 우리가 설치 시점에 검사한다.
검사는 실행할 때마다가 아니라 바인딩을 만들 때 한 번 한다.

첫 구성원이 홈서버 주인 자신이면 공용 로그인을 그대로 써도 자기 계정이다.
그 경우에도 검사를 통과하지 못하므로, 공용을 쓴다는 것을 알고 넘어가게 된다.
모르고 넘어가는 경로가 없다는 것이 이 결정의 목적이다.

두 번째 구성원부터는 자기 credential 이 반드시 필요하다.
