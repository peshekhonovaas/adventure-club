import { useState, useRef, useEffect } from "react";

const API_BASE = "";  // change to "http://localhost:8080" if running frontend separately
const DEFAULT_AGENT_NAME = "Grogu";

// Persist the running adventure so a page refresh restores it instead of dropping
// the child back on the onboarding page. Only "New adventure" (handleRestart) clears it.
const STORAGE_KEY = "adventureclub.session";
function loadPersisted() {
    try {
        const raw = localStorage.getItem(STORAGE_KEY);
        return raw ? JSON.parse(raw) : null;
    } catch { return null; }
}
function clearPersisted() {
    try { localStorage.removeItem(STORAGE_KEY); } catch { /* ignore */ }
}

// ── Authorization (real backend auth via Spring Security) ───────
// A magical sign-in gate that stands in front of the adventure, backed by the
// server's /auth/* endpoints. Accounts live in Postgres (BCrypt-hashed secret
// words); a stateful HTTP session (JSESSIONID cookie) keeps the hero signed in.
// We only cache the signed-in hero NAME in localStorage for an instant, flicker-
// free render on reload — the cookie is the real credential and /auth/me is the
// source of truth (see the mount effect in the main component).
const AUTH_SESSION_KEY = "adventureclub.auth";
function loadAuthedUser() {
    try { return localStorage.getItem(AUTH_SESSION_KEY); } catch { return null; }
}
function saveAuthedUser(name) {
    try { localStorage.setItem(AUTH_SESSION_KEY, name); } catch { /* ignore */ }
}
function clearAuthedUser() {
    try { localStorage.removeItem(AUTH_SESSION_KEY); } catch { /* ignore */ }
}

// Call an /auth/* endpoint. `credentials: "include"` makes the browser send and
// store the session cookie. On failure, surfaces the server's friendly message.
async function authRequest(path, body) {
    const res = await fetch(`${API_BASE}/auth/${path}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        credentials: "include",
        ...(body ? { body: JSON.stringify(body) } : {}),
    });
    if (res.status === 204) return null;
    let data = null;
    try { data = await res.json(); } catch { /* no body */ }
    if (!res.ok) {
        const msg = data?.message || "Something went wrong — please try again!";
        throw new Error(msg);
    }
    return data;
}

const STYLE = `
  @import url('https://fonts.googleapis.com/css2?family=Fredoka+One&family=Nunito:wght@600;700;800;900&display=swap');

  *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }

  :root {
    --bg:       #0d0720;
    --surface:  rgba(255,255,255,0.06);
    --border:   rgba(255,255,255,0.12);
    --purple:   #7c3aed;
    --teal:     #00c878;
    --amber:    #ffb830;
    --coral:    #ff6b6b;
    --txt:      rgba(255,255,255,0.92);
    --txt-dim:  rgba(255,255,255,0.45);
    --radius:   18px;
  }

  /* CHANGED: lock the viewport — no scroll ever */
  html, body {
    height: 100dvh;
    overflow: hidden;
  }

  body {
    background: var(--bg);
    color: var(--txt);
    font-family: 'Nunito', sans-serif;
    font-weight: 700;
    display: flex;
    justify-content: center;
    align-items: flex-start;
  }

  /* stars */
  .stars-bg {
    position: fixed; inset: 0; z-index: 0; pointer-events: none;
    background:
      radial-gradient(ellipse at 20% 20%, rgba(100,60,180,.25) 0%, transparent 55%),
      radial-gradient(ellipse at 80% 75%, rgba(0,150,120,.15) 0%, transparent 55%);
  }
  .stars-bg::before {
    content: '';
    position: absolute; inset: 0;
    background-image:
      radial-gradient(1px 1px at 12% 18%, rgba(255,255,255,.8) 0%, transparent 100%),
      radial-gradient(1.5px 1.5px at 33% 55%, rgba(255,255,255,.6) 0%, transparent 100%),
      radial-gradient(1px 1px at 54% 12%, rgba(255,255,255,.9) 0%, transparent 100%),
      radial-gradient(1px 1px at 72% 40%, rgba(255,255,255,.7) 0%, transparent 100%),
      radial-gradient(1.5px 1.5px at 88% 22%, rgba(255,255,255,.5) 0%, transparent 100%),
      radial-gradient(1px 1px at 8%  78%, rgba(255,255,255,.8) 0%, transparent 100%),
      radial-gradient(1px 1px at 44% 88%, rgba(255,255,255,.6) 0%, transparent 100%),
      radial-gradient(1px 1px at 63% 68%, rgba(255,255,255,.7) 0%, transparent 100%),
      radial-gradient(1.5px 1.5px at 82% 62%, rgba(255,255,255,.8) 0%, transparent 100%),
      radial-gradient(1px 1px at 26% 42%, rgba(255,255,255,.5) 0%, transparent 100%);
  }

  /* CHANGED: height:100dvh + overflow:hidden locks the column to exactly one screen.
     Nothing outside it is reachable. */
  .app {
    position: relative; z-index: 1;
    /* FIXED width (was width:100%;max-width:720px): as a flex item inside the
       centered body, an auto flex-basis let the column shrink to its content —
       so while the GM bubble only showed "Painting your picture…" the whole app
       was ~372px wide and jumped to 720px once the story text arrived. A fixed
       width (capped to the viewport) keeps it 720px from the very first render. */
    width: 720px; max-width: 100%;
    flex-shrink: 0;
    height: 100dvh;          /* was: min-height — allowed growing past viewport */
    overflow: hidden;        /* clips anything that would otherwise escape */
    display: flex; flex-direction: column;
    padding: 6px 0 16px;
  }

  /* all regions are rigid (won't grow or shrink) EXCEPT .picture-panel */
  .app-header, .scene, .gm-row, .draw-btn, .input-row { flex-shrink: 0; }

  /* header */
  .app-header { text-align: center; padding: 8px 16px 2px; position: relative; }
  /* Header icon buttons: icon only (no text label); the action is conveyed by a
     clear picture + a native tooltip (title) hint. Round, fixed-size targets. */
  .icon-btn {
    display: inline-flex; align-items: center; justify-content: center;
    width: 40px; height: 40px; border-radius: 50%;
    background: var(--surface); border: 1px solid var(--border);
    font-size: 20px; line-height: 1; cursor: pointer;
    transition: border-color .15s, transform .15s, box-shadow .15s;
  }
  .icon-btn:disabled { opacity: .4; cursor: not-allowed; }
  .restart-btn {
    position: absolute; top: 8px; right: 16px;
  }
  .restart-btn:hover:not(:disabled) { border-color: rgba(255,184,48,.6); transform: translateY(-1px); box-shadow: 0 0 14px rgba(255,184,48,.35); }
  .logout-btn {
    position: absolute; top: 8px; left: 16px;
  }
  .logout-btn:hover:not(:disabled) { border-color: rgba(255,107,107,.6); transform: translateY(-1px); box-shadow: 0 0 14px rgba(255,107,107,.35); }
  .app-title {
    font-family: 'Fredoka One', cursive; font-size: 24px; color: #fff;
    text-shadow: 0 0 20px rgba(160,60,255,.5);
  }
  .app-subtitle { font-size: 12px; color: var(--txt-dim); margin-top: 2px; }

  /* scene */
  .scene {
    margin: 8px 16px 0;
    border-radius: 20px;
    background: linear-gradient(160deg, #1a0a3e 0%, #0d1f3c 50%, #0a2818 100%);
    border: 1.5px solid rgba(255,184,48,.3);
    box-shadow: 0 0 30px rgba(100,60,180,.35), inset 0 0 40px rgba(0,0,0,.4);
    padding: 10px 16px; position: relative; overflow: hidden;
  }
  .scene-art {
    display: flex; align-items: flex-end; justify-content: space-around;
    height: 72px; padding: 0 8px; position: relative;
  }
  .scene-moon { position: absolute; top: -6px; right: 18px; font-size: 28px; filter: drop-shadow(0 0 12px rgba(255,220,100,.8)); }
  .scene-main { font-size: 50px; filter: drop-shadow(0 0 18px rgba(255,100,50,.7)); animation: dragonFloat 4s ease-in-out infinite; }
  .scene-bg   { font-size: 40px; opacity: .55; }
  @keyframes dragonFloat { 0%,100%{transform:translateY(0) rotate(-3deg)} 50%{transform:translateY(-8px) rotate(3deg)} }
  .sparkle { position: absolute; font-size: 13px; animation: twinkle 2s ease-in-out infinite; }
  @keyframes twinkle { 0%,100%{opacity:.3;transform:scale(.8)} 50%{opacity:1;transform:scale(1.2)} }

  /* gm bubble */
  .gm-row { margin: 10px 16px 0; display: flex; align-items: flex-start; gap: 10px; }
  .gm-avatar {
    width: 48px; height: 48px; border-radius: 50%; flex-shrink: 0;
    background: linear-gradient(135deg, #3a1060, #6020a0);
    border: 2.5px solid rgba(255,184,48,.65);
    box-shadow: 0 0 16px rgba(160,60,255,.5);
    display: flex; align-items: center; justify-content: center;
    font-size: 27px;
    animation: avatarGlow 3s ease-in-out infinite;
  }
  @keyframes avatarGlow { 0%,100%{box-shadow:0 0 16px rgba(160,60,255,.5)} 50%{box-shadow:0 0 28px rgba(160,60,255,.9)} }
  /* FIXED height (not min-height): the bubble occupies exactly the same box whether it
     shows the typing dots ("thinking") or the story text, so the picture panel below never
     shifts when the picture + text arrive and the page never looks like it resized. The
     bubble is a flex column so the .gm-text region can scroll INSIDE it — the full Game
     Master reply is always readable even when it's longer than the visible box, instead of
     being cut off at 3 lines. */
  .gm-bubble {
    background: var(--surface); border: 1.5px solid var(--border);
    border-radius: 4px 18px 18px 18px; padding: 13px 16px; flex: 1;
    height: 118px; overflow: hidden;
    display: flex; flex-direction: column;
  }
  .gm-name { font-size: 10px; letter-spacing: 1.5px; color: rgba(255,184,48,.8); text-transform: uppercase; margin-bottom: 5px; flex-shrink: 0; }
  /* scrolls within the fixed-height bubble so the WHOLE reply is shown (never truncated),
     while the bubble itself keeps the same height and the layout stays stable */
  .gm-text {
    font-size: 15px; line-height: 1.55; color: var(--txt);
    flex: 1; min-height: 0; overflow-y: auto;
  }

  /* typing indicator */
  .typing { display: flex; gap: 5px; align-items: center; padding: 4px 0; }
  .typing span {
    width: 7px; height: 7px; border-radius: 50%;
    background: rgba(255,255,255,.4);
    animation: typingBounce .9s ease-in-out infinite;
  }
  .typing span:nth-child(2){animation-delay:.15s}
  .typing span:nth-child(3){animation-delay:.3s}
  @keyframes typingBounce{0%,100%{transform:translateY(0);opacity:.4}50%{transform:translateY(-5px);opacity:1}}

  /* CHANGED: picture panel is now the single growing region.
     flex:1 1 0 fills whatever space the rigid regions leave.
     min-height:0 lets flex shrink it below its content size.
     Removed: aspect-ratio, max-height, flex:0 0 auto.
     Result: always the same height — with image, without, or loading. */
  .picture-panel {
    flex: 1 1 0;
    min-height: 0;
    margin: 10px 16px 0;
    border-radius: var(--radius);
    border: 2px dashed rgba(255,255,255,.18);
    background: var(--surface);
    display: flex; align-items: center; justify-content: center;
    position: relative; overflow: hidden; padding: 10px;
    transition: border-color .3s;
  }
  .picture-panel.has-image { border-style: solid; border-color: rgba(0,200,120,.4); }
  /* image fills the panel via absolute positioning — never resizes the panel */
  .picture-panel img {
    position: absolute; inset: 0;
    width: 100%; height: 100%;
    object-fit: contain;
    border-radius: calc(var(--radius) - 2px);
    display: block;
  }
  .picture-empty { text-align: center; color: var(--txt-dim); font-size: 13.5px; padding: 16px; }
  .picture-empty .pe-emoji { font-size: 40px; display: block; margin-bottom: 8px; opacity: .6; }
  .picture-save {
    position: absolute; top: 10px; right: 10px;
    width: 30px; height: 30px; border-radius: 50%;
    background: rgba(0,0,0,.55); border: 1px solid rgba(255,255,255,.25);
    color: #fff; font-size: 15px; cursor: pointer; line-height: 1;
    display: flex; align-items: center; justify-content: center;
    transition: background .15s; z-index: 2; text-decoration: none;
  }
  .picture-save:hover { background: rgba(0,200,120,.7); }
  .picture-panel.is-painting img { filter: brightness(.7) saturate(.85); }
  .picture-painting {
    position: absolute; top: 10px; left: 10px; z-index: 2;
    background: rgba(13,7,32,.6); border: 1px solid rgba(255,184,48,.4);
    border-radius: 12px; padding: 5px 11px;
    font-size: 12px; font-weight: 800; color: #ffe08a;
    animation: paintingPulse 1.3s ease-in-out infinite;
  }
  @keyframes paintingPulse { 0%,100%{opacity:.55} 50%{opacity:1} }
  .magic-img {
    animation: magicAppear .6s ease;
    box-shadow: 0 0 26px rgba(160,60,255,.4);
  }
  @keyframes magicAppear {
    from { opacity: 0; filter: blur(7px) brightness(1.7); }
    to   { opacity: 1; filter: none; }
  }
  .magic-sparkles { position: absolute; inset: 0; pointer-events: none; z-index: 1; }
  .magic-sparkles span {
    position: absolute; width: 11px; height: 11px; border-radius: 50%;
    background: radial-gradient(circle, #fff 0%, rgba(255,220,120,.6) 40%, transparent 70%);
    animation: twinkle 2.4s ease-in-out infinite;
  }
  .magic-sparkles span:nth-child(1){ top: 12%; left: 10%;  animation-delay: 0s; }
  .magic-sparkles span:nth-child(2){ top: 20%; right: 12%; animation-delay: .6s; }
  .magic-sparkles span:nth-child(3){ bottom: 24%; left: 16%; animation-delay: 1.1s; }
  .magic-sparkles span:nth-child(4){ bottom: 16%; right: 14%; animation-delay: 1.7s; }

  /* draw button */
  .draw-btn {
    margin: 10px 16px 0;
    display: flex; align-items: center; justify-content: center; gap: 10px;
    background: rgba(0,200,120,.1); border: 1.5px solid rgba(0,200,120,.45);
    border-radius: var(--radius); padding: 12px;
    cursor: pointer; color: inherit; font-family: inherit;
    transition: transform .15s, box-shadow .15s, background .15s;
  }
  .draw-btn:hover:not(:disabled) { transform: translateY(-2px); background: rgba(0,200,120,.16); box-shadow: 0 4px 20px rgba(0,200,120,.3); }
  .draw-btn:disabled { opacity: .4; cursor: not-allowed; }
  .draw-btn .db-emoji { font-size: 26px; }
  .draw-btn .db-label { font-size: 16px; font-weight: 800; color: #fff; }

  /* input row — fixed height textarea so it never grows the layout */
  .input-row { display: flex; gap: 8px; margin: 10px 16px 0; align-items: flex-end; }
  .chat-input {
    flex: 1; background: var(--surface); border: 1.5px solid var(--border);
    border-radius: 20px; padding: 13px 18px;
    color: #fff; font-family: 'Nunito', sans-serif; font-size: 15px; font-weight: 700;
    outline: none; resize: none;
    height: 48px; overflow: hidden;  /* fixed height — never pushes layout */
    transition: border-color .2s;
  }
  .chat-input::placeholder { color: var(--txt-dim); }
  .chat-input:focus { border-color: rgba(0,212,170,.5); }
  .send-btn, .undo-btn {
    width: 48px; height: 48px; border-radius: 50%; flex-shrink: 0;
    background: linear-gradient(135deg, var(--teal), #009980);
    border: none; cursor: pointer; display: flex; align-items: center; justify-content: center;
    font-size: 19px; color: #fff; box-shadow: 0 4px 16px rgba(0,212,170,.4);
    transition: transform .15s, box-shadow .15s;
  }
  .send-btn:hover:not(:disabled), .undo-btn:hover:not(:disabled) { transform: scale(1.08); box-shadow: 0 6px 24px rgba(0,212,170,.6); }
  .send-btn:disabled, .undo-btn:disabled { opacity: .4; cursor: not-allowed; }

  /* onboarding */
  .onboarding {
    display: flex; flex-direction: column; align-items: center;
    justify-content: center; flex: 1; padding: 32px 20px; gap: 20px; text-align: center;
  }
  .onboarding-hero { font-size: 72px; animation: dragonFloat 4s ease-in-out infinite; }
  .onboarding h1 { font-family: 'Fredoka One', cursive; font-size: 30px; color: #fff; line-height: 1.2; }
  .onboarding p  { font-size: 15px; color: var(--txt-dim); max-width: 320px; line-height: 1.6; }
  .interest-input {
    width: 100%; max-width: 380px; background: var(--surface); border: 1.5px solid var(--border);
    border-radius: var(--radius); padding: 14px 18px;
    color: #fff; font-family: 'Nunito', sans-serif; font-size: 15px; font-weight: 700;
    outline: none; transition: border-color .2s; text-align: center;
  }
  .interest-input::placeholder { color: var(--txt-dim); }
  .interest-input:focus { border-color: rgba(255,184,48,.6); }
  .start-btn {
    width: 100%; max-width: 380px; background: linear-gradient(135deg, var(--amber), #ff7a00);
    border: none; border-radius: var(--radius); padding: 16px;
    color: #1a0a2e; font-family: 'Fredoka One', cursive; font-size: 20px;
    cursor: pointer; box-shadow: 0 4px 24px rgba(255,140,0,.4);
    transition: transform .18s, box-shadow .18s; letter-spacing: .5px;
  }
  .start-btn:hover:not(:disabled) { transform: translateY(-2px); box-shadow: 0 8px 32px rgba(255,140,0,.6); }
  .start-btn:disabled { opacity: .5; cursor: not-allowed; }

  /* authorization page — reuses the onboarding column layout */
  .auth-fields { width: 100%; max-width: 380px; display: flex; flex-direction: column; gap: 14px; }
  .auth-error {
    max-width: 380px; width: 100%;
    background: rgba(255,107,107,.1); border: 1px solid rgba(255,107,107,.35);
    color: rgba(255,180,180,.9); border-radius: 14px; padding: 10px 14px;
    font-size: 13px; text-align: center;
  }
  .auth-toggle { font-size: 14px; color: var(--txt-dim); }
  .auth-toggle button {
    background: none; border: none; cursor: pointer;
    color: var(--amber); font-family: 'Nunito', sans-serif; font-size: 14px; font-weight: 800;
    text-decoration: underline; padding: 0; margin-left: 4px;
  }
  .auth-toggle button:hover { color: #fff; }

  /* change-secret-word modal — overlays the whole app */
  .modal-overlay {
    position: fixed; inset: 0; z-index: 50;
    background: rgba(6,8,20,.72); backdrop-filter: blur(3px);
    display: flex; align-items: center; justify-content: center; padding: 20px;
  }
  .modal-card {
    width: 100%; max-width: 420px;
    background: var(--surface); border: 1px solid var(--border);
    border-radius: var(--radius); padding: 26px 24px;
    display: flex; flex-direction: column; align-items: center; gap: 14px;
    text-align: center;
  }
  .modal-card h2 {
    font-family: 'Fredoka One', cursive; font-size: 22px; color: #fff; margin: 0;
  }
  .modal-card p { font-size: 14px; color: var(--txt-dim); margin: 0; }
  .modal-success {
    max-width: 380px; width: 100%;
    background: rgba(120,220,140,.12); border: 1px solid rgba(120,220,140,.4);
    color: #b7f0c2; border-radius: 12px; padding: 10px 14px;
    font-size: 13px; text-align: center;
  }
  .modal-actions { display: flex; gap: 10px; width: 100%; max-width: 380px; }
  .modal-actions .start-btn { flex: 1; margin: 0; }
  .modal-cancel {
    flex: 1; background: var(--surface); border: 1px solid var(--border);
    border-radius: var(--radius); padding: 16px; cursor: pointer;
    color: var(--txt-dim); font-family: 'Nunito', sans-serif; font-size: 15px; font-weight: 800;
    transition: color .15s, border-color .15s;
  }
  .modal-cancel:hover:not(:disabled) { color: #fff; border-color: rgba(255,255,255,.35); }
  .modal-cancel:disabled { opacity: .4; cursor: not-allowed; }
  .change-pw-btn {
    position: absolute; top: 8px; left: 64px;
  }
  .change-pw-btn:hover:not(:disabled) { border-color: rgba(255,184,48,.6); transform: translateY(-1px); box-shadow: 0 0 14px rgba(255,184,48,.35); }

  /* banners — overlaid on top of the picture panel (position:absolute) so they never
     steal height from the flexible .picture-panel and never shift the layout when they
     appear/disappear while a message or picture is generating. */
  .banner-overlay {
    position: absolute; left: 16px; right: 16px; bottom: 10px; z-index: 5;
    pointer-events: none;
  }
  .blocked-banner, .error-banner {
    border-radius: 14px; padding: 10px 14px;
    font-size: 13px; text-align: center;
    pointer-events: auto;
  }
  .blocked-banner + .error-banner { margin-top: 8px; }
  .blocked-banner { background: rgba(255,107,107,.1); border: 1px solid rgba(255,107,107,.3); color: rgba(255,180,180,.85); }
  .error-banner   { background: rgba(255,80,80,.1);  border: 1px solid rgba(255,80,80,.3);  color: rgba(255,160,160,.85); }
`;

// ── Scene emojis ────────────────────────────────────────────────
function SceneIllustration({ interests }) {
    const isSpace   = /space|star|planet|galaxy|astro|sci.fi|star.wars/i.test(interests);
    const isOcean   = /ocean|sea|fish|whale|shark|water/i.test(interests);
    const isPokemon = /pokemon|pok[eé]mon|pikachu/i.test(interests);
    const isRobot   = /robots?|technology|computer|IT|robotics/i.test(interests);
    let main = "🐉", bg = "🏔️", moon = "🌙";
    if (isSpace)   { main = "🚀"; bg = "🪐"; moon = "⭐"; }
    if (isOcean)   { main = "🐋"; bg = "🏝️"; moon = "🌊"; }
    if (isPokemon) { main = "⚡"; bg = "🌿"; moon = "🌟"; }
    if (isRobot)   { main = "🤖"; bg = "🏭"; moon = "🛸"; }
    return (
        <div className="scene">
            <div className="scene-art">
                <span className="scene-moon">{moon}</span>
                <span className="sparkle" style={{top:8,left:40,animationDelay:".2s"}}>✦</span>
                <span className="sparkle" style={{top:22,right:60,animationDelay:".8s"}}>✧</span>
                <span className="sparkle" style={{bottom:30,left:80,animationDelay:"1.4s"}}>✦</span>
                <span className="scene-bg">{bg}</span>
                <span className="scene-main">{main}</span>
                <span className="scene-bg" style={{fontSize:38,opacity:.45}}>{bg}</span>
            </div>
        </div>
    );
}

// ── Lightsaber avatar ───────────────────────────────────────────
function LightsaberAvatar() {
    return (
        <svg viewBox="0 0 64 64" role="img" aria-label="Lightsaber" width="40" height="40">
            <defs>
                <linearGradient id="ls-hilt" x1="0" y1="0" x2="1" y2="0">
                    <stop offset="0" stopColor="#6b7280" />
                    <stop offset="0.5" stopColor="#e5e7eb" />
                    <stop offset="1" stopColor="#4b5563" />
                </linearGradient>
                <linearGradient id="ls-blade" x1="0" y1="1" x2="0" y2="0">
                    <stop offset="0" stopColor="#00c878" />
                    <stop offset="1" stopColor="#a8ffcf" />
                </linearGradient>
                <filter id="ls-glow" x="-70%" y="-70%" width="240%" height="240%">
                    <feGaussianBlur stdDeviation="2.4" result="b" />
                    <feMerge><feMergeNode in="b" /><feMergeNode in="SourceGraphic" /></feMerge>
                </filter>
            </defs>
            <g transform="rotate(35 32 32)">
                <g filter="url(#ls-glow)">
                    <rect x="28.5" y="4" width="7" height="34" rx="3.5" fill="url(#ls-blade)" />
                    <rect x="30.6" y="5" width="2.8" height="32" rx="1.4" fill="#fff" opacity="0.95" />
                </g>
                <rect x="26.5" y="37" width="11" height="4" rx="1.5" fill="#9ca3af" />
                <rect x="27" y="40" width="10" height="19" rx="2" fill="url(#ls-hilt)" />
                <rect x="27" y="44" width="10" height="1.6" fill="#374151" />
                <rect x="27" y="48" width="10" height="1.6" fill="#374151" />
                <rect x="29" y="51.5" width="2.4" height="3" rx="0.6" fill="#ef4444" />
                <rect x="28.5" y="58" width="7" height="2.6" rx="1" fill="#6b7280" />
            </g>
        </svg>
    );
}

// ── GM bubble ───────────────────────────────────────────────────
function GMBubble({ text, loading, agentName }) {
    return (
        <div className="gm-row">
            <div className="gm-avatar"><LightsaberAvatar /></div>
            <div className="gm-bubble">
                <div className="gm-name">✦ {agentName} the Game Master</div>
                {loading
                    ? <div className="typing"><span/><span/><span/></div>
                    : <div className="gm-text">{text}</div>
                }
            </div>
        </div>
    );
}

// ── Picture panel ───────────────────────────────────────────────
function PicturePanel({ image, loading, onSave }) {
    if (!image) {
        return (
            <div className="picture-panel">
                <div className="picture-empty">
                    <span className="pe-emoji">{loading ? "🪄" : "🖼️"}</span>
                    {loading ? "Painting your picture…" : "Your magic picture appears here as your adventure unfolds!"}
                </div>
            </div>
        );
    }
    return (
        <div className={`picture-panel has-image${loading ? " is-painting" : ""}`}>
            <img key={image.length} src={image} alt="A picture of your adventure" className="magic-img" />
            <div className="magic-sparkles" aria-hidden="true">
                <span/><span/><span/><span/>
            </div>
            {loading && <div className="picture-painting" aria-hidden="true">🪄 Painting…</div>}
            <button className="picture-save" onClick={onSave} aria-label="Save this picture" title="Save this picture">💾</button>
        </div>
    );
}

// ── Onboarding ──────────────────────────────────────────────────
function Onboarding({ onStart, agentName }) {
    const [interests, setInterests] = useState("");
    const [loading, setLoading]     = useState(false);
    async function handleStart() {
        if (!interests.trim()) return;
        setLoading(true);
        await onStart(interests.trim());
        setLoading(false);
    }
    return (
        <div className="onboarding">
            <div className="onboarding-hero">🐉</div>
            <h1>Your Adventure Awaits</h1>
            <p>Tell {agentName} what you love!</p>
            <input
                className="interest-input"
                placeholder="e.g. dragons, space, Pokémon…"
                value={interests}
                onChange={e => setInterests(e.target.value)}
                onKeyDown={e => e.key === "Enter" && handleStart()}
                maxLength={100}
                autoFocus
            />
            <button
                className="start-btn"
                disabled={!interests.trim() || loading}
                onClick={handleStart}
            >
                {loading ? "Opening the portal…" : "Begin the adventure! ⚔️"}
            </button>
        </div>
    );
}

// ── Authorization page ──────────────────────────────────────────
function AuthPage({ onAuthed }) {
    const [mode, setMode]         = useState("login"); // "login" | "register"
    const [name, setName]         = useState("");
    const [password, setPassword] = useState("");
    const [confirm, setConfirm]   = useState("");
    const [error, setError]       = useState(null);
    const [loading, setLoading]   = useState(false);
    const isRegister = mode === "register";

    async function submit() {
        if (loading) return;
        const trimmed = name.trim();
        if (!trimmed || !password) { setError("Please fill in your name and secret word."); return; }
        if (isRegister && password !== confirm) { setError("The secret words don't match — try again!"); return; }
        setError(null); setLoading(true);
        try {
            const data = await authRequest(isRegister ? "register" : "login", {
                username: trimmed, password,
            });
            const authedName = data?.username ?? trimmed;
            saveAuthedUser(authedName);
            onAuthed(authedName);
        } catch (e) {
            setError(e.message);
        } finally {
            setLoading(false);
        }
    }

    function switchMode() {
        setMode(isRegister ? "login" : "register");
        setError(null); setPassword(""); setConfirm("");
    }

    return (
        <div className="onboarding">
            <div className="onboarding-hero">🔐</div>
            <h1>{isRegister ? "Join the Adventure Club" : "Welcome Back, Hero!"}</h1>
            <p>{isRegister ? "Pick a hero name and a secret word to begin." : "Sign in with your hero name and secret word."}</p>
            {error && <div className="auth-error">⚠️ {error}</div>}
            <div className="auth-fields">
                <input
                    className="interest-input"
                    placeholder="Your hero name"
                    value={name}
                    onChange={e => setName(e.target.value)}
                    onKeyDown={e => e.key === "Enter" && submit()}
                    maxLength={40}
                    autoFocus
                />
                <input
                    className="interest-input"
                    type="password"
                    placeholder="Your secret word"
                    value={password}
                    onChange={e => setPassword(e.target.value)}
                    onKeyDown={e => e.key === "Enter" && submit()}
                    maxLength={60}
                />
                {isRegister && (
                    <input
                        className="interest-input"
                        type="password"
                        placeholder="Repeat your secret word"
                        value={confirm}
                        onChange={e => setConfirm(e.target.value)}
                        onKeyDown={e => e.key === "Enter" && submit()}
                        maxLength={60}
                    />
                )}
            </div>
            <button
                className="start-btn"
                disabled={loading || !name.trim() || !password || (isRegister && !confirm)}
                onClick={submit}
            >
                {loading
                    ? (isRegister ? "Creating your hero…" : "Opening the portal…")
                    : (isRegister ? "Create my hero! ✨" : "Enter the portal! ⚔️")}
            </button>
            <div className="auth-toggle">
                {isRegister ? "Already a hero?" : "New to the club?"}
                <button onClick={switchMode}>{isRegister ? "Log in" : "Sign up"}</button>
            </div>
        </div>
    );
}

// ── Change secret word modal ────────────────────────────────────
// Lets a signed-in hero change their secret word. Calls the backend
// /auth/change-password (current word re-verified server-side) and shows a
// friendly success/error message.
function ChangePasswordModal({ onClose }) {
    const [current, setCurrent] = useState("");
    const [next, setNext]       = useState("");
    const [confirm, setConfirm] = useState("");
    const [error, setError]     = useState(null);
    const [done, setDone]       = useState(false);
    const [loading, setLoading] = useState(false);

    async function submit() {
        if (loading) return;
        if (!current || !next) { setError("Please fill in both secret words."); return; }
        if (next !== confirm) { setError("The new secret words don't match — try again!"); return; }
        if (next === current) { setError("Your new secret word should be different from the old one."); return; }
        setError(null); setLoading(true);
        try {
            await authRequest("change-password", { currentPassword: current, newPassword: next });
            setDone(true);
        } catch (e) {
            setError(e.message);
        } finally {
            setLoading(false);
        }
    }

    return (
        <div className="modal-overlay" onClick={onClose}>
            <div className="modal-card" onClick={e => e.stopPropagation()}>
                <div className="onboarding-hero">🔑</div>
                <h2>Change your secret word</h2>
                {done ? (
                    <>
                        <div className="modal-success">✅ Your secret word has been changed!</div>
                        <button className="start-btn" onClick={onClose}>Back to the adventure ⚔️</button>
                    </>
                ) : (
                    <>
                        <p>Enter your current secret word, then choose a new one.</p>
                        {error && <div className="auth-error">⚠️ {error}</div>}
                        <div className="auth-fields">
                            <input
                                className="interest-input"
                                type="password"
                                placeholder="Current secret word"
                                value={current}
                                onChange={e => setCurrent(e.target.value)}
                                onKeyDown={e => e.key === "Enter" && submit()}
                                maxLength={60}
                                autoFocus
                            />
                            <input
                                className="interest-input"
                                type="password"
                                placeholder="New secret word"
                                value={next}
                                onChange={e => setNext(e.target.value)}
                                onKeyDown={e => e.key === "Enter" && submit()}
                                maxLength={60}
                            />
                            <input
                                className="interest-input"
                                type="password"
                                placeholder="Repeat new secret word"
                                value={confirm}
                                onChange={e => setConfirm(e.target.value)}
                                onKeyDown={e => e.key === "Enter" && submit()}
                                maxLength={60}
                            />
                        </div>
                        <div className="modal-actions">
                            <button className="modal-cancel" onClick={onClose} disabled={loading}>Cancel</button>
                            <button
                                className="start-btn"
                                disabled={loading || !current || !next || !confirm}
                                onClick={submit}
                            >
                                {loading ? "Saving…" : "Save ✨"}
                            </button>
                        </div>
                    </>
                )}
            </div>
        </div>
    );
}

// ── Main ────────────────────────────────────────────────────────
export default function AdventureClub() {
    // Restore any adventure that was in progress before a refresh.
    const persisted                   = loadPersisted();
    const [authedUser, setAuthedUser] = useState(loadAuthedUser());
    const [phase, setPhase]           = useState(persisted?.phase ?? "onboarding");
    const [agentName]                 = useState(DEFAULT_AGENT_NAME);
    const [sessionId, setSessionId]   = useState(persisted?.sessionId ?? null);
    const [interests, setInterests]   = useState(persisted?.interests ?? "");
    const [story, setStory]           = useState(persisted?.story ?? "");
    const [loading, setLoading]       = useState(false);
    const [blocked, setBlocked]       = useState(false);
    const [error, setError]           = useState(null);
    const [message, setMessage]       = useState("");
    const [sceneImage, setSceneImage] = useState(persisted?.sceneImage ?? null);
    const [showChangePw, setShowChangePw] = useState(false);
    const fileInputRef                = useRef(null);

    // Keep the persisted snapshot in sync so a refresh mid-adventure resumes it.
    useEffect(() => {
        if (phase === "onboarding") { clearPersisted(); return; }
        try {
            localStorage.setItem(STORAGE_KEY, JSON.stringify({
                phase, sessionId, interests, story, sceneImage,
            }));
        } catch { /* quota exceeded (large image) — refresh will fall back to onboarding */ }
    }, [phase, sessionId, interests, story, sceneImage]);

    // On mount, confirm the session cookie is still valid with the server
    // (/auth/me is the source of truth). The cached name only avoids a flicker;
    // if the cookie has expired, drop back to the sign-in gate and clear state.
    useEffect(() => {
        (async () => {
            try {
                const res = await fetch(`${API_BASE}/auth/me`, { credentials: "include" });
                if (res.ok) {
                    const data = await res.json();
                    if (data?.username) {
                        saveAuthedUser(data.username); setAuthedUser(data.username);
                        // Nothing persisted locally (e.g. a fresh browser/device) — pull the
                        // hero's last adventure from the server so they can continue it.
                        if (!persisted) await restoreAdventure();
                        return;
                    }
                }
                forceSignOut();
            } catch { /* network hiccup — keep the cached view, later calls will re-check */ }
        })();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    // Clears all local adventure + auth state and returns to the sign-in gate.
    // Used when the server reports we are no longer authenticated (401).
    function forceSignOut() {
        clearPersisted();
        clearAuthedUser();
        setStory(""); setSceneImage(null); setMessage("");
        setBlocked(false); setError(null);
        setInterests(""); setSessionId(null);
        setPhase("onboarding");
        setAuthedUser(null);
    }

    // Load the signed-in hero's last in-progress adventure from the server (if any)
    // and drop straight into it, so a hero can continue their last quest after logging
    // in again — even on a different browser or device where nothing is cached locally.
    async function restoreAdventure() {
        try {
            const res = await fetch(`${API_BASE}/session/current`, { credentials: "include" });
            if (res.status !== 200) return;   // 204 = nothing to resume, 401 = handled elsewhere
            const data = await res.json();
            if (!data?.sessionId || !data?.storyText) return;
            setSessionId(data.sessionId);
            setInterests(data.interests ?? "");
            setStory(data.storyText);
            setSceneImage(data.imageUrl ?? null);
            setBlocked(false); setError(null); setMessage("");
            setPhase("quest");
        } catch { /* network hiccup — stay on onboarding, the hero can start fresh */ }
    }

    async function sendTurn(childMessage, currentInterests, currentSessionId, image = null) {
        setLoading(true); setBlocked(false); setError(null);
        try {
            const res = await fetch(`${API_BASE}/session/turn`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                credentials: "include",
                body: JSON.stringify({
                    sessionId: currentSessionId ?? null,
                    interests: currentInterests,
                    agentName: agentName,
                    childMessage,
                    ...(image ? { imageData: image.data, imageMediaType: image.mediaType } : {}),
                }),
            });
            if (res.status === 401) { forceSignOut(); return; }
            if (!res.ok) throw new Error(`Server error ${res.status}`);
            const data = await res.json();
            if (data.blocked) { setBlocked(true); }
            else {
                setStory(data.storyText);
                setSessionId(data.sessionId);
                if (data.imageUrl) setSceneImage(data.imageUrl);
            }
        } catch (e) {
            setError(`${agentName} is taking a short break — try again in a moment!`);
        } finally {
            setLoading(false);
        }
    }

    async function handleStart(chosenInterests) {
        setInterests(chosenInterests);
        setPhase("quest");
        await sendTurn(`I'm starting my adventure! I love: ${chosenInterests}`, chosenInterests, null);
    }

    async function handleSend() {
        const msg = message.trim();
        if (!msg || loading) return;
        setMessage("");
        await sendTurn(msg, interests, sessionId);
    }

    async function handleUndo() {
        if (loading || !sessionId) return;
        setLoading(true); setError(null);
        try {
            const res = await fetch(`${API_BASE}/session/undo`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                credentials: "include",
                body: JSON.stringify({ sessionId }),
            });
            if (res.status === 401) { forceSignOut(); return; }
            if (!res.ok) throw new Error(`Server error ${res.status}`);
            const data = await res.json();
            setSceneImage(data.imageUrl ?? null);
        } catch (e) {
            setError(`${agentName} couldn't undo the last picture — try again in a moment!`);
        } finally {
            setLoading(false);
        }
    }

    async function handleRestart() {
        if (loading) return;
        if (!window.confirm("Start a brand-new adventure? This clears the current story and picture, and lets you pick a new theme.")) return;
        const currentSessionId = sessionId;
        // Reset the screen to a clean slate and return to the very first page (onboarding),
        // so the child can choose a completely different theme.
        setStory(""); setSceneImage(null); setMessage("");
        setBlocked(false); setError(null);
        setInterests("");
        setSessionId(null);
        setPhase("onboarding");
        clearPersisted();
        // Best-effort server cleanup of the old session's story history and picture(s).
        if (currentSessionId) {
            try {
                await fetch(`${API_BASE}/session/restart`, {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    credentials: "include",
                    body: JSON.stringify({ sessionId: currentSessionId }),
                });
            } catch (e) {
                // Best-effort — even if the server call fails the user still lands on onboarding.
            }
        }
    }

    function handleAuthed(name) {
        setAuthedUser(name);
        // Just signed in — resume the hero's last adventure from the server, if any.
        restoreAdventure();
    }

    async function handleLogout() {
        if (loading) return;
        if (!window.confirm("Sign out? Your current adventure will be cleared.")) return;
        // End the server session (best-effort), then clear all local adventure +
        // auth state and fall back to the authorization gate.
        try {
            await fetch(`${API_BASE}/auth/logout`, { method: "POST", credentials: "include" });
        } catch { /* best-effort — sign out locally regardless */ }
        forceSignOut();
    }

    function handleSaveImage() {
        if (!sceneImage) return;
        const a = document.createElement("a");
        a.href = sceneImage;
        a.download = "adventure-picture.png";
        document.body.appendChild(a);
        a.click();
        a.remove();
    }

    function handleFilePick(e) {
        const file = e.target.files?.[0];
        e.target.value = "";
        if (!file || loading) return;
        const reader = new FileReader();
        reader.onload = () => {
            const base64 = String(reader.result).split(",")[1];
            sendTurn("I drew something! Add my drawing to the image.", interests, sessionId, {
                data: base64, mediaType: file.type || "image/png",
            });
        };
        reader.readAsDataURL(file);
    }

    // Gate everything behind the authorization page until a player is signed in.
    if (!authedUser) return (
        <>
            <style>{STYLE}</style>
            <div className="stars-bg"/>
            <div className="app">
                <AuthPage onAuthed={handleAuthed}/>
            </div>
        </>
    );

    if (phase === "onboarding") return (
        <>
            <style>{STYLE}</style>
            <div className="stars-bg"/>
            <div className="app">
                <button
                    className="icon-btn change-pw-btn"
                    style={{ left: 16 }}
                    onClick={() => setShowChangePw(true)}
                    aria-label="Change your secret word"
                    title="Change your secret word"
                >
                    🔑
                </button>
                <Onboarding onStart={handleStart} agentName={agentName}/>
            </div>
            {showChangePw && <ChangePasswordModal onClose={() => setShowChangePw(false)}/>}
        </>
    );

    return (
        <>
            <style>{STYLE}</style>
            <div className="stars-bg"/>
            <div className="app">

                <div className="app-header">
                    <button
                        className="icon-btn logout-btn"
                        onClick={handleLogout}
                        disabled={loading}
                        aria-label="Sign out"
                        title="Sign out"
                    >
                        🚪
                    </button>
                    <button
                        className="icon-btn change-pw-btn"
                        onClick={() => setShowChangePw(true)}
                        disabled={loading}
                        aria-label="Change your secret word"
                        title="Change your secret word"
                    >
                        🔑
                    </button>
                    <div className="app-title">✦ Adventure Club ✦</div>
                    <div className="app-subtitle">A magical quest with {agentName}</div>
                    <button
                        className="icon-btn restart-btn"
                        onClick={handleRestart}
                        disabled={loading}
                        aria-label="Start a new adventure"
                        title="Start a new adventure"
                    >
                        🔄
                    </button>
                </div>

                <SceneIllustration interests={interests}/>

                <GMBubble
                    text={story || `${agentName} is opening the portal to your adventure…`}
                    loading={loading}
                    agentName={agentName}
                />

                <PicturePanel
                    image={sceneImage}
                    loading={loading}
                    onSave={handleSaveImage}
                />

                {(blocked || error) && (
                    <div className="banner-overlay">
                        {blocked && (
                            <div className="blocked-banner">
                                ⚠️ {agentName} couldn't send that response — try asking something different!
                            </div>
                        )}
                        {error && <div className="error-banner">⚡ {error}</div>}
                    </div>
                )}

                <button className="draw-btn" onClick={() => fileInputRef.current?.click()} disabled={loading}>
                    <span className="db-emoji">🎨</span>
                    <span className="db-label">Show my drawing</span>
                </button>
                <input
                    ref={fileInputRef}
                    type="file" accept="image/*"
                    style={{ display: "none" }}
                    onChange={handleFilePick}
                />

                <div className="input-row">
                    <textarea
                        className="chat-input"
                        placeholder={`Type to ${agentName}…`}
                        value={message}
                        onChange={e => setMessage(e.target.value)}
                        onKeyDown={e => {
                            if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); handleSend(); }
                        }}
                        rows={1}
                        disabled={loading}
                    />
                    <button className="send-btn" onClick={handleSend} disabled={loading || !message.trim()}>➤</button>
                    <button className="undo-btn" onClick={handleUndo} disabled={loading || !sessionId || !sceneImage} aria-label="Undo last picture" title="Undo last picture">↩️</button>
                </div>

            </div>
            {showChangePw && <ChangePasswordModal onClose={() => setShowChangePw(false)}/>}
        </>
    );
}