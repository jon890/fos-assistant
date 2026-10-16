# rule-46

공개 저장소에는 환경 운영값과 비밀을 꺼내는 방법을 적지 않는다.

검사: [공개 정보](../../scripts/checks/check-public-safe.sh), [리뷰](../../.github/workflows/code-review-prompt.txt)

## 까닭

공개 이력에서 운영 환경과 비밀 접근 방법이 드러나지 않게 한다.

## 예외와 검토 범위

종류와 확인할 항목만 적는다.
결과 수치는 남길 수 있지만 얻는 명령은 옮기지 않는다.
비공개 운영 저장소가 실제 값을 소유한다.
검사 밖의 커밋 메시지와 앞 커밋도 push 전에 직접 본다.

## 이 저장소의 사례

[check-public-safe.sh](../../scripts/checks/check-public-safe.sh)에서 확인한다.
check-public-safe.sh가 push 전 공개 정보 검사의 진입점이다.
