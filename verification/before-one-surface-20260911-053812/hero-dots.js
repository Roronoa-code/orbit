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
  function layout(value){let offset=0,slot=0;const points=[];for(const char of String(value)){const rows=glyphs[char];if(!rows)continue;rows.forEach((row,y)=>[...row].forEach((v,x)=>{if(v==='1')points.push({x:offset+x+.5,y:y+.5,key:slot+':'+x+':'+y})}));offset+=rows[0].length+1;slot++}return {points,width:Math.max(1,offset-1)}}
  function markup(value){const {points,width}=layout(value);return `<svg class="matrix-reading" viewBox="0 0 ${width} 7" aria-hidden="true"><title>${String(value).replace(/[^0-9:.,-]/g,'')}</title>${points.map(p=>`<circle cx="${p.x}" cy="${p.y}" r=".32"/>`).join('')}</svg>`}
  function mount(canvas,value,wide=false){
    const context=canvas.getContext('2d');
    const size=wide?390:300,height=wide?116:300,dpr=Math.min(2,window.devicePixelRatio||1),particles=[],cells=new Map();
    let frame=0,last=0,stopped=false,pointer=null,current;
    canvas.width=size*dpr;canvas.height=height*dpr;context.scale(dpr,dpr);
    function draw(){context.clearRect(0,0,size,height);for(const p of particles){context.globalAlpha=p.alpha;context.fillStyle=p.bright?(wide?'#c9c4c4':'#bda3ff'):'#f4f4f6';context.beginPath();context.arc(p.x.value,p.y.value,wide?2.9:4.4,0,Math.PI*2);context.fill()}context.globalAlpha=1}
    function step(time){
      frame=0;if(stopped||document.hidden)return;
      const dt=Math.min(.04,Math.max(.001,(time-last)/1000));last=time;let moving=false;
      for(const p of particles){let x=p.tx,y=p.ty;
        if(pointer){const dx=x-pointer.x,dy=y-pointer.y,d=Math.hypot(dx,dy);if(d<78){const force=(78-d)*.8;x+=dx/Math.max(1,d)*force;y+=dy/Math.max(1,d)*force}}
        springStep(p.x,x,dt,13);springStep(p.y,y,dt,13);p.alpha+=(p.visible-p.alpha)*Math.min(1,dt*14);
        moving ||= Math.abs(p.x.value-x)+Math.abs(p.y.value-y)> .15||Math.abs(p.x.velocity)+Math.abs(p.y.velocity)>.5||Math.abs(p.alpha-p.visible)>.01;
      }draw();frame=moving||pointer?requestAnimationFrame(step):0;
    }
    function wake(){if(!frame&&!stopped){last=performance.now();frame=requestAnimationFrame(step)}}
    function set(next){
      if(String(next)===current)return;current=String(next);
      const shape=layout(next),spacing=wide?Math.min(12,(size-36)/shape.width):24,start=(size-shape.width*spacing)/2,top=(height-7*spacing)/2;
      if(wide){
        // Keep each clock cell in place: a changing second must not reshuffle the minutes.
        for(const p of particles)p.visible=0;
        for(const to of shape.points){
          let p=cells.get(to.key);const x=start+to.x*spacing,y=top+to.y*spacing;
          if(!p){p={x:{value:x,velocity:0},y:{value:y+7,velocity:0},alpha:0,bright:false};cells.set(to.key,p);particles.push(p)}
          p.tx=x;p.ty=y;p.visible=1;
        }
        wake();return;
      }
      while(particles.length<shape.points.length){const i=particles.length,angle=i*2.399;particles.push({x:{value:size/2+Math.cos(angle)*size*.42,velocity:0},y:{value:height/2+Math.sin(angle)*height*.42,velocity:0},alpha:0,bright:i%6===0})}
      particles.forEach((p,i)=>{const to=shape.points[i];p.visible=to?1:0;p.tx=to?start+to.x*spacing:size/2+Math.cos(i*2.399)*size*.44;p.ty=to?top+to.y*spacing:height/2+Math.sin(i*2.399)*height*.44;});
      wake();
    }
    function touch(e){const bounds=canvas.getBoundingClientRect();pointer={x:(e.clientX-bounds.left)/bounds.width*size,y:(e.clientY-bounds.top)/bounds.height*height};wake()}
    function down(e){if(!e.isPrimary)return;canvas.setPointerCapture(e.pointerId);touch(e)}
    function move(e){if(canvas.hasPointerCapture(e.pointerId))touch(e)}
    function up(){pointer=null;wake()}
    const events={pointerdown:down,pointermove:move,pointerup:up,pointercancel:up,lostpointercapture:up};
    for(const [type,fn] of Object.entries(events))canvas.addEventListener(type,fn);
    function visibility(){cancelAnimationFrame(frame);frame=0;pointer=null;if(!document.hidden)wake()}
    document.addEventListener('visibilitychange',visibility);
    set(value);
    return {set,stop(){stopped=true;cancelAnimationFrame(frame);document.removeEventListener('visibilitychange',visibility);for(const [type,fn] of Object.entries(events))canvas.removeEventListener(type,fn)}};
  }
  function patternPoint(i,shape){const a=i*2.399,r=6+Math.sqrt(i)*5.1;return shape===1?{x:70+Math.cos(i/72*Math.PI*2)*(i%2?40:22),y:38+Math.sin(i/72*Math.PI*2)*(i%2?27:15)}:shape===2?{x:12+i%18*6.8,y:20+Math.floor(i/18)*11+Math.sin(i%18*.47)*8}:{x:70+Math.cos(a)*r,y:38+Math.sin(a)*r*.64}}
  function pattern(shape=0){return `<svg viewBox="0 0 140 76" aria-hidden="true">${Array.from({length:72},(_,i)=>{const p=patternPoint(i,shape);return `<circle r="${i%7===0?2.1:1.5}" style="transform:translate(${p.x}px,${p.y}px);opacity:${i%4===0?1:.5}"/>`}).join('')}</svg>`}
  function reshape(button,shape){button.querySelectorAll('circle').forEach((node,i)=>{const p=patternPoint(i,shape);node.style.transform=`translate(${p.x}px,${p.y}px)`})}
  return {layout,markup,mount,pattern,reshape};
})();
