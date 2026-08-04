CREATE TABLE IF NOT EXISTS upload_session (
  id BIGINT PRIMARY KEY,
  upload_id VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  knowledge_base_id BIGINT NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  file_type VARCHAR(32) NOT NULL,
  file_size BIGINT NOT NULL,
  file_md5 VARCHAR(64) DEFAULT NULL,
  chunk_size BIGINT NOT NULL,
  total_parts INT NOT NULL,
  status VARCHAR(32) NOT NULL,
  final_bucket VARCHAR(120) DEFAULT NULL,
  final_object_key VARCHAR(500) DEFAULT NULL,
  document_id BIGINT DEFAULT NULL,
  task_id BIGINT DEFAULT NULL,
  error_message VARCHAR(1000) DEFAULT NULL,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  UNIQUE KEY uk_upload_session_upload_id (upload_id),
  KEY idx_upload_session_user_kb (user_id, knowledge_base_id, create_time),
  KEY idx_upload_session_resume (user_id, knowledge_base_id, file_name, file_size, status),
  KEY idx_upload_session_md5 (file_md5, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS upload_part (
  id BIGINT PRIMARY KEY,
  upload_id VARCHAR(64) NOT NULL,
  part_number INT NOT NULL,
  part_size BIGINT NOT NULL,
  part_md5 VARCHAR(64) DEFAULT NULL,
  bucket VARCHAR(120) NOT NULL,
  object_key VARCHAR(500) NOT NULL,
  status VARCHAR(32) NOT NULL,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  UNIQUE KEY uk_upload_part (upload_id, part_number),
  KEY idx_upload_part_upload (upload_id, part_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
