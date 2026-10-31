ALTER TABLE agent_run
  ADD COLUMN parent_run_id BIGINT NULL,
  ADD COLUMN root_run_id BIGINT NULL,
  ADD COLUMN depth INT NOT NULL DEFAULT 0,
  ADD COLUMN agent_role VARCHAR(24) NOT NULL DEFAULT 'coordinator',
  ADD COLUMN root_model_calls INT NOT NULL DEFAULT 0,
  ADD COLUMN root_prompt_tokens BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN root_completion_tokens BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN root_token_charge BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN root_reserved_tokens BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN inflight_reserved_tokens BIGINT NOT NULL DEFAULT 0,
  ADD KEY idx_agent_parent(parent_run_id,id),
  ADD KEY idx_agent_root(root_run_id,status);
UPDATE agent_run SET root_run_id=id WHERE root_run_id IS NULL;

CREATE TABLE agent_memory (
 id BIGINT PRIMARY KEY,user_id BIGINT NOT NULL,knowledge_base_id BIGINT NOT NULL,
 memory_key VARCHAR(160) NOT NULL,kind VARCHAR(24) NOT NULL,title VARCHAR(160) NOT NULL,content TEXT NOT NULL,
 scope_json JSON NOT NULL,source_note_id BIGINT NULL,source_revision INT NULL,origin_run_id BIGINT NULL,
 revision INT NOT NULL DEFAULT 1,status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',expires_at DATETIME(6) NULL,
 create_time DATETIME(6) NOT NULL,update_time DATETIME(6) NOT NULL,
 UNIQUE KEY uk_memory_key(user_id,knowledge_base_id,memory_key),
 KEY idx_memory_retrieval(user_id,knowledge_base_id,status,kind)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_tool_result (
 run_id BIGINT NOT NULL,step_no INT NOT NULL,tool_name VARCHAR(100) NOT NULL,
 content MEDIUMTEXT NOT NULL,content_hash CHAR(64) NOT NULL,token_count INT NOT NULL,create_time DATETIME(6) NOT NULL,
 PRIMARY KEY(run_id,step_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_context_manifest (
 run_id BIGINT NOT NULL,invocation_no INT NOT NULL,purpose VARCHAR(32) NOT NULL,
 manifest_json JSON NOT NULL,create_time DATETIME(6) NOT NULL,
 PRIMARY KEY(run_id,invocation_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_model_invocation (
 id BIGINT PRIMARY KEY,run_id BIGINT NOT NULL,purpose VARCHAR(32) NOT NULL,
 route_name VARCHAR(64) NOT NULL,model_name VARCHAR(120) NOT NULL,status VARCHAR(24) NOT NULL,
 latency_ms BIGINT NOT NULL,prompt_tokens INT NULL,completion_tokens INT NULL,trace_id VARCHAR(64) NULL,
 create_time DATETIME(6) NOT NULL,KEY idx_invocation_run(run_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_connector_binding (
 user_id BIGINT NOT NULL,connector_key VARCHAR(48) NOT NULL,scope_json JSON NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,revision INT NOT NULL DEFAULT 1,update_time DATETIME(6) NOT NULL,
 PRIMARY KEY(user_id,connector_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_plan_step (
 run_id BIGINT NOT NULL,step_no INT NOT NULL,title VARCHAR(160) NOT NULL,
 dependencies_json JSON NOT NULL,status VARCHAR(24) NOT NULL,child_run_id BIGINT NULL,
 PRIMARY KEY(run_id,step_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
