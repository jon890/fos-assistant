# rule-64

새 Hermes 내부 의존은 같은 변경에 계약 테스트로 선언한다.

검사: [Hermes 계약](../../hermes/tests/hermes_contract.py), [리뷰](../../.github/workflows/code-review-prompt.txt)

## 까닭

Hermes를 올릴 때 기대던 내부 지점의 변경을 자동으로 발견한다.

## 예외와 검토 범위

새 의존은 같은 커밋에 선언한다.
fake Hermes도 실제 응답 칸과 모양을 따른다.
실제 동작 계약은 hermes-contract가 소유한다.

## 이 저장소의 사례

[hermes_contract.py](../../hermes/tests/hermes_contract.py)에서 확인한다.
hermes_contract.py는 plugin이 기대는 Hermes 내부 계약을 확인한다.
