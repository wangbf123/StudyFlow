ALTER TABLE knowledge_base
  ADD COLUMN parent_id BIGINT NULL,
  ADD COLUMN template_key VARCHAR(40) NOT NULL DEFAULT 'blank',
  ADD COLUMN color VARCHAR(16) NOT NULL DEFAULT '#a78bfa',
  ADD COLUMN settings_json JSON NULL,
  ADD KEY idx_kb_parent(user_id,parent_id,status);

CREATE TABLE knowledge_note (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  knowledge_base_id BIGINT NOT NULL,
  title VARCHAR(160) NOT NULL,
  title_key VARCHAR(160) NOT NULL,
  body MEDIUMTEXT NOT NULL,
  kind VARCHAR(32) NOT NULL DEFAULT 'NOTE',
  tags_json JSON NOT NULL,
  properties_json JSON NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  revision INT NOT NULL DEFAULT 1,
  graph_revision BIGINT NOT NULL DEFAULT 1,
  origin_run_id BIGINT NULL,
  create_time DATETIME(6) NOT NULL,
  update_time DATETIME(6) NOT NULL,
  active_title VARCHAR(160) GENERATED ALWAYS AS (IF(status='ACTIVE',title_key,NULL)) STORED,
  UNIQUE KEY uk_note_title(user_id,knowledge_base_id,active_title),
  KEY idx_note_kb(user_id,knowledge_base_id,status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE knowledge_note_revision (
  note_id BIGINT NOT NULL,
  revision INT NOT NULL,
  snapshot_json MEDIUMTEXT NOT NULL,
  create_time DATETIME(6) NOT NULL,
  PRIMARY KEY(note_id,revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE knowledge_note_link (
  source_id BIGINT NOT NULL,
  ordinal INT NOT NULL,
  target_id BIGINT NULL,
  target_text VARCHAR(240) NOT NULL,
  label VARCHAR(240) NOT NULL,
  anchor VARCHAR(160) NOT NULL DEFAULT '',
  source_revision INT NOT NULL,
  PRIMARY KEY(source_id,ordinal),
  KEY idx_link_target(target_id,source_id),
  KEY idx_link_unresolved(target_id,target_text)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE knowledge_graph_outbox (
  id BIGINT PRIMARY KEY,
  note_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  claim_token VARCHAR(36) NULL,
  lease_until DATETIME(6) NULL,
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(6) NOT NULL,
  create_time DATETIME(6) NOT NULL,
  KEY idx_graph_outbox(status,next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
