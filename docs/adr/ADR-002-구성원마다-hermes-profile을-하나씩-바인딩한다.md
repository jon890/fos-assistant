## ADR-002: 구성원마다 Hermes profile 을 하나씩 바인딩한다

- Status: Accepted
- Date: 2026-09-17

### 맥락

가족 구성원마다 자기 AI credential 을 연결할 수 있어야 한다.
한 사람의 요청이 다른 사람의 credential 로 넘어가면 과금과 개인 자료가 함께 섞인다.

Hermes 는 profile 마다 `config.yaml`, `.env`, `auth.json`, `SOUL.md`, memory, session 을 따로 둔다.
`config.yaml` 의 `${VAR}` 는 그 profile 의 `.env` 에서만 값을 찾는다.

### 결정

구성원 한 사람에 Hermes profile 하나를 붙인다.
`hermes_profile_binding` 이 그 관계를 갖고 `user_id` 와 `profile_name` 에 각각 유일 제약을 둔다.

profile 의 API server key 는 데이터베이스가 아니라 홈서버의 디렉터리에 둔다.
파일 이름이 profile 이름이고 내용이 key 다.
`HermesProfileKeyStore` 가 요청받은 profile 이름으로만 파일을 찾고,
없으면 다른 파일로 대신하지 않고 `HERMES_PROFILE_KEY_MISSING` 으로 끝난다.

profile 마다 `fallback_providers` 를 비워 둔다.

### 거절한 대안

- profile 하나를 공유하고 요청마다 credential 을 바꿔 넣는 방식은
  Hermes 의 격리를 우리가 다시 만들어야 하고, 한 번의 실수가 남의 계정으로 과금되므로 쓰지 않는다.
- credential 을 우리 데이터베이스에 암호화해 보관하는 방식은
  Hermes 가 이미 profile 단위로 보관하고 있어 같은 비밀을 두 곳에 두게 되므로 쓰지 않는다.
- 사용자마다 Hermes 컨테이너를 따로 띄우는 방식은 가족 규모에 견줘 운영 부담이 크므로 쓰지 않는다.

### 결과

credential 격리를 우리 코드가 아니라 런타임이 보장한다.
바인딩이 없는 구성원은 실행 자체가 막히므로, 연결되지 않은 사람이 남의 계정을 쓰는 경로가 없다.

대신 구성원을 추가할 때마다 profile 을 만들고 key 파일을 놓는 운영 작업이 생긴다.
사람 수가 적어 감당할 수 있는 수준으로 본다.
