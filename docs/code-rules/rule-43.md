# rule-43

포맷은 고정된 formatter와 바뀐 파일 범위로 적용한다.

검사: [spotlessCheck](../../backend/build.gradle.kts), [format:check](../../web/package.json), [포맷 회귀](../../test/spotless-jdt.test.py)

## 까닭

같은 formatter 버전과 설정으로 변경 파일의 모양을 재현한다.

## 예외와 검토 범위

처음 고치는 파일은 전체가 포맷된다.
기능 변경과 포맷을 다른 커밋으로 나눈다.
전체 적용은 명시적인 Gradle 옵션으로만 한다.

## 이 저장소의 사례

[eclipse-formatter.xml](../../backend/config/spotless/eclipse-formatter.xml)에서 확인한다.
eclipse-formatter.xml이 Spotless에서 실행할 JDT 포맷 설정을 소유한다.

책임과 실행 절차는 [code-architecture.md](../../backend/docs/code-architecture.md)에서 이어 읽는다.
