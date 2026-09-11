/* Persistent Signal particle globe: direct input and interruptible movement. */
'use strict';
const SignalOrb = (() => {
  const order = ['steps', 'heart', 'sleep', 'intake'];
  const turn = Math.PI * 2 / order.length;
  const points = Array.from({ length: 19 * 34 }, (_, i) => {
    const latitude = (Math.floor(i / 34) / 18 - .5) * Math.PI;
    const longitude = (i % 34) / 34 * Math.PI * 2;
    return [Math.cos(latitude) * Math.cos(longitude), Math.sin(latitude), Math.cos(latitude) * Math.sin(longitude)];
  });
  function project([x, y, z], angle) {
    const dx = x * Math.cos(angle) + z * Math.sin(angle);
    const depth = -x * Math.sin(angle) + z * Math.cos(angle);
    const perspective = 1 / (1 - depth * .12);
    return { x: 170 + dx * 120 * perspective, y: 130 + y * 111 * perspective, r: .65 + (depth + 1) * .5, opacity: .1 + (depth + 1) * .33 };
  }
  function direction(dx, velocity) {
    if (Math.abs(dx) >= 45 || (Math.abs(dx) >= 12 && Math.abs(velocity) > .45 && Math.sign(dx) === Math.sign(velocity))) return dx < 0 ? 1 : -1;
    return 0;
  }
  const next = (selected, delta, keys = order) => keys[(keys.indexOf(selected) + delta + keys.length) % keys.length];
  const idleTurn = milliseconds => milliseconds / 90000 * Math.PI * 2;
  function markup() { return '<canvas id="orb-canvas" width="680" height="520" aria-hidden="true"></canvas>'; }
  function attach(element, { selected, onSelect, onOpen, keys = order }) {
    const turn = Math.PI * 2 / keys.length;
    const canvas = element.querySelector('canvas'), ctx = canvas.getContext('2d');
    let width=340,height=260,dpr=1;
    const controller = new AbortController();
    const listen = (target, name, handler) => target.addEventListener(name, handler, { signal: controller.signal });
    let angle = -keys.indexOf(selected) * turn, rest = angle, frame = 0, gesture = null, suppressClick = false;
    const rotation={value:angle,velocity:0},breath={value:0,velocity:0};
    let idleFrame = 0, lastIdle = 0, visible = true, settling = false, destroyed = false, paused = false, suspended = false;
    function stopIdle() { cancelAnimationFrame(idleFrame); idleFrame = 0; lastIdle = 0; }
    function startIdle() {
      if (idleFrame || destroyed || paused || suspended || !visible || document.hidden || gesture || settling) return;
      function tick(now) {
        if (destroyed || paused || suspended || !visible || document.hidden || gesture || settling) { stopIdle(); return; }
        if (!lastIdle) lastIdle = now;
        const elapsed = now - lastIdle;
        if (elapsed >= 1000 / 30) { rest += idleTurn(Math.min(elapsed, 60)); draw(rest); lastIdle = now; }
        idleFrame = requestAnimationFrame(tick);
      }
      idleFrame = requestAnimationFrame(tick);
    }
    function draw(value) {
      angle=value;
      ctx.setTransform(1,0,0,1,0,0);ctx.clearRect(0,0,canvas.width,canvas.height);
      const scale=Math.min(width/340,height/260);
      ctx.setTransform(scale*dpr,0,0,scale*dpr,(width-340*scale)*dpr/2,(height-260*scale)*dpr/2);
      ctx.fillStyle='#e8e8ee';
      for(const point of points){const p=project(point,angle),scale=1+breath.value;ctx.globalAlpha=p.opacity;ctx.beginPath();ctx.arc(170+(p.x-170)*scale,130+(p.y-130)*scale,p.r,0,Math.PI*2);ctx.fill()}
      ctx.globalAlpha=1;
    }
    function resize(){
      width=canvas.clientWidth||340;height=canvas.clientHeight||260;dpr=Math.min(window.devicePixelRatio||1,2);
      canvas.width=Math.round(width*dpr);canvas.height=Math.round(height*dpr);draw(angle);
    }
    const sizeObserver=new ResizeObserver(resize);sizeObserver.observe(canvas);
    function settle() {
      stopIdle();
      cancelAnimationFrame(frame);
      if (paused || document.hidden || !visible) { settling = false; draw(rest); return; }
      settling = true;
      rotation.value=angle;let last=performance.now();
      function tick(now) {
        const dt=Math.min(.05,Math.max(0,(now-last)/1000));last=now;
        springStep(rotation,rest,dt,12);springStep(breath,0,dt,13);draw(rotation.value);
        if(Math.abs(rotation.value-rest)>.0005||Math.abs(rotation.velocity)>.005||Math.abs(breath.velocity)>.005)frame=requestAnimationFrame(tick);
        else{rotation.value=rest;rotation.velocity=0;breath.value=breath.velocity=0;draw(rest);settling=false;frame=0;startIdle()}
      }
      frame = requestAnimationFrame(tick);
    }
    function turnPeriod(){rest-=.72;breath.velocity-=1.35;settle()}
    function select(key) {
      if (key === selected) return;
      stopIdle();
      let delta = (keys.indexOf(key) - keys.indexOf(selected) + keys.length) % keys.length;
      if (delta > keys.length / 2) delta -= keys.length;
      selected = key;
      rest -= delta * turn;
      breath.velocity-=1.1;
      settle();
    }
    function sample(event) {
      const g = gesture;
      g.dx = event.clientX - g.x;
      g.dy = event.clientY - g.y;
      g.moved = Math.max(g.moved, Math.hypot(g.dx, g.dy));
      g.samples.push({ x: event.clientX, t: event.timeStamp });
      g.samples = g.samples.filter(p => event.timeStamp - p.t <= 100);
    }
    function cancel() {
      if (!gesture) return;
      const id = gesture.id;
      gesture = null;
      suppressClick = true;
      element.classList.remove('dragging');
      if (element.hasPointerCapture(id)) element.releasePointerCapture(id);
      settle();
    }
    listen(element, 'pointerdown', event => {
      if (!event.isPrimary || event.button !== 0) return;
      stopIdle(); settling = false;
      cancelAnimationFrame(frame);
      suppressClick = false;
      gesture = { id: event.pointerId, x: event.clientX, y: event.clientY, dx: 0, dy: 0, moved: 0, axis: null, angle, samples: [{ x: event.clientX, t: event.timeStamp }] };
    });
    listen(element, 'pointermove', event => {
      if (!gesture || event.pointerId !== gesture.id) return;
      sample(event);
      const g = gesture;
      if (!g.axis && g.moved > 8) {
        if (Math.abs(g.dx) > 8 && Math.abs(g.dx) > Math.abs(g.dy) * 1.2) {
          g.axis = 'horizontal';
          element.setPointerCapture(event.pointerId);
          element.classList.add('dragging');
        } else if (Math.abs(g.dy) > 8) g.axis = 'vertical';
      }
      if (g.axis !== 'horizontal') return;
      event.preventDefault();
      if (!paused) {
        cancelAnimationFrame(frame);
        const target = g.angle + Math.max(-1.7, Math.min(1.7, g.dx * .013));
        frame = requestAnimationFrame(() => draw(target));
      }
    });
    listen(element, 'pointerup', event => {
      if (!gesture || event.pointerId !== gesture.id) return;
      sample(event);
      const g = gesture, start = g.samples[0], end = g.samples.at(-1);
      const velocity = end.t > start.t ? (end.x - start.x) / (end.t - start.t) : 0;
      const delta = g.axis === 'horizontal' ? direction(g.dx, velocity) : 0;
      rotation.velocity=g.axis==='horizontal'?Math.max(-15,Math.min(15,velocity*13)):0;
      suppressClick = g.moved > 8 || g.axis !== null;
      gesture = null;
      element.classList.remove('dragging');
      if (element.hasPointerCapture(event.pointerId)) element.releasePointerCapture(event.pointerId);
      if (delta) onSelect(next(selected, delta, keys));
      else settle();
    });
    listen(element, 'pointercancel', cancel);
    listen(element, 'lostpointercapture', cancel);
    listen(element, 'pointerleave', () => { if (gesture && gesture.axis !== 'horizontal') cancel(); });
    listen(element, 'click', event => {
      if (suppressClick && event.detail !== 0) { event.preventDefault(); event.stopPropagation(); suppressClick = false; return; }
      onOpen(element);
    });
    listen(element, 'keydown', event => {
      if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
        event.preventDefault();
        cancel();
        onSelect(next(selected, event.key === 'ArrowRight' ? 1 : -1, keys));
      }
      if (event.key === 'Enter' || event.key === ' ') suppressClick = false;
    });
    listen(window, 'blur', () => { cancel(); stopIdle(); });
    listen(window, 'focus', startIdle);
    listen(document, 'visibilitychange', () => {
      if (document.hidden) { cancel(); stopIdle(); cancelAnimationFrame(frame); settling = false; draw(rest); }
      else startIdle();
    });
    const observer = new IntersectionObserver(entries => {
      visible = entries[0].isIntersecting;
      if (!visible) { stopIdle(); cancelAnimationFrame(frame); settling = false; }
      else startIdle();
    });
    observer.observe(element);
    resize();
    startIdle();
    return { select,turnPeriod, setSuspended(value) { suspended=Boolean(value);stopIdle();if(suspended){cancelAnimationFrame(frame);settling=false;}else startIdle(); }, setPaused(value) { paused = Boolean(value); stopIdle(); cancelAnimationFrame(frame); settling = false; draw(rest); startIdle(); }, destroy() { destroyed = true; controller.abort(); observer.disconnect();sizeObserver.disconnect(); stopIdle(); cancelAnimationFrame(frame); gesture = null; } };
  }
  return { order, points, project, direction, next, markup, attach, idleTurn };
})();
