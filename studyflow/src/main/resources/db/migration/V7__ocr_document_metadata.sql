SET @column_exists = (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'document' AND COLUMN_NAME = 'parser_type'
);
SET @ddl = IF(@column_exists = 0,
  'ALTER TABLE document ADD COLUMN parser_type VARCHAR(32) DEFAULT NULL', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'document' AND COLUMN_NAME = 'page_count'
);
SET @ddl = IF(@column_exists = 0,
  'ALTER TABLE document ADD COLUMN page_count INT DEFAULT NULL', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'document' AND COLUMN_NAME = 'ocr_page_count'
);
SET @ddl = IF(@column_exists = 0,
  'ALTER TABLE document ADD COLUMN ocr_page_count INT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'document' AND COLUMN_NAME = 'ocr_elapsed_ms'
);
SET @ddl = IF(@column_exists = 0,
  'ALTER TABLE document ADD COLUMN ocr_elapsed_ms BIGINT DEFAULT NULL', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'document_chunk' AND COLUMN_NAME = 'page_number'
);
SET @ddl = IF(@column_exists = 0,
  'ALTER TABLE document_chunk ADD COLUMN page_number INT DEFAULT NULL', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'document_chunk' AND COLUMN_NAME = 'parser_type'
);
SET @ddl = IF(@column_exists = 0,
  'ALTER TABLE document_chunk ADD COLUMN parser_type VARCHAR(32) DEFAULT NULL', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'document_chunk' AND COLUMN_NAME = 'ocr_confidence'
);
SET @ddl = IF(@column_exists = 0,
  'ALTER TABLE document_chunk ADD COLUMN ocr_confidence DOUBLE DEFAULT NULL', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qa_citation' AND COLUMN_NAME = 'page_number'
);
SET @ddl = IF(@column_exists = 0,
  'ALTER TABLE qa_citation ADD COLUMN page_number INT DEFAULT NULL', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qa_citation' AND COLUMN_NAME = 'parser_type'
);
SET @ddl = IF(@column_exists = 0,
  'ALTER TABLE qa_citation ADD COLUMN parser_type VARCHAR(32) DEFAULT NULL', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
