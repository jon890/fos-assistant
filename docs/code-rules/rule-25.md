# rule-25

서비스·infra의 중첩 구현 타입은 private으로 둔다.

검사: [SERVICES_DO_NOT_EXPOSE_NESTED_TYPES](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

서비스가 돌려주는 값을 쓰려고 서비스 자체를 import하지 않게 한다.

## 예외와 검토 범위

application과 domain의 외부 공개 타입은 파일로 분리한다.
ModelPrice.ContextTier처럼 바깥 타입 밖에서 쓰지 않는 값은 안에 둘 수 있다.
서비스·infra의 구현 값은 private으로 두며 익명·지역 클래스는 자동 검사 대상이 아니다.

## 이 저장소의 사례

[ModelPrice.java](../../backend/src/main/java/com/bifos/assistant/usage/pricing/domain/ModelPrice.java)에서 확인한다.
ModelPrice.ContextTier는 바깥 값의 일부로 두는 중첩 값 사례다.
