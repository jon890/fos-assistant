# 사용자 브라우저

사용자마다 하나씩 두는 브라우저의 표와 칸, 제약을 갖는다.
상태 전이와 API 는 [`../user-browser.md`](../user-browser.md) 가 갖고, 결정은 [ADR-20261007 / user-browser](../../adr/ADR-20261007-user-browser.md) 가 갖는다.

## user_browser

브라우저 하나다. 사용자 하나에 한 줄이다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | `BIGINT` 기본키 | |
| `user_id` | `BIGINT NOT NULL`, 유니크 | 주인. `app_user` 참조 |
| `status` | `VARCHAR(16) NOT NULL` | `STOPPED`, `STARTING`, `RUNNING`, `STOPPING`, `FAILED` |
| `profile_key` | `CHAR(64) NOT NULL` | 프로필 디렉터리 이름. `SHA-256("u" + user_id)` 의 소문자 16진수다 |
| `container_id` | `VARCHAR(80)` | 켜져 있을 때만 채운다 |
| `last_error` | `VARCHAR(40)` | `FAILED` 의 까닭. Control Plane 이 정한 코드만 넣는다 |
| `last_active_at` | `DATETIME(6)` | 화면 입력이나 중계 통신이 마지막으로 있던 시각. 1분에 한 번까지만 쓴다 |
| `started_at` | `DATETIME(6)` | 지금 켜진 컨테이너를 켠 시각. 멈추면 비운다 |
| `created_at`, `updated_at` | `DATETIME(6) NOT NULL` | |
| `version` | `BIGINT NOT NULL` | 낙관적 잠금. 전이가 한 번에 하나씩만 일어난다 |

- `user_id` 에 외래 키를 둔다. 사용자 줄이 지워지면 이 줄도 지워진다
- 쿠키, 저장소, 열린 주소, 화면 프레임은 이 표에 없다. 로그인 세션은 프로필 디렉터리에만 남는다
