# rule-36

로거는 Slf4j로 선언한다.

검사: [lombokLogger](../../backend/config/checkstyle/checkstyle.xml)

## 까닭

로거 선언을 반복하지 않고 같은 선언 방식으로 찾게 한다.

## 예외와 검토 범위

테스트가 로그를 붙잡으려고 LoggerFactory에서 cast로 조회하는 것은 선언과 달라 허용한다.

## 이 저장소의 사례

[checkstyle.xml](../../backend/config/checkstyle/checkstyle.xml)에서 확인한다.
lombokLogger 정규식은 로거 선언과 테스트의 cast 조회를 구별한다.
