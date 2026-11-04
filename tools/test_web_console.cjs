// Browser contract test with a simulated phone. Requires playwright; no Android device.
// Run: node tools/test_web_console.cjs. Set PHONEUSE_BROWSER_CHANNEL=msedge when needed.
const { chromium } = require('playwright');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.join(__dirname, '../app/src/main/assets/web');
let failNextAction = false;
let nextResult = null;
let pairingState = 'pending', revoked = false, calls = [], polls = 0, pairRequests = 0, sessionRequests = 0, offline = false;
const tool = (name, required = [], properties = {}) => ({ name, description: name, inputSchema: { type: 'object', required, properties } });
const catalog = [tool('get_capabilities'), tool('get_status', [], { request_id: { type: 'string' } }), tool('launch_app', ['request_id', 'package_name'], { request_id: { type: 'string' }, package_name: { type: 'string', description: 'Android package name' }, observe_after: { type: 'boolean' }, observation_options: { type: 'object' } }), tool('home', ['request_id'], {
  request_id: { type: 'string' }
})];
const server = http.createServer(async (req, res) => {
  res.setHeader('Content-Security-Policy', "default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; connect-src 'self'; img-src data:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
  if (['/', '/app.js', '/i18n.js'].includes(req.url)) {
    res.setHeader('Content-Type', req.url === '/' ? 'text/html; charset=utf-8' : 'text/javascript');
    res.end(fs.readFileSync(path.join(root, req.url === '/' ? 'index.html' : req.url.slice(1)))); return;
  }
  res.setHeader('Content-Type', 'application/json');
  let body = ''; for await (const chunk of req) body += chunk;
  const payload = body ? JSON.parse(body) : {};
  const reply = value => res.end(JSON.stringify(value));
  const cookieName = `phoneuse_http_client_${server.address().port}`;
  const cookie = token => res.setHeader('Set-Cookie', `${cookieName}=${token}; Path=/; HttpOnly; SameSite=Strict; Max-Age=${token ? 34560000 : 0}`);
  const authorized = !revoked && (req.headers.cookie || '').includes(`${cookieName}=test-token`);
  if (offline) { res.statusCode = 503; return reply({ error: 'Service restarting' }); }
  if (req.url === '/browser/session') {
    sessionRequests++;
    if (req.method === 'POST' && req.headers.authorization === 'Bearer legacy-token') {
      cookie('test-token'); return reply({ paired: true, client_id: 'test-client' });
    }
    return reply({ paired: authorized, client_id: authorized ? 'test-client' : null });
  }
  if (req.url === '/browser/pair/request') {
    pairRequests++;
    res.statusCode = 202;
    return reply({ pairing_id: 'test-id', pairing_secret: 'test-secret', verification_code: '123456', expires_in: 120, poll_interval: 2 });
  }
  if (req.url === '/browser/pair/status') {
    assert.equal(payload.pairing_secret, 'test-secret'); polls++;
    if (pairingState === 'approved') cookie('test-token');
    return reply({ status: pairingState, ...(pairingState === 'approved' ? {
      client_id: 'test-client'
    } : {}) });
  }
  if (!authorized) {
    res.statusCode = 401; return reply({ error: 'Invalid or revoked token' });
  }
  if (req.url === '/browser/forget') { cookie(''); return reply({ paired: false }); }
  if (req.url === '/api/tools') return reply({ tools: catalog });
  if (req.url === '/api') {
    calls.push(payload);
    if (nextResult) { const result = nextResult; nextResult = null; return reply(result); }
    if (payload.tool === 'get_status') return reply({ ...(payload.arguments.request_id ? { request: { state: 'executed' } } : {}) });
    if (failNextAction) { failNextAction = false; return reply({ state: 'failed', error: { code: 'ACTION_REJECTED', message: 'Action rejected' } }); }
    return reply({ state: 'executed', request_id: payload.arguments.request_id, available: true });
  }
  res.statusCode = 404; reply({ error: 'Unknown endpoint' });
});
(async () => {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  let browser;
  try {
    browser = await chromium.launch({ headless: true, args: ['--host-resolver-rules=MAP phoneuse.test 127.0.0.1', '--no-proxy-server'], ...(process.env.PHONEUSE_BROWSER_CHANNEL ? { channel: process.env.PHONEUSE_BROWSER_CHANNEL } : {}) });
    let context = await browser.newContext({ locale: 'zh-CN', viewport: { width: 1280, height: 1000 } });
    let page = await context.newPage();
    const errors = []; page.on('pageerror', e => errors.push(e.message));
    // A non-loopback HTTP origin exposes the same secure-context limitations as LAN IP access.
    await page.goto(`http://phoneuse.test:${server.address().port}/`);
    assert.equal(await page.evaluate(() => window.isSecureContext), false);
    assert.equal(await page.title(), 'Phone Use · API');
    assert.equal(await page.locator('#run').isDisabled(), true);
    await page.locator('#pair').click();
    await page.waitForFunction(() => document.getElementById('verification').textContent === '123456');
    assert.equal(await page.locator('#run').isDisabled(), true);
    await page.reload(); // Pending request is restored, not recreated.
    await page.waitForFunction(() => document.getElementById('verification').textContent === '123456');
    // Returning from the phone app (or coming online) must not start a session check
    // while approval is being polled, even if that check would return after approval.
    const sessionsBeforeApproval = sessionRequests;
    let releaseSessionChecks;
    const sessionGate = new Promise(resolve => { releaseSessionChecks = resolve; });
    let concurrentSessionChecks = 0;
    await page.route('**/browser/session', async route => {
      concurrentSessionChecks++;
      await sessionGate;
      await route.fulfill({ json: { paired: false, client_id: null } });
    });
    await page.evaluate(() => {
      window.dispatchEvent(new Event('focus'));
      window.dispatchEvent(new Event('online'));
    });
    pairingState = 'approved';
    await page.waitForFunction(() => document.getElementById('connection').textContent === '已配对');
    releaseSessionChecks();
    await page.unrouteAll({ behavior: 'wait' });
    assert.equal(concurrentSessionChecks, 0, 'session checks must wait for pairing to finish');
    assert.equal(sessionRequests, sessionsBeforeApproval);
    await page.waitForFunction(() => !document.getElementById('run').disabled);
    assert.equal(calls.length, 0); // Pairing only loads the catalog; no automatic tool calls.
    await page.reload();
    await page.waitForFunction(() => !document.getElementById('run').disabled);
    const sessionsAfterReload = sessionRequests;
    await page.evaluate(() => window.dispatchEvent(new Event('focus')));
    await page.waitForFunction(() => !document.getElementById('run').disabled);
    assert.equal(sessionRequests, sessionsAfterReload + 1, 'session checks resume after pairing');
    assert.equal(await page.evaluate(() => document.cookie), '');
    assert.equal(await page.evaluate(() => sessionStorage.getItem('phoneuse.credential')), null);
    assert.equal((await context.cookies())[0].httpOnly, true);
    // Simulate browser restart: restore persisted cookies into a new browser process.
    const savedState = await context.storageState();
    await browser.close();
    browser = await chromium.launch({ headless: true, args: ['--host-resolver-rules=MAP phoneuse.test 127.0.0.1', '--no-proxy-server'], ...(process.env.PHONEUSE_BROWSER_CHANNEL ? { channel: process.env.PHONEUSE_BROWSER_CHANNEL } : {}) });
    context = await browser.newContext({ locale: 'zh-CN', storageState: savedState, viewport: { width: 1280, height: 1000 } });
    page = await context.newPage(); page.on('pageerror', e => errors.push(e.message));
    await page.goto(`http://phoneuse.test:${server.address().port}/`);
    await page.waitForFunction(() => !document.getElementById('run').disabled);
    assert.equal(pairRequests, 1);
    offline = true; await page.reload();
    await page.waitForFunction(() => document.getElementById('pair-status').textContent.includes('等待服务恢复'));
    offline = false;
    await page.waitForFunction(() => !document.getElementById('run').disabled);
    assert.equal(pairRequests, 1);
    await page.locator('#search').fill('home');
    assert.equal(await page.locator('.tool-item').count(), 1);
    await page.locator('[data-tool="home"]').click();
    await page.locator('#search').fill('');
    await page.locator('#run').click();
    await page.waitForFunction(() => !document.getElementById('run').disabled);
    const first = calls.at(-1);
    assert.equal('lease_id' in first.arguments, false); assert.ok(first.arguments.request_id);
    await page.locator('#run').click();
    await page.waitForFunction(() => !document.getElementById('run').disabled);
    assert.equal(calls.at(-1).arguments.request_id, first.arguments.request_id);
    await page.locator('#new-id').click();
    assert.notEqual(JSON.parse(await page.locator('#arguments').inputValue()).request_id, first.arguments.request_id);
    await page.locator('[data-tool="get_status"]').click();
    await page.locator('#param-request_id').fill(first.arguments.request_id);
    await page.locator('#run').click();
    await page.waitForFunction(() => !document.getElementById('run').disabled);
    assert.equal(calls.at(-1).arguments.request_id, first.arguments.request_id);
    await page.locator('#integration > summary').click();
    assert.match(await page.locator('#endpoints').textContent(), /mcp-stdio --url/);
    assert.equal(await page.locator('#export').count(), 0);
    await page.locator('[data-tool="launch_app"]').click();
    await page.locator('#param-package_name').fill('ai.example.app');
    await page.locator('#run').click();
    await page.waitForFunction(() => !document.getElementById('run').disabled);
    assert.equal(calls.at(-1).arguments.package_name, 'ai.example.app');
    assert.equal(calls.at(-1).arguments.observe_after, undefined);
    assert.match(await page.locator('#result-status').textContent(), /已返回.*executed/);
    assert.doesNotMatch(await page.locator('#result-status').textContent(), /观察未完成/);
    failNextAction = true; await page.locator('#new-id').click(); await page.locator('#run').click();
    await page.waitForFunction(() => !document.getElementById('run').disabled);
    assert.match(await page.locator('#notice').textContent(), /Action rejected/);
    assert.match(await page.locator('#result-status').textContent(), /操作失败.*Action rejected/);
    // The schema-driven form accepts the same observation options as direct API.
    await page.locator('#param-observe_after').selectOption('true');
    await page.locator('#param-observation_options').fill(JSON.stringify({ timeout_ms: 60000, wait_until: { package_name: 'ai.example.app' } }));
    const cases = [
      [{ state: 'accepted', action_executed: false }, /仍在排队.*原 request_id/],
      [{ state: 'executing', action_executed: true, observation_status: 'observing' }, /动作已执行，正在等待观察.*原 request_id/],
      [{ state: 'executing', action_executed: false, observation_status: 'observing', observation_purpose: 'after_rejection' }, /动作未执行，正在读取恢复观察.*原 request_id/],
      [{ state: 'executed', action_executed: true, observation_status: 'condition_timeout', observation: { condition: { status: 'unsatisfied', timed_out: true } } }, /动作已执行；观察未完成（条件未满足）/],
      [{ state: 'executed', action_executed: true, observation: { condition: { status: 'ambiguous', timed_out: true } } }, /目标匹配有歧义/],
      [{ state: 'executed', action_executed: true, observation: { condition: { status: 'unknown' }, last_observation: { condition: { status: 'satisfied' } } } }, /当前条件未知/],
      [{ state: 'executed', action_executed: true, observation: { observation_status: 'complete', screenshot: { available: false } } }, /截图不可用/],
      [{ state: 'executed', action_executed: true, observation: { observation_status: 'failed', error: { code: 'OBSERVATION_INCONSISTENT' } } }, /观察采集失败/],
      [{ state: 'unknown', action_executed: null }, /动作结果未知.*请勿重放/],
      [{ state: 'unknown', action_executed: null, error: { code: 'RESULT_UNKNOWN', reason: 'launch_not_confirmed' } }, /5 秒内未确认.*后台弹出界面.*启动请求已经发出/],
      [{ state: 'failed', action_executed: false, error: { code: 'ACTION_REJECTED', reason: 'launch_permission_denied' } }, /系统拒绝.*后台启动应用权限/]
    ];
    for (const [result, expected] of cases) {
      const before = calls.length;
      nextResult = result;
      await page.locator('#new-id').click(); await page.locator('#run').click();
      await page.waitForFunction(() => !document.getElementById('run').disabled);
      assert.match(await page.locator('#result-status').textContent(), expected);
      assert.equal(calls.length, before + 1); // No automatic replay or substitute action.
      assert.equal(calls.at(-1).arguments.observation_options.timeout_ms, 60000);
    }
    // Status polls can contain a rejected action, failed recovery and a historical image.
    await page.locator('[data-tool="get_status"]').click();
    await page.locator('#param-request_id').fill(first.arguments.request_id);
    const screenshotData = Buffer.from('nested-image').toString('base64');
    nextResult = { request: { state: 'failed', action_executed: false,
      error: { code: 'STALE_ELEMENT', message: 'Target changed' },
      recovery: { category: 'refresh_observation', advisory: true, replay_action: false },
      recovery_observation: { observation_status: 'failed', condition: { status: 'unknown' },
        last_observation: { captured_at_ms: 123, screenshot: { available: true, mime_type: 'image/jpeg', data_base64: screenshotData } } } } };
    await page.locator('#run').click(); await page.waitForFunction(() => !document.getElementById('run').disabled);
    assert.match(await page.locator('#result-status').textContent(), /操作失败.*恢复观察未完成.*重新观察/);
    assert.equal(await page.locator('#image-result').isHidden(), false);
    assert.equal(await page.locator('#screenshot').getAttribute('src'), `data:image/jpeg;base64,${screenshotData}`);
    const shownResult = JSON.parse(await page.locator('#result').textContent());
    assert.equal(shownResult.screenshot_display_source, 'request.recovery_observation.last_observation.screenshot');
    assert.equal(shownResult.request.recovery_observation.condition.status, 'unknown');
    assert.ok(!(await page.locator('#result').textContent()).includes(screenshotData));
    const callsBeforeFocus = calls.length;
    await page.evaluate(() => window.dispatchEvent(new Event('focus')));
    await page.waitForFunction(() => !document.getElementById('run').disabled);
    assert.equal(calls.length, callsBeforeFocus);
    await page.locator('[data-tool="launch_app"]').click();
    await page.locator('#param-package_name').fill('ai.example.app');
    await page.locator('#new-id').click(); await page.locator('#run').click();
    await page.waitForFunction(() => !document.getElementById('run').disabled);
    const reportDir = path.join(__dirname, '../app/build/reports'); fs.mkdirSync(reportDir, { recursive: true });
    await page.locator('#integration > summary').click();
    await page.screenshot({ path: path.join(reportDir, 'web-console-desktop.png'), fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    await page.screenshot({ path: path.join(reportDir, 'web-console-mobile.png'), fullPage: true });
    await page.locator('#forget').click();
    await page.waitForFunction(() => document.getElementById('connection').textContent === '尚未配对');
    assert.equal(await page.locator('#image-result').isHidden(), true);
    await page.locator('#pair').click();
    await page.waitForFunction(() => !document.getElementById('run').disabled);
    revoked = true;
    await page.locator('#run').click();
    await page.waitForFunction(() => document.getElementById('connection').textContent === '尚未配对');
    assert.equal(await page.locator('#run').isDisabled(), true);
    pairingState = 'denied'; revoked = false;
    await page.locator('#pair').click();
    await page.waitForFunction(() => document.getElementById('notice').textContent.includes('拒绝'));
    assert.equal(await page.locator('#pair').isEnabled(), true);
    // Upgrade an old open tab without asking the phone to approve again.
    await page.evaluate(() => sessionStorage.setItem('phoneuse.credential', JSON.stringify({ token: 'legacy-token' })));
    const beforeMigration = pairRequests;
    await page.reload();
    await page.waitForFunction(() => !document.getElementById('run').disabled);
    assert.equal(pairRequests, beforeMigration);
    assert.equal(await page.evaluate(() => sessionStorage.getItem('phoneuse.credential')), null);
    await page.locator('#forget').click();
    await page.waitForFunction(() => document.getElementById('connection').textContent === '尚未配对');
    await page.evaluate(() => sessionStorage.setItem('phoneuse.pairing', JSON.stringify({
      pairing_id: 'test-id', pairing_secret: 'test-secret', verification_code: '123456', deadline: Date.now() - 1
    })));
    await page.reload();
    await page.waitForFunction(() => document.getElementById('notice').textContent.includes('过期'));
    assert.equal(await page.locator('#pair').isEnabled(), true);
    assert.deepEqual(errors, []);
    await context.close();
    // Browser locale selects UI resources without changing API names, payloads or pairing.
    for (const locale of ['en-US', 'de-DE']) {
      context = await browser.newContext({ locale, viewport: { width: 1280, height: 1000 } });
      page = await context.newPage(); page.on('pageerror', e => errors.push(e.message));
      await page.goto(`http://phoneuse.test:${server.address().port}/`);
      await page.waitForFunction(() => !document.getElementById('pair').disabled);
      assert.equal(await page.locator('html').getAttribute('lang'), 'en');
      assert.equal(await page.locator('#pair').textContent(), 'Request pairing');
      assert.equal(await page.locator('#search').getAttribute('placeholder'), 'Search names or descriptions');
      assert.equal(await page.locator('#client-name').getAttribute('aria-label'), 'Client name');
      if (locale === 'en-US') {
        pairingState = 'approved';
        await page.locator('#pair').click();
        await page.waitForFunction(() => !document.getElementById('run').disabled);
        assert.equal(await page.locator('#connection').textContent(), 'Paired');
        await page.locator('[data-tool="launch_app"]').click();
        await page.locator('#param-package_name').fill('ai.example.app');
        assert.equal(await page.locator('#param-package_name').getAttribute('placeholder'), 'Required · string');
        nextResult = { state: 'failed', error: { code: 'CANCELLED', message: 'Cancelled' } };
        await page.locator('#run').click();
        await page.waitForFunction(() => !document.getElementById('run').disabled);
        assert.match(await page.locator('#result-status').textContent(), /Operation failed: The operation was cancelled/);
        assert.equal(calls.at(-1).tool, 'launch_app');
        assert.equal(calls.at(-1).arguments.package_name, 'ai.example.app');
        assert.ok(calls.at(-1).arguments.request_id);
        assert.doesNotMatch(await page.locator('body').innerText(), /[\p{Script=Han}]/u);
        await page.screenshot({ path: path.join(reportDir, 'web-console-english-desktop.png'), fullPage: true });
        await page.setViewportSize({ width: 390, height: 844 });
        assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
        await page.screenshot({ path: path.join(reportDir, 'web-console-english-mobile.png'), fullPage: true });
        await page.locator('#forget').click();
        await page.waitForFunction(() => document.getElementById('connection').textContent === 'Not paired');
      }
      await context.close();
    }
    assert.deepEqual(errors, []);
    console.log(`PASS: Chinese/English UI and language fallback, HttpOnly pairing, browser restart, reconnect, forms, action IDs, conditional progress, nested recovery images, no replay, unpair/revocation/expiry, responsive layout and CSP; ${polls} polls; no page errors.`);
  } finally { if (browser) await browser.close(); server.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
