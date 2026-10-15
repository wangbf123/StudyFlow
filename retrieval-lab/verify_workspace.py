#!/usr/bin/env python3
"""Real API verification for the knowledge workspace; credentials stay in process memory.

Use isolated async/mock-AI instances, real MinIO/ES/pinned BGE, and disabled fixture rate limits.
Two URLs test a shared Redis session. --after-login-command is an optional explicit process
restart hook for an isolated test instance; no process is stopped by default.
"""
import argparse
import json
import os
from pathlib import Path
import secrets
import subprocess
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, ProxyHandler, build_opener
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]

class Client:
    def __init__(self, base):
        self.base = base.rstrip('/'); self.token = None
        self.opener = build_opener(ProxyHandler({}))
    def call(self, path, data=None, method=None, body=None, content_type='application/json', expect=0):
        headers = {'Content-Type': content_type}
        if self.token: headers['Authorization'] = self.token
        raw = body if body is not None else (json.dumps(data).encode() if data is not None else None)
        req = Request(self.base + path, data=raw, method=method or ('POST' if raw is not None else 'GET'), headers=headers)
        try:
            with self.opener.open(req, timeout=120) as response: result=json.load(response)
        except HTTPError as error:
            result=json.load(error)
        if result.get('code') != expect:
            raise AssertionError({'path':path,'expected':expect,'actual':result.get('code'),'message':result.get('message')})
        return result.get('data')
    def upload(self, kb, title, content):
        boundary='studyflow'+secrets.token_hex(12)
        header=(f'--{boundary}\r\nContent-Disposition: form-data; name="knowledgeBaseId"\r\n\r\n{kb}\r\n'
                f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{title}.txt"\r\nContent-Type: text/plain\r\n\r\n')
        body=header.encode()+content.encode()+f'\r\n--{boundary}--\r\n'.encode()
        return self.call('/api/documents/upload',body=body,content_type='multipart/form-data; boundary='+boundary)
    def wait_task(self, task_id):
        until=time.monotonic()+120
        while time.monotonic()<until:
            task=self.call('/api/tasks/'+str(task_id))
            if task['status']=='SUCCESS': return task
            if task['status'] in ('FAILED','CANCELLED'): raise AssertionError(task)
            time.sleep(.2)
        raise AssertionError('Task did not complete within test window')

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--backend-url',default='http://127.0.0.1:8082')
    parser.add_argument('--second-url')
    parser.add_argument('--work-dir',default='/tmp/studyflow-workspace-verification')
    parser.add_argument('--after-login-command',nargs='+')
    parser.add_argument('--retain-fixture',action='store_true',help='Keep only this test knowledge base for screenshots')
    args=parser.parse_args()
    work=Path(args.work_dir).resolve()
    if work == ROOT or ROOT in work.parents: parser.error('工作目录包含临时登录 token，必须位于仓库之外')
    work.mkdir(parents=True,exist_ok=True)
    client=Client(args.backend_url);checks=[]
    def mark(check): checks.append(check);print('PASS:',check,flush=True)
    username='workspace_'+secrets.token_hex(5);password=secrets.token_urlsafe(24)
    client.call('/api/auth/register',{'username':username,'password':password,'nickname':'知识库体验'})
    login=client.call('/api/auth/login',{'username':username,'password':password});client.token=login['tokenValue']
    if args.second_url:
        second=Client(args.second_url);second.token=client.token
        assert second.call('/api/auth/me')['userId']==login['userId']
        mark('same Redis-backed token accepted by a second backend instance')
    if args.after_login_command:
        subprocess.run(args.after_login_command,check=True)
        deadline=time.monotonic()+60
        while True:
            try:
                assert client.call('/api/auth/me')['userId']==login['userId'];break
            except (URLError,AssertionError,ConnectionError):
                if time.monotonic()>deadline: raise
                time.sleep(.5)
        mark('same token remains valid after isolated application process restart without re-login')
    kb=client.call('/api/knowledge-bases',{'name':'计算机知识图书馆','description':'从数据库到分布式系统，整理资料、检索知识与核对原文证据。'})['id']
    dataset=json.loads((ROOT/'retrieval-lab/data/study-notes.json').read_text())
    manifest={'embedding_model':'BAAI/bge-small-zh-v1.5@7999e1d3359715c523056ef9478215996d62a620','chunk_ids':{}}
    for note in dataset['corpus']:
        uploaded=client.upload(kb,note['title'],note['content']);task=client.wait_task(uploaded['taskId']);assert task['stage']=='INDEX'
        chunks=client.call('/api/documents/'+str(uploaded['documentId'])+'/chunks');assert len(chunks)==1
        manifest['chunk_ids'][str(note['id'])]=chunks[0]['id']
    mark('24 document uploads finish the primary INDEX stage with independent SUMMARY tasks')
    until=time.monotonic()+30
    while True:
        overview=client.call(f'/api/knowledge-bases/{kb}/overview')
        if int(overview['summaryReadyCount'])==24:break
        if time.monotonic()>until:raise AssertionError(overview)
        time.sleep(.25)
    assert int(overview['readyCount'])==24 and int(overview['chunkCount'])==24 and int(overview['embeddedChunkCount'])==24
    assert any(task['stage']=='SUMMARY' for task in overview['recentTasks'])
    mark('workspace aggregates reflect current-generation documents, vectors, summaries and recent tasks')
    first=client.call(f'/api/knowledge-bases/{kb}/document-page?size=5');assert first['hasMore']
    second=client.call(f'/api/knowledge-bases/{kb}/document-page?'+urlencode({'size':5,'beforeId':first['nextCursor']}))
    assert not {d['id'] for d in first['items']} & {d['id'] for d in second['items']}
    mark('cursor document pages do not overlap')
    question=dataset['questions'][0]['question']
    trace=client.call('/api/search/explain?'+urlencode({'knowledgeBaseId':kb,'keyword':question,'mode':'RRF','size':5}))
    assert trace['hits'] and trace['embeddingProvider']=='bge-local' and not trace['degradationReason']
    assert any(hit['bm25Rank'] and hit['vectorRank'] for hit in trace['hits'])
    mark('real BGE/ES retrieval exposes original ranks and RRF contributions')
    answer=client.call('/api/qa/ask',{'knowledgeBaseId':kb,'question':question})
    assert answer['verificationMode']=='EXTRACTIVE_ONLY' and answer['claims']
    citation=answer['claims'][0]['evidence'][0]
    detail=client.call('/api/qa/citations/'+str(citation['citationId'])+'/chunk')
    assert detail['evidenceState']=='CURRENT' and detail['quote'] in detail['content']
    mark('claim evidence persists with current-version original-text quotes')
    token=client.token
    client.token=None
    client.call('/api/auth/register',{'username':username+'_other','password':password,'nickname':'隔离验证'})
    other=client.call('/api/auth/login',{'username':username+'_other','password':password});client.token=other['tokenValue']
    for path in [f'/api/knowledge-bases/{kb}/overview',f'/api/knowledge-bases/{kb}/document-page',f'/api/knowledge-bases/{kb}/tasks']:
        client.call(path,expect=400)
    client.token=token
    mark('cross-user overview, document cursor and task endpoints deny access')
    mp=work/'manifest.json';mp.write_text(json.dumps(manifest))
    env=os.environ.copy();env['STUDYFLOW_EVAL_TOKEN']=token
    with (work/'retrieval-evaluation.log').open('w') as log:
        subprocess.run(['python3',str(ROOT/'retrieval-lab/evaluate.py'),'--backend-url',args.backend_url,'--manifest',str(mp),'--knowledge-base-id',str(kb),'--user-id',str(login['userId']),'--output',str(ROOT/'retrieval-lab/reports/workspace-java-evaluation')],env=env,check=True,stdout=log)
    mark('five retrieval modes evaluated through the upgraded Java API')
    if not args.retain_fixture:client.call('/api/knowledge-bases/'+str(kb),method='DELETE')
    # A mode-0600 local file supplies screenshots; never commit this authentication material.
    session=work/'browser-session.json'
    descriptor=os.open(session,os.O_CREAT|os.O_TRUNC|os.O_WRONLY,0o600)
    os.fchmod(descriptor,0o600)
    with os.fdopen(descriptor,'w') as handle:json.dump({'token':token,'knowledgeBaseId':kb,'sessionId':answer['sessionId']},handle)
    report={'status':'PASS','generated_at':datetime.now(timezone.utc).isoformat(),'checks':checks,'document_count':24,'embedding_model':manifest['embedding_model'],'mq_mode':'async','qa_mode':'EXTRACTIVE_ONLY','fixture_rate_limit':'disabled','ocr':'not exercised','independent_semantic_quality':'not measured','production_capacity':'not measured'}
    (ROOT/'studyflow/docs/experiments/workspace-api-results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')

if __name__=='__main__':main()
