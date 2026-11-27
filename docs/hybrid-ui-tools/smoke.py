import json,sys,glob
from playwright.sync_api import sync_playwright
HTML=glob.glob('/home/claude/work/rc3*/app/src/main/assets/ui/index.html')[0]
SETTINGS=json.load(open('/home/claude/work/html_settings_schema.json'))['settings']
cfg={x['k']:(False if x['ty']=='sw' else x.get('d','')) for x in SETTINGS if x['k']}
cfg['nightTheme']='1'; cfg['appLanguage']='en'; cfg['ad_block_enabled']=False; cfg['connectionTestConcurrent']='5'
STUB="""
(function(){
 var calls=[]; window.__calls=calls;
 var state={service:'idle',canStop:false,connected:false,selectedProxy:11,selectedGroup:1,night:true,language:'en'};
 var groups=[{id:1,name:'Default',ungrouped:true,type:0,count:2},{id:2,name:'Sub A',ungrouped:false,type:1,count:1}];
 var profs={1:[{id:11,groupId:1,name:'\uD83C\uDDE9\uD83C\uDDEA Germany 1',type:'VLESS',addr:'1.2.3.4:443',status:1,ping:120,error:'',tx:11290000,rx:59380000,selected:true,reality:true},
               {id:12,groupId:1,name:'Plain SS',type:'Shadowsocks',addr:'[::1]:8388',status:3,ping:0,error:'boom',tx:0,rx:0,selected:false,reality:false}],
            2:[{id:21,groupId:2,name:'Sub node',type:'Trojan',addr:'9.9.9.9:443',status:0,ping:0,error:'',tx:5,rx:6,selected:false,reality:false}]};
 var cfg=%s, bw={enabled:false,level:'GHOST'};
 function rec(n,a){calls.push([n].concat([].slice.call(a)))}
 window.NativeBridge={
  getState:function(){return JSON.stringify(state)},
  getGroups:function(){return JSON.stringify(groups)},
  getProfiles:function(g){return JSON.stringify(profs[g]||[])},
  getSettings:function(){return JSON.stringify(cfg)},
  getBlackWall:function(){return JSON.stringify(bw)},
  setBlackWall:function(e,l){rec('setBlackWall',arguments);bw={enabled:e,level:l};return true},
  selectGroup:function(g){rec('selectGroup',arguments);state.selectedGroup=g;return true},
  selectProfile:function(p){rec('selectProfile',arguments);state.selectedProxy=p;return true},
  toggleConnection:function(){rec('toggleConnection',arguments)},
  testConnection:function(){rec('testConnection',arguments);return true},
  testGroup:function(g,k){rec('testGroup',arguments);return true},
  deleteProfiles:function(j){rec('deleteProfiles',arguments);return true},
  renameProfile:function(i,n){rec('renameProfile',arguments);return true},
  sortGroupByDelay:function(g){rec('sortGroupByDelay',arguments);return true},
  clearTestResults:function(g){rec('clearTestResults',arguments)},
  clearTraffic:function(g){rec('clearTraffic',arguments)},
  getInvalidIds:function(g){return JSON.stringify([12])},
  importText:function(t){rec('importText',arguments)},
  importFromClipboard:function(){rec('importFromClipboard',arguments)},
  importFile:function(){rec('importFile',arguments)},
  openUrl:function(u){rec('openUrl',arguments);return true},
  importFile:function(){rec('importFile',arguments)},
  openUrl:function(u){rec('openUrl',arguments);return true},
  updateSubscription:function(g){rec('updateSubscription',arguments);return true},
  openNative:function(t){rec('openNative',arguments);return true},
  setSetting:function(k,v){rec('setSetting',arguments);cfg[k]=(typeof cfg[k]==='boolean')?(v==='true'):v;return true}
 };
})();
""" % json.dumps(cfg)
errs=[]; fails=[]
def check(name,cond):
    print(('PASS ' if cond else 'FAIL ')+name)
    if not cond: fails.append(name)
with sync_playwright() as p:
    b=p.chromium.launch(); pg=b.new_page(viewport={'width':390,'height':800})
    pg.on('pageerror',lambda e:errs.append('pageerror: '+str(e)))
    pg.on('console',lambda m:errs.append('console.'+m.type+': '+m.text) if m.type=='error' else None)
    pg.on('request',lambda r:errs.append('NET '+r.url) if r.url.startswith('http') else None)
    pg.add_init_script(STUB)
    pg.goto('file://'+HTML); pg.wait_for_timeout(400)
    check('group tabs from native (2)',pg.locator('.gtab').count()==2)
    check('profile cards from native (2)',pg.locator('.profile-card').count()==2)
    check('selected card marked',pg.locator('.profile-card.selected').count()==1)
    check('reality badge shown for real reality profile',pg.locator('.p-reality').count()==1)
    check('address from native',('1.2.3.4:443' in pg.inner_text('#profileList')))
    check('no demo data (Raydikalx absent)','Raydikalx' not in pg.content())
    check('JetBrains Mono embedded font loads offline',pg.evaluate("document.fonts.load('12px \"JetBrains Mono\"').then(function(f){return f.length>0})"))
    pg.screenshot(path='/home/claude/work/test/home_dark.png')
    pg.evaluate("DeltaNative.onEvent('state',{service:'connecting'})"); 
    check('state connecting -> body data-state',pg.get_attribute('body','data-state')=='connecting')
    pg.evaluate("DeltaNative.onEvent('state',{service:'connected'})")
    pg.evaluate("DeltaNative.onEvent('speed',{txProxy:2048,rxProxy:1500000})")
    check('speed shown from native event','kB/s' in pg.inner_text('#speedUp')+pg.inner_text('#speedDown') or 'MB/s' in pg.inner_text('#speedDown'))
    pg.click('#catBtn'); check('power button -> toggleConnection',any(c[0]=='toggleConnection' for c in pg.evaluate('__calls')))
    pg.click('#statusZone'); check('status tap -> testConnection',any(c[0]=='testConnection' for c in pg.evaluate('__calls')))
    pg.evaluate("DeltaNative.onEvent('connectionTest',{ok:true,ms:88})"); pg.wait_for_timeout(50)
    check('connection test result displayed','88' in pg.inner_text('#statusText')+pg.inner_text('#pingBadge'))
    pg.evaluate("DeltaNative.onEvent('state',{service:'stopped'})")
    check('speed reset on stop','0 B/s' in pg.inner_text('#speedUp'))
    pg.click('.profile-card >> nth=1'); check('card tap -> selectProfile(12)',['selectProfile',12] in pg.evaluate('__calls'))
    pg.click('.gtab >> nth=1'); pg.wait_for_timeout(100)
    check('group tab -> selectGroup(2) + reload list',['selectGroup',2] in pg.evaluate('__calls') and 'Sub node' in pg.inner_text('#profileList'))
    pg.click('.gtab >> nth=0')
    pg.click('#moreBtn'); pg.click('#actTcpTest'); check('TCP test -> testGroup(1,tcp)',['testGroup',1,'tcp'] in pg.evaluate('__calls'))
    pg.click('#moreBtn'); pg.click('#actUrlTest'); check('URL test -> testGroup(1,url)',['testGroup',1,'url'] in pg.evaluate('__calls'))
    pg.click('#moreBtn'); pg.click('#actSort'); check('sort -> sortGroupByDelay',['sortGroupByDelay',1] in pg.evaluate('__calls'))
    pg.click('#moreBtn'); pg.click('#actResetPing'); check('reset ping -> clearTestResults',['clearTestResults',1] in pg.evaluate('__calls'))
    pg.click('#moreBtn'); pg.click('#actResetData'); check('reset data -> clearTraffic',['clearTraffic',1] in pg.evaluate('__calls'))
    pg.click('#moreBtn'); pg.click('#actDelInvalid'); pg.click('#modalOk'); check('delete invalid -> deleteProfiles([12])',['deleteProfiles','[12]'] in pg.evaluate('__calls'))
    pg.click('#addBtn'); pg.click('#actImportClip'); check('import clipboard -> bridge',any(c[0]=='importFromClipboard' for c in pg.evaluate('__calls')))
    pg.click('#addBtn'); pg.click('#actImportFile'); check('import file -> native picker (importFile)',any(c[0]=='importFile' for c in pg.evaluate('__calls')))
    pg.click('#addBtn'); pg.click('#actImportFile'); check('import file -> native picker (importFile)',any(c[0]=='importFile' for c in pg.evaluate('__calls')))
    pg.click('#shieldBtn'); pg.evaluate("document.getElementById('bwSwitch').click()"); pg.wait_for_timeout(50)
    check('black wall switch -> setBlackWall(true,GHOST)',['setBlackWall',True,'GHOST'] in pg.evaluate('__calls'))
    pg.click('.bw-level[data-level="PHANTOM"]'); check('black wall level -> PHANTOM',['setBlackWall',True,'PHANTOM'] in pg.evaluate('__calls'))
    pg.evaluate("document.getElementById('adSwitch').click()"); pg.wait_for_timeout(50)
    check('AdBlock switch in Black Wall sheet -> ad_block_enabled',['setSetting','ad_block_enabled','true'] in pg.evaluate('__calls'))
    pg.screenshot(path='/home/claude/work/test/bw_sheet.png')
    check('back closes BW sheet first',pg.evaluate("DeltaNative.onBack()") is True and not pg.evaluate("document.getElementById('bwSheet').classList.contains('open')"))
    check('back at home with nothing open -> false (Kotlin leaves app)',pg.evaluate("DeltaNative.onBack()") is False)
    pg.click('#menuBtn'); pg.click('#drawerNav [data-page="group"]'); check('drawer Group -> native openNative(group)',['openNative','group'] in pg.evaluate('__calls'))
    for pgname in ('assets','route','split','logs','tools'):
        pg.evaluate("DB.nav('%s')"%pgname)
    check('other native pages open natively',all(['openNative',n] in pg.evaluate('__calls') for n in ('assets','split','logs','tools')))
    pg.evaluate("DB.nav('about')"); pg.wait_for_timeout(150)
    pg.click('#pgBody [data-url="https://t.me/v2ray_DA"]'); check('About Telegram link -> openUrl (allow-listed)',['openUrl','https://t.me/v2ray_DA'] in pg.evaluate('__calls'))
    pg.evaluate("DB.nav('about')"); pg.wait_for_timeout(150)
    pg.click('#pgBody [data-url="https://t.me/v2ray_DA"]'); check('About Telegram link -> openUrl (allow-listed)',['openUrl','https://t.me/v2ray_DA'] in pg.evaluate('__calls'))
    pg.evaluate("DB.nav('config')"); pg.wait_for_timeout(100)
    pg.click('#menuBtn'); pg.click('#drawerNav [data-page="settings"]'); pg.wait_for_timeout(100)
    check('settings page rendered from native values',pg.locator('#pgBody .row').count()>10)
    pg.wait_for_timeout(700); pg.screenshot(path='/home/claude/work/test/settings_dark.png')
    pg.locator('#pgBody .row:has-text("Profile Traffic Statistics")').click()
    check('settings switch -> setSetting(profileTrafficStatistics,...)',any(c[0]=='setSetting' and c[1]=='profileTrafficStatistics' for c in pg.evaluate('__calls')))
    pg.locator('#pgBody .row:has-text("Theme")').first.click(); pg.wait_for_timeout(50)
    pg.locator('.opt:has-text("Light")').click()
    check('theme Light -> nightTheme=2 (native value)',['setSetting','nightTheme','2'] in pg.evaluate('__calls') and pg.get_attribute('html','data-theme')=='light')
    pg.wait_for_timeout(700); pg.screenshot(path='/home/claude/work/test/settings_light.png')
    check('settings has AdBlock + concurrency rows',pg.locator('#pgBody .row:has-text("AdBlock")').count()==1 and pg.locator('#pgBody .row:has-text("Connection test concurrency")').count()==1)
    pg.locator('#pgBody .row:has-text("AdBlock")').click()
    check('AdBlock switch -> setSetting(ad_block_enabled,true)',['setSetting','ad_block_enabled','true'] in pg.evaluate('__calls'))
    pg.locator('#pgBody .row:has-text("Language")').first.click(); pg.wait_for_timeout(50)
    check('language list is EN + FA only',pg.locator('#dlgB .opt').count()==2)
    pg.locator('#dlg .dlg-act button').first.click()
    check('back from settings page',pg.evaluate("DeltaNative.onBack()") is True)
    pg.evaluate("DeltaNative.onEvent('theme',{night:true})"); check('native theme event applies dark',pg.get_attribute('html','data-theme')=='dark')
    pg.evaluate("window.NativeBridge.getSettings=undefined"); pg.evaluate("NB.getSettings()"); pg.wait_for_timeout(100)
    check('bridge failure shows a visible toast',('Bridge error' in pg.inner_text('#toast')))
    pg.evaluate("window.NativeBridge.getSettings=undefined"); pg.evaluate("NB.getSettings()"); pg.wait_for_timeout(100)
    check('bridge failure shows a visible toast','Bridge error' in pg.inner_text('#toast'))
    b.close()
errs=[e for e in errs if 'method missing' not in e]
print('\nERRORS:',errs if errs else 'none'); print('FAILED:',fails if fails else 'none')
sys.exit(1 if fails or errs else 0)
