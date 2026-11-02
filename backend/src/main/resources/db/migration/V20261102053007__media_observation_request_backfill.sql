-- 관찰마다 그 관찰을 가리키는 요청 가운데 가장 먼저 저장한 줄의 UUID 와 hash 를 옮긴다.
-- 같은 관찰을 가리키는 나머지 요청은 완료 결과 재사용(ADR-20261011 / media-observation-cache)이 남긴 것이고
-- 그 재사용을 없애므로 옮기지 않는다(ADR-20261102 / media-observation-request-merge).
UPDATE media_observation
   SET request_id = (SELECT r.request_id FROM media_observation_request r
                      WHERE r.id = (SELECT MIN(r2.id) FROM media_observation_request r2
                                     WHERE r2.observation_id = media_observation.id)),
       request_hash = (SELECT r.request_hash FROM media_observation_request r
                        WHERE r.id = (SELECT MIN(r2.id) FROM media_observation_request r2
                                       WHERE r2.observation_id = media_observation.id))
 WHERE request_id IS NULL;
