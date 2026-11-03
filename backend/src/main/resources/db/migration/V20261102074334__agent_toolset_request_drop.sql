-- 도구 사용 요청 기능과 agent_toolset_request 를 지운다(ADR-20261102 / table-reduce-r2 D5).
-- 운영 행이 0 이라 옮길 데이터가 없다. 읽고 쓰는 코드와 화면, API 를 같은 PR 에서 지웠다.
-- 되돌리기 어려운 삭제라 이 파일 하나로 둔다.
DROP TABLE agent_toolset_request;
