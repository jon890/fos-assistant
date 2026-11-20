# Phase 03. 기능별 규칙 판정

**Execution profile**: standard

## 목표

일곱 기능의 통과 규칙을 네트워크와 DB 를 쓰지 않는 순수 함수로 만든다. 뒤 PR 의 실행기는 실행 기록에서 이 함수의 입력을 모아 부른다.

**범위 외**: 입력을 모으는 일과 실행(뒤 실행기 PR).

## 컨텍스트

**요구사항**: REQ1

**근거 문서**: `docs/features/agent-quality/design.md` 의 「기능별 문항과 판정」, `docs/features/agent-quality/spec.md` 의 「배포 뒤 확인 기능」

- 입력은 다른 패키지 타입에 기대지 않는 값 record 로 둔다. 모양은 아래로 정한다.

  | record | 칸 |
  | --- | --- |
  | `CheckObservation` | `List<TurnObservation> turns`(기능이 돈 turn 차례대로, 사진은 둘, 위임은 사용자 turn 과 자동 turn), `int vaultRows`(그 대화로 `vault_origin` 이 있는 보관 행 수), `boolean delivered`(위임 결과 전달이 `DELIVERED`), `String taskRunStatus`(예약 작업 발화의 `task_run` 상태 이름, 없으면 null), `Long taskRunExecutionId`(그 발화의 `execution_id`), `boolean timedOut` |
  | `TurnObservation` | `String answer`(답 글, 없으면 빈 글), `String executionStatus`(실행 줄 상태 이름), `String errorCode`(실패면 오류 코드, 아니면 null), `List<ToolObservation> tools`(트리 전체의 끝 사건), `List<ChildObservation> children`, `List<String> artifactKinds`(답 메시지에 묶인 결과물 종류. 그림은 `chat/artifact/domain/VaultFileType.Kind.IMAGE` 의 이름 `IMAGE`), `boolean approvalRequested` |
  | `ToolObservation` | `String toolName`, `boolean failed` |
  | `ChildObservation` | `boolean delegated`(`delegation_key` 가 있는 자식), `String status` |

  판정은 이 값만 읽는다. turn 이 여럿이면 실패·시간 초과·승인 카드는 어느 turn 하나라도 해당하면 그 까닭으로 판정한다. 기능 규칙의 답 글은 마지막 사용자 turn(사진은 둘째, 위임은 자동 turn 이 아닌 사용자 turn 의 트리와 자동 turn 의 성공)으로 본다. 예약 작업 발화는 상태가 `SUCCEEDED` 이고 `taskRunExecutionId` 가 있어야 통과다. 상태 이름 비교는 문자열 상수로 한다(다른 패키지 enum 을 import 하지 않는다).
- 출처 절 규칙은 `test/model-eval/answer/score.ts` 의 `sourceLinks` 와 같은 뜻으로 Java 로 옮긴다: 답 끝 「출처」 절(「출처」 가 든 제목 줄이나 「출처」 로 시작하는 줄 뒤)의 마크다운 링크와 맨 주소를 센다. 절 밖 링크는 세지 않는다. 그 파일의 단위 테스트(`test/model-eval/answer/answer-eval-score.test.ts`)의 출처 사례를 같은 기대값으로 옮긴다.
- 결과는 `FeatureVerdict(FeatureStatus status, String reasonCode)`. 까닭 코드는 `quality/domain/type/CheckReason.java` enum 으로 둔다: 공통 `TIMED_OUT`, `RUN_FAILED`, `HERMES_TIMEOUT`, `APPROVAL_REQUESTED`, `BUSY`, `INTERRUPTED` 와 기능 규칙 `NO_SEARCH`, `NO_SOURCES`, `NO_CHILD`, `CHILD_FAILED`, `NOT_DELIVERED`, `NO_VAULT_ROW`, `NO_IMAGE`, `FACT_MISSING`, `TASK_NOT_SUCCEEDED`, `NO_INSPECT`, `MARKER_MISSING`. 판정 순서: `timedOut` 이면 `TIMED_OUT`, 오류 코드가 `HERMES_RUN_TIMEOUT` 이면 `HERMES_TIMEOUT`(상태는 `TIMED_OUT`), `USER_BUSY`·`CONVERSATION_BUSY` 면 `BUSY`, 그 밖의 실패면 `RUN_FAILED`, 승인 카드면 `APPROVAL_REQUESTED`, 그다음 기능 규칙. `BUSY` 와 `INTERRUPTED` 는 실행기가 직접 적을 때도 쓴다.
- 미리 넣는 기억의 제목·본문·확인 낱말과 합성 사진의 심은 글자, 기능별 문항 글은 `quality/application/DeployCheckItems.java` 의 상수로 둔다. **이 클래스는 package-private `final` 클래스**다(`application` 직하의 빈이 아닌 public 클래스는 `PackageLayoutRules` 가 막는다). phase 2 의 `EvaluationUserService` 가 같은 패키지라 기억 상수를 그대로 쓴다. phase 2 가 먼저 돌면 기억 상수를 그 서비스 안에 두었다가 이 phase 에서 옮긴다.

## 의도 메모

- 판정 모델을 쓰지 않는다. PR1 은 기능이 동작하는지만 본다(스펙 REQ1).
- 실행 사건 대신 단계 로그를 읽는 판정은 Control Plane 이 읽을 수 없어 쓰지 않는다(설계 「기능별 문항과 판정」 의 사진 조회 줄).

## 작업 항목

### 1. `backend/src/main/java/com/bifos/assistant/quality/application/DeployCheckJudge.java`

빈 하나. `FeatureVerdict judge(QualityFeature feature, CheckObservation observation)`. 공통으로 먼저 본다: 실행 실패면 `RUN_FAILED`, 승인 카드가 떴으면 `APPROVAL_REQUESTED`. 그다음 기능별 규칙(설계 표 그대로).

### 2. `quality/application/dto/CheckObservation.java`, `FeatureVerdict.java`, `quality/application/DeployCheckItems.java`(문항 글, 기억 낱말, 사진 글자 상수)

### 3. 출처 절 읽기

`quality/application/SourceSection.java` 가 아니라 `DeployCheckJudge` 의 package-private 정적 도우미로 둔다(`application` 직하에는 빈만 둔다는 규칙, 빈이 소유한 도우미는 허용).

### 4. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/quality/application/DeployCheckJudgeTest.java`: 기능마다 통과 하나와 실패 하나 이상. 출처 절 사례는 `answer-eval-score.test.ts` 의 출처 사례와 같은 입력·기대값. 승인 카드와 실행 실패가 기능 규칙보다 먼저인 사례.

## 검증

```bash
cd backend && ./gradlew test --tests '*DeployCheckJudgeTest'
cd backend && ./gradlew archTest
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/quality/application/DeployCheckJudge.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/quality/application/DeployCheckItems.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/quality/application/dto/CheckObservation.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/quality/application/dto/FeatureVerdict.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/quality/application/dto/TurnObservation.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/quality/application/dto/ToolObservation.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/quality/application/dto/ChildObservation.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/quality/domain/type/CheckReason.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/quality/application/EvaluationUserService.java` | 수정 (기억 상수를 DeployCheckItems 로 옮김) |
| `backend/src/test/java/com/bifos/assistant/quality/application/DeployCheckJudgeTest.java` | 신규 |
