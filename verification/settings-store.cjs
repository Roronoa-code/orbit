const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict'),path=require('node:path');
const legacy=new Map(),disk=new Map();let fail=false,writes=0;
const context={window:{},localStorage:{getItem:k=>legacy.get(k)??null,setItem:(k,v)=>legacy.set(k,v)}};
vm.createContext(context);vm.runInContext(fs.readFileSync(path.join(__dirname,'../settings-store.js'),'utf8')+';globalThis.store=SettingsStore',context);
const store=context.store,key='orbit-profile-v1';store.write(key,'{"heightCm":178}');assert.equal(store.read(key),'{"heightCm":178}');
context.window.OrbitPreferences={read:k=>disk.get(k),write(k,v){writes++;if(fail)return false;disk.set(k,v);return true}};
assert.equal(store.read('orbit-reduce-motion-v1'),null); // Android maps a Java null String to undefined.
assert.equal(store.read(key),'{"heightCm":178}');assert.equal(disk.get(key),legacy.get(key));assert.equal(writes,1);
legacy.set(key,'old browser value');assert.equal(store.read(key),'{"heightCm":178}');assert.equal(writes,1);
fail=true;assert.throws(()=>store.write(key,'replacement'),/not saved/);assert.equal(disk.get(key),'{"heightCm":178}');
legacy.set('orbit-steps-goal-v1','12000');assert.throws(()=>store.read('orbit-steps-goal-v1'),/not saved/);assert.equal(legacy.get('orbit-steps-goal-v1'),'12000');
fail=false;assert.equal(store.read('orbit-steps-goal-v1'),'12000');store.write('orbit-reduce-motion-v1','true');assert.equal(disk.get('orbit-reduce-motion-v1'),'true');
assert.throws(()=>store.write('workouts','[]'),/Invalid/);assert.throws(()=>store.read('workouts'),/Unknown/);assert.throws(()=>store.write(key,'x'.repeat(4097)),/Invalid/);
console.log('PASS: browser fallback, native acknowledgement, one-time migration, native precedence, failed writes/migration, key and size bounds.');
