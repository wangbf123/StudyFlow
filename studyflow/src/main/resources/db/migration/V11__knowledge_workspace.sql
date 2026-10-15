ALTER TABLE document
  ADD COLUMN summary_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  ADD COLUMN summary_input_chars INT DEFAULT NULL,
  ADD COLUMN summary_truncated BOOLEAN NOT NULL DEFAULT FALSE,
  ADD KEY idx_document_workspace (knowledge_base_id, user_id, parse_status, create_time, id);
UPDATE document SET summary_status=CASE
  WHEN parse_status='DELETED' THEN 'CANCELLED'
  WHEN summary IS NOT NULL AND summary<>'' THEN 'SUCCESS'
  ELSE 'NOT_REQUESTED' END;
ALTER TABLE parse_task ADD KEY idx_task_workspace (user_id, document_id, id);
