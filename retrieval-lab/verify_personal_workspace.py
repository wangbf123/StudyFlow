"""Create isolated demo content and verify the personal workspace through authenticated APIs.

Tokens stay in a chmod 0600 file outside the checkout. No production/user content is modified.
"""
import requests,secrets,json,pathlib,time,os,argparse,tempfile
from datetime import datetime
from zoneinfo import ZoneInfo
parser=argparse.ArgumentParser()
parser.add_argument('--api-url',default='http://127.0.0.1:8080')
parser.add_argument('--peer-url')
parser.add_argument('--work-dir',default=None)
parser.add_argument('--report',default=None)
parser.add_argument('--require-neo4j',action='store_true')
args=parser.parse_args()
base=args.api_url.rstrip('/');session=requests.Session();session.trust_env=False
root=pathlib.Path(__file__).resolve().parents[1]
private=pathlib.Path(args.work_dir or tempfile.mkdtemp(prefix='studyflow-personal-')).resolve()
if private==root or root in private.parents:raise ValueError('Keep browser-session.json outside the repository')
private.mkdir(parents=True,exist_ok=True)
report_date=datetime.now(ZoneInfo('Asia/Shanghai')).date().isoformat()
def call(method,path,data=None,expected=0,port=None,**kw):
 r=session.request(method,(port or base)+path,json=data,timeout=30,**kw);r.raise_for_status();b=r.json();assert b.get('code')==expected,(path,b.get('message'));return b.get('data')
for ready_url in set([base,args.peer_url or base]):
 for _ in range(80):
  try:
   if session.get(ready_url+'/doc.html',timeout=1).status_code==200:break
  except requests.ConnectionError:pass
  time.sleep(.4)
 else:raise RuntimeError('Isolated API instance did not become ready')
name='brain_'+secrets.token_hex(5);password=secrets.token_urlsafe(18)
call('POST','/api/auth/register',{'username':name,'password':password,'nickname':'知识探索者'})
login=call('POST','/api/auth/login',{'username':name,'password':password});session.headers['Authorization']=login['tokenValue']
me=call('GET','/api/auth/me')
bases=[]
for i,(title,color,template) in enumerate([('我的知识中枢','#a78bfa','blank'),('学习与方法','#8da4ed','learning'),('研究与实验','#83c8ae','research'),('项目研发','#e2ad77','project'),('生活与远方','#dc95b3','life')]):
 bases.append(call('POST','/api/knowledge-bases',{'name':title,'description':'连接知识、经验与行动','parentId':bases[0]['id'] if i else None,'templateKey':template,'color':color}))
notes=[]
def note(kb,title,body,kind='NOTE',tags=None):
 n=call('POST','/api/notes',{'knowledgeBaseId':bases[kb]['id'],'title':title,'body':body,'revision':0,'kind':kind,'tags':tags or [],'properties':{'状态':'持续完善','来源':'个人公开演示笔记'}});notes.append(n);return n
prob=note(1,'概率与不确定性','# 概率与不确定性\n\n概率用于描述不确定事件发生的可能性。条件概率描述在已知另一事件发生的条件下，某事件发生的概率。\n\n## 连接实际问题\n\n实验设计需要明确样本、假设与评价指标。观察到相关性不能直接推出因果关系。\n\n> 方法的价值，在于知道它适用于什么情境。','CONCEPT',['数学','方法'])
system=note(1,'系统设计的基本原则','# 系统设计的基本原则\n\n可靠的系统需要清晰的边界、可观测的状态和可恢复的执行。幂等操作允许重复请求产生相同的业务结果。\n\n## 实践记录\n\n把重要业务状态持久化，使用版本检查避免旧结果覆盖新结果。','CONCEPT',['系统设计'])
experiment=note(2,'如何设计可信的实验',f'# 如何设计可信的实验\n\n先提出可检验的研究问题，再定义数据集、对照组和评价指标。\n\n实验应控制变量并记录环境，以便复现。参考 [[note:{prob["id"]}|概率与不确定性]]。\n\n## 下一步\n\n- [ ] 固定样本与评测口径\n- [ ] 完成对照实验','NOTE',['科研','实验'])
rag=note(2,'RAG 与知识检索','# RAG 与知识检索\n\n检索增强生成先查找相关资料，再让模型依据资料回答。全文检索适合明确术语，向量检索适合语义相似查询。\n\nRRF 根据候选排序融合多路召回结果。来源原文与文档版本是证据追溯的重要依据。','CONCEPT',['RAG','检索'])
project=note(3,'StudyFlow 个人知识系统',f'# StudyFlow 个人知识系统\n\n## 目标\n\n把笔记、资料、知识关系和 Agent 连接成持续生长的个人工作区。\n\n## 技术依据\n\n- [[note:{system["id"]}|系统设计的基本原则]]\n- [[note:{rag["id"]}|RAG 与知识检索]]\n- [[note:{experiment["id"]}|可信实验]]\n\n## 里程碑\n\n- [x] 知识库与笔记\n- [x] 双向链接与图谱\n- [ ] 持续完善研究体验','PROJECT',['项目','知识管理'])
lease=note(3,'一次执行恢复设计复盘',f'# 一次执行恢复设计复盘\n\n## 情境\n\n浏览器连接可能中断，服务也可能重启。\n\n## 决策\n\n将执行状态与事件持久化，并使用租约 token 隔离过期执行者。\n\n## 结果\n\n用户可以重新打开任务并查看已保存的执行记录。关联 [[note:{system["id"]}|系统设计]] 与 [[note:{project["id"]}|StudyFlow]]。','DECISION',['复盘'])
travel=note(4,'一次山野旅行的准备','# 一次山野旅行的准备\n\n## 想去的地方\n\n在陌生的自然环境中，观察与记录可以成为新的经验。\n\n## 准备清单\n\n- [ ] 确认路线与天气\n- [ ] 整理装备\n- [ ] 留下照片与沿途笔记','JOURNAL',['旅行','生活'])
reflection=note(4,'把经验变成可复用的方法',f'# 把经验变成可复用的方法\n\n记录情境、行动和结果，比仅记录结论更有助于下一次决策。\n\n项目与旅行都可以留下复盘：[[note:{lease["id"]}|项目复盘]]、[[note:{travel["id"]}|旅行准备]]。','NOTE',['复盘','成长'])
index=note(0,'我的知识地图',f'# 我的知识地图\n\n> 让每一个想法，找到它的连接。\n\n这里记录学习、研究、研发与生活中的知识。\n\n## 学习与方法\n\n[[note:{prob["id"]}|概率与不确定性]] · [[note:{system["id"]}|系统设计]]\n\n## 研究与创造\n\n[[note:{experiment["id"]}|实验设计]] → [[note:{project["id"]}|StudyFlow]]\n\n## 生活与成长\n\n[[note:{travel["id"]}|山野旅行]] · [[note:{reflection["id"]}|经验与方法]]\n\n## 正在思考\n\n如何让不同领域的经验相互启发？','NOTE',['索引'])
# Cursor, graph, stable IDs, scoped visibility.
ids=','.join(b['id'] for b in bases)
graph=call('GET','/api/notes/graph',params={'knowledgeBaseIds':ids});assert len(graph['nodes'])==9 and len(graph['edges'])>=12
narrow=call('GET','/api/notes/graph',params={'knowledgeBaseIds':bases[3]['id']});assert len(narrow['nodes'])==2 and len(narrow['edges'])==1
page=call('GET','/api/notes',params={'knowledgeBaseIds':ids,'size':3});page2=call('GET','/api/notes',params={'knowledgeBaseIds':ids,'size':3,'before':page['nextCursor']});assert not(set(n['id'] for n in page['items'])&set(n['id'] for n in page2['items']))
# Actual upload and retained BGE+ES pipeline.
payload=('Redis 使用原子 Lua 脚本执行令牌桶判断。幂等键可以避免重复请求产生重复业务效果。知识图谱可以通过双向链接连接不同领域的笔记。\n'*12).encode()
r=session.post(base+'/api/documents/upload',data={'knowledgeBaseId':bases[3]['id']},files={'file':('agent-reliability.txt',payload,'text/plain')},timeout=30);r.raise_for_status();result=r.json();assert result['code']==0,result.get('message');docId=result['data']['documentId']
for _ in range(100):
 doc=call('GET',f'/api/documents/{docId}')
 if doc['parseStatus']=='SUCCESS':break
 assert doc['parseStatus']!='FAILED',doc.get('summary');time.sleep(.3)
assert doc['parseStatus']=='SUCCESS'
request={'knowledgeBaseId':bases[0]['id'],'knowledgeBaseIds':[b['id'] for b in bases],'goal':'梳理个人知识体系，分析已有笔记之间的关联，并给出带原文引用的研究报告。','skill':'organize','contextNoteId':index['id'],'requestKey':'brain-demo-'+secrets.token_hex(8)}
run=call('POST','/api/agent/runs',request);same=call('POST','/api/agent/runs',request);assert run['id']==same['id']
# Record private browser session before waiting, never print its token.
privateData={'token':login['tokenValue'],'user':me,'knowledgeBases':bases,'notes':notes,'indexNoteId':index['id'],'runId':run['id'],'documentId':docId}
path=private/'browser-session.json';fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
with os.fdopen(fd,'w') as f:json.dump(privateData,f,ensure_ascii=False)
os.chmod(path,0o600)
for _ in range(160):
 current=call('GET',f'/api/agent/runs/{run["id"]}')
 if current['status'] in {'SUCCESS','ABSTAINED','FAILED','CANCELLED','TIMED_OUT','BUDGET_EXCEEDED'}:break
 time.sleep(.3)
assert current['status']=='SUCCESS',(current['status'],current.get('errorMessage'))
assert current['artifact']['verificationStatus']=='EXTRACTED' and current['artifact']['evidenceCurrent']
events=call('GET',f'/api/agent/runs/{run["id"]}/events');assert events and events[-1]['seq']==current['eventSeq']
replay=call('GET',f'/api/agent/runs/{run["id"]}/events',params={'after':events[3]['seq']});assert replay[0]['seq']==events[3]['seq']+1
saved=call('POST',f'/api/agent/runs/{run["id"]}/save-note',{'knowledgeBaseId':bases[0]['id']});saved2=call('POST',f'/api/agent/runs/{run["id"]}/save-note',{'knowledgeBaseId':bases[0]['id']});assert saved['id']==saved2['id'] and saved['links']
privateData['reportNoteId']=saved['id'];path.write_text(json.dumps(privateData,ensure_ascii=False));os.chmod(path,0o600)
# Cross-instance session and database task state.
for _ in range(50):
 try:peer=call('GET',f'/api/agent/runs/{run["id"]}',port=args.peer_url or base);break
 except requests.ConnectionError:time.sleep(.5)
assert peer['status']=='SUCCESS'
for _ in range(80):
 projected=call('GET','/api/notes/graph',params={'knowledgeBaseIds':ids,'focus':index['id']})
 if projected['engine']=='NEO4J_VERIFIED':break
 time.sleep(.3)
if args.require_neo4j:assert projected['engine']=='NEO4J_VERIFIED',projected['engine']
report={'date':report_date,'notes':len(notes),'knowledgeBases':len(bases),'graphNodes':len(graph['nodes']),'graphEdges':len(graph['edges']),'graphProjection':projected['engine'],'cursorPagination':'PASS','durableAgent':'PASS','agentStatus':current['status'],'verificationMode':current['artifact']['evidence']['mode'],'eventCount':len(events),'replay':'PASS','idempotentCreate':'PASS','reportToLinkedNote':'PASS','crossInstanceSession':'PASS' if args.peer_url and args.peer_url.rstrip('/')!=base else 'NOT_REQUESTED','documentPipeline':'PASS'}
out=pathlib.Path(args.report) if args.report else private/'api-results.json';out.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(json.dumps(report,ensure_ascii=False));print('Private browser session file:',path)
