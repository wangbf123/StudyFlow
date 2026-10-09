#!/usr/bin/env python3
"""Run against an isolated, empty test backend with MinIO, MySQL, Redis, ES and pinned BGE.
Use async MQ, extractive mock AI, OCR off; disable rate limiting only on the test instance.
Creates test accounts and destroys its own knowledge base after validating all retrieval modes.
No passwords or tokens are persisted or printed. Report scope does not include LLM accuracy.
"""
import json,urllib.request,urllib.parse,secrets,time,pathlib,subprocess,os,argparse
root=pathlib.Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description='Isolated StudyFlow API verification; creates its own users and knowledge base')
parser.add_argument('--backend-url',default='http://127.0.0.1:8082')
parser.add_argument('--work-dir',default='/tmp/studyflow-highlights-e2e')
args=parser.parse_args()
work=pathlib.Path(args.work_dir);work.mkdir(parents=True,exist_ok=True)
base=args.backend_url.rstrip('/'); opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
token=None
checks=[]
def request(path,data=None,method=None,body=None,content_type='application/json'):
 h={'Content-Type':content_type}
 if token:h['Authorization']=token
 raw=body if body is not None else (json.dumps(data).encode() if data is not None else None)
 req=urllib.request.Request(base+path,data=raw,method=method or ('POST' if raw is not None else 'GET'),headers=h)
 with opener.open(req,timeout=120) as r: d=json.load(r)
 assert d['code']==0, {'path':path,'code':d['code'],'message':d.get('message')}
 return d.get('data')
def mark(name):checks.append(name);print('PASS:',name,flush=True)
def upload(kb,name,text):
 boundary='boundary'+secrets.token_hex(12)
 body=(f'--{boundary}\r\nContent-Disposition: form-data; name="knowledgeBaseId"\r\n\r\n{kb}\r\n'
       f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\nContent-Type: text/plain\r\n\r\n').encode()+text.encode()+f'\r\n--{boundary}--\r\n'.encode()
 return request('/api/documents/upload',body=body,content_type='multipart/form-data; boundary='+boundary)
def wait_task(task_id):
 deadline=time.monotonic()+90
 while time.monotonic()<deadline:
  task=request('/api/tasks/'+str(task_id))
  if task['status']=='SUCCESS':assert task['stage']=='INDEX';return task
  assert task['status'] not in ['FAILED','CANCELLED'],task
  time.sleep(.25)
 raise RuntimeError('task deadline exceeded')
user='e2e_'+secrets.token_hex(6); password=secrets.token_urlsafe(18)
request('/api/auth/register',{'username':user,'password':password,'nickname':'Highlight validation'})
login=request('/api/auth/login',{'username':user,'password':password});token=login['tokenValue'];uid=login['userId']
kb=request('/api/knowledge-bases',{'name':'highlight-evaluation','description':'Generated test fixture'})['id']
mark('registration/login/knowledge-base creation')
dataset=json.loads((root/'retrieval-lab/data/study-notes.json').read_text())
manifest={'embedding_model':'BAAI/bge-small-zh-v1.5@7999e1d3359715c523056ef9478215996d62a620','chunk_ids':{}}
docs=[]
for corpus in dataset['corpus']:
 result=upload(kb,'note_'+str(corpus['id'])+'.txt',corpus['content']); task=wait_task(result['taskId']); docs.append(result)
 chunks=request('/api/documents/'+str(result['documentId'])+'/chunks')
 assert len(chunks)==1
 manifest['chunk_ids'][str(corpus['id'])]=chunks[0]['id']
mark('24 TXT uploads through PARSE/EMBED/INDEX with real CPU BGE and MinIO')
query=dataset['questions'][0]['question']
for mode in ['BM25','VECTOR','RRF','RRF_LEXICAL','LEGACY']:
 rows=request('/api/search?'+urllib.parse.urlencode({'knowledgeBaseId':kb,'keyword':query,'size':5,'mode':mode}))
 assert rows,mode
mark('all five Java retrieval modes return owned indexed material')
answer=request('/api/qa/ask',{'knowledgeBaseId':kb,'question':query})
assert answer['answerStatus']=='EXTRACTED' and answer['verificationMode']=='EXTRACTIVE_ONLY' and answer['claims'],answer
citation=answer['claims'][0]['evidence'][0]; detail=request('/api/qa/citations/'+str(citation['citationId'])+'/chunk')
assert detail['evidenceState']=='CURRENT' and detail['quote'] in detail['content']
# Offsets use UTF-16; these sample texts contain BMP Chinese.
assert detail['content'][detail['charStart']:detail['charEnd']]==detail['quote']
history=request('/api/qa/sessions/'+str(answer['sessionId'])+'/messages')
assert any(m.get('claims') for m in history)
mark('verified extractive QA, persisted claim evidence and original-text offsets')
headers={'Authorization':token,'Content-Type':'application/json'}
with opener.open(urllib.request.Request(base+'/api/qa/ask/stream',data=json.dumps({'knowledgeBaseId':kb,'question':query}).encode(),headers=headers),timeout=120) as r:
 stream=r.read().decode()
assert 'event:verification_start' in stream and 'event:delta' in stream and stream.index('event:verification_start')<stream.index('event:delta')
assert 'event:message_end' in stream and 'event:error' not in stream
mark('SSE publishes answer only after evidence verification')
mp=(work/'backend-eval-manifest.json');mp.write_text(json.dumps(manifest))
e=os.environ.copy();e['STUDYFLOW_EVAL_TOKEN']=token
subprocess.run(['python3','retrieval-lab/evaluate.py','--backend-url',base,'--manifest',str(mp),'--knowledge-base-id',str(kb),'--user-id',str(uid),'--output','retrieval-lab/reports/initial-java-evaluation'],cwd=root,env=e,check=True,stdout=open(work/'retrieval-java-evaluation.log','w'))
mark('fixed sample retrieval evaluation through the real Java API')
# Choose the actual document cited above for rebuild rather than an unrelated fixture.
rebuild=request('/api/documents/'+str(citation['documentId'])+'/rebuild',{})
wait_task(rebuild['taskId'])
stale=request('/api/qa/citations/'+str(citation['citationId'])+'/chunk')
assert stale['evidenceState']=='STALE_VERSION' and stale['charStart'] is None
mark('document rebuild creates a new generation and marks old citations stale')
request('/api/documents/'+str(citation['documentId']),method='DELETE')
try:
 request('/api/qa/citations/'+str(citation['citationId'])+'/chunk')
 raise AssertionError('deleted document citation should be inaccessible')
except (urllib.error.HTTPError,AssertionError) as ex:
 if isinstance(ex,AssertionError) and 'should be inaccessible' in str(ex):raise
mark('deleted document cannot expose saved citation text')
# A second user cannot read the first user's evidence.
first_token=token
request('/api/auth/register',{'username':user+'_other','password':password,'nickname':'Other test user'})
token=request('/api/auth/login',{'username':user+'_other','password':password})['tokenValue']
try:
 request('/api/qa/sessions/'+str(answer['sessionId'])+'/messages');raise AssertionError('foreign session visible')
except (urllib.error.HTTPError,AssertionError) as ex:
 if isinstance(ex,AssertionError) and 'foreign session visible' in str(ex):raise
mark('cross-user QA history is denied')
token=first_token
request('/api/knowledge-bases/'+str(kb),method='DELETE')
try:
 request('/api/qa/sessions/'+str(answer['sessionId'])+'/messages');raise AssertionError('deleted knowledge base visible')
except (urllib.error.HTTPError,AssertionError) as ex:
 if isinstance(ex,AssertionError) and 'deleted knowledge base visible' in str(ex):raise
mark('deleted knowledge base invalidates QA history access')
report={'status':'PASS','checks':checks,'corpus_documents':len(docs),'embedding_model':manifest['embedding_model'],'qa_mode':'EXTRACTIVE_ONLY','mq_mode':'async','ocr':'not exercised','independent_semantic_accuracy':'not measured','capacity_benchmark':'not measured','rate_limit':'disabled only in isolated fixture instance'}
path=(root/'studyflow/docs/experiments/highlights-e2e-results.json');path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
