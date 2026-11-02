-- 원고 저장 job 을 content_draft_snapshot 의 칸으로 옮기고 남은 content_draft_job 을 지운다(ADR-20261102 / table-reduce-r2 의 원고 D1).
-- 읽고 쓰는 코드는 이미 지웠고 직전 이미지가 쓴 줄은 V20261102074320 이 다시 옮겼다. 되돌리기 어려운 삭제라 이 파일 하나로 둔다.
DROP TABLE content_draft_job;
