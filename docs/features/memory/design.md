# 기억 전체 암호화 기술 설계

스펙: [`spec.md`](spec.md). PR1 이 구현한 REQ1 부터 REQ4, REQ6 부터 REQ8 은 앞의 절들이, PR2 가 구현한 REQ5 의 코드와 화면, 스펙 DECISION17 은 「PR2: 민감 구분 제거와 평문 해시」 절이, REQ5 의 「칸이 없다」 는 「PR3: 옛 칸 삭제」 절이 다룬다.
되돌리기 어려운 결정과 근거는 [ADR-20261103 / memory-encryption](../../../backend/docs/adr/ADR-20261103-memory-encryption.md) 이 갖는다.
PR2 와 PR3 의 결정은 [ADR-20261103 / memory-sensitivity-removal](../../adr/ADR-20261103-memory-sensitivity-removal.md) 이 갖는다.

## 지금 구조

- 민감 기억 본문만 환경 변수 key 로 암호화한다([ADR-055](../../../backend/docs/adr/archive/ADR-055-민감-memory-본문은-저장할-때-암호화하고-key-는-환경-변수로-받는다.md)). `content_key_id` 가 그 key id(글)를 적는다. 제목과 일반 본문은 평문이다.
- 대화 메시지는 KEK 로 감싼 사용자별 데이터 key 로 암호화한다([ADR-20261008 / data-encryption](../../../backend/docs/adr/ADR-20261008-data-encryption.md)). 엔티티는 저장된 글을 들고, 읽어 온 엔티티에 Hibernate `POST_LOAD` 사건으로 복호화 도구를 붙여 본문을 처음 꺼낼 때 푼다(`ChatMessageLoadListener`).
- 기억의 제목과 본문을 읽는 자리는 엔티티의 `title()`, `content()` 를 그대로 부른다. 조립(`context`), 도구(`mcp`), 화면 DTO, 정비, 대화의 「참고한 기억」 이 모두 그렇다. JPQL 이 제목과 본문을 직접 고르는 곳은 검색 후보(`MemorySearchCandidates`)와 임베딩 원천(`MemoryEmbeddingRepository`) 두 곳이다.

## 바꾸는 구조 한눈에

| 층 | 무엇을 바꾸나 |
| --- | --- |
| `crypto` | `user_data_key` 줄의 주인을 사용자 또는 그룹으로 넓힌다. 주인 값 `KeyOwner` 와, 주인으로 암호화하고 푸는 `OwnedTextCipher` 를 더한다. 기존 `TextCipher`, `BinaryCipher` 는 그대로 사용자 주인으로 돈다 |
| `memory.domain` | `Memory`, `MemoryRevision` 이 저장된 글(`storedTitle`, `storedContent`)과 데이터 key 번호(`dataKeyId`)를 들고, `title()`, `content()` 는 처음 부를 때 푼 글을 낸다. 풀지 못하면 `readable()` 이 거짓이고 제목은 「읽지 못함」 이다 |
| `memory.application` | `MemoryTexts` 가 새 줄의 암호화와 읽어 온 줄의 풀기를 맡는다. `MemoryTextMigration` 이 기동할 때 남은 줄을 데이터 key 로 옮긴다. 옛 보정(`MemoryContentBackfill`, `MemoryContentSealer`)은 지운다 |
| `memory.infra` | 읽어 온 기억과 revision 에 `MemoryTexts` 를 붙이는 `POST_LOAD` 사건을 둔다. 제목과 본문을 직접 고르던 질의는 엔티티를 읽게 바꾼다 |
| 소비자 | 「암호문이라 건너뛴다」(`sealed()`) 판정을 「풀었다」(`readable()`) 판정으로 바꾼다 |
| DB | `memory`, `memory_revision` 에 `data_key_id` 컬럼, 제목 컬럼 길이를 넓힌다. `user_data_key` 에 `group_id` 컬럼을 더하고 `user_id` 를 비울 수 있게 한다. 새 테이블은 없다 |

## REQ 마다 해결 방법

### REQ1 제목과 본문을 평문으로 두지 않는다

- 새 컬럼 `data_key_id BIGINT NULL` 을 `memory` 와 `memory_revision` 에 둔다. 제목과 본문을 암호화한 `user_data_key.id` 다. 비어 있으면 두 컬럼이 평문이거나 옛 key 의 줄이다.
  옛 컬럼 `content_key_id`(환경 변수 key 의 id)는 이관이 끝날 때까지 읽기만 하고 PR2 가 읽기를 걷어 냈으며 PR3 이 칸을 지웠다. 숫자 key 번호와 글 key id 를 한 컬럼에 섞지 않는다(DECISION1).
- 제목과 본문은 같은 데이터 key 로 따로 암호화한다. 모양은 기존 `v1.<IV>.<암호문과 태그>` 다.
- **AAD 는 `memory:<memory_id>:<컬럼>:<USER|GROUP>:<주인 번호>` 다.** `<컬럼>` 은 `title` 이나 `content` 다. revision(`memory_revision`)은 자기 `memory_id` 로 같은 AAD 를 만든다(DECISION2).
  그래서 revision 을 남길 때 지금 줄의 암호문을 다시 쓰지 않고 그대로 옮긴다. 암호문을 다른 기억이나 다른 컬럼, 다른 주인으로 옮기면 풀리지 않는다.
- 줄 번호가 생겨야 AAD 를 만들 수 있다. 새 기억은 대화 메시지와 같이 빈 글로 넣고, 같은 트랜잭션에서 번호로 AAD 를 만들어 암호문으로 고친다. DB 에 평문이 한 번도 쓰이지 않는다.
  새 기억을 넣는 자리는 모두 `MemoryTexts.insert` 를 거친다. `MemoryService` 의 만들기와 제안, 문서 만들기, `MemoryCaptureService` 의 바로 저장과 제안이다.
- **줄을 넣기 전에 주인 데이터 key 를 쓸 수 있는지 먼저 본다.** 넣은 뒤에 거절하면, 거절을 결과 값으로 바꿔 돌려주는 `MemoryCaptureService.remember` 같은 자리에서 빈 제목과 빈 본문의 줄이 커밋된다.
- 고치기(`MemoryRevisionWriter.revise`)는 제목과 새 본문을 먼저 암호화하고, 엔티티의 `revise` 가 그 암호문을 저장 필드에 한 번에 적는다. 평문이 저장 필드에 놓이는 순간이 없어 중간의 자동 flush 가 평문을 쓰지 못한다.
- revision 은 지금 줄이 데이터 key 의 암호문이면 그대로 옮긴다. 아직 이관하지 않은 줄(웹 요청은 기동 이관보다 먼저 받는다)의 revision 은 주인 데이터 key 로 새로 암호화해 남긴다.
- 새 저장 경로가 `MemoryTexts` 를 거치지 않아 평문 줄이 생겨도, 다음 기동의 이관이 그 줄을 데이터 key 로 옮긴다. 저장 경로마다 암호문인지는 테스트가 본다.
- 질의가 제목과 본문을 직접 고르면 암호문이 나간다. 그래서 검색 후보와 임베딩 원천은 엔티티를 읽고 앱에서 값으로 옮긴다. 운영 기억이 수십 건이라 줄마다 엔티티를 읽는 비용은 작다. 측정 게이트(REQ4)로 확인한다.

### REQ2 주인 key 로 암호화한다

- `user_data_key` 에 `group_id BIGINT NULL` 컬럼을 더하고 `user_id` 를 `NULL` 로 둘 수 있게 한다. CHECK 제약으로 둘 가운데 정확히 하나만 채운다. `group_id` 에 유일 제약을 둔다(그룹마다 한 줄).
  테이블 이름은 바꾸지 않는다. 바꾸면 외부 백업 절차와 사용자 정리(`UserPurgeWriter`)가 함께 바뀐다(DECISION3).
- 데이터 key 를 감싸는 AAD 는 사용자 줄이 지금처럼 `user_data_key:user:<사용자 번호>`, 그룹 줄이 `user_data_key:group:<그룹 번호>` 다. 감싼 key 를 다른 주인의 줄로 옮기면 풀리지 않는다.
- `crypto.domain.KeyOwner` 는 주인의 종류(`USER`, `GROUP`)와 번호다. `DataKey` 는 사용자 번호 대신 주인을 든다.
- `crypto.domain.OwnedTextCipher` 를 더한다. `seal(KeyOwner, aad, plain)` 과 `open(keyId, KeyOwner, aad, sealed)` 두 동작이고 `DataKeyService` 가 구현한다.
  기존 `TextCipher` 와 `BinaryCipher` 의 메서드는 바꾸지 않고 `KeyOwner.user(...)` 로 같은 길을 돈다. 테스트의 익명 구현과 mock 이 그대로 돈다.
- 풀 때 데이터 key 줄의 주인이 그 기억의 주인과 다르면 풀지 않는다(기존 사용자 key 와 같은 검사). 다른 사용자나 다른 그룹의 key 로 쓴 암호문은 열리지 않는다.
- KEK 교체(`DataKeyRewrapper`)는 그룹 줄도 같은 방식으로 다시 감싼다. 감싸기 AAD 를 줄의 주인으로 만든다.
- 사용자 정리는 `user_id` 로만 지우므로 그룹 줄을 건드리지 않는다. 그룹 줄의 수명은 그룹 삭제 흐름이 생기면 정한다(스펙 DECISION16).

### REQ3 민감 key 로 암호화한 28건을 잃지 않고 옮긴다

`MemoryTextMigration`(`ApplicationRunner`)이 기동할 때 돈다. 순서와 실패 처리는 아래 「이관 순서와 실패 처리」 가 갖는다.

- 대상은 `data_key_id` 가 빈 `memory` 줄과 `memory_revision` 줄 전부다. 평문 줄과 옛 key 줄이 함께 든다.
- 줄마다 트랜잭션 하나에서 쓰기 잠금으로 다시 읽고, 평문을 얻어(옛 key 줄은 `MemoryContentCipher.open`) 주인 데이터 key 로 다시 쓴다. `content_key_id` 는 비운다. revision 번호와 `updated_at`, `changed_at` 은 그대로 둔다.
- **같은 트랜잭션에서 새 암호문을 다시 풀어 옮기기 전 평문의 SHA-256 과 비교한다.** 다르면 그 줄을 되돌리고 실패로 센다. 그래서 줄마다 해시 대조를 지난 줄만 커밋된다.
- 끝나면 옮긴 수(해시 대조를 지난 줄만 센다)와 그 가운데 옛 key 줄 수(`legacyVerified`), 실패 수를 info 한 줄로, 실패한 줄 번호를 error 한 줄로 남긴다. 줄마다의 실패는 예외 클래스 이름만 남긴다. 본문과 해시 값은 로그에 넣지 않는다.
- 옛 key 줄이 0건이 되면 옛 key 설정은 읽을 줄이 없다. 옛 key 를 빼는 일은 운영 확인 뒤 코디네이터가 한다(스펙 DECISION12).

### REQ4 화면과 에이전트의 동작은 같다

엔티티의 `title()`, `content()` 가 푼 글을 내므로 대부분의 소비자는 코드가 바뀌지 않는다. 바뀌는 자리는 아래뿐이다.

| 자리 | 지금 | 바꾼 뒤 |
| --- | --- | --- |
| 개인 사실 구역, 항상 층(`ContextAssembler`) | `!memory.sealed()` 인 줄만 싣는다 | `memory.readable()` 인 줄만 싣는다 |
| 질문·연결 구역(`MemorySearchSection`, `MemoryLinkSection`) | 일반이고 평문인 줄만 본문을 점수에 넘긴다 | 일반이고 풀린 줄만 본문을 넘긴다 |
| `memory_search` 후보(`MemorySearchCandidates`) | SQL 이 민감·암호문 본문을 `NULL` 로 바꾼다 | 권한 조건 그대로 엔티티를 읽고, 풀리지 않은 줄은 빼고, 민감 줄의 본문은 앱에서 `null` 로 바꾼다 |
| 임베딩 원천(`MemoryEmbeddingRepository`) | `content_key_id is null` 인 일반 줄 | `content_key_id` 조건을 빼고 엔티티를 읽어 풀린 줄만 낸다. 민감 줄은 지금처럼 `sensitivity` 조건으로 빠진다 |
| 바로 저장의 되돌리기(`MemoryCaptureService.restore`) | 암호문 revision 이면 되돌리지 않는다 | 풀린 revision 이면 되돌린다. 민감 revision 은 지금처럼 이 길로 오지 않는다 |
| 정비 규칙의 중복(`MemoryMaintenanceRules.dedup`) | 민감 줄과 암호문 줄을 보지 않는다 | 민감 줄과 풀리지 않은 줄을 보지 않는다 |
| 화면 DTO(`MemoryDtos`, `ChatDtos`) | 민감이거나 암호문이면 본문을 빈 글로 낸다 | 민감이거나 풀리지 않았으면 빈 글로 낸다. 목록 DTO 에 `unreadable` 을 더한다 |
| `memory_read`(`MemoryService.contentOf`) | 옛 key 로 푼다 | 엔티티의 `content()` 를 낸다. 풀리지 않았으면 지금처럼 `MEMORY_ENCRYPTION_UNAVAILABLE` 이다 |

- 민감 구분은 PR1 에서 그대로다. 민감 허용이 없는 에이전트는 민감 줄을 받지 않고, 화면은 민감 본문을 싣지 않는다. 바꾸는 것은 저장 모양뿐이다.
- 「Memory 회수 측정」 게이트는 기존 측정 테스트(`MemoryEmbeddingEvalTest` 와 합성 측정)를 그대로 돌려 확인한다. 기준 파일을 바꾸지 않는다.

### REQ6 key 를 읽지 못하면 평문으로 내려 저장하지 않는다

| 상태 | 저장과 수정 | 읽기 |
| --- | --- | --- |
| KEK 가 설정되지 않았다(`required` 가 거짓인 로컬과 개발) | 평문으로 저장한다. 처음 평문으로 저장할 때 한 번 경고한다. 대화 메시지와 같다 | 평문 줄을 그대로 읽는다 |
| KEK 는 있는데 그 주인의 데이터 key 를 풀지 못한다 | `MEMORY_ENCRYPTION_UNAVAILABLE`(409)로 거절한다. 줄도 revision 도 바뀌지 않는다. 평문으로 내려 저장하지 않는다 | 그 줄만 「읽지 못함」 이다. 목록은 실패하지 않는다 |
| 데이터 key 는 있는데 암호문이 풀리지 않는다(다른 줄에서 옮겼다, 태그가 틀렸다) | 고치기를 `MEMORY_ENCRYPTION_UNAVAILABLE` 로 거절한다. 제목을 몰라 덮어쓰면 제목을 잃는다. 지우기는 된다 | 그 줄만 「읽지 못함」 이다 |
| 데이터베이스에 닿지 못한다 | 요청이 실패한다 | 요청이 실패한다. 일시 장애를 「읽지 못함」 으로 기억하지 않는다(기존 `DataKeyService` 동작) |

- 운영은 `assistant.data-encryption.required` 가 켜져 있어 첫 줄의 상태가 생기지 않는다.
- 「읽지 못함」 은 엔티티의 `title()` 이 내는 고정 글이다. 본문은 빈 글이다. 풀리지 않은 줄은 실행에 싣지 않는다. `MemoryService` 가 항상 층과 색인 후보를 낼 때 한 번에 빼므로 개인 사실 구역, 질문·연결 구역, 살펴보기 입력이 같은 판정을 쓴다. 검색 후보와 임베딩 원천에서도 뺀다. 목록 DTO 의 `unreadable` 이 참이면 화면은 고치기 단추를 숨기고 본문 자리에 「이 기억은 지금 읽지 못해요.」 를 보인다. 지우기는 그대로 된다.
- 풀기 실패 경고는 데이터 key 번호와 주인, 옛 key 줄이면 기억 번호만 남긴다. 풀지 못한 데이터 key 는 1분 동안 다시 읽지 않는다(`DataKeyService` 의 기존 동작).

### REQ7 관리자 화면은 남의 개인 기억 제목을 보이지 않는다

지금 코드가 이미 만족한다. 이 PR 은 테스트로 고정한다.

- 에이전트 상세의 「기억 영역」(`AgentMemorySettingService`)은 collection 마다 건수와 collection 변경 기록만 낸다. 항목 제목은 응답에 없다.
- 정리 후보(`MemoryMaintenanceService.candidatesFor`)는 `USER` 항목을 주인에게만 낸다. 그룹 관리자는 `GROUP` 항목만 본다.
- 테스트는 다른 사용자의 `USER` 기억 제목이 두 응답 어디에도 없고, 그 기억이 건수에는 들어 있는 것을 본다.

### REQ8 이관 실패가 남지 않는다

- 이관은 줄 하나가 실패해도 그 줄 번호만 로그에 남기고 다음 줄로 간다. 기동은 멈추지 않는다. 다음 기동이 남은 줄을 다시 본다.
- 실패한 줄은 옛 모양 그대로다. 평문 줄은 평문으로, 옛 key 줄은 옛 key 로 읽힌다. PR1 은 옛 key 읽기를 남겨 둔다.
- PR2 는 운영에서 `data_key_id` 가 빈 줄과 `content_key_id` 가 찬 줄이 모두 0건인 것을 확인한 뒤에만 연다. 확인은 코디네이터가 한다.

## 책임과 경계

| 클래스 | 자리 | 맡는 것 |
| --- | --- | --- |
| `KeyOwner` | `crypto.domain` | 데이터 key 주인. 종류와 번호. 감싸기 AAD 를 만든다 |
| `OwnedTextCipher` | `crypto.domain` | 주인으로 글을 암호화하고 푼다. 구현은 `DataKeyService` |
| `UserDataKey` | `crypto.domain.entity` | `user_id` 또는 `group_id` 하나를 주인으로 갖는다 |
| `MemoryTextOpener` | `memory.domain` | 엔티티가 처음 글을 꺼낼 때 부르는 복호화 도구. 대화의 `MessageContentOpener` 와 같은 역할이다 |
| `MemoryTexts` | `memory.application` | `MemoryTextOpener` 구현. 새 줄 넣기(`insert`), 고칠 본문 암호화, revision 암호화, 풀기. 옛 key 줄은 `MemoryContentCipher` 로 푼다 |
| `MemoryTextMigration` | `memory.application` | 기동할 때의 이관. 줄마다 `REQUIRES_NEW` 의 `TransactionTemplate` 으로 트랜잭션을 연다. 바깥 트랜잭션 안에서 불려도 줄마다 따로 커밋한다 |
| `MemoryTextLoadListener` | `memory.infra` | `POST_LOAD` 에서 `MemoryTextOpener` 를 붙인다. infra 는 domain 의 인터페이스만 알고 application 의 빈 이름을 모른다(rule-5) |
| `MemoryContentCipher` | `memory.application` | 옛 환경 변수 key 로 푼다. 새로 암호화하지 않는다. PR2 가 지운다 |

- `memory.application` 직하 클래스 수는 rule-75 의 상한(25)에 닿아 있다. 옛 보정 둘을 지우고 새 둘을 더해 수가 늘지 않는다. 더 늘려야 하면 이관을 하위 패키지로 옮긴다.
- 엔티티에 리스너 주석을 달지 않는다. 달면 domain 이 infra 를 가리킨다. 대화 메시지와 같이 Hibernate 사건 목록에 직접 더한다.
- `MemoryTexts` 는 푼 글을 엔티티의 `@Transient` 필드에만 둔다. 저장 필드(`storedTitle`, `storedContent`)은 바꾸지 않으므로 읽기만 한 엔티티가 dirty 로 보이지 않는다.

## 데이터

| 테이블 | 컬럼 | 바꾸는 것 |
| --- | --- | --- |
| `memory` | `data_key_id` | 새 컬럼. `BIGINT NULL`. 외래 키를 두지 않는다(데이터 key 줄을 지워 본문을 없애는 길을 막지 않는다) |
| `memory` | `title` | `VARCHAR(200)` 에서 `VARCHAR(1200)` 으로 넓힌다. 제목 200자를 4바이트 글자로 채운 암호문이 들어가는 길이다. 입력 제한 200자는 화면 요청(`@Size`), 문서 요청, `memory_remember` 의 검사가 지킨다. 컬럼이 넓어져 마지막 제한이 사라지므로 화면 요청에 빠져 있던 `@Size(max = 200)` 를 더한다 |
| `memory_revision` | `data_key_id`, `title` | `memory` 와 같다 |
| `user_data_key` | `group_id` | 새 컬럼. `BIGINT NULL`, 유일 |
| `user_data_key` | `user_id` | `NOT NULL` 을 푼다. CHECK 로 `user_id` 와 `group_id` 가운데 하나만 찬다 |

- DDL 과 DML 을 섞지 않는다. 이 PR 의 마이그레이션은 DDL 하나다. 줄을 옮기는 일은 key 가 필요해 SQL 이 아니라 기동 작업이 한다.
- 새 컬럼과 바꾼 컬럼에 한국어 `COMMENT` 를 단다. `MODIFY` 는 기존 설명을 그대로 다시 적는다.

## 이관 순서와 실패 처리

1. 코디네이터가 이관 직전 DB 백업을 뜨고, 두 테이블의 전체 줄 수와 `content_key_id` 가 찬 줄 수를 적어 둔다(스펙 DECISION11).
2. 배포한 Control Plane 이 Flyway 로 컬럼을 더하고 뜬다.
3. `MemoryTextMigration` 이 `data_key_id` 가 빈 줄의 번호를 트랜잭션 밖에서 읽는다. 기억 다음 revision 의 순서다.
4. 줄마다 새 트랜잭션에서 쓰기 잠금으로 다시 읽는다. 그 사이 누가 옮겼으면 건너뛴다.
5. 평문을 얻는다. 옛 key 줄은 옛 key 로 풀고, 풀지 못하면 실패로 센다.
6. 주인 데이터 key 로 제목과 본문을 쓰고 `data_key_id` 를 적고 `content_key_id` 를 비운다.
7. 같은 트랜잭션에서 다시 풀어 평문 해시와 비교한다. 다르면 예외로 되돌린다.
8. 끝에 건수와 실패 번호를 로그로 남긴다. 실패가 있으면 error 다.
9. 코디네이터가 운영에서 `data_key_id` 가 빈 줄 0건, `content_key_id` 가 찬 줄 0건, 줄 수가 이관 전과 같은 것을 확인한다. 로그의 `legacyVerified` 가 1단계에 적어 둔 옛 key 줄 수(두 테이블 합)와 같은지 본다.
10. 확인 뒤 평문이 남은 옛 백업, 이관 직전 백업, 옛 환경 변수 key 를 지운다(스펙 DECISION12).
11. **DB 의 바이너리 로그(binlog)에도 이관 UPDATE 의 이전 값과 그동안의 평문 INSERT 가 남는다.** 확인한 시점 이전의 binlog 를 지우거나 binlog 가 꺼진 설정인지 본다. 해제된 페이지에 남은 평문은 테이블을 다시 만들어 지우는 것을 함께 검토한다. 실행 방법은 운영 저장소가 갖는다.

- 이관 중에 들어온 요청은 새 길로 저장하므로 이관 대상에 새로 들지 않는다. 이관이 잠근 줄을 고치려는 요청은 잠금을 기다린다.
- KEK 가 없으면 이관하지 않고 남은 줄 수만 경고한다.
- 이 PR 을 배포한 뒤 직전 이미지로 되돌리지 않는다. 직전 이미지는 `data_key_id` 를 몰라 암호문을 그대로 낸다(스펙 DECISION11). 문제가 생기면 앞으로 고친다.

## 검증 전략

| REQ | 테스트 | DB |
| --- | --- | --- |
| REQ1 | 화면, 제안, 문서, 그룹 기억을 만들고 고친 뒤 SQL 로 제목과 본문이 `v1.` 모양이고 `data_key_id` 가 찬 것을 본다. revision 도 같다 | H2, MySQL |
| REQ2 | 다른 사용자, 다른 그룹, 다른 기억 번호, 다른 컬럼으로 암호문을 옮기면 「읽지 못함」 이 된다. 그룹 key 줄이 `user_data_key` 에 한 줄 생긴다 | H2 |
| REQ3 | 실제 MySQL 에 옛 key 로 암호화한 민감 줄, 평문 일반 줄, revision 줄을 넣고 이관한 뒤, 건수와 줄마다의 평문 해시가 이관 전과 같고, `content_key_id` 가 찬 줄과 `data_key_id` 가 빈 줄이 0건인 것을 본다. 두 번 돌려도 바뀌지 않는다 | MySQL |
| REQ4 | 기존 기억, 조립, 도구, 측정 테스트를 통과시킨다. 측정 fixture 는 넣은 줄을 이관으로 데이터 key 의 암호문으로 바꾸고 `data_key_id` 가 빈 줄이 없는 것을 확인한 뒤 측정한다. 바뀐 기대값은 SQL 로 평문을 기대하던 것과, 가짜 암호문 줄이 색인에 제목으로 오르던 것(이제 풀지 못한 줄로 빠진다)뿐이다 | H2 |
| REQ6 | 실제 MySQL 에서 KEK 파일에 없는 KEK 로 감싼 데이터 key 를 둔 사용자가 기억을 저장하고 고치면 409 이고 줄과 revision 이 바뀌지 않는다. 같은 사용자의 목록은 그 줄을 「읽지 못함」 으로 내고 실패하지 않는다. KEK 가 꺼진 설치는 평문으로 저장한다 | MySQL, H2 |
| REQ7 | 관리자가 다른 사용자의 개인 기억이 있는 에이전트 설정과 정리 후보를 읽어도 그 제목이 응답에 없고 건수에는 들어 있다 | H2 |
| REQ8 | 줄 하나의 이관이 실패해도 다른 줄은 옮겨지고 기동이 이어지며, 로그에 그 줄 번호만 남고 본문이 없다 | H2 |

## 이번 변경의 기술 결정

| ID | 결정 | 까닭 |
| --- | --- | --- |
| DECISION1 | 데이터 key 번호는 새 컬럼 `data_key_id` 에 적고 옛 `content_key_id` 는 PR2 까지 읽기만 한다 | [ADR-20261103 / memory-encryption](../../../backend/docs/adr/ADR-20261103-memory-encryption.md) 「대안 기각」 의 `content_key_id` 재사용 |
| DECISION2 | AAD 에 기억 번호와 컬럼을 묶고 revision 번호는 묶지 않는다 | [ADR-20261103 / memory-encryption](../../../backend/docs/adr/ADR-20261103-memory-encryption.md) 「대안 기각」 의 AAD 두 항목 |
| DECISION3 | 그룹 key 는 `user_data_key` 에 `group_id` 줄로 둔다. 테이블 이름은 바꾸지 않는다 | [ADR-20261103 / memory-encryption](../../../backend/docs/adr/ADR-20261103-memory-encryption.md) 「대안 기각」 의 그룹 key 새 테이블 |
| DECISION4 | 제목도 본문과 같은 key 로 따로 암호화한다. 풀 때는 둘을 함께 푼다 | 스펙 DECISION4. 컬럼마다 AAD 가 달라 제목과 본문을 바꿔 넣으면 풀리지 않는다. 함께 풀어 한 줄의 읽기 결과가 하나다 |
| DECISION5 | 이관은 SQL 마이그레이션이 아니라 기동 작업이다 | [ADR-20261103 / memory-encryption](../../../backend/docs/adr/ADR-20261103-memory-encryption.md) 「대안 기각」 의 SQL 이관 |
| DECISION6 | 제안 중복 키(`proposal_dedup_key`)와 임베딩 지문은 PR1 에서 그대로 둔다 | 스펙 DECISION17. 원문 전체를 맞혀야 확인되는 해시다. PR2 가 둘을 주인 key 로 만든 HMAC 으로 바꾼다 |
| DECISION7 | 「읽지 못함」 은 서버가 제목 자리에 내는 고정 글이고 목록 DTO 의 `unreadable` 로 화면이 고치기를 막는다 | 제목을 보이는 화면(목록, 제안 카드, 「기억했어요」, 「참고한 기억」)이 따로 고치지 않아도 같은 글을 보인다 |

## 요구사항 연결

| ID | 해결 방법 | PR |
| --- | --- | --- |
| REQ1 | 「REQ 마다 해결 방법」 의 REQ1. 제목과 본문을 주인 데이터 key 로 따로 암호화하고 새 줄은 빈 글로 넣은 뒤 같은 트랜잭션에서 암호문으로 고친다 | PR1 |
| REQ2 | 「REQ 마다 해결 방법」 의 REQ2. `user_data_key` 에 그룹 줄을 더하고 풀 때 key 주인을 대조한다 | PR1 |
| REQ3 | 「REQ 마다 해결 방법」 의 REQ3 과 「이관 순서와 실패 처리」. 기동 이관이 줄마다 다시 쓰고 평문 해시를 대조한다 | PR1 |
| REQ4 | 「REQ 마다 해결 방법」 의 REQ4. 엔티티가 푼 글을 내고 `sealed()` 판정을 `readable()` 로 바꾼다 | PR1 |
| REQ5 | 「PR2: 민감 구분 제거와 평문 해시」. 엔티티와 질의, 화면에서 민감도와 민감 허용을 빼고, 「PR3: 옛 칸 삭제」 가 칸을 지운다 | PR2, PR3 |
| REQ6 | 「REQ 마다 해결 방법」 의 REQ6. key 를 풀지 못하면 저장을 거절하고 그 줄만 「읽지 못함」 이다 | PR1 |
| REQ7 | 「REQ 마다 해결 방법」 의 REQ7. 관리자 응답에 남의 개인 기억 제목을 싣지 않는 것을 테스트로 고정한다 | PR1 |
| REQ8 | 「REQ 마다 해결 방법」 의 REQ8. 실패한 줄은 번호만 남기고 기동을 잇는다 | PR1 |

## PR2: 민감 구분 제거와 평문 해시

PR1 을 배포한 뒤 운영에서 데이터 key 로 옮기지 않은 줄과 옛 key 줄이 모두 0건인 것을 확인했다(2026-11-03). 그래서 PR2 는 옛 key 를 읽지 않아도 된다.

### 바꾸는 구조

| 층 | 무엇을 바꾸나 |
| --- | --- |
| `memory.domain` | `Memory`, `MemoryRevision` 이 `sensitivity`, `content_key_id` 를 매핑하지 않는다. `MemoryPlacement` 는 collection 과 꺼내는 방식만 갖는다. `StoredContent` 를 지운다. `MemorySensitivity` 는 문맥 항목의 민감도로만 남는다 |
| `memory.application` | `MemoryTexts` 가 옛 key 를 읽지 않고, 제안 중복 키와 임베딩 지문을 주인 key 의 HMAC 으로 낸다. `MemoryTextMigration` 이 기동할 때 남은 SHA-256 해시를 HMAC 으로 바꾼다. `MemoryContentCipher` 와 `MemoryEncryptionProperties` 를 지운다 |
| `memory` 의 판정 | `MemoryAccess` 는 받는 collection 만 갖는다. 질의(`MemoryQueries`, 검색 후보, 임베딩 원천)에서 민감도 조건을 뺀다. 민감 항목 수정 거절(`MEMORY_SENSITIVE_NOT_EDITABLE`)과 `ALWAYS` 거절(`MEMORY_SENSITIVE_ALWAYS`)을 지운다 |
| `crypto` | `OwnedTextCipher.mac(owner, purpose, text)` 을 더한다. `DataKeyService` 가 데이터 key 에서 쓰임마다 HMAC key 를 뽑는다 |
| `agent` | `AgentMemoryCollection`, `AgentMemoryCollectionChange` 가 `allow_sensitive` 를 매핑하지 않는다. `AgentMemoryGrants` 는 collection 만 갖는다. 최근 변경은 옛 `SENSITIVE_CHANGED` 기록을 내지 않는다 |
| 화면 | `/memory` 목록과 문서, 제안 카드, 대화의 「기억했어요」 에서 민감 배지와 숨김 문구, 문서의 민감 선택을 지운다. 에이전트 상세의 「민감 항목까지」 를 지운다 |
| `memory_remember` | `sensitive` 인자는 그대로 받는다. 참이면 늘 제안이고 저장하지 않는다(스펙 DECISION8) |

### HMAC

- `DataKeyService.mac` 은 `HMAC-SHA256(데이터 key, "data_key_mac:" + 쓰임)` 으로 HMAC key 를 뽑고, 그 key 로 글의 HMAC-SHA256 을 낸다. 쓰임은 `memory:proposal_dedup`, `memory:embedding` 이다
- 제안 중복 키는 `USER` 주인의 key 로 `주인 번호 \0 제목 \0 본문` 을 HMAC 한 64자 hex 다. 칸 길이(64)가 그대로다
- 임베딩 지문은 기억의 주인(`USER` 는 사용자, `GROUP` 은 그룹)의 key 로 `모델 \n 글` 을 HMAC 한 32바이트다. 칸 길이(`BINARY(32)`)가 그대로다. 그래서 검색 후보(`RelevanceCandidate`, `MemorySearchCandidate`)와 임베딩 원천(`MemoryEmbeddingSource`)이 범위와 주인 번호를 든다
- KEK 가 없는 설치는 글이 평문이라 지금처럼 SHA-256 을 쓴다. 주인 key 를 풀지 못하면 중복 키는 `MEMORY_ENCRYPTION_UNAVAILABLE` 로 저장을 거절하고, 지문은 빈 값이라 그 기억은 벡터를 쓰지 않는다

### 이미 있는 해시 바꾸기

`MemoryTextMigration.rekeyHashes` 가 글 이관 다음에 돈다. 줄마다 `REQUIRES_NEW` 트랜잭션이고 실패한 줄은 번호만 남긴다.

1. 제안 중복 키가 있는 줄을 쓰기 잠금으로 읽어 지금 글로 HMAC 을 내고, 다르면 바꾼다. 같은 HMAC 을 이미 다른 줄이 가졌으면(직전 이미지로 되돌린 동안이나 이관 전에 같은 글의 제안이 새 키로 먼저 생겼다) 이 줄의 키를 비운다. 유일 제약에 걸려 평문 해시가 남지 않게 한다
2. 벡터가 있는 줄은 지금 글과 그 줄의 모델로 HMAC 을 낸다. 같으면 둔다. 저장된 지문이 같은 글의 SHA-256 이면 지문만 HMAC 으로 바꾸고(`replaceFingerprint`, 지문이 그대로일 때만 쓴다) 벡터는 그대로 둔다. 둘 다 아니면 낡은 벡터라 벡터째 비운다. 비우기도 지문이 그대로일 때만 쓴다
3. 이미 HMAC 인 줄은 건드리지 않으므로 기동마다 돌아도 된다. KEK 가 없으면 돌지 않는다

벡터를 다시 계산하지 않는 까닭은 다시 쓴 벡터마다 연결 찾기가 돌아, 사람이 정리한 대체 제안이 다시 생길 수 있어서다.

## 인터페이스와 데이터

| 자리 | 바뀐 뒤 |
| --- | --- |
| `GET /api/v1/memories` 의 `MemoryView` | `sensitive` 를 뺀다. 본문은 풀린 줄이면 늘 싣는다 |
| 문서 요청(`CreateDocumentRequest`, `UpdateDocumentRequest`) | `sensitive` 를 받지 않는다. 옛 화면이 보내도 무시한다 |
| 문서 응답(`DocumentSummaryView`, `DocumentView`) | `sensitive` 를 뺀다 |
| 대화의 기억 기록(`MemoryCaptureView`) | `sensitive` 를 뺀다 |
| 관리자 기억 영역(`AgentMemorySettingView`) | collection 의 `allowSensitive`, `sensitiveEntryCount`, 변경의 `allowSensitive` 를 뺀다. 저장 요청은 `collection` 만 받고 옛 `allowSensitive` 는 무시한다 |
| 오류 코드 | `MEMORY_SENSITIVE_NOT_EDITABLE`, `MEMORY_SENSITIVE_ALWAYS` 를 지운다 |
| 설정 | `assistant.memory.encryption.*`(옛 환경 변수 key)를 지운다. 환경 변수가 남아 있어도 읽지 않는다 |
| `memory.sensitivity`, `memory.content_key_id`, `memory_revision.sensitivity`, `memory_revision.content_key_id`, `agent_memory_collection.allow_sensitive`, `agent_memory_collection_change.allow_sensitive` | 엔티티가 매핑하지 않는 옛 칸이다. 기본값이 없던 `memory_revision.sensitivity` 와 `agent_memory_collection_change.allow_sensitive` 에 기본값만 둔다(`V20261103054413`, DDL 하나). 칸은 「PR3」 절의 마이그레이션이 지웠다 |
| `proposal_dedup_key`, `embedding_fingerprint` | 칸 모양은 그대로이고 값이 HMAC 이다 |

H2 테스트 DB 는 엔티티로 스키마를 만들므로 PR2 동안은 옛 칸을 테스트 자원 `memory-legacy-columns.sql` 로 따로 만들어 운영 스키마와 맞췄다. PR3 이 칸과 그 파일을 함께 지웠다.

## 실패와 일관성

- **이 PR 을 배포한 뒤 PR1 이미지로 되돌릴 수 있다.** 칸이 남아 있어 `ddl-auto: validate` 가 지나간다. 되돌린 이미지는 칸에 남은 `SENSITIVE` 를 읽어 그 줄을 다시 숨긴다. PR2 이미지가 넣은 줄은 기본값 `NORMAL` 이라 숨지 않는다
- 되돌린 동안 PR1 이미지는 SHA-256 으로 중복 키를 낸다. 다시 올리면 기동 이관이 HMAC 으로 바꾸고, 같은 글의 키가 겹치면 나중 줄의 키를 비운다. 그 사이 같은 제안이 한 번 더 생길 수 있다. 되돌림이라는 예외 상황이라 막지 않는다
- 되돌린 PR1 이미지는 HMAC 지문을 낡은 것으로 보고 첫 주기에 벡터를 모두 다시 임베딩한다. 다시 쓴 벡터마다 연결 찾기가 돌아 정리한 대체 제안이 다시 생길 수 있다. 되돌리기 전에 임베딩을 끄면 막을 수 있고, 절차는 운영 저장소가 갖는다
- 해시 바꾸기는 줄마다 따로 커밋한다. 한 줄이 실패해도 다음 줄로 가고 기동은 잇는다. 실패한 줄은 다음 기동이 다시 본다
- 지문 바꾸기는 저장된 지문이 읽은 값 그대로일 때만 쓴다. 그 사이 채우는 작업이 새 벡터를 썼으면 덮지 않는다
- 옛 key 줄이 남아 있으면 옛 key 를 읽지 않으므로 암호문을 평문으로 본다. 운영 0건을 확인한 뒤 이 PR 을 열었다(스펙 REQ8). 칸을 지우는 PR3 이 그 칸이 빈 것을 다시 보고, 남았으면 지우지 않고 멈춘다

## PR2 검증 전략

| REQ | 테스트 | DB |
| --- | --- | --- |
| REQ5 | 직전 이미지가 `SENSITIVE` 로 남긴 줄이 목록에 본문과 함께 보이고 목록 경로로 고쳐지며, collection 을 받은 에이전트의 색인과 `memory_read` 에 실린다. 에이전트 기억 영역의 응답과 화면에 민감 허용이 없고, 옛 `SENSITIVE_CHANGED` 기록은 최근 변경에 없다 | H2 |
| REQ5 | 옛 key 설정 없이 뜬 서버가 화면, 그룹, 문서, 제안 기억을 저장하고 다시 읽는다 | H2, MySQL |
| DECISION17 | 제안 중복 키가 평문 SHA-256 이 아니고 같은 글이면 같은 값, 다른 사용자면 다른 값이다. 기동 이관이 SHA-256 이던 중복 키와 지문을 HMAC 으로 바꾸고 벡터는 그대로이며, 낡은 지문은 벡터째 비우고, 두 번 돌려도 바뀌지 않는다 | H2, MySQL |
| REQ4 | 기존 기억, 조립, 도구, 측정 테스트를 통과시킨다. 측정 데이터의 민감 경계 사례(BO-04)는 받지 않는 collection 경계로 바꿔 같은 금지 판정을 유지한다 | H2 |

## 결정

| ID | 결정 | 답 | 누가 | 근거 |
| --- | --- | --- | --- | --- |
| DECISION8 | 칸을 이 PR 에서 지울지 | 지우지 않는다. 코드는 읽고 쓰지 않고 기본값 DDL 만 담는다. 칸은 배포를 확인한 뒤 따로 지운다 | 작성자(위임) | 코디네이터가 정했다(2026-11-03). 직전 이미지로 되돌릴 길을 남긴다(ADR-20261102 / table-reduce-r2 의 선례) |
| DECISION9 | HMAC key | 데이터 key 에서 쓰임마다 HMAC 으로 뽑는다 | 작성자 | 암호화 key 를 HMAC key 로 그대로 쓰지 않는다. 데이터 key 를 지우면 해시도 맞혀 볼 수 없다 |
| DECISION10 | 이미 있는 임베딩 지문 | 같은 글의 SHA-256 이면 그 자리에서 HMAC 으로 바꾸고, 아니면 벡터째 비운다 | 작성자 | 다시 임베딩하면 연결 찾기가 다시 돌아 정리한 대체 제안이 되살아날 수 있다 |
| DECISION11 | `MemorySensitivity` | 문맥 항목의 민감도로 남긴다. 기억 항목은 늘 `NORMAL` 이다 | 작성자 | 위임 결과, 커넥터 결과, 할 일이 `SENSITIVE` 로 `instructions` 에서 빠지는 규칙은 그대로다 |
| DECISION12 | 옛 `SENSITIVE_CHANGED` 기록 | 값은 읽을 수 있게 두고 최근 변경에 내지 않는다 | 작성자 | 없어진 설정의 변경을 보여도 뜻이 없다. 지우면 감사 기록이 사라진다 |

## PR3: 옛 칸 삭제

PR2 를 배포한 뒤 운영에서 옛 key 줄(`content_key_id` 가 찬 줄)이 0건인 것을 확인했다(2026-11-03). PR3 은 코드가 읽고 쓰지 않는 옛 칸 여섯 개를 지워 REQ5 의 「칸이 없다」 를 닫는다.

| 표 | 지우는 칸 |
| --- | --- |
| `memory` | `sensitivity`, `content_key_id` |
| `memory_revision` | `sensitivity`, `content_key_id` |
| `agent_memory_collection` | `allow_sensitive` |
| `agent_memory_collection_change` | `allow_sensitive` |

- `V20261103054416__MemoryLegacyKeyGuard`(Java)가 먼저 돈다. `memory` 와 `memory_revision` 에서 `content_key_id` 가 찬 줄을 세어 하나라도 있으면 줄 수만 담은 예외로 멈춘다. DDL 을 하지 않으므로 멈춰도 칸은 그대로이고, 줄을 데이터 key 로 옮긴 뒤 실패한 기록을 정리하고 다시 올린다. 본문과 key id 는 예외에 넣지 않는다
- 칸을 지우는 SQL 은 표마다 파일이다(`V20261103054417` 부터 `V20261103054420`). MySQL DDL 은 되돌려지지 않아 한 표가 실패해도 앞 표의 삭제는 남는다. H2 가 한 문장에서 칸 여럿을 지우는 문법을 받지 않아 칸마다 문장을 나눈다
- `sensitivity` 와 `allow_sensitive` 는 확인하지 않는다. 코드가 PR2 부터 읽지 않는 값이고 지워도 잃는 동작이 없다
- 테스트 자원 `memory-legacy-columns.sql` 과 `application-test.yml` 의 import 항목을 지우고, 칸에 값을 넣던 테스트와 사용자 purge fixture(`purge/fixture.sql`)에서 옛 칸을 뺀다
- **이 PR 을 배포한 뒤에는 PR1 이전 이미지로 되돌리지 못한다.** 그 이미지는 칸을 매핑해 `ddl-auto: validate` 가 실패한다. PR2 이미지는 칸을 매핑하지 않아 칸이 없어도 뜬다

### PR3 검증 전략

| REQ | 테스트 | DB |
| --- | --- | --- |
| REQ5 | 옛 칸이 있는 Flyway 스키마에 줄을 넣고 끝까지 적용하면 줄은 그대로이고 여섯 칸이 모두 없다(`MemoryLegacyColumnsDropMigrationTest`) | H2, MySQL |
| REQ8 | 옛 key 줄이 있으면 줄 수를 알리고 멈추며 어느 칸도 지우지 않는다(같은 테스트) | H2, MySQL |
| REQ4 | 기억을 읽고 쓰는 기존 테스트, 조립, 도구, 사용자 purge 가 칸 없는 스키마에서 통과한다 | H2, MySQL |
