# rule-62

운영 코드에 테스트 전용 인증·상태 우회를 만들지 않는다.

검사: [리뷰](../../.github/workflows/code-review-prompt.txt)

## 까닭

테스트 전용 인증 우회가 운영에 남지 않게 한다.

## 예외와 검토 범위

테스트는 NextAuth 세션 쿠키를 직접 만든다.
테스트 환경일 때만 인증을 건너뛰는 운영 분기는 두지 않는다.

## 이 저장소의 사례

[README.md](../../test/browser/README.md)에서 확인한다.
browser README의 로그인 절차는 테스트가 세션을 준비하는 방식을 설명한다.
