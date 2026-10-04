# 알림

사용자에게 대화 밖에서 알리는 줄을 저장하는 표의 칸과 제약을 갖는다.
언제 만들고 화면이 어떻게 받는지는 [`../notification.md`](../notification.md) 가 갖는다.

## notification

알림 하나다. 근거는 [ADR-070](../../adr/ADR-070-알림은-control-plane-의-notification-표가-원장이고-웹은-사용자-단위-SSE-로-받는다.md) 이다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | `BIGINT` 기본키 | Control Plane 밖으로 나가지 않는다 |
| `public_id` | `BINARY(16) NOT NULL`, 유니크 | 화면과 API 가 쓰는 UUID v7. 대화의 공개 식별자와 같은 방식이다([ADR-025](../../adr/ADR-025-대화는-주소에-공개-식별자를-쓰고-번호는-안에만-둔다.md)) |
| `user_id` | `BIGINT NOT NULL` | 받는 사람. `app_user` 참조 |
| `kind` | `VARCHAR(32) NOT NULL` | 알림 종류. 값은 [`../notification.md`](../notification.md) 의 「알림 종류」 가 갖는다 |
| `title` | `VARCHAR(200) NOT NULL` | 사람이 읽는 제목 |
| `body` | `VARCHAR(500) NOT NULL` | 사람이 읽는 짧은 본문. 빈 문자열을 받는다. 도구 인자 원문, 비밀값, 모델 답 전문을 넣지 않는다 |
| `target_type` | `VARCHAR(20)` | 누르면 갈 곳의 종류. 지금은 `CONVERSATION`. 갈 곳이 없으면 비운다 |
| `target_public_id` | `BINARY(16)` | 갈 곳의 공개 식별자. `target_type` 과 함께 채우거나 함께 비운다 |
| `created_at` | `DATETIME(6) NOT NULL` | |
| `read_at` | `DATETIME(6)` | 읽음으로 표시한 시각. 비면 읽지 않았다 |

- `user_id` 에만 외래 키를 둔다. 갈 곳은 지워져도 알림을 남긴다
- 보관 기간이 지난 줄은 지운다. 원인이 된 승인 줄과 실행 기록은 따로 남는다
- 다음 단계의 외부 채널이 어디로 보냈는지는 이 표에 아직 칸이 없다. 그 단계가 더한다
