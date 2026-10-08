# 암호화 key 표

## user_data_key

사용자 한 명의 데이터 key(DEK)를 KEK 로 감싼 것이다. 사용자마다 한 줄이다.
원문 key 는 저장하지 않는다. KEK 는 데이터베이스 밖의 서버 파일에 있고, 이 표에는 어느 KEK 로 감쌌는지만 적는다.
근거와 위협 모델은 [ADR-20261008 / data-encryption](../../adr/ADR-20261008-data-encryption.md) 에 있다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | BIGINT | 본문 칸 옆의 `*_key_id` 가 가리키는 번호 |
| `user_id` | BIGINT | 이 key 의 주인. `app_user` 의 외래 키다 |
| `kek_id` | VARCHAR(32) | 감싼 KEK 의 id. KEK 를 바꾸면 기동 작업이 새 id 로 다시 감싼다 |
| `wrapped_key` | VARCHAR(255) | `v1.<IV>.<감싼 key 와 태그>`. AAD 는 `user_data_key:user:<user_id>` 다 |
| `created_at` | DATETIME(6) | |
| `rewrapped_at` | DATETIME(6) NULL | 마지막으로 다른 KEK 로 다시 감싼 시각 |

| 제약 | 까닭 |
| --- | --- |
| `UNIQUE (user_id)` | 사용자 한 명의 본문은 key 하나로 암호화한다. 같은 사용자의 첫 저장 둘이 겹치면 한쪽이 이 제약에 걸려 다시 읽는다 |

**이 줄을 지우면 그 사용자의 암호문은 누구도 풀지 못한다.** 사용자를 지우는 흐름이 생기면 이 줄을 지워 본문을 없앤다.
본문 칸 쪽은 이 표에 외래 키를 두지 않는다. 이 줄을 지우는 길을 막지 않기 위해서다.
