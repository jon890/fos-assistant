# rule-35

의존은 명시 import로 보이고 wildcard·불필요 import를 두지 않는다.

검사: [UnusedImports](../../backend/config/checkstyle/checkstyle.xml), [AvoidStarImport](../../backend/config/checkstyle/checkstyle.xml), [RedundantImport](../../backend/config/checkstyle/checkstyle.xml), [IllegalImport](../../backend/config/checkstyle/checkstyle.xml), [fullyQualifiedName](../../backend/config/checkstyle/checkstyle.xml)

## 까닭

실제 의존을 import 줄에서 바로 확인할 수 있게 한다.

## 예외와 검토 범위

같은 단순 이름의 타입을 함께 쓰는 줄에만 전체 이름 허용 주석과 까닭을 단다.
import와 package 선언 및 문자열은 전체 이름 검사에서 뺀다.

## 이 저장소의 사례

[ArchitectureRules.java](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)에서 확인한다.
ArchitectureRules는 이름이 같은 Spring과 Jakarta Transactional을 함께 써 전체 이름 허용 주석을 단다.
