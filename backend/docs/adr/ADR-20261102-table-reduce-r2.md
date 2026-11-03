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
| Memory collection 설정 | `memory_collection` 을 `group_setting` 의 `MEMORY_COLLECTIONS` 키로 옮긴다. 줄이 없으면 코드의 기본 collection 을 내고 저장하지 않는다 | 끝났다(#661). 옛 테이블은 둘째 DROP PR 이 지웠다 |
| 실행 질문 | `execution_question` 을 `agent_execution.question_message_id` 로 옮긴다. 조건부 UPDATE 로 채우고 대화 정리가 비운다 | 끝났다(#658). 옛 테이블은 둘째 DROP PR 이 지웠다. 지우기 전에 다시 옮길 때 정리한 대화의 실행은 옮기지 않고 질문 번호를 비웠다 |
| 결과 전달(D3) | `result_delivery_item` 을 `result_delivery.items_json` 으로, `result_delivery_attempt` 를 마지막 시도 칸으로 옮긴다. 출처 줄의 전달 표시가 바꾼 줄 수가 1 이 아니면 예외로 되돌린다. ADR-075 를 고친다 | 끝났다(#660). DROP 만 남았다 |
| 기억 벡터 | `memory_embedding` 을 `memory` 의 `embedding_*` 칸으로 옮긴다. 엔티티는 매핑하지 않고 채우는 작업과 캐시가 전용 쿼리로 다룬다. 벡터는 옮기지 않고 다시 계산한다 | 사용자 정리([ADR-20261101 / user-purge](ADR-20261101-user-purge.md))의 삭제 단계가 머지된 뒤. 정리 미리보기와 대상 목록이 겹친다 |
| 플러그인 허락 | `connector_tool_grant`, `connector_conversation_grant` 를 허락을 준 `connector_action` 줄의 `grant_scope`, `grant_expires_at`, `grant_revoked_at` 으로 옮긴다. 상시 허락은 승인 줄 번호가 없어 승인 시각이 생성 시각에 가장 가까운 같은 도구의 승인 줄로 옮기고, 고를 줄이 없으면 옮기지 않는다. 옛 행은 직전 이미지가 읽도록 남기고, 그 행이 사용자 정리를 막지 않게 옛 표의 사용자와 대화 외래 키를 뗀다. 코드는 옛 표를 읽고 쓰지 않되, 사용자 정리와 정리 미리보기만 예외로 그 사용자의 옛 행을 native 질의로 지우고 센다([ADR-20261101 / user-purge](ADR-20261101-user-purge.md) 의 약속은 그대로다). 되돌리면 새 코드가 거둔 허락이 직전 이미지에서 다시 통한다. 옛 행을 남기는 구조라 생기고, 이관 때 운영 행 수는 0 이었다. 거두기는 허락 칸만 바꾸고 엔티티는 바꾼 칸만 쓴다. 대화 허락을 따로 둔 [ADR-20261012 / conversation-grant](../../../docs/adr/ADR-20261012-conversation-grant.md) 의 저장 결정을 고쳐, 두 허락을 `grant_scope` 로 나누고 모든 조회가 범위를 건다 | 코드 PR 이 옮겼다. DROP 만 남았다. DROP PR 은 사용자 정리와 정리 미리보기의 옛 표 삭제도 걷어 낸다. DROP PR 의 재이관은 이 배포 뒤 직전 이미지가 옛 표에 새로 쓴 행(`created_at` 이 이 이관의 Flyway 적용 시각 뒤인 행)만 같은 규칙으로 다시 옮긴다. 이미 옮긴 행을 다시 옮기면 새 코드가 거둔 상시 허락이 다른 승인 줄로 되살아난다 |
| 대기 메시지 | `chat_pending_message` 를 `conversation.pending_messages` JSON 칸으로 옮기고 ADR-048 을 고친다. 옮긴 행은 옛 표에서 지운다. 남기면 직전 이미지로 되돌렸을 때 보낸 글을 다시 보낸다 | 끝났다(#666). DROP 만 남았다. DROP PR 의 재이관은 직전 이미지가 옛 표에 쓴 글을 대화 줄에 이미 있는 JSON 뒤에 이어 붙인다. 이번 이관처럼 이미 값이 있으면 멈추게 두면 되돌렸다 다시 올린 대화에서 기동이 멈춘다. 운영 조회 계정은 칸 단위 권한이라 새 칸은 권한을 다시 맞추기 전까지 보이지 않고, 배포 뒤 `fos-home-infra` 절차로 다시 맞춘다 |
| 도구 사용 요청(D5) | `agent_toolset_request` 와 요청 기능(요청 단추, 요청 화면, 관리자 처리 화면, API, 알림)을 지운다. 이 묶음은 「코드 PR → DROP PR」 을 나누지 않고 한 PR 에서 코드와 테이블을 함께 지운다. 운영 행이 0 이라 옮길 데이터가 없고, 화면과 API 를 함께 지워 새 자리로 옮길 기능도 없다. 대가는 직전 이미지로 되돌릴 수 없다는 것이다(옛 엔티티가 지운 테이블을 찾지 못해 `validate` 로 기동하지 못한다). 되돌릴 기능이 없는 삭제라 이 묶음에서는 받아들인다(사용자 결정 2026-11-02, D5). 알림 종류 두 값(`TOOLSET_REQUESTED`, `TOOLSET_REQUEST_DECIDED`)과 대상 종류 두 값(`ADMIN_TOOL_REQUEST`, `TOOLSET_REQUEST`)도 지우고, 남은 알림 줄이 있으면 enum 변환이 실패하므로 그 줄을 지우는 마이그레이션을 같은 PR 에 둔다. [ADR-20261009 / tool-request-flow](../../../docs/adr/archive/ADR-20261009-tool-request-flow.md) 는 퇴역했다 | 끝났다. 사용자 정리의 삭제 단계(#656)가 머지됐고 운영 행 수 0 을 확인했다(2026-11-03). 정리 분류와 fixture, 기능 문서를 같은 PR 에서 고쳤다 |
| 기억 연결(D4) | `memory_link` 를 지우고 대체만 `memory.superseded_by_memory_id` 로 남긴다. `RELATED`, 연결 제안, 자동 찾기와 화면을 지운다 | 사용자 정리의 삭제 단계가 머지되고 운영 행 수를 확인한 뒤 |
| 원고(D1) | `content_draft_job` 의 칸을 `content_draft_snapshot` 으로, `content_draft_asset` 을 `content_draft_revision.assets_json` 으로 옮긴다. 배포 뒤 블로그 새 글 저장을 한 번 왕복시켜 본다 | 끝났다(#663). 옛 테이블은 둘째 DROP PR 이 지웠다. 사진 참조 칸은 NOT NULL 로 바꿨다 |
| DROP | 배포를 확인한 묶음의 옛 테이블과 칸을 모아 지운다. 첫 DROP 은 `media_observation_request`, `media_observation.analysis_key`, `toolset_hidden` 이고 끝났다. 지우기 전에 직전 이미지가 쓴 줄을 다시 옮기고 요청 칸을 NOT NULL 로 바꿨다. 둘째 DROP 은 `execution_question`, `memory_collection`, `content_draft_job`, `content_draft_asset` 이고 같은 순서로 했다. 원고의 사진 참조 칸(`assets_json`)을 NOT NULL 로 바꾸고, 같은 snapshot 에 새 코드와 직전 이미지가 만든 job 은 새 칸을 지킨다. 결과 전달과 대기 메시지는 배포를 확인한 뒤 다음 DROP PR 이 지운다 | 각 코드 PR 의 배포 확인. proactive 테이블 DROP([ADR-20261101 / proactive-suggestion](../../../docs/adr/ADR-20261101-proactive-suggestion.md))과 정리 분류, fixture 가 겹치므로 그 PR 이 머지된 뒤 |

허용 목록(D6)과, 줄어드는 수에 비해 안전 계약을 바꾸는 범위가 큰 `connector_action_execution`, 하위 에이전트 두 테이블, `execution_skill_use`, `execution_context_source` 는 이번에 다루지 않는다.

### 대안 기각

- 0행 테이블은 코드 PR 에서 함께 지우기: 직전 이미지로 되돌릴 수 없게 된다.
- 묶음마다 DROP PR 을 따로 내기: DROP 은 짧고 정리 분류와 fixture 를 함께 고치므로 배포를 확인한 것끼리 모으는 편이 충돌이 적다.

### 결과

- 얻는 것: 모든 PR 이 직전 이미지로 되돌릴 수 있다. 다음 작업자가 묶음과 선행 조건을 이 문서 하나에서 읽는다.
- 감당할 것: 코드 PR 과 DROP PR 사이에 옛 테이블과 칸이 한동안 남는다. 그동안 data-schema 가 그 테이블을 「코드가 읽고 쓰지 않는다」 로 적어 둔다.
