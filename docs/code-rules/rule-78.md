# rule-78

운영 클래스의 단순 이름은 겹치지 않는다.

검사: [SIMPLE_CLASS_NAMES_ARE_UNIQUE](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

기준 파일은 위반 줄을 패키지 없이 단순 클래스 이름으로 견준다. 이름이 겹치면 새 위반이 기존 줄에 가려진다.

## 예외와 검토 범위

최상위 클래스만 본다. 중첩 타입의 이름은 겹쳐도 된다.
겹치면 한쪽의 이름을 바꾼다. 기준 파일은 이 규칙을 얼리지 않는다.

## 이 저장소의 사례

[ClassNameViolationLineMatcher.java](../../backend/src/test/java/com/bifos/assistant/architecture/ClassNameViolationLineMatcher.java)에서 확인한다.
ClassNameViolationLineMatcher가 이 유일성에 기대어 패키지를 지우고 줄을 견준다.

결정과 근거는 [ADR-20261015 / package-layout](../../backend/docs/adr/ADR-20261015-package-layout.md)가, 책임과 실행 절차는 [code-architecture.md](../../backend/docs/code-architecture.md)가 갖는다.
