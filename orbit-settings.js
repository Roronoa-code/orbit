/* Personal defaults stay separate from imported measurements and workout history. */
'use strict';
const OrbitSettings=(()=>{
  const key='orbit-profile-v1',q=s=>document.querySelector(s);
  let error='';
  const today=()=>{const d=new Date();return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`};
  function validate(value){
    if(!value||typeof value!=='object'||Array.isArray(value))return 'Your saved profile could not be read.';
    if(typeof value.name!=='string'||value.name.length>80)return 'Use a name of up to 80 characters.';
    const dob=value.birthDate;
    if(typeof dob!=='string'||dob&&(!/^\d{4}-\d{2}-\d{2}$/.test(dob)||dob<'1900-01-01'||dob>today()||!Number.isFinite(Date.parse(dob))||new Date(dob).toISOString().slice(0,10)!==dob))return 'Enter a valid birth date, up to today.';
    for(const [field,label,min,max,unit] of [['heightCm','height',40,260,'cm'],['weightKg','weight',20,350,'kg']]){
      const n=value[field];if(n!==null&&(!Number.isFinite(n)||n<min||n>max||Math.abs(n*10-Math.round(n*10))>.000001))return `Enter ${label} from ${min} to ${max} ${unit}, with up to one decimal place, or leave it empty.`;
    }
    return '';
  }
  function profile(){
    error='';try{const raw=SettingsStore.read(key);if(raw===null)return {};const value=JSON.parse(raw);if(validate(value))throw Error('Invalid profile');return value}
    catch{error='Your saved profile could not be read. It has been preserved.';return {}}
  }
  function view(){return `<section class="settings-section settings-health"><h2>Samsung Health</h2><p id="health-live-status" class="settings-note" role="status"></p><button class="settings-action" id="health-live">Connect live steps</button><p id="health-import-status" class="settings-note" role="status"></p><div class="actions"><button class="settings-action" id="health-connect">Connect</button><button class="settings-action" id="health-sync">Import now</button></div><details class="settings-about"><summary>Connection &amp; history</summary><div class="details-body"><p>In Samsung Health → Settings → Health Connect, allow Samsung Health to share your data. Then connect Orbit and allow the readings you want.</p><p id="health-import-coverage"></p><p>Live steps use Samsung’s combined phone and watch total. Watch steps appear after the watch syncs. This personal development build needs Samsung Health → Settings → About Samsung Health → tap the version ten times → Developer mode → Data Read, then Connect live steps.</p><p>Older history appears only if Samsung has shared it. All records stay on this phone.</p><button class="settings-action" id="health-permissions">Health permissions</button></div></details></section><form id="profile-form" class="settings-profile" novalidate>
    <h2>Profile</h2><p class="settings-note">Your defaults for new workouts.</p>
    <div class="settings-fields"><label>Name<input id="profile-name" autocomplete="name" maxlength="80" placeholder="Optional"/></label>
    <label>Date of birth<input id="profile-birth" inputmode="numeric" maxlength="10" placeholder="DD / MM / YYYY" autocomplete="bday"/></label>
    <div class="settings-measurements"><label>Height<span><input id="profile-height" type="number" min="40" max="260" step="0.1" inputmode="decimal" placeholder="—"/><small>cm</small></span></label>
    <label>Weight<span><input id="profile-weight" type="number" min="20" max="350" step="0.1" inputmode="decimal" placeholder="—"/><small>kg</small></span></label></div></div>
    <p id="profile-error" class="health-error" role="alert"></p><p id="profile-saved" class="settings-saved" role="status"></p><button class="workout-primary" type="submit">Save profile</button></form>
    <section class="settings-section"><h2>Daily goal</h2><form id="settings-goal-form" novalidate><div class="settings-goal-row"><label class="settings-goal">Steps<input id="settings-goal" type="number" min="100" max="100000" step="100" inputmode="numeric" required/></label><button type="submit" class="settings-action">Save</button></div><p id="settings-goal-error" class="health-error" role="alert"></p></form></section>
    <section class="settings-section"><h2>Motion</h2><label class="tracking-option"><span>Reduce motion</span><input id="settings-reduce" type="checkbox" role="switch"/></label><label class="tracking-option"><span>Globe rotation</span><input id="settings-rotation" type="checkbox" role="switch"/></label><p class="health-error" id="settings-motion-error" role="alert"></p></section>
    <section class="settings-section"><h2>Music</h2><button class="music-access" data-music-connect ${window.OrbitMusic?'':'disabled'}>${MusicPlayer.accessLabel()}</button></section>
    <details class="settings-section settings-about"><summary>About Orbit</summary><div class="details-body"><p>Samsung Health supplies your shared readings. Orbit keeps a private local copy and does not change Samsung Health. Your profile supplies workout defaults; past sessions keep their original values.</p></div></details>`}
  function healthStatus(){
    if(!q('#health-import-status'))return;
    const info=HealthData.info,meta=HealthData.meta;
    q('#health-live-status').textContent=info.live?.status||'Connect for live phone and watch steps';q('#health-live').disabled=!window.OrbitHealth?.connectLive;q('#health-live').textContent=info.live?.connected?'Live steps access':'Connect live steps';
    q('#health-import-status').textContent=info.status+(info.syncing&&info.scanned?' · '+info.scanned.toLocaleString()+' records':'');
    q('#health-connect').disabled=!info.available||info.syncing;q('#health-connect').textContent=info.permitted?'Access':'Connect';
    q('#health-sync').disabled=!info.permitted||info.syncing;q('#health-permissions').disabled=!info.available;
    q('#health-import-coverage').textContent=meta.lastSync?`${meta.recordCount.toLocaleString()} records saved · ${new Date(meta.lastSync).toLocaleString('en-GB')}. ${meta.historyAllowed?'Extended history access enabled.':'Recent shared history only. Allow history access to import older records.'}`:'No records imported yet.';
  }
  function mount(options){
    healthStatus();q('#health-live').addEventListener('click',()=>window.OrbitHealth?.connectLive());q('#health-connect').addEventListener('click',()=>window.OrbitHealth?.connect());q('#health-sync').addEventListener('click',()=>window.OrbitHealth?.sync());q('#health-permissions').addEventListener('click',()=>window.OrbitHealth?.permissions());
    const value=profile(),form=q('#profile-form');
    for(const [id,field] of [['name','name'],['birth','birthDate'],['height','heightCm'],['weight','weightKg']])q('#profile-'+id).value=value[field]??'';
    if(value.birthDate)q('#profile-birth').value=value.birthDate.split('-').reverse().join('/');
    q('#profile-error').textContent=error;form.querySelector('button[type=submit]').disabled=Boolean(error);
    form.addEventListener('input',event=>{
      const input=event.target;
      if(input.id==='profile-birth'&&/^[\d/]*$/.test(input.value)){
        const at=input.selectionStart,digitsBefore=input.value.slice(0,at).replace(/\D/g,'').length;
        input.value=input.value.replace(/\D/g,'').replace(/^(\d{2})(\d)/,'$1/$2').replace(/^(\d{2}\/\d{2})(\d)/,'$1/$2');
        const caret=digitsBefore+(digitsBefore>2?1:0)+(digitsBefore>4?1:0);input.setSelectionRange(caret,caret);
      }
      q('#profile-saved').textContent='';q('#profile-error').textContent=error;
    });
    form.addEventListener('submit',event=>{
      event.preventDefault();if(error)return;
      const number=id=>q(id).value===''?null:Number(q(id).value);
      const birth=q('#profile-birth').value.trim(),parts=birth.match(/^(\d{1,2})\s*\/\s*(\d{1,2})\s*\/\s*(\d{4})$/);
      const birthDate=parts?`${parts[3]}-${parts[2].padStart(2,'0')}-${parts[1].padStart(2,'0')}`:birth;
      const next={name:q('#profile-name').value.trim(),birthDate,heightCm:number('#profile-height'),weightKg:number('#profile-weight')};
      const invalid=validate(next)||([...form.querySelectorAll('input')].some(n=>n.validity.badInput)?'Enter a valid number for height and weight.':'');
      if(invalid){q('#profile-error').textContent=invalid;return}
      try{SettingsStore.write(key,JSON.stringify(next))}
      catch{q('#profile-error').textContent='Could not confirm the save. Keep this page open and try again.';return}
      q('#profile-error').textContent='';q('#profile-saved').textContent='Profile saved';
    });
    q('#settings-goal').value=options.getGoal();
    q('#settings-goal-form').addEventListener('submit',event=>{event.preventDefault();const message=options.saveGoal(Number(q('#settings-goal').value));q('#settings-goal-error').textContent=message||''});
    q('#settings-reduce').checked=SurfaceMotion.reduced;q('#settings-rotation').checked=!options.paused();
    q('#settings-reduce').addEventListener('change',event=>{
      try{options.reduce(event.target.checked);q('#settings-rotation').checked=!options.paused();q('#settings-motion-error').textContent=''}
      catch{event.target.checked=SurfaceMotion.reduced;q('#settings-motion-error').textContent='Could not save the motion setting. Try again.'}
    });
    q('#settings-rotation').addEventListener('change',event=>options.rotate(event.target.checked));
  }
  return {view,mount,profile,validate,healthStatus,get error(){return error}};
})();
