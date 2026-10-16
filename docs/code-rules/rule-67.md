# rule-67

운영값은 설치 묶음과 환경에서 받고 코드에 고정하지 않는다.

검사: [공개 정보](../../scripts/checks/check-public-safe.sh), [리뷰](../../.github/workflows/code-review-prompt.txt)

## 까닭

누구나 설치할 수 있는 묶음과 우리 환경의 실제 값을 분리한다.

## 예외와 검토 범위

묶음을 만들 때와 프로세스 환경에서 값을 받는다.
공개할 수 없는 내용의 목록은 rule-46과 루트 지침을 따른다.

## 이 저장소의 사례

[README.md](../../hermes/README.md)에서 확인한다.
Hermes README는 묶음과 프로세스 환경이 받는 운영 값의 입력 경계를 설명한다.
