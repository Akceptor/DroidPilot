 'use strict';
let revision='', fields={}, busy=false, dirty=false, selected='overview', messageTimer;
const groups=[['MAVLink',['input','usbKey','usbPort','baud','listenAddress','udpPort','endpoints','systemId','componentId'],'mavlink'],['Video',['video','cameraId','width','height','fps','bitrate','keyframeSeconds','videoHost','videoPort','autofocus','focusDistance','exposure','stabilization','zoom'],'video'],['Network & power',['webEnabled','webBindAddress','webPort','wakeLock'],'network']];
const labels = {input:'Vehicle input',usbKey:'USB adapter VID:PID (empty = first)',usbPort:'USB port index',baud:'UART baud rate',listenAddress:'MAVLink bind IP',udpPort:'Vehicle UDP listener port',endpoints:'UDP peers: IP:port@localPort; separate with ;',systemId:'Local system ID',componentId:'Local component ID',video:'Enable H.264 RTP video',cameraId:'Camera ID (empty = rear, @synthetic = test)',width:'Width',height:'Height',fps:'Frames per second',bitrate:'Bitrate (bit/s)',keyframeSeconds:'Keyframe interval (seconds)',videoHost:'Video destination IP',videoPort:'Video destination port',autofocus:'Autofocus',focusDistance:'Focus distance (diopters)',exposure:'Exposure compensation steps',stabilization:'Video stabilization',zoom:'Zoom ratio',webEnabled:'Enable web server',webBindAddress:'HTTP bind IP',webPort:'HTTP port',wakeLock:'Keep CPU awake'};
const booleans = new Set(['video','autofocus','stabilization','wakeLock','webEnabled']);
const descriptions={overview:['Overview','A live view of your companion service.'],mavlink:['MAVLink','Configure the vehicle connection and telemetry routing.'],video:['Video','Configure H.264 capture and RTP transmission.'],network:['Network & power','Manage the web listener and companion power settings.'],diagnostics:['Diagnostics','Inspect device capabilities, network information, and events.']};
const $=id=>document.getElementById(id);
function message(text,isError=false) {
  clearTimeout(messageTimer);$('message').textContent=text;$('message').hidden=false;$('message').dataset.error=String(isError);
  if(!isError) messageTimer=setTimeout(()=>{$('message').hidden=true;},6500);
}
function setDirty(value) { dirty=value;$('edit-state').textContent=value?'Unsaved changes':'No unsaved changes'; }
function view(name) {
  selected=Object.hasOwn(descriptions,name)?name:'overview';
  const settings=['mavlink','video','network'].includes(selected);
  $('overview').hidden=selected!=='overview';$('diagnostics').hidden=selected!=='diagnostics';$('settings').hidden=!settings;
  for(const group of document.querySelectorAll('[data-settings]')) group.hidden=group.dataset.settings!==selected;
  for(const link of document.querySelectorAll('[data-view]')) {
    if(link.dataset.view===selected) link.setAttribute('aria-current','page');else link.removeAttribute('aria-current');
  }
  $('page-title').textContent=descriptions[selected][0];$('page-description').textContent=descriptions[selected][1];
  $('settings-title').textContent=descriptions[selected][0]+' settings';document.title=descriptions[selected][0]+' · DroidPilot';
}
async function api(path,options={}) {
  const response=await fetch(path,{...options,credentials:'same-origin',cache:'no-store',headers:{'X-DroidPilot-Request':'1',...(options.headers||{})}});
  if(response.status===401) { location.replace('/login?expired=1');throw new Error('Your session ended. Sign in again.'); }
  if(!response.ok) {
    const text=await response.text();
    throw new Error(response.status===409&&path==='/api/config'?'Settings changed elsewhere. Reload saved settings before saving again.':text||'Request failed.');
  }
  return response.json();
}
function form(config) {
  const root=$('groups');root.replaceChildren();
  for(const [title,keys,name] of groups) {
    const group=document.createElement('fieldset'),legend=document.createElement('legend'),grid=document.createElement('div');
    group.dataset.settings=name;legend.textContent=title;grid.className='fields';group.append(legend,grid);
    for(const key of keys) {
      const label=document.createElement('label');label.textContent=labels[key]||key;
      let input;
      if(key==='input'||key==='baud') {
        input=document.createElement('select');
        const values=key==='input'?['UDP','USB']:['57600','115200','230400','460800','921600'];
        if(config[key]&&!values.includes(config[key])) values.push(config[key]);
        for(const value of values) {const option=document.createElement('option');option.value=value;option.textContent=value;input.append(option);}
      } else input=document.createElement(key==='endpoints'?'textarea':'input');
      input.name=key;input.disabled=busy;
      if(booleans.has(key)) { input.type='checkbox';input.checked=config[key]==='true'; }
      else input.value=config[key]||'';
      label.append(input);grid.append(label);
    }
    root.append(group);
  }
  view(selected);setDirty(false);
}
async function load() {const result=await api('/api/config');revision=result.revision;fields=result.config;form(fields);}
const number=value=>typeof value==='number'?value.toLocaleString():'—';
function bytes(value) {
  if(typeof value!=='number')return 'No data available';
  if(value<1024)return number(value)+' B';
  return value<1048576?(value/1024).toFixed(1)+' KiB':(value/1048576).toFixed(1)+' MiB';
}
async function status() {
  const s=await api('/api/status'),t=s.traffic||{};
  $('connection').textContent='Connected';$('connection').dataset.state='connected';
  $('runtime').textContent=s.running?'Running':'Paused';
  $('runtime-detail').textContent=s.running?'Telemetry runtime active':'Web administration available';
  $('heartbeat').textContent=t.heartbeatCurrent?'Receiving':t.lastHeartbeat?'Stale':'Waiting';
  $('vehicle-detail').textContent=t.vehicleSystem!=null?'Vehicle system '+t.vehicleSystem:'No vehicle heartbeat received';
  $('rx-packets').textContent=number(t.rxPackets);$('tx-packets').textContent=number(t.txPackets);
  $('rx-bytes').textContent=bytes(t.rxBytes)+' received';$('tx-bytes').textContent=bytes(t.txBytes)+' transmitted';
  $('video-status').textContent=s.video||'Unavailable';$('video-rate').textContent=typeof s.rate==='number'?bytes(s.rate)+'/s':'—';$('reconnects').textContent=number(s.reconnects);
  const peers=$('connections');peers.replaceChildren();
  for(const [name,value] of Object.entries(s.connections||{})) {const row=document.createElement('div'),label=document.createElement('span'),state=document.createElement('span');row.className='peer';label.textContent=name;state.textContent=String(value);row.append(label,state);peers.append(row);}
  if(!peers.childNodes.length) peers.textContent='No active transport connections';
  $('status').textContent=JSON.stringify({traffic:s.traffic,connections:s.connections,video:s.video,rate:s.rate,reconnects:s.reconnects},null,2);
  $('network-info').textContent=[s.network,s.phone,s.web].filter(Boolean).join('\n')||'Device information unavailable';
  $('capabilities').textContent=s.capabilities||'No capability report available';$('events').textContent=(s.logs||[]).join('\n')||'No recent events';
  const saved=s.pending?'Saved settings differ from the active runtime. Apply saved settings from Overview to activate them.':'Saved settings match the active runtime.';
  $('overview-saved').textContent=saved;$('saved-state').textContent=saved+' Web listener changes require Stop service and Start in the Android app.';
}
function offline() {$('connection').textContent='Disconnected · retrying';$('connection').dataset.state='offline';$('runtime-detail').textContent='Status may be out of date';}
async function action(task) {
  if(busy)return;busy=true;document.querySelectorAll('button, input, select, textarea').forEach(b=>b.disabled=true);
  try{await task();}catch(e){if(e instanceof TypeError){offline();message('Cannot reach the phone. Check your connection and try again.',true);}else message(e.message,true);}
  finally{busy=false;document.querySelectorAll('button, input, select, textarea').forEach(b=>b.disabled=false);}
}
$('configuration').addEventListener('input',()=>setDirty(true));
$('configuration').addEventListener('submit',event=>{event.preventDefault();action(async()=>{
  const config={...fields};
  for(const input of $('configuration').elements) if(input.name)config[input.name]=booleans.has(input.name)?String(input.checked):input.value;
  const saved=await api('/api/config',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({revision,config})});
  revision=saved.revision;fields=config;setDirty(false);message('Settings saved. Open Overview to apply them.');await status();
});});
$('apply').onclick=()=>action(async()=>{await api('/api/runtime/apply',{method:'POST'});message('Saved settings applied.');await status();});
$('pause').onclick=()=>action(async()=>{await api('/api/runtime/pause',{method:'POST'});message('Companion paused. Web administration remains available.');await status();});
$('reload').onclick=()=>{if(dirty&&!confirm('Discard unsaved changes and reload saved settings?'))return;action(async()=>{await load();message('Saved settings reloaded.');});};
$('refresh').onclick=()=>action(status);
$('logout').onclick=()=>{if(dirty&&!confirm('Sign out and discard unsaved changes?'))return;action(async()=>{await api('/api/logout',{method:'POST'});location.replace('/login');});};
window.addEventListener('beforeunload',event=>{if(dirty){event.preventDefault();event.returnValue='';}});
window.addEventListener('hashchange',()=>view(location.hash.slice(1)));
view(location.hash.slice(1));
action(async()=>{await load();await status();});
let polling=false;
setInterval(async()=>{if(busy||polling||document.hidden)return;polling=true;try{await status();}catch{offline();}finally{polling=false;}},3000);
