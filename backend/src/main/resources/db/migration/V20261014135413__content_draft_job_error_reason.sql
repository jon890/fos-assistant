-- worker 가 알린 실패 사유의 고정 코드다. error_code 와 같은 모양만 담고 화면의 글은 담지 않는다.
ALTER TABLE content_draft_job ADD (
    error_reason VARCHAR(64) NULL
);
