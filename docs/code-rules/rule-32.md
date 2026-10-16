# rule-32

빈 private utility 생성자는 Lombok으로 만든다.

검사: [privateEmptyConstructor](../../backend/config/checkstyle/checkstyle.xml)

## 까닭

인스턴스를 만들지 않는다는 의도를 생성자 본문 대신 선언으로 보인다.

## 예외와 검토 범위

인자가 있거나 초기화 동작이 있는 생성자는 빈 utility 생성자와 다르다.

## 이 저장소의 사례

[Sha256.java](../../backend/src/main/java/com/bifos/assistant/shared/util/Sha256.java)에서 확인한다.
Sha256은 NoArgsConstructor로 인스턴스 생성을 닫는 utility다.
