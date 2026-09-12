/* One storage boundary for profile, goal and motion; browser preview stays localStorage-only. */
'use strict';
const SettingsStore=(()=>{
  const allowed=new Set(['orbit-profile-v1','orbit-steps-goal-v1','orbit-reduce-motion-v1']);
  function write(key,value){
    if(!allowed.has(key)||typeof value!=='string'||value.length>4096)throw Error('Invalid setting');
    const native=window.OrbitPreferences;
    if(native){if(!native.write(key,value)||native.read(key)!==value)throw Error('Setting was not saved')}
    else{localStorage.setItem(key,value);if(localStorage.getItem(key)!==value)throw Error('Setting was not saved')}
  }
  function read(key){
    if(!allowed.has(key))throw Error('Unknown setting');
    const native=window.OrbitPreferences;if(!native)return localStorage.getItem(key);
    let value=native.read(key)??null;if(value!==null&&typeof value!=='string')throw Error('Setting could not be read');
    if(value===null){const previous=localStorage.getItem(key);if(previous!==null){write(key,previous);value=previous}}
    return value;
  }
  return {read,write};
})();
