# rule-4

shared는 다른 기능에 의존하지 않는다.

검사: [SHARED_DOES_NOT_DEPEND_ON_DOMAINS](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

여러 기능이 쓰는 기반 코드가 특정 기능을 끌어들이지 않게 한다.

## 예외와 검토 범위

기준 파일은 예외 허용 목록이 아니다.
처리 방법은 rule-45를 따른다.

## 이 저장소의 사례

[Sha256.java](../../backend/src/main/java/com/bifos/assistant/shared/util/Sha256.java)에서 확인한다.
Sha256은 shared에서 기능 의존 없이 재사용하는 해시 구현이다.
