# Phase 01. 원고 REST에 MCP 도구와 사건 가림 연결 (PR B)

**Execution profile**: deep

## 목표

main에 원고 revision REST·삭제·만료·purge가 반영된 뒤 같은 서비스를 MCP에서 사용한다.
## 컨텍스트

**근거 문서**: `docs/features/content-draft.md`의 「원고 revision 저장 계약」과 `backend/docs/data-schema.md`다.
PR A에서 구현한 schema·canonical·암호화·REST·삭제 장벽 phase는 지웠다.
운영 코드 1,500줄 상한을 지키며 이번 PR은 MCP와 그 본문 가림이라는 관심사 하나다.

## 범위

- 실제 `content_draft_write`·`content_draft_read` 정의와 호출, origin 실행 전후 재검사.
- Hermes 실행 사건의 start/complete/fail detail·text 가림.
- fos-ctx REQUIRED_TOOLS와 README·서명 필수 목록 회귀.

**범위 외**: compiler 변경, cache, queue, preview, 승인, 영속 job, 새 UI, 발행·실제 거래, 다른 저장소와 Hermes core 변경은 범위 밖이다.
원본 워커의 세션과 worktree는 재개·수정·삭제하지 않는다.
설계만 push하거나 PR을 열지 않는다. 최신 main은 일반 merge하며 rebase·force push하지 않는다.

## 작업 항목

### 1. MCP와 사건 가림

1. `McpContentDraftTools`가 write/read 정의와 결과를 소유한다. 생성의 draftId·expectedRevision은 함께 생략, 수정은 둘 다 필수다. read의 revision은 생략 가능하다.
2. `McpCallerResolver`의 서명 origin 실행으로 사용자·대화를 정한다. 인자에 사용자·대화·profile을 받지 않는다. 기존 `McpMediaObservationTools.requireCurrent`의 agent 활성·삭제·profile 일치·현재 사용자 권한 검사를 실행 전후 guard로 적용한다. 이동이 필요하면 동작 변경과 별도 커밋으로 나눈다.
3. `ContentDraftException.currentRevision` 필드만 전달한다. 고정 message를 유지하며 예외 문자열 파싱과 본문·revision 로그 message 삽입을 하지 않는다.
4. `McpToolCatalog`에 정의, `McpController`에 한 줄 위임 handler, `McpDtos`나 도구 클래스에 인자 해석을 둔다. controller 500줄 상한을 지킨다. 살펴보기 허용 집합은 확대하지 않는다.
5. `HermesRunEventStream`에서 두 도구와 `__` 접두 이름의 detail·text를 수집 전에 null로 만든다. start/complete/fail 모두 관리자에게도 본문을 남기지 않는다.
6. fos-ctx `REQUIRED_TOOLS`, README의 서명 설명·도구 목록, `test_fos_ctx.py` 필수 서명 회귀를 함께 고친다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpContentDraftTools.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpToolCatalog.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesRunEventStream.java` | 수정 |
| `hermes/plugins/fos-ctx/hooks.py` | 수정 |
| `hermes/plugins/fos-ctx/README.md` | 수정 |
| `hermes/tests/test_fos_ctx.py` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpContentDraftToolTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/hermes/ToolDetailEventStreamTest.java` | 수정 |
| `docs/features/content-draft.md` | 수정 |

공유 MCP 파일의 충돌은 코디네이터에게 보고한다. 원고 REST와 첨부 삭제 계약은 그대로 재사용한다.

## 검증

실제 /mcp HTTP에서 서명 origin·다른 대화·살펴보기·취소·agent 비활성·권한 변경을 거절한다.
생성·CAS·이전 조회·typed STALE/CONFLICT를 확인하고 외부 호출 0회를 단언한다.
가짜 Hermes 사건으로 두 도구와 접두 이름의 모든 사건에서 본문이 저장되지 않는지 검사한다.

```bash
# cwd: 저장소 root
python3 "$SKILL_DIR/scripts/verify_task.py" plan203-content-draft-revision
cd backend && ./gradlew test --tests '*McpContentDraft*' --tests '*McpCallerInvariantTest' --tests '*ToolDetailEventStreamTest'
cd backend && ./gradlew qualityCheck
python3 -m unittest discover -s hermes/tests
scripts/check-local.sh --skip-browser
python3 "$SKILL_DIR/scripts/verify_task.py" plan203-content-draft-revision --audit
```

SKILL_DIR는 설치된 planning 스킬 번들 경로다. 같은 checkout의 Gradle은 순차로 실행한다.
기본 verify_task 실패와 선행 코드 부재는 PHASE_BLOCKED로 보고한다. 단언·timeout·skip·baseline을 완화하지 않는다.
create-pr·content-preview·korean-check·pr-merge를 읽고 한국어 Draft로 올린다. Ready·merge·운영 배포는 하지 않는다.
구현이 끝나면 이 phase를 구현 PR에서 지우고 오래 남을 계약만 feature 문서에 남긴다.
배포 뒤 실제 MCP 왕복은 `fos-home-infra` 담당에 맡기며 환경 값과 실행 명령은 공개 저장소에 쓰지 않는다.
