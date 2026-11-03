-- 결과 전달의 마지막 시도를 result_delivery.last_attempt_* 칸으로 옮기고 남은 result_delivery_attempt 를 지운다(ADR-20261102 / table-reduce-r2).
-- 읽고 쓰는 코드는 이미 지웠고 직전 이미지가 쓴 줄은 V20261102074328 이 다시 옮겼다. 앞의 시도는 옮기지 않았다. 되돌리기 어려운 삭제라 이 파일 하나로 둔다.
DROP TABLE result_delivery_attempt;
