# 이동 PR 의 공통 절차

phase 03 부터 09 까지의 이동 PR 이 모두 따른다. 이 문서만 읽고 실행할 수 있어야 한다.
phase 하나에 PR 이 여러 개일 수 있다. PR 번호 `n-m` 은 각 phase 문서의 「이동 PR n-m」 이고, 한 PR 은 한 작업이다.

## 시작 조건

- 쓰지 않는 코드 정리가 머지됐다. 정리는 다음 넷이다.
  - 서비스 토큰: `memory` 의 `ServiceToken*` 와 문서 읽기 API
  - 고정 흐름: `ResearchAndBuildFlow`, `Flow`, `FlowRegistry`, `KnownFlows`
  - 옛 커넥터 호환: 옛 전용 에이전트의 설치와 확인과 삭제 경로, vault import
  - 작은 것 묶음: `MemoryProposer`, `MemoryProposalProperties`, `ModelTierSeedImporter`, `ModelTierProperties`
  `git grep -nE 'ServiceToken|ResearchAndBuildFlow|FlowRegistry|KnownFlows|MemoryProposer|MemoryProposalProperties|ModelTierSeedImporter|ModelTierProperties' -- backend/src/main backend/src/test` 가 아무것도 내지 않아야 한다. 나오면 시작하지 않고 코디네이터에 알린다.
  정리가 지운 클래스는 이동 표에 없다.
- 앞선 이동 PR 이 머지됐고 최신 main 에서 새 브랜치를 땄다. 이동 PR 은 직렬이다. 둘을 동시에 열지 않는다.
- 표에 있는데 지금 없는 클래스, 지금 있는데 표에 없는 클래스는 표가 낡은 것이다. 해당 하위 기능의 설명(ADR 의 「하위 기능」 표)으로 자리를 정하고 PR 본문에 적는다.

## 순서

1. 표를 입력으로 받는 스크립트로 옮긴다. 에이전트 구현자에게는 IDE 의 패키지 이동이 없다. 스크립트는 저장소에 두지 않고 임시 디렉터리에 둔다. 스크립트가 하는 일은 아래와 같다.
   - `git mv` 로 파일을 새 디렉터리에 옮긴다. 클래스 이름은 바꾸지 않는다.
   - 옮긴 파일의 `package` 줄을 새 패키지로 고친다.
   - `backend/src` 의 모든 Java 파일에서 옛 전체 이름을 새 전체 이름으로 바꾼다. `import`, `import static`, 문자열, Javadoc 을 모두 포함한다. 클래스 하나씩 긴 이름부터 바꿔 접두 충돌을 피한다.
   - 옮기기 전에는 같은 패키지라 `import` 가 없던 참조에 `import` 를 더한다. 반대로 같은 패키지가 된 `import` 는 지운다. 와일드카드 import 는 Checkstyle 이 금지하므로 없다.
   - 마지막에 `./gradlew spotlessApply` 로 `import` 순서를 맞춘다.
   파일 내용에서 바뀌는 것은 `package` 줄과 `import`(와 아래 순서 4 의 문자열)뿐이다.
2. 테스트는 두 곳에 있다. 기능 루트 패키지(`com.bifos.assistant.chat` 같은 곳)의 테스트는 옮기지 않는다. 그 패키지는 `application` 의 package-private 에 닿지 못하고 public 만 쓰므로 `import` 만 바뀐다. `src/test/java/.../<기능>/application/` 에 있는 테스트는 대상 클래스(`<이름>Test`)가 간 새 `application` 패키지로 따라 간다. 대상이 둘 이상이면 가장 위 하위 기능에 둔다.
3. package-private 접근이 끊긴 곳이 컴파일 오류로 나온다. 표 아래 「승격」 목록의 클래스만 `public` 으로 올리고, 목록에 없는 것이 더 나오면 같은 방식으로 올려 PR 본문에 적는다. 본문은 고치지 않는다. 올린 비빈 도우미가 `application` 직하에 남으면 `APPLICATION_ROOT_HOLDS_ONLY_BEANS` 에 줄이 생긴다. 해당 PR 의 「기준 파일에 더하는 줄」 에 있다.
4. 전체 이름 문자열을 확인한다. 순서 1 이 문자열도 바꾸지만 컴파일이 검증하지 못하므로 눈으로 본다.
   `git grep -nE 'com\.bifos\.assistant\.<옛 패키지>' -- backend/src` 에서 `import` 와 `package` 가 아닌 줄이다. JPQL 의 `new com.bifos...`, `@Slf4j(topic = ...)`, Javadoc 의 `{@link}` 가 여기 든다. 표 아래 「문자열 전체 이름」 목록이 이 PR 몫이다.
5. 규칙이 직접 적은 전체 이름과 대상 선택 조건을 고친다. `ArchitectureRules.java` 의 import 와 `*PlacementTest.java`, `ContentDraftSnapshotArchitectureTest.java` 의 문자열이다. `agentAdminRoles`, `adminHttpRoles`, `agentPurgeRoles` 는 경계 문자열(`.agent.admin.`, `.agent.purge.`)과 `application.model` 리터럴로 대상을 고르므로, 기대 전체 이름만 고치면 이동 뒤에 아무것도 선택하지 못해 조용히 통과한다. 경계와 리터럴도 새 구조로 다시 쓴다.
6. 경로를 적은 곳을 새 경로로 고친다.
   - `scripts/checks/file-length-baseline.json`
   - `scripts/pr/pr-risk-labels.sh`
   - `docs/` 와 `backend/docs/` 의 경로 표기. `node --test test/unit/doc-code-references.test.ts` 가 찾는다.
7. 기준 파일을 가감한다. 줄은 이 PR 의 「기준 파일에 더하는 줄」 목록과 「지우는 줄」 목록대로만 늘고 준다.
   - 줄이는 것: 옮겨서 위반이 사라졌으면 `./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true` 로 기준에서 뺀다. 기준 대조가 패키지 이름을 무시하므로 옮기기만 해서 줄이 늘지 않는다.
   - 더하는 것: 목록에 줄이 있으면 `backend/docs/code-architecture.md` 의 「구조 규칙의 기준 파일」 의 「새 위반을 받아들이거나 다시 얼린다」 를 따른다. 규칙 하나씩 `--tests '*ArchitectureRulesTest.<메서드>'` 로 한정해 얼린다. `--tests` 를 빼면 다른 규칙의 새 위반까지 조용히 들어간다. 얼린 뒤 `git diff backend/config/archunit/store` 가 목록과 같은 줄만 더하는지 본다. 까닭을 커밋 메시지와 PR 본문에 적는다.
8. 검사를 돌린다. 저장소 root 에서 `scripts/dev/check-local.sh --fast`, `backend/` 에서 이동한 기능의 테스트(`./gradlew test --tests 'com.bifos.assistant.<기능>.*'`)와 `./gradlew archTest`.
   저장소를 옮겼으면 `RepositoryQueryMysqlTest` 가 JPQL 의 전체 이름을 확인한다. Docker 가 있는 곳에서 `scripts/checks/check-mysql-migration.sh --tests '*RepositoryQueryMysqlTest'` 로 돌리거나 Draft CI 에 맡긴다.
9. 커밋을 나눈다. 이동(`package` 와 `import` 만 바뀜), 승격, 전체 이름 문자열과 경로 수정, 기준 파일 순이다. `./gradlew spotlessApply` 결과는 따로 커밋한다. 이동 커밋은 `git diff --color-moved` 로 이동만 있다는 것이 보여야 한다.
10. PR 은 Draft 로 연다. 본문에 옮긴 클래스 수, 추정 줄 수와 실제 줄 수, 승격한 클래스, 줄어든 기준 파일의 줄과 더한 줄을 적는다. 운영 코드 1500줄 상한을 넘으면 하위 기능 단위로 PR 을 나눈다.

## 한 번에 하는 이동의 크기

추정 줄 수는 `package` 줄 두 줄, 바뀌는 `import` 한 줄당 두 줄, 새로 생기는 `import` 한 줄당 한 줄로 센 값이다. 실제는 포맷과 `import` 정렬 때문에 3할쯤 다를 수 있다.
추정이 1200줄을 넘는 PR 은 없다. 넘으면 하위 기능 단위로 나눈다.

## 충돌

이동 PR 은 거의 모든 파일의 `import` 를 건드린다. 이동 PR 이 열려 있는 동안 다른 작업의 PR 이 같은 파일을 고치면 그쪽이 충돌한다.
이동 PR 은 Draft CI 를 통과시킨 뒤 바로 Ready 로 올리고 같은 날 머지한다. 이동 PR 뒤에 머지하는 다른 PR 은 main 을 합친 뒤 새 패키지 이름으로 `import` 를 고친다.
