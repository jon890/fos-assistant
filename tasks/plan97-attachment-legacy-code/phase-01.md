# Phase 01. 사진 첨부의 옛 경로 처리 제거

**Execution profile**: standard

## 목표

운영 이전이 끝난 사진 첨부는 사용자별 경로만 저장·읽기·삭제하고 기동 복사를 실행하지 않는다.

**범위 외**: DB 스키마와 마이그레이션, 권한과 사용자 격리, API와 화면, Hermes plugin, 운영 compose 수정과 배포.

## 컨텍스트

#221 종료 댓글에서 사용자 사본 검증·백업·옛 폴더 삭제·양쪽 저장 종료가 확인됐다.
#308과 코디네이터 승인은 옛 경로 처리·설정·시험 제거 및 문서 정리를 요구한다.
코디네이터는 같은 배포에 운영 compose의 변수 행을 제거한다. 배포 후 왕복 확인도 코디네이터가 한다.
현재 `AttachmentStore.save`는 두 경로에 쓰고 `open`과 `AttachmentService.agentInput`은 `prepare`로 복사한다.
`AttachmentBackfill`만 `ChatAttachmentRepository.findByDeletedAtIsNullAndStoredNameIsNotNull(Pageable)`을 쓴다.
설정 record에는 6개 인자와 5개 인자의 보조 생성자가 있다. 제거 후 5개 인자 record로 합친다.

**근거 문서**: `docs/backend/attachment.md`의 「파일을 읽고 지울 때」와 ADR-091의 「기존 사진 이전 이력과 되돌리기」.

## 의도 메모

- 옛 경로의 파일을 자동 복구하지 않는다. 현재 경로에 파일이 없으면 기존 `ATTACHMENT_GONE` 410을 유지한다.
- 저장 실패 조각 정리, 기존 파일 충돌 보존, 이름 검증과 심볼릭 링크 거절, synchronized 잠금은 유지한다.
- 문서 수정안은 계획 단계에서 마련됐다. 구현과 같은 커밋에 포함한다.
- 이전 버전에서 운영 변수부터 빼면 기본값 true로 양쪽 저장이 다시 켜진다. 새 Backend를 먼저 적용하고 변수를 제거한다.

## 작업 항목

### 1. 회귀 테스트 확인

편집 전에 기존 `AttachmentStoreIsolationTest`, `AttachmentServiceTest`, `AttachmentCleanerTest`를 실행해 저장·읽기·권한·삭제·만료의 정상/실패 계약을 확인한다.
`AttachmentStoreIsolationTest`에서 옛 사본 복사·충돌·롤백·양쪽 삭제 전용 시험과 `legacyOf`를 제거한다.
사용자별 저장 시험은 첨부 루트 바로 아래 자식이 `users` 하나임을 확인하도록 강화한다.
현재 파일을 삭제하면 읽기가 `ATTACHMENT_GONE`이고 반복 삭제가 성공하는 시험을 둔다.
현재 경로의 중복 파일을 저장하면 실패하고 기존 바이트를 보존하는 시험과 본문 읽기 실패 때 조각을 남기지 않는 시험을 둔다.
이름 조작과 사용자 폴더 심볼릭 링크 거절 시험은 `save`, `open`, `delete` 대상으로 유지한다.

### 2. 저장소와 설정 정리

`AttachmentStore`에서 `legacyWriteEnabled`, `legacyPath`, `prepare` 및 관련 import를 제거한다.
`save`는 사용자 경로에 `saveNewFile`만 호출하고 `open`은 복사 없이 사용자 파일을 연다. `delete`는 사용자 파일만 지운다.
`AttachmentProperties`에서 legacy 인자·기본값·보조 생성자와 주석을 제거한다.
`application.yml`의 `legacy-write-enabled` 행을 제거한다.
`AttachmentBackfill.java`를 삭제하고 repository의 전용 Page 조회와 관련 import·주석을 제거한다.
`AttachmentService.agentInput`에서 `attached.forEach(store::prepare)`만 제거하며 주인 검사와 입력 문자열은 유지한다.

### 3. 서비스 테스트와 기동 대역 정리

`AttachmentServiceTest`에서 backfill 주입·import와 네 개 옛 경로 전용 시험, 옛 폴더 helper를 제거한다.
업로드 정상 시험에서 첨부 루트 자식이 `users` 하나인 것을 확인한다.
두 사용자의 첨부는 각 사용자 경로에서 읽히며 남의 대화 접근은 거절되는 회귀 시험을 유지한다.
현재 저장·읽기·입력 안내·장수·삭제 시험을 유지한다.
`CollationMixQueryMysqlTest`에서 사라지는 `AttachmentBackfill` 대역·import·설명만 제거한다.

### 4. 문서 일치 확인

계획 단계 수정안이 사용자별 저장 계약과 맞는지 확인한다. `docs/backend/attachment.md`, ADR-091, `docs/self-hosting.md`를 구현과 함께 커밋한다.
새 ADR과 스키마 변경은 만들지 않는다. 다른 docs는 제품·흐름·API·저장 모델을 바꾸지 않아 수정하지 않는다.

## 검증

```bash
cd backend && ./gradlew test --tests '*AttachmentStoreIsolationTest' --tests '*AttachmentServiceTest' --tests '*AttachmentCleanerTest' --tests '*AttachmentCleanerClockTest' --tests '*ChatAttachmentTurnTest' --tests '*RegenerateDeletedAttachmentTest' --tests '*AttachmentUploadLimitTest'
cd backend && ./gradlew qualityCheck --continue
scripts/check-public-safe.sh
```

Gradle 명령은 공통 규칙의 heavy-lock으로 감싸 실행한다. 무거운 전체 backend·MySQL·e2e·브라우저 검사는 PR CI에서 확인한다.
`git diff --check`는 성공해야 하고 attachment 운영 코드와 설정에는 legacy 처리와 `AttachmentBackfill`, `prepare` 호출이 없어야 한다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/chat/infra/AttachmentStore.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/AttachmentProperties.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ChatAttachmentRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/AttachmentService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/AttachmentBackfill.java` | 삭제 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/infra/AttachmentStoreIsolationTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/AttachmentServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/CollationMixQueryMysqlTest.java` | 수정 |
| `docs/backend/attachment.md` | 수정 |
| `docs/adr/ADR-091-사진-첨부는-사용자별로-저장하고-실행-공간에는-그-사용자만-붙인다.md` | 수정 |
| `docs/self-hosting.md` | 수정 |
