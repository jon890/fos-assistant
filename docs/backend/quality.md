# backend 품질 검사

기준 파일을 갱신하거나 규칙을 뺄 때 읽는 절차를 갖는다.
규칙의 이름과 뜻은 `ArchitectureRules.java` 의 Javadoc 이, 코드 규칙의 심각도와 까닭은 `checkstyle.xml` 의 주석이 갖는다.

## 구조 규칙의 기준 파일

지금 있는 위반은 `backend/config/archunit/store/` 의 기준 파일에 얼려 두고 새 위반만 실패시킨다.
`stored.rules` 가 규칙의 `as(...)` 설명과 기준 파일 이름을 잇는다.
설명을 바꾸면 기준이 새 규칙으로 옮겨지지 않으므로, 설명을 바꿀 때는 그 규칙을 다시 얼린다.

**기준에 든 위반은 허용이 아니라 줄여 갈 목록이다.**
기준마다 연 GitHub 이슈가 있다. 위반을 고치면 그 기준 파일도 같은 커밋에서 줄인다.
지금은 구조 규칙의 위반을 모두 고쳐 기준 파일이 비어 있다. 파일은 새 위반을 받아들여야 할 때를 위해 남긴다.

평소의 테스트는 기준 파일을 쓰지 않고, 기준과 실제가 어긋나면 실패한다.
쓰기는 Gradle 속성으로만 켠다. 명령은 모두 `backend/` 에서 돈다.

| 언제 | 어떻게 |
| --- | --- |
| 기준에 든 위반을 고쳤다 | 평소 테스트가 `StoreUpdateFailedException: Updating frozen violations is disabled` 로 실패한다. `./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true` 로 기준에서 빼고 그 변경을 같은 커밋에 넣는다. `scripts/quality.sh fix` 도 같은 명령으로 기준을 줄인다 |
| 기준에 든 클래스의 이름만 바꿨다 | 같은 예외로 실패한다. 아래 「새 위반을 받아들이거나 다시 얼린다」 를 따른다 |
| 규칙을 새로 더했다 | `./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true` 로 그 규칙의 기준 파일을 만든다. `allowStoreCreation` 은 `stored.rules` 가 없을 때만 쓴다 |
| 새 위반을 받아들이거나 다시 얼린다 | `./gradlew archTest --rerun --tests '*ArchitectureRulesTest.<메서드>' -Parchunit.freeze.refreeze=true -Parchunit.freeze.store.default.allowStoreUpdate=true`. 까닭을 커밋 메시지와 PR 본문에 적는다 |

**다시 얼릴 때는 `--tests` 로 규칙 하나만 대상으로 삼는다.**
`refreeze` 는 그 실행이 검사하는 모든 규칙을 다시 얼린다.
`--tests` 를 빼면 다른 규칙의 새 위반까지 조용히 기준에 들어간다.

## 뺀 규칙

| 규칙 | 까닭 |
| --- | --- |
| `LineLength`, `Indentation`, `WhitespaceAround`, `CustomImportOrder` | 포매터가 정한다. 둘이 같은 것을 다르게 판정하면 고칠 수 없는 위반이 생긴다 |
| `JavadocMethod`, `JavadocType`, `MissingJavadocMethod` 같은 Javadoc 규칙 | 주석은 한국어로 필요한 곳에만 쓴다(`backend/AGENTS.md` 의 「주석」 절). 모든 메서드에 요구하면 뜻 없는 주석이 늘어난다 |
| `MagicNumber` | 테스트와 설정 기본값에서 대부분 오탐이다 |
| `FinalParameters`, `HiddenField` | 생성자 주입과 record 가 이름을 같게 쓰는 것이 이 저장소의 모양이다 |
| `DesignForExtension` | Spring 빈과 싸운다 |

## 코드 규칙의 기준 파일

지금 있는 위반은 `config/checkstyle/baseline.xml` 에 `(파일, 규칙)` 한 쌍마다 한 줄로 두고 새 위반만 실패시킨다.
**기준에 든 위반은 허용이 아니라 줄여 갈 목록이다.**
지금은 error 위반을 모두 고쳐 `baseline.xml` 이 비어 있다. 파일은 새 위반을 받아들여야 할 때를 위해 남긴다.

| 언제 | 어떻게 |
| --- | --- |
| 기준에 든 위반을 고쳤다 | `baseline.xml` 에서 그 줄을 지운다. Checkstyle 은 쓰지 않는 기준 줄을 알리지 않으므로 고친 사람이 직접 지운다. 같은 커밋에 넣는다 |
| 새 위반이 생겼다 | 기준에 더하지 않고 고친다 |
| 꼭 받아들여야 한다 | 까닭을 커밋 메시지와 PR 본문에 적고 그 줄을 더한다 |
| 규칙을 새로 더했다 | 기준을 비운 뒤 `build.gradle.kts` 의 `isIgnoreFailures` 를 잠시 `true` 로 두고 `./gradlew checkstyleMain checkstyleTest` 를 돌린다. 보고서에서 severity 가 error 인 위반의 `(파일, 규칙)` 을 뽑아 줄을 만들고 `isIgnoreFailures` 를 `false` 로 되돌린다. 뽑는 스크립트는 저장소에 두지 않는다 |

줄의 모양이다. 경로 구분자는 `[\\/]` 로 쓰고, 파일 경로 순으로 둔다.

```xml
<suppress checks="(^|\.)NeedBraces(Check)?$" files="src[\\/]main[\\/]java[\\/]...[\\/]AgentService\.java$"/>
<suppress id="lombokLogger" files="src[\\/]main[\\/]java[\\/]...[\\/]ChatService\.java$"/>
```

- 내장 규칙은 `checks` 로 걸고 끝을 `(Check)?$` 로 고정한다. 고정하지 않으면 `ParameterName` 이 `LambdaParameterName` 까지 억제한다
- 정규식 규칙과 `id` 를 붙인 규칙(`RightCurly` 둘)은 `id` 로 건다. `checks` 로 걸면 같은 종류의 규칙이 모두 억제된다. `RightCurly` 를 `checks` 로 걸면 `rightCurlyAlone` 과 `rightCurlySame` 이 함께 꺼진다
- warning 규칙은 기준에 넣지 않는다

**기준은 `(파일, 규칙)` 단위라 한계가 있다.**
줄 번호로 두면 파일을 고칠 때마다 기준이 어긋나기 때문이다.
그 대신 기준에 든 파일에 같은 규칙의 위반이 새로 생겨도 잡지 못한다.
그 파일을 고칠 때는 그 규칙의 위반을 모두 고치고 기준 줄을 지우는 것을 원칙으로 한다.

## OpenRewrite 버전

플러그인은 7.39.0 에 둔다. 7.40.0 과 7.41.0 은 Maven Central 에 없는 `rewrite-bom` 8.91.0 을 가리켜 받지 못한다.
