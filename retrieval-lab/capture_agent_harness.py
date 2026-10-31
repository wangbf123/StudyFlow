"""Exercise memory/connection/diagnostic UI against a real authenticated demo backend."""
import argparse
import json
import secrets
from datetime import datetime, timezone
from pathlib import Path
from urllib.request import Request, urlopen
from playwright.sync_api import sync_playwright

parser = argparse.ArgumentParser()
parser.add_argument('--session-file', required=True)
parser.add_argument('--api-url', default='http://127.0.0.1:8080')
parser.add_argument('--web-url', default='http://127.0.0.1:5173')
parser.add_argument('--output-dir', required=True)
parser.add_argument('--report', required=True)
parser.add_argument('--chromium-path')
args = parser.parse_args()
fixture = json.loads(Path(args.session_file).read_text())
request = Request(args.api_url.rstrip('/') + '/api/agent/runs/' + fixture['harnessRunId'], headers={'Authorization': fixture['token']})
with urlopen(request, timeout=20) as response:
    run = json.load(response)['data']
out = Path(args.output_dir)
out.mkdir(parents=True, exist_ok=True)
errors = []
with sync_playwright() as p:
    browser = p.chromium.launch(executable_path=args.chromium_path, headless=True, args=['--no-sandbox', '--disable-dev-shm-usage'])
    context = browser.new_context(viewport={'width': 1600, 'height': 1000})
    layout = {'selectedKb': fixture['knowledgeBases'][0]['id'], 'tabs': [fixture['indexNoteId']], 'left': 248, 'right': 410}
    context.add_init_script('localStorage.setItem("studyflow_token",' + json.dumps(fixture['token']) + ');localStorage.setItem(' + json.dumps('studyflow-workspace-' + fixture['user']['userId']) + ',' + json.dumps(json.dumps(layout)) + ');')
    page = context.new_page()
    page.on('pageerror', lambda error: errors.append(str(error)))
    page.goto(args.web_url, wait_until='networkidle')
    page.get_by_role('button', name='Agent 助手', exact=True).click()
    page.locator('select[aria-label="Agent 历史会话"]').select_option(run['sessionId'])
    page.locator('.brain-artifact-head').wait_for(timeout=20000)
    page.locator('.brain-workbench > summary').click()
    title = '浏览器偏好验证 ' + secrets.token_hex(3)
    page.get_by_role('textbox', name='记忆标题').fill(title)
    page.get_by_role('textbox', name='记忆内容').fill('解释复杂概念时先给出例子。')
    page.get_by_role('button', name='保存记忆', exact=True).click()
    card = page.locator('.brain-memory-card').filter(has_text=title)
    card.wait_for()
    card.get_by_role('button', name='忘记', exact=True).click()
    card.wait_for(state='detached')
    page.get_by_role('tab', name='MCP 连接', exact=True).click()
    page.get_by_text('尚未配置 MCP 服务。', exact=False).wait_for()
    page.get_by_role('tab', name='运行详情', exact=True).click()
    page.get_by_text('团队共享调用', exact=False).wait_for()
    assert page.locator('.brain-workbench .brain-memory-card').count() == 3
    assert page.locator('.brain-context-card').count() > 0
    assert page.locator('.brain-model-call').count() > 0
    page.locator('.brain-workbench').evaluate('(el) => { const pane = el.closest(".brain-context-scroll"); pane.scrollBy(0, el.getBoundingClientRect().top - pane.getBoundingClientRect().top - 14) }')
    page.wait_for_timeout(200)
    page.screenshot(path=str(out / 'brain-harness.png'), animations='disabled')
    page.set_viewport_size({'width': 390, 'height': 844})
    page.wait_for_timeout(300)
    assert page.evaluate('document.documentElement.scrollWidth <= innerWidth'), 'Mobile horizontal overflow'
    report = {'date': datetime.now(timezone.utc).date().isoformat(), 'memoryCreateAndForget': 'PASS',
        'unconfiguredMcpState': 'PASS', 'teamDiagnostics': 'PASS', 'contextManifests': 'PASS',
        'modelTraceDisplay': 'PASS', 'mobileOverflow': False, 'pageErrors': errors}
    Path(args.report).write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    browser.close()
    print(json.dumps(report, ensure_ascii=False))
    assert not errors, errors
