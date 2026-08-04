SET @embedding_exists = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'document_chunk'
    AND COLUMN_NAME = 'embedding'
);
SET @embedding_sql = IF(
  @embedding_exists = 0,
  'ALTER TABLE document_chunk ADD COLUMN embedding MEDIUMTEXT DEFAULT NULL',
  'SELECT 1'
);
PREPARE embedding_stmt FROM @embedding_sql;
EXECUTE embedding_stmt;
DEALLOCATE PREPARE embedding_stmt;

SET @embedding_model_exists = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'document_chunk'
    AND COLUMN_NAME = 'embedding_model'
);
SET @embedding_model_sql = IF(
  @embedding_model_exists = 0,
  'ALTER TABLE document_chunk ADD COLUMN embedding_model VARCHAR(120) DEFAULT NULL',
  'SELECT 1'
);
PREPARE embedding_model_stmt FROM @embedding_model_sql;
EXECUTE embedding_model_stmt;
DEALLOCATE PREPARE embedding_model_stmt;

SET @embedding_dim_exists = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'document_chunk'
    AND COLUMN_NAME = 'embedding_dim'
);
SET @embedding_dim_sql = IF(
  @embedding_dim_exists = 0,
  'ALTER TABLE document_chunk ADD COLUMN embedding_dim INT DEFAULT NULL',
  'SELECT 1'
);
PREPARE embedding_dim_stmt FROM @embedding_dim_sql;
EXECUTE embedding_dim_stmt;
DEALLOCATE PREPARE embedding_dim_stmt;
