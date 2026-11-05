-- 먼저 살펴보기의 발견·문제 후보 표 둘을 지운다(docs/features/proactive/design.md, ADR-20261101 / proactive-suggestion).
-- 읽고 쓰는 코드와 옛 줄을 제안 줄로 옮기던 기동 이관을 모두 지웠다. 마지막 이관은 PR1 배포 기동에서 실패 0건으로 끝났다.
-- 되돌리기 어려운 삭제라 다른 변경과 섞지 않고 이 파일 하나로 둔다. 두 표는 서로를 가리키지 않아 순서를 가리지 않는다.
DROP TABLE proactive_check_finding;
DROP TABLE proactive_check_problem;
