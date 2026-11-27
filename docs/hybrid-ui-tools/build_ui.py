import re,sys,shutil,os
SRC='/mnt/user-data/uploads/DeltaBox_Lime_Premium_Refined.html'
ROOT=[d for d in os.listdir('/home/claude/work') if d.startswith('rc3')][0]
OUT=f'/home/claude/work/{ROOT}/app/src/main/assets/ui/index.html'
s=open(SRC,encoding='utf-8').read()

def cut(s,start,end,new,keep_end=True):
    i=s.index(start); j=s.index(end,i)
    return s[:i]+new+(s[j:] if keep_end else s[j+len(end):])
def rep(s,old,new,count=1):
    assert old in s, old[:80]
    return s.replace(old,new,count)

# ---------- head: offline fonts ----------
s=rep(s,'''  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
  <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500;600&display=swap" rel="stylesheet">
  <style>''','''  <style>
    /* Offline fonts. JetBrains Mono ships with the app (res/font). Inter is NOT bundled: system sans-serif is used. */
    /* Smooth, contained scrolling */
    html, body { overscroll-behavior: none; }
    #profileList, #pgBody, .drawer-nav, .modal-body { -webkit-overflow-scrolling: touch; overscroll-behavior: contain; scroll-behavior: smooth; }
    .profile-card { contain: layout paint; }
    @font-face { font-family: 'JetBrains Mono'; src: url('data:font/ttf;base64,__FONT_B64__') format('truetype'); font-weight: 100 800; font-display: swap; }''')

# ---------- script 1: data model ----------
DATA_MODEL=r'''/* ---- Data model: NO local data. Everything comes from Kotlin through NativeBridge. ---- */
  // Every bridge call goes through this wrapper: a Java exception becomes a visible toast instead of a silent dead button.
  var NB = window.NB = (function (raw) {
    function fail(name, e) {
      try { console.error('NativeBridge.' + name + ' failed: ' + e); } catch (x) {}
      var el = document.getElementById('toast');
      if (el) { el.textContent = 'Bridge error (' + name + '): ' + (e && e.message ? e.message : e); el.classList.add('show'); setTimeout(function () { el.classList.remove('show'); }, 4000); }
    }
    return new Proxy({}, { get: function (_, name) {
      return function () {
        try { if (typeof raw[name] !== 'function') throw new Error('method missing'); return raw[name].apply(raw, arguments); }
        catch (e) { fail(String(name), e); return undefined; }
      };
    } });
  })(window.NativeBridge);
  function nj(x) { try { return JSON.parse(x); } catch (e) { return null; } }
  function flagOf(name) {
    var m = /[\uD83C][\uDDE6-\uDDFF][\uD83C][\uDDE6-\uDDFF]/.exec(name || '');
    return m ? m[0] : '🌐';
  }
  function mapProfile(r) {
    var nm = (r.name || r.addr || '').replace(/^\s*[\uD83C][\uDDE6-\uDDFF][\uD83C][\uDDE6-\uDDFF]\s*/, '') || r.name || r.addr;
    return { id: r.id, group: r.groupId, name: nm, raw: r.name || '', proto: r.type, addr: r.addr,
      tx: r.tx, rx: r.rx, ping: r.status === 1 ? r.ping : null, status: r.status, error: r.error,
      reality: !!r.reality, flag: flagOf(r.name) };
  }
  var st = { groups: [], profiles: [], selected: null, group: 0, bw: { enabled: false, level: 'GHOST' } };
  // Runtime-only connection state, pushed by Kotlin (BaseService.State)
  var rt = { conn: 'stopped', testMs: null, testing: false };

  function refreshGroups() {
    var g = nj(NB.getGroups()) || [];
    st.groups = g.map(function (x) { return { id: x.id, name: x.name, sub: x.type === 1, def: !!x.ungrouped }; });
    var s0 = nj(NB.getState()) || {};
    if (!st.groups.some(function (x) { return x.id === st.group; })) {
      st.group = st.groups.some(function (x) { return x.id === s0.selectedGroup; }) ? s0.selectedGroup : (st.groups[0] ? st.groups[0].id : 0);
    }
    st.selected = s0.selectedProxy;
  }
  function refreshProfiles() {
    var s0 = nj(NB.getState()) || {};
    st.selected = s0.selectedProxy;
    st.profiles = (nj(NB.getProfiles(st.group)) || []).map(mapProfile);
  }
  function refreshBW() {
    var b = nj(NB.getBlackWall()) || {};
    st.bw.enabled = !!b.enabled;
    if (b.level === 'GHOST' || b.level === 'PHANTOM' || b.level === 'WRAITH') st.bw.level = b.level;
  }
  var refreshTimer = null, refreshFull = false;
  function scheduleRefresh(full) {            // coalesce bursts of native events (traffic ticks, test results)
    refreshFull = refreshFull || !!full;
    if (refreshTimer) return;
    refreshTimer = setTimeout(function () {
      refreshTimer = null;
      if (refreshFull) { refreshGroups(); renderGroups(); renderBW(); }
      refreshFull = false;
      refreshProfiles();
      renderProfiles();
      renderStatus();
    }, 150);
  }
  function load() {}
  function save() {}

'''
s=cut(s,'/* ---- Data model (mirrors ProxyEntity','  function find(id) {',DATA_MODEL)

# ---------- remove mock constants no longer used (PROTO/CAMO/FP/STORE_KEY keep harmlessly? remove) ----------
s=rep(s,"  var STORE_KEY = 'deltabox-home-preview-v1';\n","")
s=cut(s,"  var PROTO = {","  var ICON_EDIT","")
s=rep(s,"  function pick(a) { return a[Math.floor(Math.random() * a.length)]; }\n","")

# ---------- connection state machine ----------
CONN=r'''/* ---- Connection (real): state and speed are pushed from Kotlin (SagerConnection callbacks) ---- */
  var SVC = { idle: 'stopped', stopped: 'stopped', connecting: 'connecting', connected: 'connected', stopping: 'stopping' };
  function showSpeed(up, down) {
    $('speedUp').textContent = fmt(up) + '/s';
    $('speedDown').textContent = fmt(down) + '/s';
  }
  function setConn(s) {
    if (s !== 'connected') { rt.testMs = null; rt.testing = false; showSpeed(0, 0); }
    rt.conn = s;
    document.body.setAttribute('data-state', s);
    renderStatus();
  }
  function toggleConnect() {
    if (rt.conn === 'stopping') return;
    if (rt.conn === 'stopped' && !find(st.selected)) { toast(t('no_profile')); return; }
    NB.toggleConnection();                     // SagerNet.stopService() or VPN-permission + startService()
  }
  // Tap while connected: real StatsBar.testConnection() equivalent (service.urlTest())
  function runStatusTest() {
    if (rt.conn !== 'connected' || rt.testing) return;
    rt.testing = true;
    renderStatus();
    if (!NB.testConnection()) { rt.testing = false; renderStatus(); }
  }

  function selectProfile(id) {
    var live = rt.conn === 'connected' || rt.conn === 'connecting';
    var p = find(id), changed = id !== st.selected;
    if (!NB.selectProfile(id)) return;
    st.selected = id;
    if (live && p && changed) toast(t('switched', p.name));
    renderProfiles();
    renderStatus();
  }

'''
s=cut(s,'/* ---- Timers ---- */','  function bwMeta() {',
'''/* ---- Toast ---- */
  var toastTimer = null;
  function toast(msg) {
    var el = $('toast');
    el.textContent = msg;
    el.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { el.classList.remove('show'); }, 2200);
  }

  '''+CONN)

# bwMeta: only real data (level); camouflage SNI / fingerprint are chosen per-outbound in Kotlin and not exposed
s=cut(s,'  function bwMeta() {','  function renderStatus() {','''function bwMeta() {
    var lv = effectiveLevel(find(st.selected) || {});
    if (lv === 'OFF') return t('bw_meta_off');
    if (lv === 'REALITY') return t('bw_meta_reality');
    return levelName(lv);
  }

  ''')
s=rep(s,"      else if (rt.exitLabel) text = rt.exitLabel;\n","")

# profile card address (real "host:port" string from displayAddress())
s=rep(s,"esc(p.host) + ':' + p.port","esc(p.addr)")

# ---------- import / profile actions ----------
ACTIONS=r'''/* ---- Import (Kotlin: parseProxies + ProfileManager.createProfile) ---- */
  function importText(txt) { NB.importText(txt || ''); }
  function importClipboard() { closeMenus(); NB.importFromClipboard(); }

  /* ---- Profile actions (Kotlin mirrors ConfigurationFragment) ---- */
  function removeProfiles(ids) { NB.deleteProfiles(JSON.stringify(ids)); }
  function renameProfile(p) {
    openModal(t('rename_title'), '', {
      input: true, value: p.raw || p.name, okText: t('save'),
      onOk: function (v) { v = (v || '').trim(); if (v) NB.renameProfile(p.id, v); }
    });
  }
  function runGroupTest(kind) {
    closeMenus();
    if (!inGroup().length) return;
    if (!NB.testGroup(st.group, kind === 'tcp' ? 'tcp' : 'url')) toast(t('test_busy'));
  }
  function sortByPing() { closeMenus(); NB.sortGroupByDelay(st.group); toast(t('sorted')); }
  function resetPing() { closeMenus(); NB.clearTestResults(st.group); toast(t('ping_reset')); }
  function resetData() { closeMenus(); NB.clearTraffic(st.group); toast(t('data_reset')); }
  function deleteInvalid() {
    closeMenus();
    var bad = nj(NB.getInvalidIds(st.group)) || [];
    if (!bad.length) { toast(t('nothing_invalid')); return; }
    openModal(t('confirm'), t('delete_invalid_prompt'), { onOk: function () { removeProfiles(bad); } });
  }

  '''
s=cut(s,'/* ---- Import (ProfileManager','  /* ---- Black Wall sheet',ACTIONS)

# ---------- events ----------
s=rep(s,"""      st.group = Number(b.getAttribute('data-gid'));
      save();
      renderGroups();
      renderProfiles();""","""      st.group = Number(b.getAttribute('data-gid'));
      NB.selectGroup(st.group);
      renderGroups();
      refreshProfiles();
      renderProfiles();""")
s=rep(s,"""      st.bw.enabled = this.checked;
      save();
      renderBW();
      renderStatus();""","""      if (NB.setBlackWall(this.checked, st.bw.level)) st.bw.enabled = this.checked;
      renderBW();
      renderStatus();""")
s=rep(s,"""        st.bw.level = this.getAttribute('data-level');
        save();
        renderBW();""","""        var lv = this.getAttribute('data-level');
        if (NB.setBlackWall(st.bw.enabled, lv)) st.bw.level = lv;
        renderBW();""")
s=rep(s,"""      var g = curGroup();
      toast(g && g.sub ? t('sub_preview') : t('sub_not'));""","""      var g = curGroup();
      if (g && g.sub && NB.updateSubscription(g.id)) toast(t('sub_updating')); else toast(t('sub_not'));""")
s=rep(s,"$('actUrlTest').addEventListener('click', runGroupTest);\n    $('testPingBtn').addEventListener('click', runGroupTest);",
 "$('actUrlTest').addEventListener('click', function () { runGroupTest('url'); });\n    $('actTcpTest').addEventListener('click', function () { runGroupTest('tcp'); });\n    $('testPingBtn').addEventListener('click', function () { runGroupTest('url'); });")
# TCP test menu item (real: ConfigurationFragment tcp test), placed after URL test
m=re.search(r'            <button id="actUrlTest".*?</button>\n',s,re.S)
tcp=m.group(0).replace('actUrlTest','actTcpTest').replace('data-i18n="m_urltest"','data-i18n="tcp_test"')
s=s[:m.end()]+tcp+s[m.end():]

# ---------- init + native events ----------
INIT=r'''function langFromSettings() {
    var cfg = nj(NB.getSettings()) || {};
    var l = cfg.appLanguage || (navigator.language || 'en').slice(0, 2);
    return l === 'fa' ? 'fa' : 'en';
  }
  function extraStrings() {
    var en = { vpn_denied: 'VPN permission denied', test_busy: 'A test is already running', tcp_test: 'TCP Test', sub_updating: 'Updating subscription…', import_failed: 'Import failed' };
    var fa = { vpn_denied: 'مجوز VPN داده نشد', test_busy: 'یک تست در حال اجراست', tcp_test: 'تست TCP', sub_updating: 'در حال به‌روزرسانی اشتراک…', import_failed: 'وارد کردن ناموفق بود' };
    Object.keys(en).forEach(function (k) { if (I18N.en[k] === undefined) I18N.en[k] = en[k]; });
    Object.keys(fa).forEach(function (k) { if (I18N.fa[k] === undefined) I18N.fa[k] = fa[k]; });
  }

  /* ---- Kotlin -> JS events ---- */
  function onNativeEvent(name, d) {
    d = d || {};
    if (name === 'state') {
      setConn(SVC[d.service] || 'stopped');
      if (d.msg) toast(d.msg);
    } else if (name === 'speed') {
      if (rt.conn === 'connected') showSpeed(d.txProxy, d.rxProxy);
    } else if (name === 'changed') {
      scheduleRefresh(d.what === 'groups' || d.what === 'all');
    } else if (name === 'connectionTest') {
      rt.testing = false;
      if (d.ok) rt.testMs = d.ms; else if (d.msg) toast(d.msg);
      renderStatus();
    } else if (name === 'testDone') {
      toast(t('tested'));
      scheduleRefresh(false);
    } else if (name === 'theme' || name === 'ready') {
      if (window.DB && DB.setTheme) DB.setTheme(d.night ? 'dark' : 'light', true);
    } else if (name === 'toast') {
      var codes = { import_invalid: 'no_proxies', no_proxies: 'no_proxies', clipboard_empty: 'clip_empty', vpn_permission_denied: 'vpn_denied' };
      if (d.code === 'imported') toast(t('imported', d.count));
      else if (d.code === 'import_failed') toast(d.msg || t('import_failed'));
      else toast(t(codes[d.code] || d.code));
    }
  }
  // Back button: close the innermost layer; return true when handled, otherwise Kotlin leaves the app.
  function onNativeBack() {
    if ($('modal').classList.contains('open')) { closeModal(); return true; }
    if ($('bwSheet').classList.contains('open')) { closeBW(); return true; }
    if ($('drawer').classList.contains('open')) { setDrawer(false); return true; }
    if (document.querySelector('.dropdown.open')) { closeMenus(); return true; }
    return !!(window.DB && DB.pageBack && DB.pageBack());
  }
  window.DeltaNative = { onEvent: onNativeEvent, onBack: onNativeBack };

  function init() {
    LANG = langFromSettings();
    extraStrings();
    STR = I18N[LANG];
    applyI18n();
    bindEvents();
    refreshGroups();
    refreshProfiles();
    refreshBW();
    renderGroups();
    renderProfiles();
    renderBW();
    var s0 = nj(NB.getState()) || {};
    setConn(SVC[s0.service] || 'stopped');
  }
  init();
})();'''
s=cut(s,'  function init() {\n    load();','\n  </script>',INIT+'\n',keep_end=True)
s=rep(s,"  var LANG = 'en';\n  var STR = I18N[LANG];","  var LANG = 'en';\n  var STR = I18N[LANG];")


# ---- remove the reference's "Preview only / sample data" drawer note (false in the real app) ----
import re as _re
s,_n=_re.subn(r'\s*<p class="preview-note"[^>]*>.*?</p>','',s,flags=_re.S); assert _n==1
s=_re.sub(r'"preview_note": "[^"]*",?\s*','',s)

# =================== script 2 (settings / pages) ===================
import json
lines=s.split('\n')
di=[i for i,l in enumerate(lines) if l.startswith('var D={')][0]
D,end=json.JSONDecoder().raw_decode(lines[di][6:])
for it in D['settings']:
    if it.get('k')=='nightTheme':
        it['v']=['1','2']; it['d']='1'     # native values: 1 = Dark, 2 = Light (Theme.getNightMode)
for it in D['settings']:
    if it.get('k')=='appLanguage':          # product decision: English + Persian only
        it['o']=['English','فارسی']; it['of']=['English','فارسی']; it['v']=['en','fa']; it['d']='en'
idx=[i for i,it in enumerate(D['settings']) if it.get('k')=='connectionTestURL'][0]
D['settings'][idx+1:idx+1]=[
  {"t":"Connection test concurrency","tf":"همزمانی تست اتصال","k":"connectionTestConcurrent","s":"Parallel profiles tested at once (1-50)","sf":"تعداد پروفایل‌های تست همزمان (۱ تا ۵۰)","ty":"txt","d":"5"},
  {"t":"AdBlock","tf":"مسدودکننده تبلیغات","k":"ad_block_enabled","s":"","sf":"","ty":"sw","d":False}]
for k in ('forms','route','group'): D.pop(k,None)   # editors/route/group forms live in native screens
lines[di]='var D='+json.dumps(D,ensure_ascii=False,separators=(',',':'))+';'
s='\n'.join(lines)

s=rep(s,"var P=ls(PK,{}), FORM={}, cur='config', stack=[];",
 "var NB=window.NativeBridge;\nfunction nj0(x){try{return JSON.parse(x)||{}}catch(e){return {}}}\nvar P=nj0(NB.getSettings()), TH=nj0(NB.getState()).night?'dark':'light', cur='config', stack=[];")
s=rep(s,"function ls(k,d){try{var v=localStorage.getItem(k);return v?JSON.parse(v):d}catch(e){return d}}\nfunction ss(k,v){try{localStorage.setItem(k,JSON.stringify(v))}catch(e){}}\n","")
s=rep(s,"if(it.k==='nightTheme')return (P.theme||'dark')==='dark'?it.v[0]:it.v[1];","")
# theme
i=s.index("function applyTheme(){"); j=s.index("var SUN=",i)
s=s[:i]+"""function applyTheme(){document.documentElement.setAttribute('data-theme',TH);
  var m=document.querySelector('meta[name=theme-color]');if(m)m.content=TH==='dark'?'#080B10':'#F2F5F8'}
function setTheme(th,fromNative){TH=th;applyTheme();['tgl','tgl2'].forEach(function(i){if($(i))$(i).innerHTML=th==='dark'?SUN:MOON});
  if(!fromNative)NB.setSetting('nightTheme',th==='dark'?'1':'2')}
"""+s[j:]
s=s.replace("(P.theme||'dark')==='dark'","TH==='dark'")
# remove mock helpers/pages
s=rep(s,"function profiles(){var s=ls(SK,null);return s&&s.profiles?s.profiles:[]}\n","function profiles(){return []}\n")
s=rep(s,"else if(it.ty==='app'){var n=(ls(AK,{}).sel||[]).length;r='<span class=\"r-v\">'+n+'</span>'+CHEV}\n","")
i=s.index("function pForm("); j=s.index("function pAbout(",i)
s=s[:i]+s[j:]
i=s.index("/* ---------- manual profile editors"); j=s.index("function init(){",i)
s=s[:i]+s[j:]
# bind(): write settings through the bridge
s=rep(s,"var set=function(v){bag[it.k]=v;persist();rerender();if(it.k==='nightTheme')setTheme(v===it.v[0]?'dark':'light')};",
 "var set=function(v){if(bag===P&&!NB.setSetting(it.k,String(v))){toastM('Invalid value');return}bag[it.k]=v;persist();rerender();if(it.k==='nightTheme')setTheme(v==='1'?'dark':'light',true)};")
s=rep(s,"if(e.target!==c)c.checked=!c.checked;bag[it.k]=c.checked;persist();return}",
 "if(e.target!==c)c.checked=!c.checked;if(bag===P&&!NB.setSetting(it.k,String(c.checked))){c.checked=!c.checked;toastM('Invalid value');return}bag[it.k]=c.checked;persist();return}")
s=s.replace("function(){ss(PK,P)}","function(){}")
s=rep(s,"else if(it.ty==='out'){var o=['Direct','Proxy','Block'].concat(profiles().map(function(p){return p.name}));pick({t:it.t,tf:it.tf,o:o,v:o,_bag:bag,k:it.k,d:'Direct'},set)}\n    else if(it.ty==='app')nav('split',1)}}","}}") if "else if(it.ty==='out'){var o=" in s else s
# navigation: native screens open the existing Activities/Fragments
i=s.index("function nav(page,push){"); j=s.index("function pSettings(b){",i)
s=s[:i]+"""var NATIVE_PAGES={group:1,assets:1,route:1,split:1,logs:1,tools:1};
function nav(page,push){if(NATIVE_PAGES[page]){NB.openNative(page);return}
  if(!push)stack=[];else stack.push(cur);show(page);if(page==='config')return;
  var b=$('pgBody');b.scrollTop=0;if(page==='settings')pSettings(b);else if(page==='about')pAbout(b)}
function back(){if(stack.length){var p=stack.pop();nav(p,0);stack=[]}else{$('drawer').classList.add('open')}}
function pageBack(){if($('dlg').classList.contains('open')){closeDlg();return true}if(cur!=='config'){nav('config');return true}return false}
"""+s[j:]
# init tail: no local file/restore/session handling
i=s.index("  $('pgFile').onchange=function(){"); j=s.index("D.forms.group_=",i)
s=s[:i]+"  $('tgl2').innerHTML=TH==='dark'?SUN:MOON;$('tgl2').onclick=tg.onclick;applyTheme()}\n"+s[j:]
s=rep(s,"D.forms.group_=D.forms.group_||{title:'Group',items:D.group};D.forms.route_={title:'Route',items:D.route};\nvar _k=Object.keys(D.forms);\n","")
s=rep(s,"mbn.onclick=function(){protoList()};","mbn.onclick=function(){NB.openNative('classic')};")
# ---- device-test fixes ----
# (a) second script shares the wrapped bridge
s=rep(s,"var NB=window.NativeBridge;\nfunction nj0","var NB=window.NB;\nfunction nj0")
# (b) import from file: native SAF picker (an <input type=file> does nothing in WebView without a file chooser)
s=rep(s,"""    $('actImportFile').addEventListener('click', function () { closeMenus(); $('fileInput').click(); });
    $('fileInput').addEventListener('change', function () {
      var f = this.files && this.files[0];
      this.value = '';
      if (f) f.text().then(importText);
    });
""","""    $('actImportFile').addEventListener('click', function () { closeMenus(); NB.importFile(); });
""")
s=_re.sub(r'\s*<input type="file" id="fileInput"[^>]*>','',s,count=1)
# (c) About links: WebView navigation is blocked on purpose -> open through Kotlin (allow-listed)
s=rep(s,"""return '<a class="row" style="text-decoration:none;color:inherit" '+(x[2]?'href="'+x[2]+'" target="_blank" rel="noopener"':'')+'>""","""return '<a class="row" style="text-decoration:none;color:inherit;cursor:pointer" '+(x[2]?'data-url="'+x[2]+'"':'')+'>""")
s=rep(s,"</a>'}).join('')+'</div>';b.onclick=null}","</a>'}).join('')+'</div>';b.onclick=function(e){var a=e.target.closest('[data-url]');if(a)NB.openUrl(a.getAttribute('data-url'))}}")
# ---- Black Wall: trust native state (re-read) instead of the call's return value; AdBlock switch in the sheet ----
s=rep(s,"      if (NB.setBlackWall(this.checked, st.bw.level)) st.bw.enabled = this.checked;\n      renderBW();","      NB.setBlackWall(this.checked, st.bw.level);\n      refreshBW();\n      renderBW();")
s=rep(s,"        if (NB.setBlackWall(st.bw.enabled, lv)) st.bw.level = lv;\n        renderBW();","        NB.setBlackWall(st.bw.enabled, lv);\n        refreshBW();\n        renderBW();")
s=rep(s,"    st.bw.enabled = !!b.enabled;\n","    st.bw.enabled = !!b.enabled;\n    st.ad = !!(nj(NB.getSettings()) || {}).ad_block_enabled;\n")
s=rep(s,"    $('bwSwitch').checked = on;\n","    $('bwSwitch').checked = on;\n    $('adSwitch').checked = !!st.ad;\n")
s=rep(s,"""          <span class="switch"><input id="bwSwitch" type="checkbox"><span></span></span>
        </label>
""","""          <span class="switch"><input id="bwSwitch" type="checkbox"><span></span></span>
        </label>
        <label class="switch-row" style="margin-top:10px">
          <span data-i18n="adblock_title">AdBlock</span>
          <span class="switch"><input id="adSwitch" type="checkbox"><span></span></span>
        </label>
""")
s=rep(s,"    $('bwScrim').addEventListener('click', closeBW);\n","    $('bwScrim').addEventListener('click', closeBW);\n    $('adSwitch').addEventListener('change', function () {\n      NB.setSetting('ad_block_enabled', String(this.checked));\n      refreshBW();\n      renderBW();\n    });\n")
s=rep(s,"var en = { vpn_denied:","var en = { adblock_title: 'AdBlock', vpn_denied:")
s=rep(s,"var fa = { vpn_denied:","var fa = { adblock_title: 'مسدودکننده تبلیغات', vpn_denied:")
k=s.rindex("init();\n})();")
s=s[:k]+"window.DB={nav:nav,setTheme:setTheme,pageBack:pageBack};\n"+s[k:]
s=rep(s,"  <input type=\"file\" id=\"pgFile\" hidden>\n","") if '  <input type="file" id="pgFile" hidden>\n' in s else s
import base64
fb=base64.b64encode(open(f'/home/claude/work/{ROOT}/app/src/main/res/font/jetbrains_mono.ttf','rb').read()).decode()
s=s.replace('__FONT_B64__',fb)
open(OUT,'w',encoding='utf-8').write(s)
shutil.rmtree(os.path.dirname(OUT)+'/fonts',ignore_errors=True)
print('built',OUT,len(s))
