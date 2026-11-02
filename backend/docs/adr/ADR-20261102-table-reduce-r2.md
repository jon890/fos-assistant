## ADR-20261102 / table-reduce-r2: 테이블 줄이기 2차는 묶음마다 코드 PR, 배포, DROP PR 순서로 한다

- **status**: `accepted`
- Date: 2026-11-02

### 맥락

사용자는 테이블을 늘리지 않고 설정 테이블은 `group_setting` 하나로 합치기로 했다(2026-11-01).
이어서 잘 쓰지 않는 테이블과 중복 데이터를 더 줄이기로 하고, 묶음마다 아래 D1~D6 을 정했다(2026-11-02).
`ddl-auto: validate` 라서 엔티티를 지운 PR 과 같은 PR 에서 테이블을 지우면, 직전 이미지로 되돌릴 때 옛 엔티티가 테이블을 찾지 못해 기동하지 못한다. 운영에서 0행인 테이블도 같다.

### 결정

**모든 묶음은 「코드 PR → 배포 확인 → DROP PR」 순서로 한다.**
코드 PR 은 새 자리(본체 컬럼, JSON 칸, `group_setting` 키)를 만들고 옛 데이터를 옮긴 뒤 코드가 옛 테이블을 더는 읽고 쓰지 않게 한다. 직전 이미지가 새 스키마에서도 돌도록 새 칸은 NULL 을 허용하고 옛 칸과 테이블은 남긴다.
DROP PR 은 배포를 확인한 묶음의 옛 테이블과 칸을 모아 지운다. 그 전에 옛 이미지가 쓴 줄을 다시 옮기고, 필요한 칸을 NOT NULL 로 바꾼다.
기능을 지우거나 0행으로 본 테이블을 지우기 전에는 운영의 정확한 행 수를 다시 센다. 조사 때의 행 수는 InnoDB 추정치였다.
JSON 을 만드는 이관은 Java 마이그레이션으로 하고, DDL 과 DML 은 파일을 나눈다(data-schema 「마이그레이션 작성 규칙」).

사용자 결정은 아래와 같다.

| 번호 | 묶음 | 결정 |
| --- | --- | --- |
| D1 | 원고(`content_draft_*`) | 네이버 블로그 원고 경로는 남기고 두 테이블만 줄인다 |
| D2 | 사진 관찰(`media_observation`, `media_observation_request`) | 하나로 합친다. [ADR-20261102 / media-observation-request-merge](ADR-20261102-media-observation-request-merge.md) |
| D3 | 결과 전달 시도(`result_delivery_attempt`) | 마지막 시도만 남기고 ADR-075 를 고친다 |
| D4 | 기억 연결(`memory_link`) | 대체(`SUPERSEDES`)만 `memory` 컬럼으로 남기고 나머지는 지운다 |
| D5 | 도구 사용 요청(`agent_toolset_request`) | 기능을 지운다 |
| D6 | 허용 목록(`allowed_person`) 합치기 | 보류한다 |

`agent_memory_collection` 은 매 실행의 Memory 권한 경계라 남긴다(사용자 결정 2026-11-02).

### 묶음과 순서

PR 하나의 운영 코드는 `scripts/pr/pr-size.mjs` 의 상한 안에 둔다. 넘으면 그 묶음을 PR 여럿으로 나눈다.

| 묶음 | 코드 PR 이 하는 일 | 선행 조건 |
| --- | --- | --- |
| 사진 관찰(D2) | 요청 UUID 와 hash 를 `media_observation` 칸으로 옮기고 완료 결과 재사용을 없앤다 | 끝났다(#655). 옛 테이블과 칸은 첫 DROP PR 이 지웠다 |
| 도구 숨김 설정 | `toolset_hidden` 을 `group_setting` 의 `TOOLSET` 키로 옮긴다. `GroupSetting` 과 저장소를 `user` 패키지로 옮기는 이동 커밋을 먼저 두고, 공통 저장소를 만든다 | 끝났다(#657). 옛 테이블은 첫 DROP PR 이 지웠다 |
| Memory collection 설정 | `memory_collection` 을 `group_setting` 의 `MEMORY_COLLECTIONS` 키로 옮긴다. 줄이 없으면 코드의 기본 collection 을 내고 저장하지 않는다 | 도구 숨김 설정의 PR 이 `GroupSetting` 을 `user` 로 옮긴 뒤(층 순서 검사) |
| 실행 질문 | `execution_question` 을 `agent_execution.question_message_id` 로 옮긴다. 조건부 UPDATE 로 채우고 대화 정리가 비운다 | 끝났다(#658). DROP 만 남았다. DROP 전에 다시 옮길 때 정리한 대화의 실행은 옮기지 않고 질문 번호를 비운다 |
| 결과 전달(D3) | `result_delivery_item` 을 `result_delivery.items_json` 으로, `result_delivery_attempt` 를 마지막 시도 칸으로 옮긴다. 출처 줄의 전달 표시가 바꾼 줄 수가 1 이 아니면 예외로 되돌린다. ADR-075 를 고친다 | 없다 |
| 기억 벡터 | `memory_embedding` 을 `memory` 의 `embedding_*` 칸으로 옮긴다. 엔티티는 매핑하지 않고 채우는 작업과 캐시가 전용 쿼리로 다룬다. 벡터는 옮기지 않고 다시 계산한다 | 사용자 정리([ADR-20261101 / user-purge](ADR-20261101-user-purge.md))의 삭제 단계가 머지된 뒤. 정리 미리보기와 대상 목록이 겹친다 |
| 플러그인 허락 | `connector_tool_grant`, `connector_conversation_grant` 를 허락을 준 `connector_action` 줄의 기간과 거두기 칸으로 옮긴다 | 사용자 정리의 삭제 단계가 머지된 뒤. 그 ADR 의 외래 키 테이블 목록과 겹친다 |
| 대기 메시지 | `chat_pending_message` 를 `conversation.pending_messages` JSON 칸으로 옮기고 ADR-048 을 고친다 | 운영 조회 계정의 컬럼 권한 변경(`fos-home-infra`)이 끝난 뒤 |
| 도구 사용 요청(D5) | `agent_toolset_request` 와 요청 기능, 관리자 화면을 지운다 | 사용자 정리의 삭제 단계가 머지되고 운영 행 수 0 을 확인한 뒤 |
| 기억 연결(D4) | `memory_link` 를 지우고 대체만 `memory.superseded_by_memory_id` 로 남긴다. `RELATED`, 연결 제안, 자동 찾기와 화면을 지운다 | 사용자 정리의 삭제 단계가 머지되고 운영 행 수를 확인한 뒤 |
| 원고(D1) | `content_draft_job` 의 칸을 `content_draft_snapshot` 으로, `content_draft_asset` 을 `content_draft_revision.assets_json` 으로 옮긴다. 배포 뒤 블로그 새 글 저장을 한 번 왕복시켜 본다 | 없다 |
| DROP | 배포를 확인한 묶음의 옛 테이블과 칸을 모아 지운다. 첫 DROP 은 `media_observation_request`, `media_observation.analysis_key`, `toolset_hidden` 이고 끝났다. 지우기 전에 직전 이미지가 쓴 줄을 다시 옮기고 요청 칸을 NOT NULL 로 바꿨다 | 각 코드 PR 의 배포 확인. proactive 테이블 DROP([ADR-20261101 / proactive-suggestion](../../../docs/adr/ADR-20261101-proactive-suggestion.md))과 정리 분류, fixture 가 겹치므로 그 PR 이 머지된 뒤 |

허용 목록(D6)과, 줄어드는 수에 비해 안전 계약을 바꾸는 범위가 큰 `connector_action_execution`, 하위 에이전트 두 테이블, `execution_skill_use`, `execution_context_source` 는 이번에 다루지 않는다.

### 대안 기각

- 0행 테이블은 코드 PR 에서 함께 지우기: 직전 이미지로 되돌릴 수 없게 된다.
- 묶음마다 DROP PR 을 따로 내기: DROP 은 짧고 정리 분류와 fixture 를 함께 고치므로 배포를 확인한 것끼리 모으는 편이 충돌이 적다.

### 결과

- 얻는 것: 모든 PR 이 직전 이미지로 되돌릴 수 있다. 다음 작업자가 묶음과 선행 조건을 이 문서 하나에서 읽는다.
- 감당할 것: 코드 PR 과 DROP PR 사이에 옛 테이블과 칸이 한동안 남는다. 그동안 data-schema 가 그 테이블을 「코드가 읽고 쓰지 않는다」 로 적어 둔다.
