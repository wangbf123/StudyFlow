from playwright.sync_api import sync_playwright
from pathlib import Path
import json,time,urllib.request,argparse
from datetime import datetime
from zoneinfo import ZoneInfo
parser=argparse.ArgumentParser()
parser.add_argument('--session-file',required=True)
parser.add_argument('--api-url',default='http://127.0.0.1:8080')
parser.add_argument('--web-url',default='http://127.0.0.1:5173')
parser.add_argument('--output-dir',required=True)
parser.add_argument('--report',required=True)
parser.add_argument('--chromium-path',default=None)
args=parser.parse_args()
root=Path(__file__).resolve().parents[1];private=Path(args.session_file)
for _ in range(160):
 if private.exists():
  cfg=json.loads(private.read_text())
  if cfg.get('reportNoteId'):break
 time.sleep(.3)
else:raise RuntimeError('API fixture did not finish')
def api(path):
 req=urllib.request.Request(args.api_url.rstrip('/')+path,headers={'Authorization':cfg['token']})
 with urllib.request.urlopen(req,timeout=15) as r:return json.load(r)['data']
run=api('/api/agent/runs/'+cfg['runId']);out=Path(args.output_dir);out.mkdir(parents=True,exist_ok=True)
errors=[]
with sync_playwright() as p:
 browser=p.chromium.launch(executable_path=args.chromium_path,headless=True,args=['--no-sandbox','--disable-dev-shm-usage'])
 context=browser.new_context(viewport={'width':1600,'height':1000},device_scale_factor=1)
 context.add_init_script('localStorage.setItem("studyflow_token",'+json.dumps(cfg['token'])+');localStorage.setItem('+json.dumps('studyflow-workspace-'+cfg['user']['userId'])+','+json.dumps(json.dumps({'selectedKb':cfg['knowledgeBases'][0]['id'],'tabs':[cfg['indexNoteId']],'left':248,'right':360}))+');')
 page=context.new_page();page.on('pageerror',lambda e:errors.append(str(e)));page.goto(args.web_url,wait_until='networkidle');page.locator('.brain-note-title input').wait_for()
 page.wait_for_function('document.querySelector(".brain-note-title input")?.value === "我的知识地图"')
 page.locator('.brain-prose .brain-wiki').filter(has_text='概率与不确定性').first.click()
 page.wait_for_function('document.querySelector(".brain-note-title input")?.value === "概率与不确定性"')
 editor=page.locator('.brain-editor .cm-content');editor.click();editor.press('Control+End');editor.press('Enter');editor.type('浏览器验证：知识可以持续积累。')
 page.get_by_role('button',name='保存',exact=True).click();page.wait_for_function('document.querySelector(".brain-statusbar")?.textContent.includes("笔记已保存")')
 prob=next(n for n in cfg['notes'] if n['title']=='概率与不确定性');assert '浏览器验证' in api('/api/notes/'+prob['id'])['body']
 # Return to the actual saved index and capture the editor/preview/links workspace.
 page.locator('.brain-tab').filter(has_text='我的知识地图').click();page.wait_for_function('document.querySelector(".brain-note-title input")?.value === "我的知识地图"')
 page.wait_for_timeout(1300);page.screenshot(path=str(out/'brain-workspace.png'),animations='disabled')
 page.get_by_title('知识图谱',exact=True).click();page.get_by_role('button',name='个人全景',exact=True).click();page.locator('.brain-center .brain-graph-canvas canvas').first.wait_for();page.wait_for_timeout(2000)
 assert page.locator('.brain-center .brain-graph-canvas canvas').count()>0
 page.screenshot(path=str(out/'brain-graph.png'),animations='disabled')
 page.get_by_role('button',name='Agent 助手',exact=True).click();page.locator('select[aria-label="Agent 历史会话"]').select_option(run['sessionId']);page.locator('.brain-artifact-head').wait_for(timeout=15000)
 page.get_by_role('button',name='存为笔记',exact=True).click();page.wait_for_function('document.querySelector(".brain-note-title input")?.value.includes("梳理个人知识体系")')
 page.locator('.brain-context-scroll:visible').evaluate('(el) => el.scrollTop = 0');page.get_by_role('button',name='阅读',exact=True).click();page.wait_for_timeout(800);page.screenshot(path=str(out/'brain-agent.png'),animations='disabled')
 # Real command palette and search.
 page.keyboard.press('Control+o');page.locator('.brain-palette input').fill('可信');page.locator('.brain-palette-result').filter(has_text='如何设计可信的实验').wait_for();page.locator('.brain-palette .brain-icon').click()
 page.set_viewport_size({'width':390,'height':844});page.get_by_role('button',name='切换右侧面板').click();page.get_by_role('button',name='切换左侧导航').click();page.wait_for_timeout(300)
 assert page.evaluate('document.documentElement.scrollWidth <= innerWidth'), 'Mobile horizontal overflow'
 page.screenshot(path=str(out/'brain-mobile.png'),animations='disabled')
 page.set_viewport_size({'width':1600,'height':1000});page.goto(args.web_url.rstrip('/')+'/#classic',wait_until='networkidle');page.locator('.app-shell').wait_for();page.get_by_role('tab',name='学习 Agent',exact=True).click();page.locator('.brain-embedded .brain-agent').wait_for()
 page.get_by_role('link',name='返回知识工作区 ↗').click();page.locator('.brain-shell').wait_for()
 result={'date':datetime.now(ZoneInfo('Asia/Shanghai')).date().isoformat(),'markdownEditAndSave':'PASS','wikilinkNavigation':'PASS','g6Canvas':'PASS','crossLibraryGraph':'PASS','agentHistoryReplay':'PASS','reportSavedAsNote':'PASS','commandSearch':'PASS','mobileWidth':390,'horizontalOverflow':False,'classicWorkspaceCompatibility':'PASS','pageErrors':errors}
 Path(args.report).write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');browser.close();print(json.dumps(result,ensure_ascii=False));assert not errors,errors
