package com.iptv.player

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD

class WebServer(
    private val context: Context,
    port: Int,
    private val onM3uUrlReceived: (String) -> Unit
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "WebServer"
    }

    private val gson = Gson()

    override fun serve(session: IHTTPSession): Response {
        return when {
            session.uri == "/" -> serveMainPage()
            session.uri == "/api/list" -> serveApiList()
            session.uri == "/api/add" -> serveApiAdd(session)
            session.uri == "/api/delete" -> serveApiDelete(session)
            session.uri == "/api/activate" -> serveApiActivate(session)
            session.uri == "/api/next" -> serveApiNext()
            session.uri == "/api/prev" -> serveApiPrev()
            else -> NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404")
        }
    }

    // ── API ──

    private fun serveApiList(): Response {
        val list = PlaylistManager.getAll(context)
        val active = PlaylistManager.getActive(context)
        val result = mapOf("playlists" to list, "activeId" to (active?.id ?: ""))
        return jsonResponse(gson.toJson(result))
    }

    private fun serveApiAdd(session: IHTTPSession): Response {
        val p = session.parms ?: emptyMap()
        val id = p["id"] ?: ""
        val name = p["name"] ?: ""
        val url = p["url"] ?: ""
        val ua = p["ua"] ?: "VLC/3.0.20 LibVLC/3.0.20"
        val ref = p["ref"] ?: ""

        if (url.isBlank() || name.isBlank()) {
            return jsonResponse("""{"ok":false,"error":"Nome e URL obbligatori"}""")
        }

        val playlist = Playlist(
            id = id.ifBlank { java.util.UUID.randomUUID().toString() },
            name = name, url = url, userAgent = ua, referer = ref
        )
        PlaylistManager.addOrUpdate(context, playlist)
        PlaylistManager.setActive(context, playlist.id)

        val prefs = context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("m3u_url", url).apply()

        onM3uUrlReceived(url)
        return jsonResponse("""{"ok":true,"name":"${name.replace("\"", "\\\"")}"}""")
    }

    private fun serveApiDelete(session: IHTTPSession): Response {
        val id = session.parms?.get("id") ?: ""
        PlaylistManager.delete(context, id)
        val active = PlaylistManager.getActive(context)
        active?.let {
            val prefs = context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("m3u_url", it.url).apply()
            onM3uUrlReceived(it.url)
        }
        return jsonResponse("""{"ok":true}""")
    }

    private fun serveApiActivate(session: IHTTPSession): Response {
        val id = session.parms?.get("id") ?: ""
        PlaylistManager.setActive(context, id)
        val pl = PlaylistManager.getActive(context)
        pl?.let {
            val prefs = context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("m3u_url", it.url).apply()
            onM3uUrlReceived(it.url)
        }
        return jsonResponse("""{"ok":true}""")
    }

    private fun serveApiNext(): Response {
        val pl = PlaylistManager.getNext(context)
        pl?.let {
            val prefs = context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("m3u_url", it.url).apply()
            onM3uUrlReceived(it.url)
        }
        return jsonResponse("""{"ok":true}""")
    }

    private fun serveApiPrev(): Response {
        val pl = PlaylistManager.getPrev(context)
        pl?.let {
            val prefs = context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("m3u_url", it.url).apply()
            onM3uUrlReceived(it.url)
        }
        return jsonResponse("""{"ok":true}""")
    }

    private fun jsonResponse(json: String): Response {
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", json)
    }

    // ── HTML ──

    private fun serveMainPage(): Response {
        val prefs = context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
        val savedUrl = prefs.getString("m3u_url", "") ?: ""
        val savedUA = prefs.getString("user_agent", "VLC/3.0.20 LibVLC/3.0.20") ?: "VLC/3.0.20 LibVLC/3.0.20"
        val savedReferer = prefs.getString("referer", "") ?: ""

        val html = """
<!DOCTYPE html>
<html lang="it">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>IPTV Player</title>
    <style>
        *{box-sizing:border-box;margin:0;padding:0}
        body{
            font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;
            background:linear-gradient(135deg,#1a1a2e,#16213e 50%,#0f3460);
            color:#eee;display:flex;justify-content:center;align-items:center;
            min-height:100vh;padding:20px;
        }
        .container{
            background:rgba(22,33,62,.92);padding:24px;border-radius:16px;
            width:100%;max-width:540px;box-shadow:0 8px 32px rgba(0,0,0,.4);
        }
        .logo{text-align:center;margin-bottom:12px}
        .logo svg{width:48px;height:48px}
        h1{color:#e94560;text-align:center;font-size:20px;margin-bottom:2px}
        .subtitle{text-align:center;color:#8899aa;margin-bottom:18px;font-size:12px}
        h2{color:#e94560;font-size:14px;margin-bottom:8px;display:flex;justify-content:space-between;align-items:center}
        .btn-sm{
            padding:4px 10px;border:1px solid #e94560;border-radius:4px;
            background:transparent;color:#e94560;font-size:11px;cursor:pointer
        }
        .btn-sm:hover{background:rgba(233,69,96,.15)}
        .input-group{margin-bottom:10px}
        label{display:block;margin-bottom:3px;color:#aabbcc;font-size:11px}
        input[type=url],input[type=text]{
            width:100%;padding:10px;border:2px solid #0f3460;border-radius:8px;
            background:#0f3460;color:#fff;font-size:14px;outline:none;transition:border-color .3s;
        }
        input:focus{border-color:#e94560}
        .presets{display:flex;flex-wrap:wrap;gap:4px;margin-top:4px}
        .preset-btn{
            padding:4px 10px;border:1px solid #0f3460;border-radius:4px;
            background:#0a1625;color:#aabbcc;font-size:10px;cursor:pointer
        }
        .preset-btn:hover{border-color:#e94560;color:#e94560}
        button[type=submit]{
            width:100%;padding:12px;background:#e94560;color:#fff;border:none;
            border-radius:8px;font-size:16px;font-weight:600;cursor:pointer;margin-top:6px
        }
        button[type=submit]:hover{background:#c23152}
        button[type=submit]:disabled{background:#555;cursor:not-allowed}
        .status{text-align:center;margin-top:12px;padding:10px;border-radius:8px;display:none;font-weight:500;font-size:13px}
        .status.show{display:block}
        .success{background:rgba(27,94,32,.8);color:#a5d6a7}
        .error{background:rgba(183,28,28,.8);color:#ef9a9a}
        .loading{background:rgba(15,52,96,.8);color:#90caf9}
        .pl-list{margin-bottom:16px}
        .pl-item{
            display:flex;align-items:center;justify-content:space-between;
            padding:10px;margin-bottom:6px;border-radius:8px;
            background:rgba(15,52,96,.5);border:1px solid #0f3460;gap:8px;
        }
        .pl-item.active{border-color:#e94560;background:rgba(233,69,96,.1)}
        .pl-info{flex:1;min-width:0}
        .pl-name{color:#fff;font-size:13px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
        .pl-url{color:#667788;font-size:10px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
        .pl-actions{display:flex;gap:4px;flex-shrink:0}
        .pl-actions button{
            padding:4px 8px;border:1px solid #0f3460;border-radius:4px;
            background:transparent;color:#aabbcc;font-size:10px;cursor:pointer;white-space:nowrap
        }
        .pl-actions .act-btn{color:#4caf50;border-color:#4caf50}
        .pl-actions .del-btn{color:#ef5350;border-color:#ef5350}
        .pl-actions .nav-btn{color:#e94560;border-color:#e94560}
        .empty-text{text-align:center;color:#556677;font-size:13px;padding:20px}
        .section{margin-bottom:16px}
        .usage-hint{font-size:10px;color:#667788;margin-top:3px}
    </style>
</head>
<body>
<div class="container">
    <div class="logo">
        <svg viewBox="0 0 64 64" fill="none">
            <circle cx="32" cy="32" r="30" stroke="#e94560" stroke-width="2.5"/>
            <polygon points="24,18 24,46 48,32" fill="#e94560"/>
        </svg>
    </div>
    <h1>IPTV Player</h1>
    <p class="subtitle">Gestione Multi-Playlist</p>

    <div class="section">
        <h2>Le Tue Playlist <span><button class="btn-sm nav-btn" onclick="apiCall('/api/prev')" title="Playlist precedente">&#9664;</button> <button class="btn-sm nav-btn" onclick="apiCall('/api/next')" title="Playlist successiva">&#9654;</button></span></h2>
        <div class="pl-list" id="plList">
            <div class="empty-text">Caricamento...</div>
        </div>
    </div>

    <div class="section">
        <h2>Aggiungi / Modifica Playlist</h2>
        <form id="addForm">
            <input type="hidden" id="editId">
            <div class="input-group">
                <label>Nome playlist</label>
                <input type="text" id="plName" placeholder="es. Italia, Sport, Film..." required>
            </div>
            <div class="input-group">
                <label>URL M3U</label>
                <input type="url" id="plUrl" placeholder="https://esempio.com/playlist.m3u" required>
            </div>
            <div class="input-group">
                <label>User-Agent (anti-blocco)</label>
                <input type="text" id="plUA" value="$savedUA">
                <div class="presets">
                    <button type="button" class="preset-btn" onclick="setUA('VLC/3.0.20 LibVLC/3.0.20')">VLC</button>
                    <button type="button" class="preset-btn" onclick="setUA('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36')">Chrome</button>
                    <button type="button" class="preset-btn" onclick="setUA('Mozilla/5.0 (Linux; Android 14; SM-S908B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36')">Android</button>
                    <button type="button" class="preset-btn" onclick="setUA('Mozilla/5.0 (iPhone; CPU iPhone OS 17_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Mobile/15E148 Safari/604.1')">iPhone</button>
                    <button type="button" class="preset-btn" onclick="setUA('SmartTV')">SmartTV</button>
                </div>
            </div>
            <div class="input-group">
                <label>Referer (opzionale)</label>
                <input type="text" id="plRef" placeholder="https://esempio.com/">
                <div class="usage-hint">Alcuni server lo richiedono</div>
            </div>
            <button type="submit" id="submitBtn">Salva e Attiva</button>
        </form>
    </div>

    <div id="status"></div>
</div>

<script>
let editId = '';

function setUA(v) { document.getElementById('plUA').value = v; }

async function apiCall(path) {
    try {
        const res = await fetch(path);
        const data = await res.json();
        if (data.ok !== false) loadPlaylists();
        return data;
    } catch(e) { return null; }
}

async function loadPlaylists() {
    const res = await fetch('/api/list');
    const data = await res.json();
    const container = document.getElementById('plList');
    if (!data.playlists || data.playlists.length === 0) {
        container.innerHTML = '<div class="empty-text">Nessuna playlist. Aggiungine una qui sotto.</div>';
        return;
    }
    container.innerHTML = data.playlists.map(p => {
        const isActive = p.id === data.activeId;
        const cls = isActive ? 'pl-item active' : 'pl-item';
        const badge = isActive ? ' [ATTIVA]' : '';
        return '<div class="'+cls+'">' +
            '<div class="pl-info">' +
                '<div class="pl-name">'+esc(p.name)+badge+'</div>' +
                '<div class="pl-url">'+esc(p.url)+'</div>' +
            '</div>' +
            '<div class="pl-actions">' +
                (!isActive ? '<button class="act-btn" onclick="apiCall(\'/api/activate?id='+p.id+'\')">Attiva</button>' : '') +
                '<button onclick="editPl(\''+p.id+'\',\''+escAttr(p.name)+'\',\''+escAttr(p.url)+'\',\''+escAttr(p.userAgent)+'\',\''+escAttr(p.referer)+'\')">Modifica</button>' +
                '<button class="del-btn" onclick="if(confirm(\'Eliminare '+esc(p.name)+'?\'))apiCall(\'/api/delete?id='+p.id+'\')">Elimina</button>' +
            '</div>' +
        '</div>';
    }).join('');
}

function editPl(id, name, url, ua, ref) {
    editId = id;
    document.getElementById('editId').value = id;
    document.getElementById('plName').value = name;
    document.getElementById('plUrl').value = url;
    document.getElementById('plUA').value = ua;
    document.getElementById('plRef').value = ref;
    document.getElementById('submitBtn').textContent = 'Aggiorna e Attiva';
    document.getElementById('plName').focus();
}

function esc(s) { return (s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;'); }
function escAttr(s) { return (s||'').replace(/\\/g,'\\\\').replace(/'/g,"\\'").replace(/"/g,'\\"'); }

document.getElementById('addForm').addEventListener('submit', async function(e) {
    e.preventDefault();
    const name = document.getElementById('plName').value.trim();
    const url = document.getElementById('plUrl').value.trim();
    const ua = document.getElementById('plUA').value.trim();
    const ref = document.getElementById('plRef').value.trim();
    const id = document.getElementById('editId').value;
    if (!name || !url) return;

    const btn = document.getElementById('submitBtn');
    btn.disabled = true;
    btn.textContent = 'Salvando...';
    showStatus('Salvataggio...', 'loading');

    const params = new URLSearchParams({ name, url, ua, ref, id });
    const res = await fetch('/api/add?' + params.toString());
    const data = await res.json();

    if (data.ok) {
        showStatus('Playlist "'+esc(name)+'" salvata e attivata!', 'success');
        document.getElementById('addForm').reset();
        document.getElementById('editId').value = '';
        document.getElementById('submitBtn').textContent = 'Salva e Attiva';
        editId = '';
        loadPlaylists();
    } else {
        showStatus(data.error || 'Errore', 'error');
    }
    btn.disabled = false;
    if (editId) btn.textContent = 'Aggiorna e Attiva'; else btn.textContent = 'Salva e Attiva';
});

function showStatus(msg, type) {
    const s = document.getElementById('status');
    s.textContent = msg;
    s.className = 'status show ' + type;
}

loadPlaylists();
</script>
</body>
</html>
        """.trimIndent()

        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }
}
