import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';

// Exercise the actual action coordinator with a pending network operation.
// Layout and browser APIs still need a real browser acceptance check.
function admin() {
  const elements=new Map();
  const element=()=>({textContent:'',hidden:false,disabled:false,dataset:{},addEventListener(){}});
  const controls=[element(),element(),element(),element()];
  const document={
    hidden:false,
    getElementById(id){if(!elements.has(id))elements.set(id,element());return elements.get(id);},
    querySelectorAll(selector){
      if(selector==='button')return controls.slice(0,1);
      if(selector==='button, input, select, textarea')return controls;
      return [];
    }
  };
  const context=vm.createContext({document,window:{addEventListener(){}},location:{hash:'',replace(){}},fetch:()=>new Promise(()=>{}),setInterval(){},setTimeout(){},clearTimeout(){},console});
  vm.runInContext(fs.readFileSync(new URL('../webserver/src/main/resources/web/app.js',import.meta.url),'utf8'),context);
  vm.runInContext('busy=false',context);
  return {context,controls};
}
test('pending save locks all editable controls until completion',async()=>{
  const {context,controls}=admin();
  let complete;
  const pending=context.action(()=>new Promise(resolve=>{complete=resolve;}));
  for(const control of controls) assert.equal(control.disabled,true,'Inputs must not accept edits during a save');
  complete();await pending;
  for(const control of controls) assert.equal(control.disabled,false);
});
test('failed request unlocks controls and shows the error',async()=>{
  const {context,controls}=admin();
  await context.action(async()=>{throw new Error('Save rejected');});
  for(const control of controls) assert.equal(control.disabled,false);
  assert.equal(context.document.getElementById('message').textContent,'Save rejected');
});
