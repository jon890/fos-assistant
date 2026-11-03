## ADR-20261103 / memory-sensitivity-removal: 기억의 민감 구분을 없애고, 같은 글이 같은 값이어야 하는 칸은 주인 key 의 HMAC 으로 둔다

- **status**: `accepted`
- Date: 2026-11-03
- [ADR-052](../../backend/docs/adr/ADR-052-memory-는-collection-종류-꺼내는-방식-민감도-판-출처를-가진다.md) 의 민감도(`sensitivity`)와 「민감 항목은 `ALWAYS` 로 둘 수 없다」 를 이 결정이 대체한다. 나머지 칸은 그대로다.
- [ADR-053](../../backend/docs/adr/ADR-053-에이전트는-허용된-collection-의-memory-만-받는다.md) 의 세 번째 조건(민감 항목은 그 collection 의 민감 허용을 받은 에이전트만)을 이 결정이 대체한다. 범위와 collection 조건은 그대로다.
- [ADR-20261008 / agent-memory-grants-admin](ADR-20261008-agent-memory-grants-admin.md) 의 collection 마다의 민감 허용과 `SENSITIVE_CHANGED` 기록을 이 결정이 대체한다.
- [ADR-055](../../backend/docs/adr/archive/ADR-055-민감-memory-본문은-저장할-때-암호화하고-key-는-환경-변수로-받는다.md) 의 남은 부분(옛 환경 변수 key 로 읽기)을 이 결정이 거둔다. 그 ADR 은 전체가 대체됐다.
- [ADR-20261015 / memory-hybrid-selection](../../backend/docs/adr/ADR-20261015-memory-hybrid-selection.md) 의 「민감 항목은 임베딩하지 않고 제목만 쓴다」, [ADR-20261008 / memory-facts](ADR-20261008-memory-facts.md) 의 민감 조건, [ADR-20261008 / memory-remember-guard](ADR-20261008-memory-remember-guard.md) 의 「민감도는 모델이 준 값 그대로 둔다」 도 이 결정이 대체한다.
- [ADR-20261103 / memory-encryption](../../backend/docs/adr/ADR-20261103-memory-encryption.md) 이 「감당할 것」 에 남긴 평문 해시를 이 결정이 거둔다.
- 스펙은 [`docs/features/memory/spec.md`](../features/memory/spec.md) 의 REQ5 와 DECISION1, DECISION10, DECISION17 이고, 설계는 [`design.md`](../features/memory/design.md) 의 「PR2」 절이 갖는다.

### 결정

**기억에는 민감 구분이 없다. 에이전트가 받을 기억은 collection 허용 하나로 나눈다.** 주인은 자기 기억을 모두 화면에서 보고 고친다.

| 무엇 | 바뀐 뒤 |
| --- | --- |
| 기억의 민감도 | 없다. 문맥 항목(`ContextItem`)의 민감도는 남고, 기억 항목은 늘 `NORMAL` 이다. 위임 결과처럼 기억 밖의 항목만 `SENSITIVE` 를 쓴다 |
| 에이전트의 민감 허용 | 없다. 화면, 요청, 응답에서 뺐다 |
| 화면의 「민감한 내용이라 보이지 않아요」 와 민감 항목 수정 거절 | 없다. 풀지 못한 줄만 「이 기억은 지금 읽지 못해요.」 다 |
| `memory_remember` 의 `sensitive` 인자 | 남는다. 참이면 늘 제안이다. 저장하는 값이 아니다(스펙 DECISION8) |
| 옛 환경 변수 key | 읽지 않는다. 설정에서 뺐다. 옛 key 의 줄은 운영에서 0건을 확인했다 |
| 제안 중복 키와 임베딩 지문 | 주인 데이터 key 에서 쓰임마다 뽑은 key 로 낸 HMAC-SHA256 이다. KEK 가 없는 설치는 평문이라 SHA-256 이다 |
| 이미 있던 해시 | 기동 작업이 HMAC 으로 바꾼다. 지금 글과 맞는 임베딩 지문은 그 자리에서 바꿔 다시 임베딩하지 않고, 맞지 않는 지문은 벡터째 비워 채우는 작업이 다시 만든다 |

HMAC key 는 `HMAC-SHA256(데이터 key, "data_key_mac:" + 쓰임)` 으로 뽑는다. 쓰임은 `memory:proposal_dedup` 과 `memory:embedding` 이다. 암호화 key 를 HMAC key 로 그대로 쓰지 않는다.

**칸은 PR2 에서 지우지 않았다.** `memory.sensitivity`, `memory_revision.sensitivity`, 두 표의 `content_key_id`, `agent_memory_collection.allow_sensitive`, `agent_memory_collection_change.allow_sensitive` 는 코드가 읽고 쓰지 않는 옛 칸이다.
새 코드가 넣는 줄을 위해 기본값이 없던 두 칸에 기본값만 두었고(`V20261103054413`), 배포를 확인한 뒤 PR3 이 지웠다. 지우기 전에 `V20261103054416` 이 옛 key 줄이 0건인지 보고 남았으면 멈춘다. 이 칸을 지운 뒤에는 PR1 이전 이미지로 되돌리지 못한다.

### 맥락

민감 기억 28건은 암호화돼 있었지만 민감 허용을 켠 에이전트가 없어 아무도 쓰지 못했고, 주인도 화면에서 보지 못했다.
[ADR-20261103 / memory-encryption](../../backend/docs/adr/ADR-20261103-memory-encryption.md) 으로 모든 기억의 제목과 본문이 암호문이 되어, 민감 구분이 따로 지키던 것(본문을 평문으로 두지 않기)이 모든 기억에 적용된다.
사용자는 민감 구분을 없애고 에이전트별 분리는 collection 으로 하기로 정했다(스펙 DECISION1, DECISION10).

### 대안 기각

- **민감 구분을 남기고 주인만 볼 수 있게 한다**: 다른 서비스에도 본인에게 숨기는 층이 없고, 민감 허용을 켠 에이전트가 하나도 없어 지금도 아무도 쓰지 못한다.
- **칸을 이 PR 에서 지운다**: REQ5 가 한 PR 로 닫힌다. 그러나 `ddl-auto: validate` 라 직전 이미지로 되돌리면 기동하지 못한다. 권한 경계를 바꾸는 배포라 되돌릴 길을 남긴다([ADR-20261102 / table-reduce-r2](../../backend/docs/adr/ADR-20261102-table-reduce-r2.md) 의 선례, 코디네이터 결정 2026-11-03).
- **해시를 앱 전체 key 하나로 낸다**: key 를 하나 더 두어야 하고, 사용자의 데이터 key 를 지워도 그 사용자의 해시를 맞혀 볼 수 있다.
- **해시를 비운다**: 제안 중복 막기와 낡은 벡터 알아보기가 둘 다 깨진다.
- **임베딩 지문을 바꿀 때 모두 다시 임베딩한다**: 코드가 적다. 그러나 다시 쓴 벡터마다 연결 찾기가 다시 돌아 이미 정리한 대체 제안이 다시 생길 수 있다.

### 결과

- 얻는 것:
  - 주인은 자기 기억을 모두 보고 고친다. collection 을 받은 에이전트는 예전 민감 기억도 쓴다.
  - DB 에 기억 글의 평문 해시가 남지 않는다. 사용자의 데이터 key 줄을 지우면 그 사용자의 해시도 맞혀 볼 수 없다.
  - 칸을 지우기 전까지는 직전 이미지로 되돌려도 떴다. 되돌린 이미지는 칸에 남은 `SENSITIVE` 를 다시 읽어 그 줄을 숨겼다. 칸을 지운 뒤에는 그렇게 되돌리지 못한다.
- 감당할 것:
  - 민감해 보이는 글을 에이전트에 싣지 않는 장치가 없다. 받는 collection 을 고르는 것이 그 장치다. 바로 저장 조건 6, 7 은 제안 카드로 내릴 뿐이다.
  - HMAC 과 임베딩 지문을 낼 때마다 주인 데이터 key 가 필요하다. 풀린 줄은 이미 그 key 를 캐시에 둔다.
  - 직전 이미지로 되돌린 동안 직전 이미지는 SHA-256 으로 중복 키를 내 같은 제안이 한 번 더 생길 수 있다. 되돌림이라는 예외 상황이라 막지 않는다.
  - 예전 민감 기억도 이제 임베딩한다. 그 글이 임베딩 서비스로 가고 벡터는 평문으로 남는다(스펙 DECISION6 의 벡터 평문과 같다).
  - 직전 이미지로 되돌리면 그 이미지는 HMAC 지문을 낡은 것으로 보고 벡터를 모두 다시 임베딩한다. 그때 연결 찾기가 다시 돌아 정리한 대체 제안이 다시 생길 수 있다.
  - 옛 칸은 PR2 배포를 확인한 PR3 이 지웠다. 그 뒤에는 PR1 이전 이미지로 되돌리지 못한다.
