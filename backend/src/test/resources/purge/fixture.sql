-- 삭제 전 모든 테이블에 한 행씩 넣는다. 900000001은 검사마다 고유 번호로 치환한다.
-- nullable FK는 참조 대상 삽입 뒤 연결한다. FK 검사와 CHECK는 끄지 않는다.

INSERT INTO agent (id, code, name, hermes_profile, api_base_url, cost_mode, credential_scope, visibility, owner_user_id, enabled, created_at, profile_managed, connector_managed, connector_attachments, proactive_check_writes_allowed)
VALUES (900000001, 'coverage-900000001', 'coverage-900000001', 'coverage-900000001', 'http://agent-runtime.test', 'SUBSCRIPTION', 'SHARED_HOUSEHOLD', 'PRIVATE', 900000001, 1, '2020-01-01 00:00:00', 0, 0, 0, 1);

INSERT INTO agent_execution (id, user_id, conversation_id, agent_id, profile_name, cost_mode, status, input_tokens, output_tokens, estimated_cost_micros, started_at, output_text, event_observation)
VALUES (900000001, 900000001, 900000001, 900000001, 'coverage-900000001', 'SUBSCRIPTION', 'SUCCEEDED', 17, 23, 123, '2020-01-01 00:00:00', '지워야 할 실행 본문', 'coverage-900000001');

INSERT INTO agent_token (id, token_hash, label, created_at, profile_name)
VALUES (900000001, 'coverage-900000001', 'coverage-900000001', '2020-01-01 00:00:00', 'coverage-900000001');

INSERT INTO allowed_person (id, email, display_name, hermes_profile, enabled, created_at)
VALUES (900000001, 'coverage-900000001@example.test', 'coverage-900000001', 'coverage-900000001', 1, '2020-01-01 00:00:00');

INSERT INTO app_user (id, email, display_name, group_id, role, created_at, limit_order_amount_krw, limit_updated_at)
VALUES (900000001, 'coverage-900000001@example.test', 'coverage-900000001', 900000001, 'MEMBER', '2020-01-01 00:00:00', 1, '2020-01-01 00:00:00');

INSERT INTO chat_artifact (id, conversation_id, message_id, path, byte_size, created_at)
VALUES (900000001, 900000001, 900000001, 'coverage-900000001', 1, '2020-01-01 00:00:00');

INSERT INTO chat_attachment (id, conversation_id, message_id, uploaded_by_user_id, original_name, stored_name, content_type, byte_size, expires_at, created_at, position)
VALUES (900000001, 900000001, 900000001, 900000001, 'coverage-900000001', '900000001.png', 'image/png', 1, '2099-01-01 00:00:00', '2020-01-01 00:00:00', 1);

INSERT INTO chat_message (id, conversation_id, role, content, execution_id, created_at)
VALUES (900000001, 900000001, 'USER', 'coverage-900000001', 900000001, '2020-01-01 00:00:00');

INSERT INTO chat_pending_message (id, conversation_id, user_id, content, held, created_at)
VALUES (900000001, 900000001, 900000001, 'coverage-900000001', 1, '2020-01-01 00:00:00');

INSERT INTO content_draft_creation_request (id, creation_key, identity_key, owner_user_id, conversation_id, profile_name, origin_execution_id, root_execution_id, root_session_id, request_id, request_hash, draft_public_id, status, created_at)
VALUES (900000001, UNHEX(LPAD(HEX(900000001), 64, '0')), UNHEX(LPAD(HEX(900000001), 64, '0')), 900000001, 900000001, 'coverage-900000001', 900000001, 900000001, 'coverage-900000001', 'coverage-900000001', UNHEX(REPEAT('01', 32)), '00000000-0000-4000-8000-000900000001', 'CREATED', '2020-01-01 00:00:00');

INSERT INTO content_draft_job (id, snapshot_id, action_id, owner_user_id, conversation_id, status, created_at, save_clicked)
VALUES (900000001, 900000001, 900000001, 900000001, 900000001, 'PREVIEW_READY', '2020-01-01 00:00:00', 1);

INSERT INTO content_draft_snapshot (id, public_id, revision_id, owner_user_id, conversation_id, origin_execution_id, agent_id, connection_id, binding_id, connection_updated_at, binding_updated_at, policy_fingerprint, schema_version, compiler_version, payload_fingerprint, snapshot_hash, authorization_fingerprint, created_at, expires_at)
VALUES (900000001, UNHEX(LPAD(HEX(900000001), 32, '0')), 900000001, 900000001, 900000001, 900000001, 900000001, 900000001, 900000001, '2020-01-01 00:00:00', '2020-01-01 00:00:00', 'coverage-900000001', 1, 'coverage-900000001', 'coverage-900000001', 'coverage-900000001', 'coverage-900000001', '2020-01-01 00:00:00', '2099-01-01 00:00:00');

INSERT INTO conversation (id, user_id, agent_id, hermes_session_id, title, created_at, updated_at, public_id, hermes_root_session_id, auto_turn_count, purpose)
VALUES (900000001, 900000001, 900000001, 'coverage-900000001', 'coverage-900000001', '2020-01-01 00:00:00', '2020-01-01 00:00:00', UNHEX(LPAD(HEX(900000001), 32, '0')), 'coverage-root-900000001', 1, 'CHAT');

INSERT INTO execution_context_source (execution_id, position, source, source_ref, body_mode, freshness, created_at)
VALUES (900000001, 1, 'coverage-900000001', 'coverage-900000001', 'test', 'test', '2020-01-01 00:00:00');

INSERT INTO execution_event (id, execution_id, sequence, event_type, detail, occurred_at)
VALUES (900000001, 900000001, 1, 'TOOL_STARTED', '지워야 할 사건 본문', '2020-01-01 00:00:00');

INSERT INTO execution_question (execution_id, message_id, created_at)
VALUES (900000001, 900000001, '2020-01-01 00:00:00');

INSERT INTO execution_skill_use (id, execution_id, skill_name, source, occurred_at)
VALUES (900000001, 900000001, 'coverage-900000001', 'coverage-900000001', '2020-01-01 00:00:00');

INSERT INTO hermes_session_binding (id, profile_name, session_id, user_id, origin_execution_id, root_session_id, parent_session_id, created_at)
VALUES (900000001, 'coverage-900000001', 'coverage-900000001', 900000001, 900000001, 'coverage-900000001', 'coverage-900000001', '2020-01-01 00:00:00');

INSERT INTO memory (id, scope, owner_user_id, group_id, title, content, always_inject, status, proposed_by_execution_id, accepted_by_user_id, accepted_at, created_at, updated_at, collection, entry_type, retrieval, sensitivity, revision)
VALUES (900000001, 'USER', 900000001, 900000001, 'coverage-900000001', 'coverage-900000001', 1, 'ACCEPTED', 900000001, 900000001, '2020-01-01 00:00:00', '2020-01-01 00:00:00', '2020-01-01 00:00:00', 'coverage-900000001', 'MEMORY', 'ALWAYS', 'NORMAL', 1);

INSERT INTO memory_capture (id, memory_id, user_id, conversation_id, execution_id, kind, created_at)
VALUES (900000001, 900000001, 900000001, 900000001, 900000001, 'CREATED', '2020-01-01 00:00:00');

INSERT INTO memory_collection (group_id, collection_key, display_name, sort_order, created_at)
VALUES (900000001, 'coverage-900000001', 'coverage-900000001', 1, '2020-01-01 00:00:00');

INSERT INTO memory_revision (memory_id, revision, change_type, scope, owner_user_id, group_id, collection, entry_type, status, title, content, retrieval, sensitivity, changed_at)
VALUES (900000001, 1, 'UPDATED', 'USER', 900000001, 900000001, 'coverage-900000001', 'MEMORY', 'ACCEPTED', 'coverage-900000001', 'coverage-900000001', 'ALWAYS', 'NORMAL', '2020-01-01 00:00:00');

INSERT INTO model_hidden (id, group_id, provider, model)
VALUES (900000001, 900000001, 'coverage-900000001', 'coverage-900000001');

INSERT INTO model_tier_definition (id, group_id, tier)
VALUES (900000001, 900000001, 'FAST');

INSERT INTO model_tier_group_setting (group_id)
VALUES (900000001);

INSERT INTO result_delivery (id, conversation_id, status, attempt_count, created_at, updated_at)
VALUES (900000001, 900000001, 'DELIVERING', 1, '2020-01-01 00:00:00', '2020-01-01 00:00:00');

INSERT INTO subagent_usage_job (id, execution_id, child_session_id, profile_name, api_base_url, status, created_at, next_attempt_at, expires_at, attempts, backoff_attempts)
VALUES (900000001, 900000001, 'coverage-900000001', 'coverage-900000001', 'coverage-900000001', 'RECORDED', '2020-01-01 00:00:00', '2020-01-01 00:00:00', '2099-01-01 00:00:00', 1, 1);

INSERT INTO toolset_hidden (id, group_id, name)
VALUES (900000001, 900000001, 'coverage-900000001');

INSERT INTO user_data_key (id, user_id, kek_id, wrapped_key, created_at)
VALUES (900000001, 900000001, 'coverage-900000001', 'coverage-900000001', '2020-01-01 00:00:00');

INSERT INTO agent_memory_collection (agent_id, collection, allow_sensitive, created_at)
VALUES (900000001, 'coverage-900000001', 1, '2020-01-01 00:00:00');

INSERT INTO agent_memory_collection_change (id, agent_id, collection, change_type, allow_sensitive, changed_by_user_id, changed_at)
VALUES (900000001, 900000001, 'coverage-900000001', 'GRANTED', 1, 900000001, '2020-01-01 00:00:00');

INSERT INTO agent_toolset_request (id, public_id, group_id, agent_id, requester_user_id, toolset, status, requested_at)
VALUES (900000001, UNHEX(LPAD(HEX(900000001), 32, '0')), 900000001, 900000001, 900000001, 'coverage-900000001', 'REJECTED', '2020-01-01 00:00:00');

INSERT INTO attention_control (id, user_id, card_key, item_key, action, created_at, updated_at)
VALUES (900000001, 900000001, 'test', 'coverage-900000001', 'test', '2020-01-01 00:00:00', '2020-01-01 00:00:00');

INSERT INTO attention_event (id, user_id, item_key, state_key, trigger_type, attention, event_type, stale, created_at)
VALUES (900000001, 900000001, 'coverage-900000001', 'coverage-900000001', 'coverage-900000001', 'test', 'test', 1, '2020-01-01 00:00:00');

INSERT INTO connector_action (id, public_id, user_id, connector_id, hermes_tool, decision, passed, status, origin_execution_id, conversation_id, dedupe_key, args_json, args_sha256, result_text, created_at, check_tree, limit_kind, limit_requested, limit_previous)
VALUES (900000001, UNHEX(LPAD(HEX(900000001), 32, '0')), 900000001, 'coverage-900000001', 'coverage-900000001', 'ALLOWED', 1, 'EXECUTED', 900000001, 900000001, 'coverage-900000001', '{"private":"지울 인자"}', 'coverage-900000001', '지울 결과', '2020-01-01 00:00:00', 1, 'ORDER_AMOUNT_KRW', 1, 1);

INSERT INTO connector_connection (id, user_id, connector_id, status, fields, restart_required, desired_enabled, created_at, updated_at, undeclared_tools, vault_stored)
VALUES (900000001, 900000001, 'coverage-900000001', 'CONNECTED', 'coverage-900000001', 1, 1, '2020-01-01 00:00:00', '2020-01-01 00:00:00', 1, 1);

INSERT INTO connector_conversation_grant (id, user_id, conversation_id, agent_id, connector_id, tool_name, action_id, expires_at, created_at)
VALUES (900000001, 900000001, 900000001, 900000001, 'coverage-900000001', 'coverage-900000001', 900000001, '2099-01-01 00:00:00', '2020-01-01 00:00:00');

INSERT INTO connector_tool_grant (id, user_id, connector_id, tool_name, expires_at, created_at)
VALUES (900000001, 900000001, 'coverage-900000001', 'coverage-900000001', '2099-01-01 00:00:00', '2020-01-01 00:00:00');

INSERT INTO content_draft (id, public_id, conversation_id, owner_user_id, current_revision, created_at, expires_at)
VALUES (900000001, '00000000-0000-4000-8000-000900000001', 900000001, 900000001, 1, '2020-01-01 00:00:00', '2099-01-01 00:00:00');

INSERT INTO content_draft_creation_call (id, call_key, identity_key, creation_request_id, session_id, tool_call_id)
VALUES (900000001, UNHEX(LPAD(HEX(900000001), 64, '0')), UNHEX(LPAD(HEX(900000001), 64, '0')), 900000001, 'coverage-900000001', 'coverage-900000001');

INSERT INTO decision_feedback_event (id, user_id, subject_type, subject_key, event_type, actor, contract_version, occurred_at)
VALUES (900000001, 900000001, 'FOLLOW_UP', 'coverage-900000001', 'SURFACED', 'USER', 1, '2020-01-01 00:00:00');

INSERT INTO follow_up (id, public_id, user_id, conversation_id, proposed_by_execution_id, title, title_key, waiting, status, created_at, updated_at)
VALUES (900000001, UNHEX(LPAD(HEX(900000001), 32, '0')), 900000001, 900000001, 900000001, 'coverage-900000001', 'coverage-900000001', 1, 'coverage-900000001', '2020-01-01 00:00:00', '2020-01-01 00:00:00');

INSERT INTO media_observation (id, attachment_id, conversation_id, owner_user_id, revision, source_fingerprint, status, provenance_kind, schema_version, prompt_version, origin_execution_id, body, body_key_id, created_at, expires_at)
VALUES (900000001, 900000001, 900000001, 900000001, 1, 'coverage-900000001', 'VALID', 'coverage-900000001', 1, 'coverage-900000001', 900000001, '암호화 관찰 대역', 900000001, '2020-01-01 00:00:00', '2099-01-01 00:00:00');

INSERT INTO notification (id, public_id, user_id, kind, title, body, created_at)
VALUES (900000001, UNHEX(LPAD(HEX(900000001), 32, '0')), 900000001, 'coverage-900000001', 'coverage-900000001', 'coverage-900000001', '2020-01-01 00:00:00');

INSERT INTO proactive_check (id, user_id, agent_id, conversation_id, trigger_type, status, tool_calls, delegations, new_findings, reference_findings, started_at, writes_allowed, report_json)
VALUES (900000001, 900000001, 900000001, 900000001, 'MANUAL', 'SUCCEEDED', 1, 1, 1, 1, '2020-01-01 00:00:00', 1, '{"private":"지울 보고"}');

INSERT INTO proactive_loop_setting (id, user_id, agent_id, enabled, updated_at)
VALUES (900000001, 900000001, 900000001, 1, '2020-01-01 00:00:00');

INSERT INTO result_delivery_attempt (id, delivery_id, attempt_no, status, execution_id, started_at)
VALUES (900000001, 900000001, 1, 'coverage-900000001', 900000001, '2020-01-01 00:00:00');

INSERT INTO result_delivery_item (id, delivery_id, source, result_key)
VALUES (900000001, 900000001, 'coverage-900000001', 'coverage-900000001');

INSERT INTO service_token (id, user_id, token_hash, label, created_at, expires_at)
VALUES (900000001, 900000001, 'coverage-900000001', 'coverage-900000001', '2020-01-01 00:00:00', '2099-01-01 00:00:00');

INSERT INTO task (id, public_id, owner_user_id, agent_id, title, state, conversation_mode, conversation_id, notify, created_at, updated_at, kind)
VALUES (900000001, UNHEX(LPAD(HEX(900000001), 32, '0')), 900000001, 900000001, 'coverage-900000001', 'ACTIVE', 'NEW_PER_RUN', 900000001, 'ALWAYS', '2020-01-01 00:00:00', '2020-01-01 00:00:00', 'TURN');

INSERT INTO user_autonomy_preference (user_id, read_only_execution, updated_at)
VALUES (900000001, 1, '2020-01-01 00:00:00');

INSERT INTO user_browser (id, user_id, status, profile_key, created_at, updated_at, version)
VALUES (900000001, 900000001, 'STOPPED', 'coverage-900000001', '2020-01-01 00:00:00', '2020-01-01 00:00:00', 1);

INSERT INTO agent_connector_binding (id, agent_id, connection_id, status, restart_required, desired_enabled, created_at, updated_at)
VALUES (900000001, 900000001, 900000001, 'coverage-900000001', 1, 1, '2020-01-01 00:00:00', '2020-01-01 00:00:00');

INSERT INTO connector_action_execution (action_id, connection_id, binding_id, connection_updated_at, binding_updated_at, execution_args_json, summary_json, scope_json, execution_args_sha256, scope_sha256, request_key, protocol, created_at)
VALUES (900000001, 900000001, 900000001, '2020-01-01 00:00:00', '2020-01-01 00:00:00', 'coverage-900000001', 'coverage-900000001', 'coverage-900000001', 'coverage-900000001', 'coverage-900000001', 'coverage-900000001', 'coverage-900000001', '2020-01-01 00:00:00');

INSERT INTO content_draft_revision (id, draft_id, revision, status, schema_version, created_at, body, body_key_id)
VALUES (900000001, 900000001, 1, 'VALID', 1, '2020-01-01 00:00:00', '암호화 본문 대역', 900000001);

INSERT INTO media_observation_request (id, attachment_id, request_id, request_hash, observation_id)
VALUES (900000001, 900000001, 'coverage-900000001', 'coverage-900000001', 900000001);

INSERT INTO proactive_check_finding (id, check_id, conversation_id, kind, area, title, created_at)
VALUES (900000001, 900000001, 900000001, 'NEW', 'coverage-900000001', 'coverage-900000001', '2020-01-01 00:00:00');

INSERT INTO proactive_check_problem (id, check_id, conversation_id, status, problem_key, problem, evidence_json, created_at)
VALUES (900000001, 900000001, 900000001, 'ACCEPTED', 'coverage-900000001', '지울 문제 본문', '{"private":"지울 근거"}', '2020-01-01 00:00:00');

INSERT INTO proactive_loop_run (id, user_id, source_check_id, status, created_at)
VALUES (900000001, 900000001, 900000001, 'test', '2020-01-01 00:00:00');

INSERT INTO proactive_value_evaluation (id, check_id, user_id, outcome, evidence_json, created_at)
VALUES (900000001, 900000001, 900000001, 'RUNNING', '{"private":"지울 평가 근거"}', '2020-01-01 00:00:00');

INSERT INTO service_token_collection (token_id, collection, allow_sensitive)
VALUES (900000001, 'coverage-900000001', 1);

INSERT INTO task_trigger (id, task_id, type, time_zone, missed_policy, created_at, updated_at)
VALUES (900000001, 900000001, 'coverage-900000001', 'coverage-900000001', 'coverage-900000001', '2020-01-01 00:00:00', '2020-01-01 00:00:00');

INSERT INTO content_draft_asset (id, revision_id, position, attachment_id, source_fingerprint)
VALUES (900000001, 900000001, 1, 900000001, 'coverage-900000001');

INSERT INTO proactive_autonomy_decision (id, user_id, evaluation_id, candidate_id, source_check_id, action_level, reasons_json, inputs_json, policy_version, created_at)
VALUES (900000001, 900000001, 900000001, 900000001, 900000001, 'test', '{}', '{}', 1, '2020-01-01 00:00:00');

INSERT INTO task_run (id, public_id, task_id, trigger_id, owner_user_id, scheduled_for, status, conversation_id, execution_id, created_at)
VALUES (900000001, UNHEX(LPAD(HEX(900000001), 32, '0')), 900000001, 900000001, 900000001, '2020-01-01 00:00:00', 'coverage-900000001', 900000001, 900000001, '2020-01-01 00:00:00');

UPDATE connector_action SET agent_id = 900000001 WHERE id = 900000001;
UPDATE connector_connection SET agent_id = 900000001 WHERE id = 900000001;
UPDATE decision_feedback_event SET conversation_id = 900000001 WHERE id = 900000001;
UPDATE decision_feedback_event SET origin_execution_id = 900000001 WHERE id = 900000001;
UPDATE decision_feedback_event SET source_check_id = 900000001 WHERE id = 900000001;
UPDATE decision_feedback_event SET autonomy_decision_id = 900000001 WHERE id = 900000001;
UPDATE proactive_check SET root_execution_id = 900000001 WHERE id = 900000001;
UPDATE proactive_loop_run SET evaluation_id = 900000001 WHERE id = 900000001;
