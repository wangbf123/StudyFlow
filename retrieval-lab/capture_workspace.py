#!/usr/bin/env python3
"""Real browser verification and screenshots. Use only a public, isolated sample fixture.
Session credentials are read from a local private file and never printed or committed.
Install Playwright separately; this script captures pages without fabricating their DOM.
"""
import json,pathlib,argparse,shutil
from playwright.sync_api import sync_playwright
root=pathlib.Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description='Capture actual authenticated workspace views from an isolated public fixture')
parser.add_argument('--session-file',required=True)
parser.add_argument('--web-url',default='http://127.0.0.1:5173')
parser.add_argument('--output-dir',default=str(root/'.github/assets'))
parser.add_argument('--chromium-path',default=shutil.which('chromium'))
args=parser.parse_args()
output=pathlib.Path(args.output_dir);output.mkdir(parents=True,exist_ok=True)
session=json.load(open(args.session_file))
errors=[]
with sync_playwright() as p:
 browser=p.chromium.launch(executable_path=args.chromium_path,headless=True,args=['--no-sandbox','--disable-dev-shm-usage'])
 context=browser.new_context(viewport={'width':1600,'height':1100},device_scale_factor=1)
 context.add_init_script('localStorage.setItem("studyflow_token",'+json.dumps(session['token'])+');')
 page=context.new_page();page.on('pageerror',lambda e:errors.append(str(e)))
 page.goto(args.web_url,wait_until='networkidle')
 page.locator('.knowledge-stats strong').first.wait_for()
 page.screenshot(path=str(output/'knowledge-workspace.png'),full_page=False, animations="disabled")
 page.get_by_role('tab',name='知识检索',exact=True).click()
 question=json.load(open(root/'retrieval-lab/data/study-notes.json'))['questions'][0]['question']
 page.get_by_role('textbox',name='检索问题').fill(question)
 page.get_by_role('button',name='搜索资料').click()
 page.locator('.knowledge-hit').first.wait_for()
 page.locator('.knowledge-hit details').first.locator('summary').click()
 page.screenshot(path=str(output/'knowledge-search.png'),full_page=False, animations="disabled")
 page.get_by_role('tab',name='问答',exact=True).click()
 page.locator('.session-item').first.click()
 page.locator('.claim-evidence .citation-chip').first.wait_for()
 page.locator('.claim-evidence .citation-chip').first.click()
 page.locator('.el-drawer__body mark').wait_for()
 page.wait_for_function("getComputedStyle(document.querySelector('.el-drawer')).transform === 'none'")
 page.screenshot(path=str(output/'knowledge-evidence.png'),full_page=False, animations="disabled")
 # Check actual client-side filtering and the mobile layout without fabricated DOM state.
 page.get_by_role('button',name='Close this dialog',exact=False).click() if page.get_by_role('button',name='Close this dialog',exact=False).count() else page.keyboard.press('Escape')
 page.get_by_role('tab',name='资料',exact=True).click()
 page.get_by_placeholder('按文件名筛选资料').fill('Redis')
 page.get_by_role('button',name='筛选',exact=True).click()
 page.wait_for_timeout(600)
 assert 'Redis' in page.locator('.el-table__body').inner_text()
 page.get_by_role('tab',name='知识概览',exact=True).click()
 page.set_viewport_size({'width':390,'height':844})
 page.wait_for_timeout(400)
 width=page.evaluate('({scroll:document.documentElement.scrollWidth,view:window.innerWidth})')
 assert width['scroll']<=width['view'],width
 assert not errors,errors
 (root/'studyflow/docs/experiments/workspace-browser-results.json').write_text(json.dumps({'status':'PASS','browser':'Chromium headless','checks':['real authenticated knowledge overview','hybrid retrieval with ranking detail','claim citation original-text highlight','document filename filter','390px mobile layout without horizontal overflow'],'page_errors':errors,'screenshot_data':'24 real sample documents, real BGE/ES/MinIO, extractive mock AI'},ensure_ascii=False,indent=2)+'\n')
 browser.close()
 print('Browser checks and screenshots: PASS')
