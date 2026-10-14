-- 승인을 기다리는 줄이 더는 없는 대화를 가리키는 「승인을 기다리는 요청」 알림을 읽음으로 바꾼다.
-- 배포 때 그 대화에 기다리는 줄이 남아 있던 알림은 원인 칸이 비어 있어 건너뛰며, 사용자가 눌러 읽을 때까지 남는다.
-- 이 변경 전에는 대화 화면에서 승인하거나 거절해도 알림이 읽지 않음으로 남았다. 이후에는 줄이 바뀔 때 서버가 읽음으로 바꾼다.
UPDATE notification
   SET read_at = CURRENT_TIMESTAMP(6)
 WHERE kind = 'APPROVAL_REQUESTED'
   AND read_at IS NULL
   AND NOT EXISTS (
       SELECT 1
         FROM connector_action a
         JOIN conversation c ON c.id = a.conversation_id
        WHERE c.public_id = notification.target_public_id
          AND a.user_id = notification.user_id
          AND a.status = 'PENDING');
