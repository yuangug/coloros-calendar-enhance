// Local adapter. Read only the selected school's timetable and teaching calendar.
(function (token) {
    const home = 'https://xgjwxt.xit.edu.cn';
    if (location.origin !== home) return false;
    const text = el => (el?.textContent || '').replace(/\s+/g, ' ').trim();
    const termOf = doc => doc.querySelector('select[name="xnxq01id"],#xnxq01id,input[name="xnxq"]')?.value || '';
    const cleanTerm = s => /^\d{4}-\d{4}-[12]$/.test(s);
    async function read(path, params) {
        const controller = new AbortController(), timer = setTimeout(() => controller.abort(), 8000);
        try {
            const r = await fetch(path, {method:params ? 'POST' : 'GET', body:params, credentials:'same-origin',
                redirect:'error', signal:controller.signal});
            if (!r.ok || new URL(r.url).origin !== home) throw Error();
            const html = await r.text();
            if (html.length > 1800000) throw Error();
            return new DOMParser().parseFromString(html,'text/html');
        } finally { clearTimeout(timer); }
    }
    function dates(doc, term) {
        if (termOf(doc) && termOf(doc) !== term) return [];
        for (const t of doc.querySelectorAll('table')) {
            const result = [];
            let columns = null;
            for (const row of t.rows) {
                const cells = Array.from(row.cells, text);
                const days = cells.map(v => /^(?:星期|周)([一二三四五六日天])$/.exec(v));
                if (days.filter(Boolean).length === 7) {
                    columns = days.map(v => v ? '一二三四五六日'.indexOf(v[1].replace('天','日')) : -1);
                    continue;
                }
                const week = /^(?:第\s*)?(\d{1,2})\s*周?$/.exec(cells[0] || '');
                if (!week || cells.length !== 8) continue;
                for (let col=1; col<8; col++) {
                    const day = columns ? columns[col] : col-1;
                    const m = /^(?:(\d{4})\s*[-/.年]\s*)?(\d{1,2})\s*[-/.月]\s*(\d{1,2})(?:日)?(?:\s|$)/.exec(cells[col]);
                    if (day>=0 && m) result.push([Number(week[1]),day,Number(m[1]||0),+m[2],+m[3]]);
                }
            }
            if (result.length) return result;
        }
        return [];
    }
    function clocks(t) {
        const result = [], re = /([0-2]?\d[:：][0-5]\d)\s*[-~～—–至]\s*([0-2]?\d[:：][0-5]\d)/;
        for (const row of t.rows) {
            const first = text(row.cells[0]);
            const m = re.exec(first);
            if (m) result.push([first.slice(0,m.index).trim(), m[1],m[2]]);
        }
        return result;
    }
    function explicitDate(doc,term) {
        if(termOf(doc) && termOf(doc)!==term) return '';
        const candidates=new Set();
        for(const el of doc.querySelectorAll('[name="xqksrq"],[name="semesterStartDate"],td,th,label')) {
            const label=text(el);
            const raw=el.matches('input') ? el.value :
                /^(?:开学日期|学期开始日期|学期起始日期|第一周开始日期)\s*[:：]?\s*$/.test(label) ? text(el.nextElementSibling) :
                /^(?:开学日期|学期开始日期|学期起始日期|第一周开始日期)\s*[:：]/.test(label) ? label : '';
            const m=/(20\d\d)[-/.年](\d{1,2})[-/.月](\d{1,2})(?:日|\b)/.exec(raw);
            if(m) candidates.add(m[1]+'-'+m[2].padStart(2,'0')+'-'+m[3].padStart(2,'0'));
        }
        return candidates.size===1 ? [...candidates][0] : '';
    }
    function bellRows(doc) {
        const found=[];
        for (const other of doc.querySelectorAll('table')) {
            for (const header of Array.from(other.rows).slice(0,3)) {
                const titles=Array.from(header.cells,text);
                const index=titles.findIndex(s=>s.includes('节次')), start=titles.findIndex(s=>/上课时间|开始时间/.test(s));
                const end=titles.findIndex(s=>/下课时间|结束时间/.test(s));
                if (index<0 || start<0 || end<0) continue;
                for (const r of Array.from(other.rows).slice(header.rowIndex+1)) {
                    const from=text(r.cells[start]), to=text(r.cells[end]);
                    if (/^\d{1,2}[:：]\d{2}$/.test(from) && /^\d{1,2}[:：]\d{2}$/.test(to))
                        found.push([text(r.cells[index]),from,to]);
                }
                break;
            }
        }
        return found;
    }
    async function collect() {
        const docs = [document];
        for (let n=0;n<docs.length && n<12;n++) {
            for (const frame of docs[n].querySelectorAll('iframe,frame')) try {
                const child = frame.contentDocument;
                if (child && child.location.origin===home && !docs.includes(child) && docs.length<12) docs.push(child);
            } catch (_) {}
        }
        let doc = docs.find(d => d.querySelector('#timetable .kbcontent') && cleanTerm(termOf(d)));
        if (!doc) throw Error('请登录后打开「学期理论课表」，再点解析课表');
        const term=termOf(doc), pick=doc.querySelector('select[name="xnxq01id"]'), week=doc.querySelector('select[name="zc"]');
        if ((week && !['','0','all'].includes(week.value)) ||
                (pick && pick.querySelector('option[selected]') && pick.querySelector('option[selected]').value !== term))
            throw Error('请选择学期和全部周次，点击教务网页的查询后再解析');
        const t=doc.querySelector('#timetable'), slots=clocks(t);
        // A separate bell table may contain the interior times absent from grouped timetable rows.
        const details=bellRows(doc);
        if (!details.length) {
            const link=Array.from(doc.querySelectorAll('a[href]')).find(a=>/^(?:作息时间表?|节次时间表?|上课时间表)$/.test(text(a)));
            if (link) try {
                const u=new URL(link.getAttribute('href'),doc.baseURI);
                if (u.origin===home && u.pathname.startsWith('/jsxsd/') && !u.hash) {
                    const bells=await read(u.href);
                    if(!termOf(bells) || termOf(bells)===term) details.push(...bellRows(bells));
                }
            } catch (_) {}
        }
        slots.push(...details);
        let anchors = dates(doc,term), begin=explicitDate(doc,term);
        if (!anchors.length && !begin) {
            const path='/jsxsd/jxzl/jxzl_query', params=new URLSearchParams({xnxq01id:term});
            for (const request of [[path,params],[path+'?'+params,null]]) try {
                const calendar=await read(...request);
                anchors=dates(calendar,term); begin=explicitDate(calendar,term);
                if (anchors.length || begin) break;
            } catch (_) {}
        }
        // Send only the timetable, not the enclosing page, inputs or session credentials.
        const copy=t.cloneNode(true);
        for(const el of copy.querySelectorAll('script,input,button,iframe,style')) el.remove();
        return {type:'xit',term,table:copy.outerHTML,slots,anchors,begin};
    }
    function connect(e) {
        if (e.data!==token || e.ports?.length!==1) return;
        window.removeEventListener('message',connect);
        const port=e.ports[0];
        collect().then(r=>port.postMessage(JSON.stringify(r))).catch(err=>
            port.postMessage(JSON.stringify({type:'error',message:err.message || '读取学校课表失败'})));
    }
    window.addEventListener('message',connect);
    return true;
})
