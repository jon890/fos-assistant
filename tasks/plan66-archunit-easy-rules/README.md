# plan66 ArchUnit 쉬운 규칙의 기준 줄이기

GitHub 이슈 66 의 첫 묶음이다.
`backend/config/archunit/store/` 의 기준 파일 가운데 아래 넷을 0 줄로 만든다.

| phase | 규칙 | 기준 파일 | 지금 줄 수 |
| --- | --- | --- | --- |
| 1 | `MESSAGE_DIGEST_ONLY_IN_SHA256` | `d3f00600-4e52-412f-ab23-2abb80f9eefd` | 3 |
| 2 | `CONFIGURATION_PROPERTIES_ARE_VALIDATED` | `f875bef7-581f-41c7-8e85-cf4b3d36f833` | 14 |
| 3, 4 | `NO_DIRECT_INSTANT_NOW` | `d3d721a0-86e5-4069-8051-dd3e7adf2c55` | 36 |
| 5, 6 | `TRANSACTIONAL_ONLY_IN_APPLICATION` | `54473729-2b30-4508-9d02-64e810d6f34b` | 14 |

phase 는 번호 순서로 실행한다. 3 과 4, 5 와 6 은 같은 기준 파일을 줄인다.

## 모든 phase 에 걸리는 규칙

- **동작을 바꾸지 않는다.** 응답, 저장되는 값, 트랜잭션이 열리고 닫히는 자리가 그대로여야 한다.
- 고친 코드와 줄어든 기준 파일을 같은 커밋에 넣는다. 기준 파일은 손으로 고치지 않고 아래 명령으로 줄인다.
- `ArchitectureRules.java` 의 규칙을 느슨하게 바꾸지 않는다. 규칙을 통과하려고 이름이나 호출 모양만 바꾸는 방법도 쓰지 않는다.
- 기능 변경과 포맷은 다른 커밋이다. phase 커밋 뒤에 `./gradlew spotlessApply` 결과가 있으면 따로 커밋한다.
- 주석과 Javadoc 은 한국어로 쓴다. 테스트 메서드에는 `@DisplayName` 을 단다.
- `gradlew` 는 `backend/` 안에 있다.

## 기준을 줄이는 명령

```bash
# cwd: backend/
./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true
```

이 명령은 고쳐진 위반만 기준에서 뺀다. 새 위반이 있으면 실패한다.
**클래스나 메서드의 이름과 시그니처를 바꾸면 다른 규칙의 기준 줄이 글자만 달라져 새 위반으로 잡힌다.**
그때만 그 규칙 하나를 다시 얼린다. 절차는 `docs/backend/quality.md` 의 「구조 규칙의 기준 파일」 에 있다.
다시 얼린 뒤 그 규칙의 기준 파일 줄 수가 늘지 않았는지 `git diff --stat` 로 확인하고, 까닭을 커밋 메시지에 적는다.
줄 수가 늘면 다시 얼리지 않고 코드를 고친다.

## 이 plan 에 넣지 않은 것

`ENUMERATED_FIELDS_USE_DOMAIN_TYPE`, `LAYER_DIRECTION`, `TOP_LEVEL_PACKAGES_FREE_OF_CYCLES`,
`SHARED_DOES_NOT_DEPEND_ON_DOMAINS`, `SERVICES_DO_NOT_EXPOSE_NESTED_TYPES` 는 뒤의 PR 이 맡는다.
enum 을 `domain.type` 으로 옮기면 import 가 수백 파일에서 바뀌어 이 PR 과 섞으면 검토할 수 없다.
