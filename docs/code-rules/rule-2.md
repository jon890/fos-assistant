# rule-2

최상위 기능 사이에 순환을 만들지 않는다.

검사: [TOP_LEVEL_PACKAGES_FREE_OF_CYCLES](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

기능 사이 순환이 생기면 한 기능을 바꿀 때 다른 기능까지 함께 알아야 한다.

## 예외와 검토 범위

shared는 그래프에서 빼고 rule-4로 따로 검사한다.
간선 집계 방식은 TopLevelPackageCycles가 갖는다.

## 이 저장소의 사례

[TopLevelPackageCycles.java](../../backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageCycles.java)에서 확인한다.
TopLevelPackageCycles는 최상위 패키지의 간선을 집계해 순환 여부를 검사한다.
