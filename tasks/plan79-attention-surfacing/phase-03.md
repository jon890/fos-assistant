# Phase 03. 결과 전달 실패 후보

**Execution profile**: standard

## 목표

#162 가 저장하는 결과 전달 실패 상태를 읽어 `DELIVERY_FAILED` 후보를 만든다.
같은 대화의 실패한 turn 과 한 항목으로 합쳐 실패 카드에 보인다.
이 phase 가 이 plan 의 마지막이다. 남은 「아직 구현 전」 표시를 지운다.

**범위 외**: 결과 전달 상태를 저장하거나 고치는 일, 「결과 다시 전하기」 경로(#162). 화면의 단추(plan81).

## Blocked 조건

이 phase 를 맡기는 지시문에 아래 둘이 모두 적혀 있어야 한다. 하나라도 없으면 `PHASE_BLOCKED: #162 의 결과 전달 상태를 알 수 없다` 를 출력하고 멈춘다.

| 적혀 있어야 하는 것 | 예 |
| --- | --- |
| #162 의 구현 PR 이 main 에 머지됐다 | PR 번호와 머지한 날짜 |
| 그 상태를 읽을 표와 칸, 상태 값, 사용자와 대화를 잇는 칸 | 「실패」 와 「완료」, 「의도적 중단」 을 가르는 칸의 이름과 값 |

코드로도 본다. 지시문에 적힌 표의 엔티티가 `backend/src/main/java/com/bifos/assistant/` 아래에 없으면 `PHASE_BLOCKED: #162 의 상태 표가 main 에 없다` 를 출력하고 멈춘다.
구현자가 #162 의 상태를 추정해 새 표나 칸을 만들지 않는다.

## 컨텍스트

**근거 문서**: `docs/backend/attention.md` 의 「후보와 trigger」 의 `DELIVERY_FAILED` 줄, 「`signals` 의 값」 의 `DELIVERY_NOT_DONE`, 「카드의 단추와 승인 경계」. `docs/adr/ADR-072-먼저-알리기의-기본값은-알리지-않음이고-control-plane-기록에서-정한-신호만-화면-안에-올린다.md` 의 「적용 범위」. #162 가 머지하며 고친 `docs/` 의 결과 전달 절(지시문이 가리킨다).

phase 01 이 만든 것을 쓴다.

| 무엇 | 어디 |
| --- | --- |
| 실패 카드의 후보 | `attention.application.FailedTurnCandidates`. 대화마다 `conversation:<UUID>` 열쇠 하나 |
| 후보 값 | `attention.application.model.AttentionCandidate` |
| trigger 이름 | `attention.domain.type.AttentionTrigger.DELIVERY_FAILED`(phase 01 이 이름만 두었다) |
| 신호 | `attention.application.model.AttentionSignal.DELIVERY_NOT_DONE` |
| 중복 판정 | `AttentionJudge` 는 같은 카드 안에서 같은 `itemKey` 둘을 따로 낸다. 그래서 합치기는 후보 수집에서 한다 |

## 의도 메모

- **상태를 읽기만 한다.** #162 의 표에 쓰지 않고 그 서비스의 쓰기 메서드를 부르지 않는다. 읽기 메서드가 없으면 #162 패키지의 `application` 에 읽기 메서드 하나만 더한다
- **한 대화는 한 항목이다.** 같은 대화에 실패한 turn 과 결과 전달 실패가 함께 있으면 열쇠는 그대로 `conversation:<UUID>` 이고, `signals` 에 `NOT_RETRIED` 와 `DELIVERY_NOT_DONE` 이 함께 든다. `trigger` 는 더 최근에 생긴 쪽이다
- `stateKey` 재료에 두 쪽의 상태를 함께 넣는다. 마지막 실패 실행 번호와 마지막 전달 시도 번호다. 둘 중 하나만 바뀌어도 숨긴 항목이 다시 보인다
- 해결된 상태는 #162 가 정한 완료나 의도적 중단이다. 그 전달만 해결되고 실패한 turn 이 남아 있으면 항목은 `EXECUTION_FAILED` 로 남는다
- `sources` 에 #162 상태의 참조를 더한다. 형식은 `docs/backend/context-bundle.md` 의 「항목의 칸」 의 `ref` 처럼 `<표 이름>:<번호>` 다. 지시문이 정한 이름을 쓴다

## 작업 항목

### 1. #162 상태의 읽기

지시문이 가리킨 #162 의 서비스에서 「한 사용자의, 아직 해결되지 않은 결과 전달 실패」 를 대화 번호와 함께 읽는다. 기간은 `failureWindow` 다.
없으면 그 패키지 `application` 에 `undeliveredFailuresOf(Long userId, Instant since)` 같은 읽기 메서드 하나를 더한다. 이름과 반환 타입은 #162 코드의 관례를 따른다.

### 2. `FailedTurnCandidates` 에 합친다

- 결과 전달 실패를 대화 번호로 묶어 실패한 turn 후보와 합친다. 대화가 지워졌으면 뺀다(`OwnConversations.activeOf`)
- 결과 전달 실패만 있는 대화도 후보가 된다. 이때 `trigger` 는 `DELIVERY_FAILED`, `signals` 는 `[DELIVERY_NOT_DONE]` 이다
- `nowSignal` 은 참이다. `at` 은 두 쪽 가운데 늦은 시각이다
- 그 구현의 `cards()` 는 그대로 `FAILURES` 다. #162 상태를 읽다 예외가 나면 실패 카드가 `UNAVAILABLE` 이 된다. phase 01 의 규칙 그대로다

### 3. 문서의 「아직 구현 전」 표시를 지운다

- `docs/adr/ADR-072-먼저-알리기의-기본값은-알리지-않음이고-control-plane-기록에서-정한-신호만-화면-안에-올린다.md` 의 `status` 를 `` `accepted` `` 로
- `docs/adr/INDEX.md` 의 ADR-072 줄 상태 칸을 `Accepted` 로
- `docs/README.md` 의 `backend/attention.md` 줄 끝 「아직 구현 전이다」 를 지운다
- `docs/backend/attention.md` 의 구현 전 단락을 지운다. 「후보와 trigger」 아래의 「`DELIVERY_FAILED` 는 #162 가 main 에 들어온 뒤에 더한다」 문장을 #162 가 정한 상태 이름을 가리키는 한 줄로 바꾼다
- `docs/code-architecture.md` 의 「아직 만들지 않은 것」 에서 `attention` 줄을 지운다
- 이 plan 디렉터리 `tasks/plan79-attention-surfacing/` 를 지우는 것은 `build-with-teams` 의 마감 단계가 한다

### 4. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/attention/AttentionServiceTest.java` 에 더한다.

| 입력 | 기대 |
| --- | --- |
| 한 대화에 결과 전달 실패 하나(#162 의 실패 상태) | `failures` 에 `conversation:<UUID>` 항목, `trigger` `DELIVERY_FAILED`, `signals` `["DELIVERY_NOT_DONE"]`, `NOW` |
| 같은 대화에 실패한 turn 도 있다 | 항목이 하나이고 `signals` 에 둘이 다 있다 |
| 그 전달이 완료 상태가 된다 | 실패한 turn 만 남아 `EXECUTION_FAILED` 로 보인다 |
| 그 전달이 의도적 중단 상태다 | 결과 전달 실패로 보이지 않는다 |
| 다른 사용자의 결과 전달 실패 | 응답에 없다 |

`test/e2e/scenarios/attention.ts` 에는 #162 가 e2e 에서 결과 전달 실패를 만드는 방법을 열어 두었을 때만 더한다. 없으면 더하지 않고 그 까닭을 커밋 메시지에 한 줄 남긴다.

## 검증

```bash
# cwd: backend/
./gradlew test --tests 'com.bifos.assistant.attention.AttentionServiceTest' --tests 'com.bifos.assistant.attention.AttentionJudgeTest'
./gradlew test
./gradlew checkstyleMain checkstyleTest spotlessCheck
```

```bash
# cwd: 저장소 root
node test/e2e/run.ts
scripts/check-mysql-migration.sh
scripts/check-public-safe.sh
grep -n "아직 구현 전" docs/adr/ADR-072-*.md docs/backend/attention.md
```

기대값: 앞의 명령은 모두 종료 코드 0 이다. 마지막 `grep` 은 아무것도 내지 않고 종료 코드 1 이다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/attention/application/FailedTurnCandidates.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/attention/AttentionServiceTest.java` | 수정 |
| `docs/adr/ADR-072-먼저-알리기의-기본값은-알리지-않음이고-control-plane-기록에서-정한-신호만-화면-안에-올린다.md` | 수정 |
| `docs/adr/INDEX.md` | 수정 |
| `docs/backend/attention.md` | 수정 |
| `docs/README.md` | 수정 |
| `docs/code-architecture.md` | 수정 |

#162 패키지에 읽기 메서드를 더해야 하면 그 파일과 `test/e2e/scenarios/attention.ts` 를 이 표에 더한다. 경로는 지시문이 가리킨 #162 코드에서 정한다.
