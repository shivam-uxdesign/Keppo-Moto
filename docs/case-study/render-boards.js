const { chromium } = require('playwright'); const fs = require('fs');
const P = process.argv[2], OUT = process.argv[3];
const shim = `
<script>
class DCLogic { constructor(p){this.props=p||{};this.state={};} setState(o){Object.assign(this.state,o);} }
window.renderBoard = function(step){
  const src=[...document.querySelectorAll('script[type="text/x-dc"]')].map(s=>s.textContent).join('\\n');
  const C=new Function('DCLogic', src+'; return Component;')(DCLogic);
  const c=new C({}); if(c.go) { const realSI=window.setInterval; window.setInterval=()=>0; c.go(step); window.setInterval=realSI; } else c.setState({step});
  const v=c.renderVals();
  const ev=(e,sc)=>{ try{ return new Function(...Object.keys(sc),'return ('+e+')')(...Object.values(sc)); }catch(x){ return ''; } };
  const sub=(str,sc)=>str.replace(/\\{\\{\\s*([^}]+?)\\s*\\}\\}/g,(_,e)=>{const r=ev(e,sc); return typeof r==='function'?'':r;});
  function walk(node,sc){
    [...node.childNodes].forEach(n=>{
      if(n.nodeType===3){ n.textContent=sub(n.textContent,sc); return; }
      if(n.nodeType!==1) return;
      const tag=n.tagName.toLowerCase();
      if(tag==='sc-if'){ const ok=ev(n.getAttribute('value').replace(/[{}]/g,'').trim(),sc); if(!ok){n.remove();return;} walk(n,sc); n.replaceWith(...n.childNodes); return; }
      if(tag==='sc-for'){ const list=ev(n.getAttribute('list').replace(/[{}]/g,'').trim(),sc)||[]; const as=n.getAttribute('as'); const tpl=n.innerHTML; const frag=document.createElement('div');
        list.forEach(it=>{ const d=document.createElement('div'); d.innerHTML=tpl; walk(d,Object.assign({},sc,{[as]:it})); frag.append(...d.childNodes); }); n.replaceWith(...frag.childNodes); return; }
      if(tag==='helmet'){ return; }
      [...n.attributes].forEach(a=>{ if(a.value.includes('{{')) { if(a.name.startsWith('on')) n.removeAttribute(a.name); else n.setAttribute(a.name, sub(a.value,sc)); } });
      walk(n,sc);
    });
  }
  const root=document.querySelector('x-dc'); walk(root,v);
  [...root.querySelectorAll('button,div,span')].filter(e=>/^(Ride on|Waiting for the countdown|Start again)/.test(e.textContent.trim())&&e.children.length<3).forEach(e=>e.remove());
  const h=root.querySelector('helmet'); if(h){ document.head.append(...h.childNodes); h.remove(); }
};
</script>`;
(async () => {
  const b = await chromium.launch();
  const jobs = [['DirA',0,'opt-a'],['DirB',0,'opt-b'],['DirC',0,'opt-c'],['DirC2',0,'opt-c2'],['DirC3',0,'opt-c3'],['DirC2',3,'c2-jam'],['DirC2',4,'c2-offer'],['DirC2',1,'c2-filming']];
  for (const [f, step, name] of jobs) {
    let html = fs.readFileSync(`${P}/${f}.dc.html`, 'utf8').replace(/<script src="\.\/support\.js"><\/script>/g, '');
    html = html.replace('</head>', shim + '</head>');
    const tmp = `${OUT}/_${name}.html`; fs.writeFileSync(tmp, html);
    const p = await b.newPage({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2 });
    const errs=[]; p.on('pageerror', e => errs.push(e.message));
    await p.goto('file://' + tmp); await p.evaluate(s => window.renderBoard(s), step); await p.waitForTimeout(1200);
    await p.screenshot({ path: `${OUT}/${name}.png`, clip: { x: 0, y: 0, width: 390, height: 844 } });
    console.log(name, errs.length ? errs : 'ok'); fs.unlinkSync(tmp); await p.close();
  }
  await b.close();
})();
