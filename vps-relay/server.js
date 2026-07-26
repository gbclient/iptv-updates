const http = require('http');
const https = require('https');
const url = require('url');

const PORT = process.env.PORT || 8888;

const server = http.createServer((req, res) => {
    res.setHeader('Access-Control-Allow-Origin', '*');

    if (req.method === 'OPTIONS') { res.writeHead(200); res.end(); return; }

    const parsed = url.parse(req.url, true);
    
    // /proxy?url=ENCODED_URL
    if (parsed.pathname === '/proxy' && parsed.query.url) {
        const target = decodeURIComponent(parsed.query.url);
        const opts = url.parse(target);
        const t = opts.protocol === 'https:' ? https : http;
        
        const proxyReq = t.request({
            hostname: opts.hostname, port: opts.port, path: opts.path,
            headers: { 'User-Agent': 'VLC/3.0.20 LibVLC/3.0.20' }
        }, (proxyRes) => {
            res.writeHead(proxyRes.statusCode, proxyRes.headers);
            proxyRes.pipe(res);
        });
        proxyReq.on('error', () => { if (!res.headersSent) res.writeHead(502); res.end(); });
        req.pipe(proxyReq);
        return;
    }

    res.writeHead(200, { 'Content-Type': 'text/plain' });
    res.end('IPTV Relay OK');
});

server.listen(PORT, () => console.log(`Relay on port ${PORT}`));
