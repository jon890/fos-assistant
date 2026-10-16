# rule-65

plugin은 서비스 이름을 모르고 connector만 서비스별 동작을 갖는다.

검사: [서비스 중립](../../test/unit/connector-neutral.test.ts)

## 까닭

범용 plugin이 특정 서비스의 이름과 동작에 묶이지 않게 한다.

## 예외와 검토 범위

서비스별 이름은 connectors와 그 검사·문서에만 둔다.

## 이 저장소의 사례

[connector-neutral.test.ts](../../test/unit/connector-neutral.test.ts)에서 확인한다.
connector-neutral 테스트는 범용 plugin에 서비스 이름이 들어가지 않는지 확인한다.
