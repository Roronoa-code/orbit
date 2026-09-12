/* Personal defaults are separate from demonstration measurements and workout history. */
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
    error='';try{const raw=localStorage.getItem(key);if(raw===null)return {};const value=JSON.parse(raw);if(validate(value))throw Error('Invalid profile');return value}
    catch{error='Your saved profile could not be read. It has been preserved.';return {}}
  }
  function view(){return `<form id="profile-form" class="settings-profile" novalidate>
    <h2>Your profile</h2><p class="settings-note">Set it once. Your saved weight fills in automatically for new workouts.</p>
    <div class="settings-fields"><label>Name<input id="profile-name" autocomplete="name" maxlength="80" placeholder="Your name"/></label>
    <label>Date of birth<input id="profile-birth" type="date" min="1900-01-01" max="${today()}" autocomplete="bday"/></label>
    <div class="settings-measurements"><label>Height<span><input id="profile-height" type="number" min="40" max="260" step="0.1" inputmode="decimal" placeholder="—"/><small>cm</small></span></label>
    <label>Weight<span><input id="profile-weight" type="number" min="20" max="350" step="0.1" inputmode="decimal" placeholder="—"/><small>kg</small></span></label></div></div>
    <p class="settings-note">All fields are optional. Energy estimates use your weight; previous workouts keep their original values.</p>
    <p id="profile-error" class="health-error" role="alert"></p><p id="profile-saved" class="settings-saved" role="status"></p><button class="workout-primary" type="submit">Save profile</button></form>
    <section class="settings-section"><h2>Daily goal</h2><form id="settings-goal-form" novalidate><label class="settings-goal">Steps per day<input id="settings-goal" type="number" min="100" max="100000" step="100" inputmode="numeric" required/></label><p id="settings-goal-error" class="health-error" role="alert"></p><button type="submit" class="settings-action">Save goal</button></form></section>
    <section class="settings-section"><h2>Motion</h2><label class="tracking-option"><span>Reduce motion<small>Short fades instead of large movements</small></span><input id="settings-reduce" type="checkbox" role="switch"/></label><label class="tracking-option"><span>Globe rotation<small>Rotate the globe on the Home screen</small></span><input id="settings-rotation" type="checkbox" role="switch"/></label><p class="health-error" id="settings-motion-error" role="alert"></p></section>
    <section class="settings-section"><h2>Music</h2><button class="music-access" data-music-connect ${window.OrbitMusic?'':'disabled'}>${MusicPlayer.accessLabel()}</button></section>
    <section class="settings-section settings-about"><h2>About Orbit</h2><p>Workouts and your profile stay on this device. Home and Body charts currently use labelled demonstration data; they do not use your profile as recorded measurements.</p></section>`}
  function mount(options){
    const value=profile(),form=q('#profile-form');
    for(const [id,field] of [['name','name'],['birth','birthDate'],['height','heightCm'],['weight','weightKg']])q('#profile-'+id).value=value[field]??'';
    q('#profile-error').textContent=error;form.querySelector('button[type=submit]').disabled=Boolean(error);
    form.addEventListener('input',()=>{q('#profile-saved').textContent='';q('#profile-error').textContent=error});
    form.addEventListener('submit',event=>{
      event.preventDefault();if(error)return;
      const number=id=>q(id).value===''?null:Number(q(id).value);
      const next={name:q('#profile-name').value.trim(),birthDate:q('#profile-birth').value,heightCm:number('#profile-height'),weightKg:number('#profile-weight')};
      const invalid=validate(next)||([...form.querySelectorAll('input')].some(n=>n.validity.badInput)?'Enter a valid number for height and weight.':'');
      if(invalid){q('#profile-error').textContent=invalid;return}
      try{const raw=JSON.stringify(next);localStorage.setItem(key,raw);if(localStorage.getItem(key)!==raw)throw Error('Save not confirmed')}
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
  return {view,mount,profile,validate,get error(){return error}};
})();
