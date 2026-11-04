'use strict';
const { t } = PhoneUseI18n;
const $ = id => document.getElementById(id);
// Use Web Crypto randomness for request IDs.
function newRequestId() {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 15) | 64;
  bytes[8] = (bytes[8] & 63) | 128;
  const hex = Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
let selectedTool = '';
let credential = null, pending = null, tools = [], busy = false, generation = 0, reconnectTimer;
let pairingPollActive = false;
try {
  pending = JSON.parse(sessionStorage.getItem('phoneuse.pairing') || 'null');
} catch { sessionStorage.removeItem('phoneuse.pairing'); }
$('endpoints').textContent = t('endpoints', { origin: location.origin });
const messages = {
  CANCELLED: t('error_cancelled'),
  UNAUTHORIZED: t('error_unauthorized'),
  STALE_OBSERVATION: t('error_stale_observation')
};
function notice(error) { $('notice').textContent = error ? messages[error.code] || error.message : ''; }
function controls() {
  $('connection').textContent = credential ? t('paired') : pending ? t('awaiting_approval') : t('unpaired');
  $('pair').disabled = !!credential || !!pending || busy;
  $('pair-form').hidden = !!credential;
  $('forget').disabled = !credential || busy;
  $('run').disabled = !credential || !selectedTool || busy;
  $('new-id').disabled = !credential || busy || !tools.find(t => t.name === selectedTool)?.inputSchema.required?.includes('request_id');
  for (const field of $('fields').querySelectorAll('input, textarea, select')) field.disabled = busy || !credential;
  for (const button of $('tool-list').querySelectorAll('button')) button.disabled = busy;
}
async function request(path, payload, legacyToken) {
  const headers = {};
  if (payload !== undefined) headers['Content-Type'] = 'application/json';
  if (legacyToken) headers.Authorization = `Bearer ${legacyToken}`;
  const response = await fetch(path, { method: payload === undefined ? 'GET' : 'POST', headers,
    credentials: 'same-origin', body: payload === undefined ? undefined : JSON.stringify(payload), signal: AbortSignal.timeout(payload?.tool === 'observe' ? 90000 : 30000) });
  const value = await response.json();
  if (response.status === 401) {
    credential = null; clearResult(); sessionStorage.removeItem('phoneuse.credential'); controls();
    $('pair-status').textContent = t('pairing_revoked');
  }
  if (!response.ok) throw new Error(response.status === 401 ? t('authorization_expired') : value.error?.message || value.error || `HTTP ${response.status}`);
  return value;
}
const labels = {
  get_capabilities: t('tool_get_capabilities'), get_status: t('tool_get_status'),
  cancel: t('tool_cancel'), observe: t('tool_observe'), list_apps: t('tool_list_apps'), wait_for_change: t('tool_wait_for_change'),
  tap: t('tool_tap'), long_press: t('tool_long_press'), swipe: t('tool_swipe'), scroll: t('tool_scroll'), input_text: t('tool_input_text'),
  back: t('tool_back'), home: t('tool_home'), recents: t('tool_recents'), launch_app: t('tool_launch_app')
};
function renderList() {
  const query = $('search').value.trim().toLowerCase();
  const matches = tools.filter(t => (t.name + ' ' + t.description + ' ' + (labels[t.name] || '')).toLowerCase().includes(query));
  $('tool-list').replaceChildren(...matches.map(tool => {
    const button = document.createElement('button'); button.className = 'tool-item';
    button.dataset.tool = tool.name; button.setAttribute('aria-pressed', String(selectedTool === tool.name));
    const name = document.createElement('strong'); name.textContent = tool.name;
    const label = document.createElement('small'); label.textContent = labels[tool.name] || tool.description;
    button.append(name, label); button.onclick = () => selectTool(tool.name); return button;
  }));
  if (!matches.length) $('tool-list').textContent = tools.length ? t('no_matches') : t('load_after_pairing');
  controls();
}
function readArguments() {
  const args = JSON.parse($('arguments').value);
  if (!args || Array.isArray(args) || typeof args !== 'object') throw new Error(t('arguments_object'));
  return args;
}
function renderFields() {
  const tool = tools.find(t => t.name === selectedTool);
  if (!tool) return;
  const args = readArguments(), required = tool.inputSchema.required || [];
  const fields = Object.entries(tool.inputSchema.properties).filter(([key]) => !(key === 'request_id' && required.includes(key)));
  $('fields').replaceChildren(...fields.map(([key, schema]) => {
    const wrapper = document.createElement('div'); wrapper.className = 'field';
    const label = document.createElement('label'); label.htmlFor = 'param-' + key; label.textContent = key + (required.includes(key) ? ' *' : '');
    let input;
    if (schema.type === 'boolean') {
      input = document.createElement('select'); input.append(new Option(t('omit_value'), ''), new Option('true', 'true'), new Option('false', 'false'));
    } else {
      input = document.createElement(['object', 'array'].includes(schema.type) ? 'textarea' : 'input');
      if (schema.type === 'number' || schema.type === 'integer') { input.type = 'number'; input.step = schema.type === 'integer' ? '1' : 'any'; }
    }
    input.id = 'param-' + key;
    input.value = args[key] === undefined ? '' : typeof args[key] === 'object' ? JSON.stringify(args[key]) : String(args[key]);
    input.placeholder = t(required.includes(key) ? 'required_type' : 'optional_type', { type: schema.type });
    input.oninput = () => {
      try {
        const value = readArguments();
        if (input.value === '' && !required.includes(key)) delete value[key];
        else value[key] = schema.type === 'string' ? input.value : JSON.parse(input.value);
        $('arguments').value = JSON.stringify(value, null, 2); input.setCustomValidity(''); notice(null);
      } catch { input.setCustomValidity(t('invalid_type', { type: schema.type })); }
    };
    const help = document.createElement('small'); help.textContent = (schema.description || schema.type) +
      (schema.default === undefined ? '' : t('default_value', { value: schema.default }));
    wrapper.append(label, input, help); return wrapper;
  }));
  $('no-fields').hidden = fields.length !== 0;
  controls();
}
function selectTool(name) {
  selectedTool = name;
  const tool = tools.find(t => t.name === name);
  if (!tool) return;
  $('tool-title').textContent = tool.name;
  $('description').textContent = tool.description;
  $('schema').textContent = JSON.stringify(tool.inputSchema, null, 2);
  const args = {};
  for (const key of tool.inputSchema.required || []) {
    if (key === 'request_id') continue;
    const field = tool.inputSchema.properties[key];
    args[key] = field.type === 'string' ? '' : field.type === 'object' ? {} : field.type === 'array' ? [] : field.type === 'boolean' ? false : 0;
  }
  if (name === 'observe') args.mode = 'tree';
  $('arguments').value = JSON.stringify(args, null, 2);
  renderList(); renderFields();
}
async function loadTools() {
  tools = (await request('/api/tools')).tools;
  if (tools.some(t => t.name === selectedTool)) { renderList(); renderFields(); }
  else if (tools.length) selectTool(tools[0].name);
}
async function pollPairing() {
  if (pairingPollActive) return;
  pairingPollActive = true;
  const current = ++generation;
  $('verification').textContent = pending.verification_code;
  $('pair-status').textContent = t('pairing_instructions');
  controls();
  try {
    while (pending && current === generation) {
      if (Date.now() >= pending.deadline) throw new Error(t('pairing_expired'));
      await new Promise(resolve => setTimeout(resolve, 2000));
      if (current !== generation) return;
      const value = await request('/browser/pair/status', { pairing_id: pending.pairing_id, pairing_secret: pending.pairing_secret });
      if (value.status === 'denied') throw new Error(t('pairing_denied'));
      if (value.status === 'approved') {
        credential = { client_id: value.client_id };
        $('pair-status').textContent = t('browser_remembered');
        break;
      }
    }
  } catch (error) { notice(error); }
  finally { pairingPollActive = false; pending = null; sessionStorage.removeItem('phoneuse.pairing'); $('verification').textContent = ''; controls(); }
  if (credential) await connect();
}
$('pair').onclick = async () => {
  busy = true; notice(null); controls();
  try {
    pending = await request('/browser/pair/request', { client_name: $('client-name').value.trim() });
    pending.deadline = Date.now() + pending.expires_in * 1000;
    sessionStorage.setItem('phoneuse.pairing', JSON.stringify(pending));
    void pollPairing();
  } catch (error) { notice(error); }
  finally { busy = false; controls(); }
};
function resultObjects(value, path = []) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return [];
  return [{ value, path }, ...['request', 'observation', 'recovery_observation', 'last_observation']
    .flatMap(key => resultObjects(value[key], [...path, key]))];
}
function activeObservations(value) {
  return [
    ...(['observation_id', 'tree', 'screenshot', 'condition'].some(key => key in value) ? [value] : []),
    value.observation, value.recovery_observation
  ].filter(item => item && typeof item === 'object');
}
function observationIncomplete(value) {
  return ['observing', 'failed', 'condition_timeout'].includes(value.observation_status) || activeObservations(value).some(item =>
    item.error || ['observing', 'failed', 'condition_timeout'].includes(item.observation_status) ||
    (item.condition && (item.condition.status !== 'satisfied' || item.condition.timed_out)) ||
    item.tree?.available === false || item.screenshot?.available === false);
}
const recoveryLabels = {
  refresh_observation: t('recovery_refresh_observation'),
  wait_original_request: t('recovery_wait_original_request'),
  reconcile_result: t('recovery_reconcile_result'),
  choose_explicit_target: t('recovery_choose_explicit_target')
};
function resultStatus(value) {
  const result = !value.error && value.request && typeof value.request === 'object' ? value.request : value;
  if (result.state === 'accepted') return t('result_accepted');
  if (result.state === 'executing') {
    const progress = result.observation_purpose === 'after_rejection' && result.action_executed === false ? t('progress_recovery') : result.action_executed === true ? t('progress_observing') : t('progress_executing');
    return t('result_executing', { progress });
  }
  if (result.error?.reason === 'launch_not_confirmed') return t('launch_not_confirmed');
  if (result.error?.reason === 'launch_permission_denied') return t('launch_permission_denied');
  if (result.state === 'unknown') return t('result_unknown');
  const recovery = recoveryLabels[result.recovery?.category];
  const hint = recovery ? t('recovery_hint', { recovery }) : '';
  if (result.error) return t('operation_failed', { message: messages[result.error.code] || result.error.message }) +
    (result.recovery_observation ? (observationIncomplete(result.recovery_observation) ? t('recovery_incomplete') : t('recovery_attached')) : '') + hint;
  if (observationIncomplete(result)) {
    const conditions = activeObservations(result).map(item => item.condition).filter(Boolean);
    const detail = conditions.some(item => item.status === 'unknown') ? t('condition_unknown') : conditions.some(item => item.status === 'ambiguous') ? t('condition_ambiguous') : conditions.some(item => item.status !== 'satisfied') ? t('condition_unsatisfied') : activeObservations(result).some(item => item.error) ? t('observation_failed') : t('observation_unavailable');
    return t('observation_incomplete', { prefix: result.action_executed === true || result.state === 'executed' ? t('action_executed_prefix') : '', detail }) + hint;
  }
  return t('result_returned', { state: result.state || t('completed') }) + hint;
}
function showResult(value) {
  const copy = JSON.parse(JSON.stringify(value));
  const observations = resultObjects(copy);
  const candidates = observations.filter(item => item.value.screenshot?.available && item.value.screenshot.mime_type === 'image/jpeg' && item.value.screenshot.data_base64);
  candidates.sort((a, b) => (b.value.captured_at_ms || 0) - (a.value.captured_at_ms || 0));
  const selected = candidates[0];
  const shot = selected?.value.screenshot;
  $('image-result').hidden = true; $('image-result').open = false; $('screenshot').removeAttribute('src');
  if (shot) {
    $('screenshot').src = `data:image/jpeg;base64,${shot.data_base64}`;
    $('image-result').hidden = false;
    copy.screenshot_display_source = [...selected.path, 'screenshot'].join('.');
  }
  for (const item of observations) if (item.value.screenshot?.data_base64) item.value.screenshot.data_base64 = item.value.screenshot === shot ? t('screenshot_attached') : t('screenshot_omitted');
  $('result').textContent = JSON.stringify(copy, null, 2);
  $('result-status').textContent = resultStatus(value);
}
async function call(tool, args) {
  const result = await request('/api', { tool, arguments: args });
  showResult(result);
  if (result.error) {
    throw Object.assign(new Error(result.error.message), { code: result.error.code, state: result.state, resultShown: true });
  }
  return result;
}
async function execute(work) {
  if (busy) return;
  busy = true; notice(null); controls(); $('result-status').textContent = t('processing');
  try { await work(); } catch (error) {
    notice(error);
    if (!error.resultShown) $('result-status').textContent = t('call_failed');
  } finally { busy = false; controls(); }
}
$('search').oninput = renderList;
$('arguments').onchange = () => { try { renderFields(); notice(null); } catch (error) { notice(error); } };

$('run').onclick = () => execute(async () => {
  for (const input of $('fields').querySelectorAll('input, textarea, select')) if (!input.validity.valid) throw new Error(t('invalid_parameter', { field: input.id }));
  const args = readArguments();
  const schema = tools.find(t => t.name === selectedTool).inputSchema;
  if (schema.required?.includes('request_id') && !args.request_id) args.request_id = newRequestId();
  $('arguments').value = JSON.stringify(args, null, 2);
  await call(selectedTool, args);
});
$('new-id').onclick = () => {
  try {
    const args = readArguments();
    if (!tools.find(t => t.name === selectedTool)?.inputSchema.required?.includes('request_id')) throw new Error(t('no_action_id'));
    args.request_id = newRequestId();
    $('arguments').value = JSON.stringify(args, null, 2); notice(null);
  } catch (error) { notice(error); }
};
$('forget').onclick = () => execute(async () => {
  await request('/browser/forget', {});
  credential = null; sessionStorage.removeItem('phoneuse.credential');
  $('pair-status').textContent = t('browser_unpaired');
  $('result').textContent = '{}'; clearResult(); controls();
});
controls();
void restoreConnection();
window.addEventListener('online', () => void restoreConnection());
window.addEventListener('focus', () => void restoreConnection());

function clearResult() {
  $('image-result').hidden = true; $('screenshot').removeAttribute('src'); $('result').textContent = '{}';
  selectedTool = ''; tools = []; $('arguments').value = '{}'; $('fields').replaceChildren();
  $('schema').textContent = t('not_loaded'); $('tool-title').textContent = t('select_api');
  $('description').textContent = t('select_api_help'); $('no-fields').hidden = true; renderList();
}
async function connect() {
  $('pair-status').textContent = t('browser_persistent');
  await execute(loadTools);
}
async function restoreConnection() {
  // Focus/online events must not race approval with an older, unpaired session response.
  // A reloaded tab can still check its cookie before resuming the saved pairing request.
  if (busy || pairingPollActive) return;
  clearTimeout(reconnectTimer);
  busy = true; controls();
  let restored = false;
  try {
    // Upgrade an existing tab in place, then discard its JavaScript-readable token.
    let legacy = null;
    try { legacy = JSON.parse(sessionStorage.getItem('phoneuse.credential') || 'null'); }
    catch { sessionStorage.removeItem('phoneuse.credential'); }
    const value = legacy?.token
      ? await request('/browser/session', {}, legacy.token)
      : await request('/browser/session');
    sessionStorage.removeItem('phoneuse.credential');
    notice(null);
    const wasPaired = !!credential;
    credential = value.paired ? { client_id: value.client_id } : null;
    if (credential) {
      if (pending) {
        generation++; pending = null; sessionStorage.removeItem('phoneuse.pairing'); $('verification').textContent = '';
      }
      await loadTools();
      $('pair-status').textContent = t('auto_connected');
    } else if (!pending) {
      clearResult();
      $('pair-status').textContent = wasPaired ? t('pair_again') : t('pairing_first_time');
    }
    restored = true;
  } catch (error) {
    notice(error);
    $('pair-status').textContent = t('reconnecting');
    reconnectTimer = setTimeout(() => void restoreConnection(), 5000);
  } finally { busy = false; controls(); }
  if (restored && pending && !credential && !$('verification').textContent) void pollPairing();
}
