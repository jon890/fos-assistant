# Phase 02. 예약한 지 30일이 지난 사용자의 데이터를 지운다

**Execution profile**: deep

## 목표

정리 작업이 예약한 지 30일이 지난 사용자의 데이터를 ADR 의 순서대로 지우고, 그룹 공유 데이터를 관리자에게 넘긴다. 보관본과 묶음 자식과 지우기 이력을 함께 지운다.

**범위 외**: 화면, 정리 알림.

## 컨텍스트

**근거 문서**: `backend/docs/adr/ADR-20261101-user-purge.md`, `backend/docs/adr/ADR-20261008-conversation-purge.md`, `backend/docs/adr/ADR-20261009-agent-purge.md`, `backend/src/test/resources/purge/tables.tsv`

- 대화 정리는 `backend/src/main/java/com/bifos/assistant/chat/purge/application/ConversationPurger.java`, 에이전트 정리는 `backend/src/main/java/com/bifos/assistant/agent/application/AgentPurger.java` 가 맡는다. 이 단계는 그 사용자의 대화와 비공개 에이전트를 지운 것으로 표시하기만 하고 지우는 일은 두 정리 작업에 맡긴다
- 보관본은 `chat_artifact` 의 `vault_user_id` 행이다. 지우기는 `backend/src/main/java/com/bifos/assistant/chat/artifact/infra/ChatArtifactRepository.java` 에 둔다
- 사용자 줄과 key 는 위 단계가 끝났음을 확인한 뒤에만 지운다. 확인 전에 key 를 지우면 되돌릴 수 없다
- 단계는 각각 다시 해도 같은 결과여야 한다

## 작업 항목

### 1. 정리 작업

- `UserPurger` 를 `people.application` 에 둔다. 매분 `purge_scheduled_at` 이 30일 전보다 오래된 사람을 보고, 켜져 있으면 건너뛴다
- 그 사람의 `app_user` 마다 아래 순서로 한다. 한 단계가 실패하면 다음 차례에 처음부터 다시 한다
  1. 그룹 공개 에이전트의 소유를 그룹에서 `app_user.id` 가 가장 작은 `ADMIN` 에게 넘긴다. 관리자가 없으면 정리를 미루고 error 로그를 남긴다
  2. 보관본 행(묶음 자식과 지우기 이력 포함)을 지운다
  3. 개인 기억과 딸린 표, 할 일, 예약 작업, 알림, 주의 이벤트, 동의, 커넥터 연결, 브라우저를 지운다. `app_user` 를 가리키는 외래 키 표는 ADR 의 「app_user 를 가리키는 다른 표」 대로 소유 줄을 지우고 `decided_by_user_id` 는 비운다
  4. 대화와 비공개 에이전트를 지운 것으로 표시한다
  5. 대화 정리가 모두 끝났는지(`purged_at`) 확인한다. 아니면 다음 차례로 미룬다
  6. `user_data_key`, `app_user` 를 지우고 `purge_scheduled_at` 을 비운다

### 2. 분류와 문서

- `tables.tsv` 의 USER 칸을 DELETE 와 TRANSFER 로 바꾸고 `PurgeClassificationTest` 와 `PurgeCoverageMysqlTest` 가 통과하게 한다. 근거 칸의 「물리 삭제 경로가 없다」 를 고친다
- `docs/features/workspace.md` 의 보관한 결과물 절과 `docs/features/users.md` 를 맞춘다

### 3. 테스트

- 정리 뒤 그 사용자의 보관본과 묶음 자식과 지우기 이력이 없고 다른 사용자의 보관본은 남는다
- 그룹 공개 에이전트의 소유가 관리자로 바뀐다
- 정리 뒤 `app_user` 줄을 지워도 외래 키 오류가 없다(ADR 에 적은 표마다 줄을 만들어 넣고 확인한다)
- 30일 전에 다시 켜면 아무것도 지워지지 않는다
- 중간 단계가 실패해도 key 가 남아 있고, 다시 돌리면 끝난다

## 검증

```bash
cd backend && ./gradlew test --tests '*UserPurgerTest' --tests '*PurgeClassificationTest'
cd backend && ./gradlew test
cd backend && ./gradlew qualityCheck
```

모두 실패 없이 끝난다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/people/application/UserPurger.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/infra/ChatArtifactRepository.java` | 수정 |
| `backend/src/test/resources/purge/tables.tsv` | 수정 |
| `backend/src/test/java/com/bifos/assistant/people/UserPurgerTest.java` | 신규 |
| `docs/features/workspace.md` | 수정 |
| `docs/features/users.md` | 수정 |
