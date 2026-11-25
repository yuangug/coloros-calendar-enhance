// Installed only for an explicitly requested import in the current top-level document.
// The adapter receives no Java object, filesystem access or calendar write interface.
(function (token) {
    function connect(event) {
        if (event.data !== token || !event.ports || event.ports.length !== 1) return;
        window.removeEventListener("message", connect);
        const port = event.ports[0], waiting = new Map();
        let next = 0, courses = null, finished = false, slots = false, config = false, combo = false;
        function send(type, fields) { port.postMessage(JSON.stringify(Object.assign({type}, fields))); }
        function ask(type, fields) {
            const id = ++next;
            return new Promise(resolve => { waiting.set(id, resolve); send(type, Object.assign({id}, fields)); });
        }
        port.onmessage = function (event) {
            const answer = JSON.parse(event.data), resolve = waiting.get(answer.id);
            if (resolve) { waiting.delete(answer.id); resolve(answer.value); }
        };
        function parse(value, array) {
            const data = typeof value === "string" ? JSON.parse(value) : value;
            if (!data || typeof data !== "object" || Array.isArray(data) !== array) throw Error("适配器返回的数据格式无效");
            return data;
        }
        const bridge = {
            showAlert: (title, message, confirm) => ask("alert", {title, message, confirm}),
            showSingleSelection: (title, choices, selected = -1) =>
                ask("choose", {title, choices: parse(choices, true), selected}),
            async showPrompt(title, tip, value = "", validator) {
                let error = "";
                for (;;) {
                    value = await ask("prompt", {title, tip: error || tip, value});
                    if (value == null || !validator) return value;
                    const check = typeof validator === "function" ? validator : window[validator];
                    if (typeof check !== "function") throw Error("找不到适配器的输入校验函数");
                    const result = check(value);
                    if (typeof result !== "string" || !result) return value;
                    error = result;
                }
            },
            async saveImportedCourses(raw) {
                const data = parse(raw, true);
                if (!data.length || data.length > 2000 || JSON.stringify(data).length > 400000)
                    throw Error("没有课程或课表数据过大");
                courses = data;
                return true;
            },
            // The preview discloses that the target's semester and bells are retained.
            async savePresetTimeSlots(raw) { parse(raw, true); slots = true; return true; },
            async saveCourseConfig(raw) { parse(raw, false); config = true; return true; },
            async saveComboSchedule(raw) {
                if (!slots) throw Error("缺少基础作息");
                parse(raw, false); combo = true; return true;
            }
        };
        const status = {
            showToast(message) {
                // Some adapters announce a successful DB write while still collecting metadata.
                // No data has been written here. Retain guidance and errors before the preview.
                if (!/成功|已导入|已保存|配置完成|导入完成/.test(String(message))) send("status", {message: String(message).slice(0, 400)});
            },
            notifyTaskCompletion() {
                if (finished) return;
                finished = true;
                if (!courses) { send("error", {message: "拾光未返回课程"}); return; }
                send("done", {courses, slots, config, combo});
            }
        };
        window.shiguangBridgePromise = window.AndroidBridgePromise = bridge;
        window.shiguangBridge = window.AndroidBridge = status;
        send("ready");
    }
    window.addEventListener("message", connect);
    return true;
})
