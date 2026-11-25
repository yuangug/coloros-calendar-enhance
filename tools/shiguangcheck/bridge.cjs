// node bridge.cjs <bridge.js> <pinned zhengfang_01.js>
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const bootstrap = fs.readFileSync(process.argv[2], 'utf8');
const adapter = fs.readFileSync(process.argv[3], 'utf8');
const capture = s => JSON.parse(JSON.stringify(s));
function setup(confirm, validPage = true) {
    const events = [], listeners = {}, win = { location: {href: 'https://fixture.invalid/timetable'} };
    let promptInput = ['bad', '2026'];
    Object.assign(win, {
        window: win, console: { log() {}, warn() {}, error() {} },
        addEventListener: (name, fn) => listeners[name] = fn,
        removeEventListener: name => delete listeners[name],
        fetch: async () => ({ text: async () => validPage ? '课表查询' : '登录' }),
        document: {
            querySelector: sel => sel === '#shcPDF' ? { dataset: {type: 'grid'} } : {},
            querySelectorAll: () => [{
                textContent: '离散数学', classList: {contains: () => true}, getAttribute: () => '7-3',
                querySelectorAll: () => [{ querySelectorAll: s => s === '.title font' ? [{textContent: '离散数学'}] :
                    [['节次', '(3-4节)1-5周(单)'], ['实验楼 301'], ['周老师']].map(txt => ({
                        querySelectorAll: () => txt.map(textContent => ({textContent}))
                    })) }]
            }]
        }
    });
    vm.createContext(win);
    assert.equal(vm.runInContext(bootstrap + '("document-token")', win), true);
    const port = { postMessage(raw) {
        const m = JSON.parse(raw); events.push(m);
        if (!m.id) return;
        const value = m.type === 'alert' ? confirm : m.type === 'prompt' ? promptInput.shift() : null;
        queueMicrotask(() => port.onmessage({data: JSON.stringify({id: m.id, value})}));
    }};
    listeners.message({data: 'other-document', ports: [port]});
    assert.equal(win.shiguangBridgePromise, undefined);
    listeners.message({data: 'document-token', ports: [port]});
    return { win, events, run: () => vm.runInContext(adapter, win) };
}
(async function() {
    const ok = setup(true); await ok.run();
    const done = ok.events.filter(e => e.type === 'done');
    assert.equal(done.length, 1);
    assert.deepEqual(capture(done[0].courses), [{name:'离散数学', day:7, weeks:[1,3,5],
        teacher:'周老师', position:'301', startSection:3, endSection:4}]);
    ok.win.shiguangBridge.notifyTaskCompletion();
    assert.equal(ok.events.filter(e => e.type === 'done').length, 1);
    const cancel = setup(false); await cancel.run();
    assert(!cancel.events.some(e => e.type === 'done'));
    const login = setup(true, false); await login.run();
    assert(login.events.some(e => e.title === '导入失败')); assert(!login.events.some(e => e.type === 'done'));
    const form = setup(true); const api = form.win.shiguangBridgePromise;
    form.win.validateYear = s => /^\d{4}$/.test(s) ? false : '年份格式无效';
    assert.equal(await api.showPrompt('学年', '年份', '', 'validateYear'), '2026');
    assert.equal(form.events.filter(e => e.type === 'prompt')[1].tip, '年份格式无效');
    assert.equal(await api.showSingleSelection('学期', '["秋季"]', 0), null);
    await assert.rejects(api.saveComboSchedule('{}'));
    await api.savePresetTimeSlots('[]'); await api.saveCourseConfig('{}'); await api.saveComboSchedule('{}');
    form.win.shiguangBridge.notifyTaskCompletion();
    assert(form.events.some(e => e.type === 'error')); assert(!form.events.some(e => e.type === 'done'));
    console.log('PASS: upstream adapter, odd weeks, cancellation, login page, prompt validation, selection, metadata, duplicate completion');
})().catch(e => { console.error(e); process.exitCode = 1; });
