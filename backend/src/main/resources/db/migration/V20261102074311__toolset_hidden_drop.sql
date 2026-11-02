-- 숨긴 toolset 을 group_setting 의 TOOLSET 줄로 옮기고 남은 toolset_hidden 을 지운다(ADR-20261102 / table-reduce-r2).
-- 읽고 쓰는 코드는 이미 지웠고 직전 이미지가 쓴 줄은 앞 단계(ToolsetHiddenRemigrate)가 다시 옮겼다. 되돌리기 어려운 삭제라 이 파일 하나로 둔다.
DROP TABLE toolset_hidden;
