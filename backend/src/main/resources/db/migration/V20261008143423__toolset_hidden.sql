CREATE TABLE toolset_hidden (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    group_id BIGINT NOT NULL,
    name VARCHAR(64) NOT NULL,
    CONSTRAINT uk_toolset_hidden_group_name UNIQUE (group_id, name)
) DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
