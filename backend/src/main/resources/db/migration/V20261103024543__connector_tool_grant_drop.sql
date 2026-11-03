-- 상시 허락의 옛 표 connector_tool_grant 를 지운다(ADR-20261102 / table-reduce-r2).
-- 허락은 connector_action 의 grant_* 칸이 갖고, 직전 이미지가 이 표에 쓴 줄은 V20261103024542 가 다시 옮겼다.
-- 되돌리기 어려운 삭제라 표마다 파일을 나눈다.
DROP TABLE connector_tool_grant;
