import React, {useEffect, useState} from 'react';
import {createRoot} from 'react-dom/client';
import './styles.css';

const copy = {
  idle: ['Ready to connect', 'Your filtered proxy route is standing by.'],
  'needs-config': ['Add your route', 'Import a Clash configuration to begin.'],
  checking: ['Checking route', 'Finding a healthy non-USA path.'],
  connected: ['You are connected', 'Traffic is using your filtered route.'],
  blocked: ['No eligible route', 'Your configuration only contains USA nodes.'],
  error: ['Could not connect', 'Check the tunnel engine and try again.'],
};

function App(){
  const [state,setState]=useState('needs-config');
  const [busy,setBusy]=useState(false);
  const message=copy[state]||copy.error;
  useEffect(()=>{fetch('/api/status').then(r=>r.json()).then(d=>setState(d.state)).catch(()=>{});},[]);
  async function toggle(){
    setBusy(true);
    try { const endpoint=state==='connected'?'/api/disconnect':'/api/connect'; const r=await fetch(endpoint,{method:'POST'}); const d=await r.json(); setState(d.state||'error'); }
    catch { setState('error'); }
    finally { setBusy(false); }
  }
  return <main className="app-shell"><section className="hero"><div className="brand"><span className="brand-mark">S</span><span>SimpleVPN</span></div><div className={`state-pill ${state}`}><span className="dot"/>{state.replace('-', ' ')}</div><div className="orb"><div className="orb-ring"/><div className="orb-core"><span>{state==='connected'?'ON':'OFF'}</span></div></div><h1>{message[0]}</h1><p>{message[1]}</p><button className={`connect ${state==='connected'?'disconnect':''}`} onClick={toggle} disabled={busy||state==='checking'}>{busy?'Working…':state==='connected'?'Disconnect':'Connect'}</button><div className="footnote">USA nodes are excluded automatically.</div></section></main>
}
createRoot(document.getElementById('root')).render(<App/>);
