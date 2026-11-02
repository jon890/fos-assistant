-- 요청 UUID 를 관찰 줄에 합치고 남은 media_observation_request 를 지운다(ADR-20261102 / media-observation-request-merge).
-- 읽고 쓰는 코드는 이미 지웠고 요청 UUID 와 hash 는 media_observation 으로 옮겼다. 되돌리기 어려운 삭제라 이 파일 하나로 둔다.
DROP TABLE media_observation_request;
