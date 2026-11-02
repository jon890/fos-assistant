-- 직전 이미지가 쓴 관찰 줄의 요청 UUID 와 hash 를 옛 테이블에서 다시 옮긴다(ADR-20261102 / media-observation-request-merge).
-- 칸을 NOT NULL 로 바꾸고 media_observation_request 를 지우기 전 단계다. 이미 채운 줄은 건드리지 않는다.
-- 관찰마다 가장 먼저 저장한 요청의 UUID 와 hash 를 옮기는 규칙은 V20261102053007 과 같다.
UPDATE media_observation
   SET request_id = (SELECT r.request_id FROM media_observation_request r
                      WHERE r.id = (SELECT MIN(r2.id) FROM media_observation_request r2
                                     WHERE r2.attachment_id = media_observation.attachment_id
                                       AND r2.observation_id = media_observation.id)),
       request_hash = (SELECT r.request_hash FROM media_observation_request r
                        WHERE r.id = (SELECT MIN(r2.id) FROM media_observation_request r2
                                       WHERE r2.attachment_id = media_observation.attachment_id
                                       AND r2.observation_id = media_observation.id))
 WHERE request_id IS NULL;
