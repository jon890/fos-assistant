# Phase 02. 민감해 보이는 제목과 본문은 sensitive=false 여도 제안으로 내린다

**Execution profile**: standard

## 목표

모델이 `sensitive` 를 거짓으로 주어도 제목이나 본문에 건강, 금융, 신원과 신념의 낱말이나 여섯 자리 이상의 숫자열, 메일 주소가 있으면 바로 저장하지 않고 제안으로 내린다.

**범위 외**: 본문 구간 판정(phase 01), 기능 도입 전 실행을 세는 쿼리(phase 03). 민감도 값은 바꾸지 않는다(SENSITIVE 로 올리지 않는다).

## 컨텍스트

**근거 문서**: `docs/backend/memory.md` 의 「에이전트가 기억을 남기는 길」 절, `docs/adr/ADR-20261008-memory-remember-guard.md`

- 계약: `docs/backend/memory.md` 의 「바로 저장 판정」 표 7, 8 과 「민감해 보이는 글」 절.
- 근거: `docs/adr/ADR-20261008-memory-remember-guard.md` 의 결정 3 과 대안 기각의 「SENSITIVE 로 올려 암호화 저장한다」.
- `backend/src/main/java/com/bifos/assistant/memory/application/MemoryCaptureService.java` 의 `remember(CurrentUser, MemoryAccess, MemoryRememberRequest)` 가
  `boolean direct = request.direct() && sensitivity == MemorySensitivity.NORMAL;` 로 바로 저장을 정한다. 이 값이 새 항목, 같은 제안 받아들이기(`existingOutcome`)에 쓰인다.
  고치기는 `update(...)` 가 `!request.direct() || request.sensitivity() != MemorySensitivity.NORMAL` 이면 `UPDATE_NEEDS_CONFIRMATION` 을 낸다.
- `MemoryRememberRequest` 의 `title()`, `content()` 는 앞뒤 공백을 지운 값이다. `toString()` 은 본문을 싣지 않는다. 로그에 제목과 본문을 남기지 않는다.
- 시험은 `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryRememberToolTest.java` 다. 이 phase 시작 시점에는 phase 01 이 바꾼 시험이 들어 있다.
  바로 저장 조건을 만족하는 질문은 `askedInThisTurn()` 이 잇는 `QUESTION = "우리 집   다른 사람은 홍길동이야. 기억해 줘"` 다.
  민감 낱말 시험은 질문을 따로 이어야 하므로 질문 원문을 받는 보조 메서드(`askedInThisTurn(String question)`)를 더한다.

## 의도 메모

- 낱말 목록은 잘못 걸리는 경우가 있다(「장애물」). 잘못 걸려도 제안 카드가 될 뿐이라 넓게 잡는다. 「약」 처럼 한 글자 낱말은 넣지 않는다(「약속」, 「예약」).
- 낱말은 공백을 뺀 글에서 찾는다(「카드 번호」 와 「카드번호」).
- 숫자열은 공백을 빼기 전의 글에서 `\d[\d -]*\d` 를 찾아 그 안의 숫자가 6개 이상이면 걸린다(전화, 계좌, 주민번호).
- 메일 주소는 `[^\s@]+@[^\s@]+\.[^\s@]+` 다.

## 작업 항목

### 1. `backend/src/main/java/com/bifos/assistant/memory/application/MemorySensitiveHints.java` 신규

```java
/** 제목이나 본문이 민감해 보이는가. 바로 저장을 막는 데만 쓴다(ADR-20261008 / memory-remember-guard). */
public static boolean suspected(String title, String content)
```

- `public final class`, private 생성자. 입력을 NFC 로 맞춘다.
- 낱말 목록(`List<String>` 상수, 갈래별 주석):
  - 건강: 병원, 진단, 질환, 질병, 병력, 지병, 수술, 입원, 처방, 복용, 투약, 우울, 공황, 장애, 정신과, 임신, 당뇨, 혈압, 항암, 알레르기, 알러지, 치료, 증상, 투병, 건강검진
  - 금융: 계좌, 카드번호, 신용카드, 비밀번호, 암호, 대출, 빚, 부채, 연봉, 월급, 급여, 소득, 재산, 자산, 주식, 코인, 보험, 세금, 신용점수, 파산, 적금, 예금
  - 신원과 신념: 주민등록, 주민번호, 여권, 운전면허, 종교, 신앙, 기독교, 천주교, 불교, 이슬람, 교회, 성당, 정당, 투표, 성적지향, 동성애, 성정체성, 트랜스젠더, 국적, 체류, 비자, 전과, 범죄
- 숫자열과 메일 주소 규칙은 의도 메모와 같다.

### 2. `backend/src/main/java/com/bifos/assistant/memory/application/MemoryCaptureService.java` 수정

- `remember` 에서 `boolean direct = request.direct() && sensitivity == MemorySensitivity.NORMAL && !MemorySensitiveHints.suspected(request.title(), request.content());`
- `update` 의 첫 검사에 `|| MemorySensitiveHints.suspected(request.title(), request.content())` 를 더해 `UPDATE_NEEDS_CONFIRMATION` 을 낸다.
- 클래스 Javadoc 의 「민감 항목은 바로 저장 조건이어도 제안이다」 뒤에 「모델이 일반으로 주어도 민감해 보이는 글이면 제안이다」 를 더한다.

### 3. 시험

- `backend/src/test/java/com/bifos/assistant/memory/application/MemorySensitiveHintsTest.java` 신규(디렉터리도 새로 생긴다). 순수 단위 시험.
  - 참: 「아들은 땅콩 알레르기가 있어」, 「월급 통장은 국민은행이야」, 「종교는 불교야」, 「카드 번호는 1234 5678 9012」, 「메일은 user@example.com 이야」, 제목에만 「계좌」 가 든 경우.
  - 거짓: 「아들 이름은 홍길동이야」, 「매운 음식을 못 먹어」, 「약속은 금요일이야」(한 글자 「약」 이 걸리지 않음), 「아들은 열 살이야」, 「전화는 오후에 해」.
- `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryRememberToolTest.java` 수정.
  - `askedInThisTurn(String question)` 를 더하고 `askedInThisTurn()` 은 그것을 `QUESTION` 으로 부른다.
  - 시험 하나를 더한다: 질문 「아빠 계좌는 국민은행이야.」 를 잇고 `remember("아빠 계좌", "아빠 계좌는 국민은행이야", null)` (sensitive 없음)이 PROPOSED, 항목 `status` 가 `PROPOSED` 이고 `sensitivity` 가 `NORMAL` 이다.
  - 시험 하나를 더한다: 질문 「진단받은 병은 없어. 다른 사람은 홍길동이야.」 처럼 본문 밖에만 민감 낱말이 있으면 `remember("다른 사람", "다른 사람은 홍길동이야", null)` 가 바로 저장된다(질문 원문 전체가 아니라 제목과 본문만 본다).

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.memory.application.MemorySensitiveHintsTest' --tests 'com.bifos.assistant.mcp.McpMemoryRememberToolTest'
cd backend && ./gradlew checkstyleMain checkstyleTest archTest
```

기대값: 모두 통과.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/memory/application/MemorySensitiveHints.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryCaptureService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/application/MemorySensitiveHintsTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryRememberToolTest.java` | 수정 |
