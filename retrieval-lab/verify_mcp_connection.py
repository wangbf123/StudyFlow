"""Check authenticated MCP binding and source import against an admin-configured local fixture."""
import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
import secrets
import requests

parser = argparse.ArgumentParser()
parser.add_argument('--session-file', required=True)
parser.add_argument('--api-url', required=True)
parser.add_argument('--connector', default='fixture')
parser.add_argument('--report', required=True)
args = parser.parse_args()
fixture = json.loads(Path(args.session_file).read_text())
client = requests.Session()
client.trust_env = False
client.headers['Authorization'] = fixture['token']

def call(method, path, data=None, expected=0):
    response = client.request(method, args.api_url.rstrip('/') + path, json=data, timeout=25)
    response.raise_for_status()
    result = response.json()
    assert result.get('code') == expected, (path, result.get('message'))
    return result.get('data')

kb, other = [item['id'] for item in fixture['knowledgeBases'][:2]]
path = '/api/agent/connections/' + args.connector
connections = call('GET', '/api/agent/connections')
assert any(item['key'] == args.connector for item in connections), 'Configure the server-side fixture endpoint first'
call('PUT', path, {'knowledgeBaseIds': [kb], 'enabled': True})
resources = call('GET', path + '/resources')
assert resources['resources'][0]['uri'] == 'kb://fixture/source'
request = {'knowledgeBaseId': kb, 'uri': 'kb://fixture/source', 'title': 'MCP 导入验证 ' + secrets.token_hex(3)}
note = call('POST', path + '/import', request)
assert note['properties']['connector'] == args.connector and len(note['properties']['sha256']) == 64
assert '来源核验' in note['body']
call('POST', path + '/import', {**request, 'knowledgeBaseId': other}, expected=403)
call('POST', path + '/import', {**request, 'uri': 'file:///etc/passwd'}, expected=403)
call('PUT', path, {'knowledgeBaseIds': [kb], 'enabled': False})
call('GET', path + '/resources', expected=403)
report = {'date': datetime.now(timezone.utc).date().isoformat(), 'server': 'LOCAL_MCP_PROTOCOL_FIXTURE',
    'sdk': '2.1.0', 'authenticatedBinding': 'PASS', 'resourceBrowse': 'PASS', 'importToVersionedNote': 'PASS',
    'uriAllowlist': 'PASS', 'targetScopeRestriction': 'PASS', 'revokeBinding': 'PASS', 'thirdPartyServiceAvailability': 'NOT_MEASURED'}
Path(args.report).write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
print(json.dumps(report, ensure_ascii=False))
