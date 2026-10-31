"""Validate the twelve-module harness using an isolated demo user's private session.

First run verify_personal_workspace.py. Keep its session file outside the checkout.
No model keys are needed; Mock mode is reported as extractive, not model quality.
"""
import argparse
import json
import os
from pathlib import Path
import secrets
import time
from datetime import datetime, timezone
import requests

parser = argparse.ArgumentParser()
parser.add_argument('--session-file', required=True)
parser.add_argument('--api-url', default='http://127.0.0.1:8080')
parser.add_argument('--peer-url')
parser.add_argument('--report', required=True)
args = parser.parse_args()
fixture = json.loads(Path(args.session_file).read_text())
client = requests.Session()
client.trust_env = False
client.headers['Authorization'] = fixture['token']
base = args.api_url.rstrip('/')

def call(method, path, data=None, expected=0, host=None, **kwargs):
    response = client.request(method, (host or base) + path, json=data, timeout=30, **kwargs)
    response.raise_for_status()
    result = response.json()
    assert result.get('code') == expected, (path, result.get('message'))
    return result.get('data')

def wait_run(run_id, limit=240):
    for _ in range(limit):
        run = call('GET', f'/api/agent/runs/{run_id}')
        if run['status'] in {'SUCCESS', 'ABSTAINED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'BUDGET_EXCEEDED'}:
            return run
        time.sleep(.4)
    raise AssertionError('Run did not finish within verification deadline')

architecture = call('GET', '/api/agent/architecture')
assert len(architecture['layers']) == 4
assert sum(len(layer['modules']) for layer in architecture['layers']) == 12
assert len(architecture['skills']) == 5
assert architecture['context']['encoding'] == 'O200K_ESTIMATE'
ids = [kb['id'] for kb in fixture['knowledgeBases']]
primary = ids[0]
note = call('POST', '/api/notes', {'knowledgeBaseId': primary, 'title': 'Harness 验证来源 ' + secrets.token_hex(3),
    'body': '知识应保留可核验的来源。任务状态记录执行进度，长期记忆保存未来可以复用的经验。',
    'revision': 0, 'tags': ['harness'], 'properties': {}, 'kind': 'NOTE'})
memory = call('POST', '/api/agent/memories', {'knowledgeBaseId': primary, 'kind': 'SEMANTIC', 'title': '记忆与状态的区分',
    'content': '先阅读来源笔记，再区分任务状态和长期记忆。', 'sourceNoteId': note['id'], 'ttlDays': 30})
preference = call('POST', '/api/agent/memories', {'knowledgeBaseId': primary, 'kind': 'PREFERENCE', 'title': '解释顺序',
    'content': '先用具体例子解释，再说明原理。', 'ttlDays': 365})
managed = call('GET', '/api/agent/memories', params={'knowledgeBaseIds': primary})
assert next(m for m in managed if m['id'] == memory['id'])['current']
request = {'knowledgeBaseId': primary, 'knowledgeBaseIds': ids, 'goal': '根据现有笔记研究知识管理与可信来源，生成带原文引用的报告。',
    'contextNoteId': note['id'], 'collaboration': True, 'skill': 'research', 'requestKey': 'harness-' + secrets.token_hex(8)}
created = call('POST', '/api/agent/runs', request)
assert created['id'] == call('POST', '/api/agent/runs', request)['id']
completed = wait_run(created['id'])
assert completed['status'] == 'SUCCESS', (completed['status'], completed.get('errorMessage'))
diagnostics = call('GET', f'/api/agent/runs/{created["id"]}/diagnostics')
children = diagnostics['children']
assert len(children) == min(3, len(ids))
covered = set()
for child in children:
    assert child['depth'] == 1 and child['rootRunId'] == created['id']
    assert set(child['knowledgeBaseIds']).issubset(ids)
    assert not covered.intersection(child['knowledgeBaseIds'])
    covered.update(child['knowledgeBaseIds'])
    assert child['status'] in {'SUCCESS', 'ABSTAINED'}
assert covered == set(ids)
assert diagnostics['contexts'] and diagnostics['modelCalls']
assert all(call['traceId'] != '0' * 32 for call in diagnostics['modelCalls'])
assert all(ctx['manifest']['estimatedInputTokens'] <= ctx['manifest']['inputBudget'] for ctx in diagnostics['contexts'])
assert any(memory['id'] in ctx['manifest']['memoryIds'] for ctx in diagnostics['contexts'])
assert int(diagnostics['sharedBudget']['reservedTokens']) == 0
assert diagnostics['sharedBudget']['calls'] == completed['modelCalls'] + sum(c['modelCalls'] for c in children)
evaluation = call('GET', f'/api/agent/runs/{created["id"]}/evaluation')
assert all(check['passed'] for check in evaluation['checks']), evaluation
if args.peer_url:
    peer = call('GET', f'/api/agent/runs/{created["id"]}/diagnostics', host=args.peer_url.rstrip('/'))
    assert peer['sharedBudget'] == diagnostics['sharedBudget']
# Source edits invalidate memory and reports; no obsolete memory enters later contexts.
call('PUT', f'/api/notes/{note["id"]}', {**note, 'body': note['body'] + '\n此来源已经更新。', 'revision': note['revision']})
managed = call('GET', '/api/agent/memories', params={'knowledgeBaseIds': primary})
assert not next(m for m in managed if m['id'] == memory['id'])['current']
call('DELETE', f'/api/agent/memories/{preference["id"]}')
assert all(m['id'] != preference['id'] for m in call('GET', '/api/agent/memories', params={'knowledgeBaseIds': primary}))
# Cancelling a root fences children, even when some dispatches have already started.
request['requestKey'] = 'cancel-team-' + secrets.token_hex(8)
second = call('POST', '/api/agent/runs', request)
for _ in range(60):
    current = call('GET', f'/api/agent/runs/{second["id"]}/diagnostics')
    if current['children']:
        break
    time.sleep(.2)
assert current['children'], 'Cancellation check requires actual dispatched children'
call('POST', f'/api/agent/runs/{second["id"]}/cancel')
after = call('GET', f'/api/agent/runs/{second["id"]}/diagnostics')
assert all(c['status'] in {'CANCELLED', 'SUCCESS', 'ABSTAINED'} for c in after['children'])
assert call('GET', f'/api/agent/runs/{second["id"]}')['status'] == 'CANCELLED'
# Retrying a cancelled team must retain collaboration and must not re-inject stale/forgotten memory.
retried = call('POST', f'/api/agent/runs/{second["id"]}/retry', {'requestKey': 'retry-team-' + secrets.token_hex(8)})
for _ in range(90):
    retried_diag = call('GET', f'/api/agent/runs/{retried["id"]}/diagnostics')
    if retried_diag['children']:
        break
    time.sleep(.2)
assert retried_diag['children'], 'Retry lost the requested collaboration mode'
assert all(memory['id'] not in ctx['manifest']['memoryIds'] and preference['id'] not in ctx['manifest']['memoryIds'] for ctx in retried_diag['contexts'])
call('POST', f'/api/agent/runs/{retried["id"]}/cancel')
# Store only non-secret result metadata. Keep the optional browser pointer private.
fixture['harnessRunId'] = created['id']
Path(args.session_file).write_text(json.dumps(fixture, ensure_ascii=False))
os.chmod(args.session_file, 0o600)
report = {'date': datetime.now(timezone.utc).date().isoformat(), 'layers': 4, 'modules': 12,
    'skills': 5, 'collaborativeRun': completed['status'], 'children': len(children), 'disjointScopeCoverage': 'PASS',
    'sharedModelCalls': diagnostics['sharedBudget']['calls'], 'tokenManifests': 'PASS', 'scopedMemoryRecall': 'PASS',
    'sourceRevisionInvalidation': 'PASS', 'forgetMemory': 'PASS', 'cancelCascade': 'PASS',
    'collaborationRetryPreserved': 'PASS', 'invalidMemoryNotReinjected': 'PASS',
    'crossInstanceDiagnostics': 'PASS' if args.peer_url else 'NOT_REQUESTED', 'evaluation': evaluation,
    'modelMode': completed['artifact']['evidence']['mode'], 'mcpWireProtocol': 'COVERED_BY_JUNIT_LOCAL_SERVER',
    'remoteModelQuality': 'NOT_MEASURED'}
Path(args.report).write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
print(json.dumps(report, ensure_ascii=False))
