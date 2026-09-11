"""Apply the focused multi-metric extension to the standalone HTML."""
from pathlib import Path

folder = Path(__file__).resolve().parent.parent
p = folder / 'index.html'
s = p.read_text(encoding='utf-8')
assert 'const metricOrder=' not in s
css = '''
.orb-button{position:absolute;inset:0;width:100%;padding:0;border:0;background:transparent;touch-action:pan-y;border-radius:40%;color:var(--muted);cursor:grab}.orb-button:active{cursor:grabbing}.orb-hint{position:absolute;bottom:0;left:0;right:0;font-size:8px;letter-spacing:1px;color:#a9bcd6}.orb-dots{display:inline-flex;gap:5px;margin-right:8px}.orb-dots i{width:3px;height:3px;background:#687d97;border-radius:50%}.orb-dots i.active{background:#c6b4ff;box-shadow:0 0 4px #b69cff66}.hero .readout{will-change:transform}.metrics .metric-value{font-weight:500}.metric-summary-value{font-size:18px;line-height:1.55;white-space:nowrap}.metric-summary-value span{font-size:10px}.metric-summary-label{display:block;font-size:9px;color:var(--muted)}
@media(max-width:360px){.metric-summary-value{font-size:16px}.metric-summary-value span{font-size:9px}.metric-value{white-space:normal}.metric-value span{white-space:nowrap}}
'''
s=s.replace('</style>',css+'\n</style>',1)
s=s.replace('</section>\n<nav class="periods"','<button class="orb-button" id="orb-button" aria-label="Steps. Swipe left or right, or tap, to change metric"><span class="orb-hint"><span class="orb-dots" aria-hidden="true"></span><span id="orb-hint">Swipe for Heart rate →</span></span></button><span class="sr-only" aria-live="polite" aria-atomic="true" id="metric-announcement"></span></section>\n<nav class="periods"',1)
s=s.replace('function movement(){','function movement(){if(metric!==\'steps\')return extraMovement();',1)
s=s.replace('function week(){return windowRows(days===30?30:7).map(r=>({label:dateLabel(r.date),date:r.date,value:r.steps}))}',"function week(){return windowRows(days===30?30:7).map(r=>({label:dateLabel(r.date),date:r.date,value:dailyMetric(r)}))}")
s=s.replace("Math.max(4000,Math.ceil(Math.max(...rows.map(r=>r.value??0))/2000)*2000)","chartCeiling(rows)")
s=s.replace("Math.max(12000,Math.ceil(Math.max(...rows.map(r=>r.value))/2000)*2000)","chartCeiling(rows,true)")
s=s.replace("fmt(max/1000)+'K'","axisText(max)").replace("fmt(max/2000)+'K'","axisText(max/2)")
s=s.replace("fmt(r.value)+' steps'","metricText(r.value)+' '+metricUnit()")
s=s.replace("r.value===null?'Not recorded':fmt(r.value)","r.value===null?'Not recorded':metricText(r.value)")
s=s.replace("${fmt(r.value)} steps","${metricText(r.value)} ${metricUnit()}")
s=s.replace("${fmt(a)} <span>steps</span>","${fmt(a)} <span>steps</span>")
s=s.replace('function render(){','function renderSteps(){',1)
s=s.replace("rows[0].label,rows[Math.floor(rows.length/2)].label,rows.at(-1).label", "rows[0].label,rows[Math.floor(rows.length/2)].label,rows.at(-1).label")
s=s.replace("(days===1?['00:00','06:00','12:00','18:00','24:00']:","(days===1&&['steps','heart'].includes(metric)?['00:00','06:00','12:00','18:00','24:00']:")
s=s.replace("days===1?'Inspect hourly steps':'Inspect daily steps'","days===1?`Inspect ${metricNames[metric]} readings`:`Inspect daily ${metricNames[metric]}`")
s=s.replace("$('#back').disabled=days===1&&selected===anchor;","$('#back').disabled=days===1&&selected===anchor&&metric==='steps';")
s=s.replace("selected=anchor;days=1;render()","selected=anchor;days=1;metric='steps';render()",1)
s=s.replace("const rows=b.dataset.table==='movement'?movement():week();","const rows=b.dataset.table==='movement'?movement():week();")
s=s.replace("$('#table-note').textContent='Demonstration readings. Unrecorded hours are left blank.';","$('#table-note').textContent='Demonstration readings · '+metricUnit()+'. Unrecorded hours are left blank.';")
s=s.replace('<th scope="col">Steps</th>','<th scope="col" id="table-unit">Steps</th>')
s=s.replace("$('#table-heading').textContent=b.dataset.table", "$('#table-unit').textContent=metricNames[metric]+' ('+metricUnit()+')';$('#table-heading').textContent=b.dataset.table")
s=s.replace('<dialog id="options">','<dialog id="options" aria-label="Metric options">').replace('<h2>Steps options</h2>','<h2>Metric options</h2>')
s=s.replace('<dialog id="goal-dialog">','<dialog id="goal-dialog" aria-label="Daily step goal">').replace('<dialog id="date-dialog">','<dialog id="date-dialog" aria-label="Choose date">').replace('<dialog id="readings">','<dialog id="readings" aria-labelledby="table-heading">').replace('<dialog id="about">','<dialog id="about" aria-label="About Orbit">')
extension=(folder/'verification'/'metrics-source.js').read_text(encoding='utf-8')
s=s.replace('function renderSteps(){',extension+'\nfunction renderSteps(){',1)
s=s.replace('\nrender();\n</script>','\ninitOrb();\nrender();\n</script>')
p.write_text(s,encoding='utf-8')
