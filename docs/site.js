const svg = document.querySelector('#plot');
const controls = ['x-axis', 'y-axis', 'neighbors'].map(id => document.getElementById(id));
const status = document.querySelector('#selection');
const labels = {battery:'Battery (%)',temperature:'Temperature (°F)',history:'History (minutes)'};
const ns = 'http://www.w3.org/2000/svg';
let data = [];
function element(tag, attributes, text) {
  const node = document.createElementNS(ns,tag);
  for (const [key,value] of Object.entries(attributes)) node.setAttribute(key,value);
  if (text !== undefined) node.textContent = text;
  return node;
}
function draw() {
  const [x,y,k] = controls.map(control=>control.value);
  svg.querySelectorAll('g').forEach(node=>node.remove());
  const layer = element('g',{}); svg.append(layer);
  const range = key => [Math.min(...data.map(d=>d[key])),Math.max(...data.map(d=>d[key]))];
  const ranges = Object.fromEntries(Object.keys(labels).map(key=>[key,range(key)]));
  const normalized = (item,key) => ranges[key][1] === ranges[key][0] ? .5 : (item[key]-ranges[key][0])/(ranges[key][1]-ranges[key][0]);
  const px = d=>95+normalized(d,x)*850, py=d=>450-normalized(d,y)*405;
  for(let i=0;i<=4;i++) {
    const tx=95+i/4*850,ty=450-i/4*405;
    layer.append(element('line',{x1:95,y1:ty,x2:945,y2:ty,stroke:'#e7ebf3'}));
    layer.append(element('line',{x1:tx,y1:45,x2:tx,y2:450,stroke:'#e7ebf3'}));
    layer.append(element('text',{x:tx,y:480,'text-anchor':'middle',fill:'#556074','font-size':16},(ranges[x][0]+i/4*(ranges[x][1]-ranges[x][0])).toFixed(1)));
    layer.append(element('text',{x:78,y:ty+5,'text-anchor':'end',fill:'#556074','font-size':16},(ranges[y][0]+i/4*(ranges[y][1]-ranges[y][0])).toFixed(1)));
  }
  layer.append(element('text',{x:520,y:520,'text-anchor':'middle',fill:'#556074','font-size':17},labels[x]));
  layer.append(element('text',{x:95,y:25,fill:'#556074','font-size':17},labels[y]));
  const edges=element('g',{'stroke':'#b5c1d8','stroke-width':1,'opacity':.65});layer.append(edges);
  const seen=new Set();
  const dimensions=[...new Set([x,y])];
  const distance=(a,b)=>dimensions.reduce((sum,key)=>sum+Math.abs(normalized(a,key)-normalized(b,key)),0)/dimensions.length;
  for(const a of data) {
    const nearest=data.filter(b=>b.id!==a.id).sort((b,c)=>distance(a,b)-distance(a,c)||b.id-c.id).slice(0,Number(k));
    for(const b of nearest) {
      const key=[a.id,b.id].sort((a,b)=>a-b).join(':'); if(seen.has(key)) continue; seen.add(key);
      edges.append(element('line',{x1:px(a),y1:py(a),x2:px(b),y2:py(b)}));
    }
  }
  for(const d of data) {
    const t=normalized(d,'temperature'), low=[37,99,235],high=[220,38,38];
    const color=`rgb(${low.map((v,i)=>Math.round(v+(high[i]-v)*t)).join(',')})`;
    const point=element('circle',{cx:px(d),cy:py(d),r:6,fill:color,stroke:'#fff','stroke-width':1.4,tabindex:0,role:'button','aria-label':`${d.title}: ${d.battery}% battery, ${d.temperature} degrees Fahrenheit, history ${d.history} minutes`});
    point.style.cursor='pointer';
    const describe=()=>{status.textContent=`${d.title} · Battery ${d.battery}% · Temperature ${d.temperature}°F · History ${d.history} min · ${d.weather}`;};
    point.addEventListener('click',describe); point.addEventListener('focus',describe);
    point.addEventListener('keydown',e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();describe();}});
    point.append(element('title',{},`${d.title} · ${d.weather}`));layer.append(point);
  }
  status.textContent=`${data.length} synthetic items · ${seen.size} connections · Select a point to inspect its readings.`;
}
controls.forEach(control=>control.addEventListener('change',()=>{if(data.length)draw();}));
fetch('assets/synthetic-data.json').then(response=>{if(!response.ok)throw new Error('Data unavailable');return response.json();}).then(items=>{data=items;draw();}).catch(()=>{status.textContent='The example could not load. Reload this page to try again.';});

const conditionInfo = document.querySelector('#condition-info');
const conditionsOverview = conditionInfo.innerHTML;
const conditionDetails = {
  time: ['A time for everything.', 'Choose a daily time window for a library, including windows that cross midnight. A library can be available at night, during study hours, or whenever your time rule allows.'],
  location: ['Content with a place.', 'Choose a place and radius for a location condition. The app checks the current location and its accuracy to determine whether you are inside or outside that area.'],
  battery: ['A charge-based condition.', 'Use a battery percentage range as a condition. Combine it with other rules to make a library available when the device has the charge level you choose.'],
  'wi-fi': ['The network matters.', 'Use the connected Wi-Fi network as a condition. A matching network name can be part of the rule that makes a library available.'],
  bluetooth: ['Connected devices. Connected rules.', 'Use a connected Bluetooth device, or the absence of connected devices, as a condition. Combine it with time, location, or other signals using conditional logic.'],
  history: ['How recently were you here?', 'Use the time since your previous visit as a condition. Choose a range in minutes to make availability depend on how recently you used the app.'],
  weather: ['Let the environment in.', 'Use local weather categories or a temperature range as conditions. Environment readings come from Open-Meteo using the current location and require internet access.']
};
document.querySelectorAll('[data-condition]').forEach(button => {
  button.addEventListener('click', () => {
    document.querySelectorAll('[data-condition]').forEach(other => other.setAttribute('aria-pressed', String(other === button)));
    const detail = conditionDetails[button.dataset.condition];
    if (!detail) { conditionInfo.innerHTML = conditionsOverview; return; }
    const heading = document.createElement('h2'); heading.textContent = detail[0];
    const description = document.createElement('p'); description.textContent = detail[1];
    conditionInfo.replaceChildren(heading, description);
  });
});
