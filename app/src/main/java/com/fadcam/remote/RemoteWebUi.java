package com.fadcam.remote;

/** Self-contained web UI for the ported FadCam remote control feature. */
final class RemoteWebUi {
    private RemoteWebUi() {
    }

    static final String HTML =
            "<!doctype html><html><head><meta charset=utf-8>" +
            "<meta name=viewport content='width=device-width,initial-scale=1'>" +
            "<title>FadCam Remote</title>" +
            "<style>" +
            "*{box-sizing:border-box}body{margin:0;font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;" +
            "background:#111;color:#eee;display:flex;flex-direction:column;min-height:100vh}" +
            "header{padding:12px 16px;background:#1b1b1b;display:flex;align-items:center;gap:10px;border-bottom:1px solid #2a2a2a}" +
            "header h1{font-size:16px;margin:0;font-weight:600;letter-spacing:.5px}" +
            ".dot{width:9px;height:9px;border-radius:50%;background:#e53935;display:inline-block}" +
            ".dot.on{background:#43a047}" +
            "main{flex:1;display:flex;flex-direction:column;align-items:center;padding:12px;gap:12px}" +
            ".view{width:100%;max-width:920px;background:#000;border-radius:10px;overflow:hidden;" +
            "display:flex;align-items:center;justify-content:center;min-height:240px;position:relative}" +
            ".view img{width:100%;height:auto;display:block}" +
            ".view .placeholder{color:#777;font-size:14px;padding:40px;text-align:center}" +
            ".bar{display:flex;gap:10px;flex-wrap:wrap;justify-content:center;width:100%;max-width:920px}" +
            "button{flex:1;min-width:130px;padding:14px 18px;border:0;border-radius:10px;font-size:15px;" +
            "font-weight:600;color:#fff;background:#37474f;cursor:pointer}" +
            "button.rec{background:#c62828}button.torch{background:#5e35b1}button:active{transform:scale(.98)}" +
            ".stats{width:100%;max-width:920px;display:grid;grid-template-columns:repeat(auto-fit,minmax(120px,1fr));" +
            "gap:8px;font-size:13px}" +
            ".stat{background:#1b1b1b;border:1px solid #2a2a2a;border-radius:8px;padding:8px 10px}" +
            ".stat b{display:block;color:#9e9e9e;font-weight:500;font-size:11px;text-transform:uppercase;letter-spacing:.5px}" +
            ".stat span{font-size:15px}" +
            "footer{color:#555;font-size:11px;padding:10px;text-align:center}" +
            "</style></head><body>" +
            "<header><span class=dot id=dot></span><h1>FadCam Remote</h1>" +
            "<span style='margin-left:auto;font-size:12px;color:#888' id=ver></span></header>" +
            "<main>" +
            "<div class=view><img id=vid src='/stream.mjpg' alt='live' " +
            "onerror=\"this.style.display='none';document.getElementById('ph').style.display='block'\">" +
            "<div class=placeholder id=ph style='display:none'>No video yet.<br>Start recording to enable the live view.</div></div>" +
            "<div class=bar>" +
            "<button id=btnRec class=rec onclick=\"post('/recording/toggle')\">Start recording</button>" +
            "<button class=torch onclick=\"post('/torch/toggle')\">Toggle torch</button>" +
            "<button onclick=\"document.getElementById('vid').src='/stream.mjpg?'+Date.now()\">Reload stream</button>" +
            "</div>" +
            "<div class=stats>" +
            "<div class=stat><b>State</b><span id=sState>-</span></div>" +
            "<div class=stat><b>Battery</b><span id=sBat>-</span></div>" +
            "<div class=stat><b>Free storage</b><span id=sStore>-</span></div>" +
            "<div class=stat><b>Frame</b><span id=sFrame>-</span></div>" +
            "<div class=stat><b>Torch</b><span id=sTorch>-</span></div>" +
            "<div class=stat><b>Streaming</b><span id=sStream>-</span></div>" +
            "</div>" +
            "</main><footer>FadCam remote control (Android 8 port)</footer>" +
            "<script>" +
            "function post(u){fetch(u,{method:'POST'}).then(r=>r.json()).then(()=>setTimeout(tick,600)).catch(()=>{});}" +
            "function tick(){fetch('/status',{cache:'no-store'}).then(r=>{if(!r.ok)throw 0;return r.json()}).then(s=>{" +
            "document.getElementById('sState').textContent=s.state;" +
            "document.getElementById('sBat').textContent=(s.battery>=0?s.battery+'%':'-');" +
            "document.getElementById('sStore').textContent=(s.storageFreeMb>=0?(s.storageFreeMb/1024).toFixed(1)+' GB':'-');" +
            "document.getElementById('sFrame').textContent=(s.frameAgeMs<0?'none':(s.width+'x'+s.height+' / '+Math.round(s.frameAgeMs)+'ms'));" +
            "document.getElementById('sTorch').textContent=(s.torch?'on':'off');" +
            "document.getElementById('sStream').textContent=(s.streaming?'live':'idle');" +
            "document.getElementById('ver').textContent=s.version;" +
            "document.getElementById('dot').className='dot'+(s.recording?' on':'');" +
            "var b=document.getElementById('btnRec');b.textContent=s.recording?'Stop recording':'Start recording';" +
            "var img=document.getElementById('vid'),ph=document.getElementById('ph');" +
            "if(s.streaming){img.style.display='block';ph.style.display='none';}else{img.style.display='none';ph.style.display='block';}" +
            "}).catch(()=>{document.getElementById('sState').textContent='auth/no connection';});}" +
            "setInterval(tick,2000);tick();" +
            "</script></body></html>";
}
