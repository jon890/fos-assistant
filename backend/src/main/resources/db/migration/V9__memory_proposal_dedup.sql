ALTER TABLE memory ADD COLUMN proposal_dedup_key VARCHAR(64) NULL;

CREATE UNIQUE INDEX uk_memory_proposal_dedup ON memory (proposal_dedup_key);
