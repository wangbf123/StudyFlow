-- Preserve already-reported usage when upgrading pre-harness runs.
UPDATE agent_run SET root_model_calls=model_calls,root_prompt_tokens=COALESCE(prompt_tokens,0),root_completion_tokens=COALESCE(completion_tokens,0),root_token_charge=COALESCE(prompt_tokens,0)+COALESCE(completion_tokens,0);
