/* A shared dot alphabet for focal readings; the countdown briefly sets the dots free. */
'use strict';
const HeroDots=(()=>{
  const glyphs={
    '0':['01110','11011','11011','11011','11011','11011','01110'],
    '1':['01100','11100','01100','01100','01100','01100','11110'],
    '2':['01110','11011','00011','00110','01100','11000','11111'],
    '3':['11110','00011','00011','01110','00011','00011','11110'],
    '4':['00011','00111','01111','11011','11111','00011','00011'],
    '5':['11111','11000','11000','11110','00011','00011','11110'],
    '6':['01110','11000','11000','11110','11011','11011','01110'],
    '7':['11111','00011','00110','00110','01100','01100','01100'],
    '8':['01110','11011','11011','01110','11011','11011','01110'],
    '9':['01110','11011','11011','01111','00011','00011','01110'],
    ':':['0','1','1','0','1','1','0'],'.':['0','0','0','0','0','1','1'],
    ',':['00','00','00','00','00','01','10'],'-':['000','000','000','111','000','000','000']
  };
  function layout(value){let offset=0;const points=[];for(const char of String(value)){const rows=glyphs[char];if(!rows)continue;rows.forEach((row,y)=>[...row].forEach((v,x)=>{if(v==='1')points.push({x:offset+x+.5,y:y+.5})}));offset+=rows[0].length+1}return {points,width:Math.max(1,offset-1)}}
  function markup(value){const {points,width}=layout(value);return `<svg class="matrix-reading" viewBox="0 0 ${width} 7" aria-hidden="true"><title>${String(value).replace(/[^0-9:.,-]/g,'')}</title>${points.map(p=>`<circle cx="${p.x}" cy="${p.y}" r=".32"/>`).join('')}</svg>`}
  function mount(canvas,value){
    const context=canvas.getContext('2d'),reduced=matchMedia('(prefers-reduced-motion: reduce)');
    const size=300,dpr=Math.min(2,window.devicePixelRatio||1),particles=[];
    let frame=0,last=0,stopped=false,pointer=null,current=value;
    canvas.width=size*dpr;canvas.height=size*dpr;context.scale(dpr,dpr);
    function draw(){context.clearRect(0,0,size,size);for(const p of particles){context.globalAlpha=p.alpha;context.fillStyle=p.bright?'#bda3ff':'#f4f4f6';context.beginPath();context.arc(p.x.value,p.y.value,4.4,0,Math.PI*2);context.fill()}context.globalAlpha=1}
    function step(time){
      if(stopped||document.hidden)return;
      const dt=Math.min(.04,Math.max(.001,(time-last)/1000));last=time;let moving=false;
      for(const p of particles){let x=p.tx,y=p.ty;
        if(pointer&&!reduced.matches){const dx=x-pointer.x,dy=y-pointer.y,d=Math.hypot(dx,dy);if(d<78){const force=(78-d)*.8;x+=dx/Math.max(1,d)*force;y+=dy/Math.max(1,d)*force}}
        springStep(p.x,x,dt,13);springStep(p.y,y,dt,13);p.alpha+=(p.visible-p.alpha)*Math.min(1,dt*14);
        moving ||= Math.abs(p.x.value-x)+Math.abs(p.y.value-y)> .15||Math.abs(p.x.velocity)+Math.abs(p.y.velocity)>.5||Math.abs(p.alpha-p.visible)>.01;
      }draw();frame=moving||pointer?requestAnimationFrame(step):0;
    }
    function wake(){if(!frame&&!stopped){last=performance.now();frame=requestAnimationFrame(step)}}
    function set(next){
      current=next;
      const shape=layout(next),spacing=24,start=(size-shape.width*spacing)/2;
      while(particles.length<shape.points.length){const i=particles.length,angle=i*2.399;particles.push({x:{value:150+Math.cos(angle)*128,velocity:0},y:{value:150+Math.sin(angle)*128,velocity:0},alpha:0,bright:i%6===0})}
      particles.forEach((p,i)=>{const to=shape.points[i];p.visible=to?1:0;p.tx=to?start+to.x*spacing:150+Math.cos(i*2.399)*134;p.ty=to?66+to.y*spacing:150+Math.sin(i*2.399)*134;if(reduced.matches){p.x.value=p.tx;p.y.value=p.ty;p.alpha=p.visible;p.x.velocity=p.y.velocity=0}});
      if(reduced.matches)draw();else wake();
    }
    function touch(e){const bounds=canvas.getBoundingClientRect();pointer={x:(e.clientX-bounds.left)/bounds.width*size,y:(e.clientY-bounds.top)/bounds.height*size};wake()}
    function down(e){if(!e.isPrimary||reduced.matches)return;canvas.setPointerCapture(e.pointerId);touch(e)}
    function move(e){if(canvas.hasPointerCapture(e.pointerId))touch(e)}
    function up(){pointer=null;wake()}
    const events={pointerdown:down,pointermove:move,pointerup:up,pointercancel:up,lostpointercapture:up};
    for(const [type,fn] of Object.entries(events))canvas.addEventListener(type,fn);
    function motionPreference(){if(reduced.matches){cancelAnimationFrame(frame);frame=0;pointer=null;set(current)}}
    reduced.addEventListener('change',motionPreference);
    set(value);
    return {set,stop(){stopped=true;cancelAnimationFrame(frame);reduced.removeEventListener('change',motionPreference);for(const [type,fn] of Object.entries(events))canvas.removeEventListener(type,fn)}};
  }
  return {layout,markup,mount};
})();
