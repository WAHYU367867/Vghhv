import express from 'express';
import type { Request, Response } from 'express';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createServer as createViteServer } from 'vite';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const USER_AGENTS = [
  'AL-HUR-AL-ASLI/1.0 (Android)',
  'Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36',
  'IPTVSmartersPro',
];

function isValidHttpUrl(raw: string): boolean {
  try {
    const u = new URL(raw);
    return u.protocol === 'http:' || u.protocol === 'https:';
  } catch {
    return false;
  }
}

async function fetchWithRedirects(
  targetUrl: string,
  headers: Record<string, string>,
  timeoutMs = 30000
): Promise<globalThis.Response> {
  let current = targetUrl;
  for (let hop = 0; hop <= 5; hop++) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const res = await fetch(current, {
        method: 'GET',
        headers,
        redirect: 'manual',
        signal: controller.signal,
      });
      if (res.status >= 300 && res.status < 400) {
        const loc = res.headers.get('location');
        if (!loc) return res;
        current = new URL(loc, current).toString();
        continue;
      }
      return res;
    } finally {
      clearTimeout(timer);
    }
  }
  throw new Error('تحويلات كثيرة');
}

async function main() {
  const app = express();
  app.use(express.json({ limit: '1mb' }));

  app.get('/api/download-android-project', (_req: Request, res: Response) => {
    const zipPath = path.resolve(__dirname, 'alhorr_tv_updated.zip');
    res.setHeader('Cache-Control', 'no-store, no-cache, must-revalidate, proxy-revalidate');
    res.setHeader('Pragma', 'no-cache');
    res.setHeader('Expires', '0');
    res.download(zipPath, 'alhorr_tv_updated.zip');
  });

  app.get('/api/download-apk', (_req: Request, res: Response) => {
    const apkPath = path.resolve(__dirname, 'alhorr-tv-v0.8.0.apk');
    res.setHeader('Cache-Control', 'no-store, no-cache, must-revalidate, proxy-revalidate');
    res.setHeader('Pragma', 'no-cache');
    res.setHeader('Expires', '0');
    res.setHeader('Content-Type', 'application/vnd.android.package-archive');
    res.download(apkPath, 'alhorr-tv-v0.8.0.apk');
  });

  // Live Arabic Free-To-Air channels M3U proxy for isolated free catalog
  app.get('/api/free-live-m3u', async (_req: Request, res: Response) => {
    try {
      const upstream = await fetchWithRedirects(
        'https://iptv-org.github.io/iptv/languages/ara.m3u',
        { 'User-Agent': USER_AGENTS[0], Accept: 'text/plain,*/*' },
        12000
      );
      if (!upstream.ok) {
        res.status(upstream.status).send('');
        return;
      }
      const text = await upstream.text();
      res.setHeader('Content-Type', 'text/plain; charset=utf-8');
      res.setHeader('Cache-Control', 'public, max-age=1800');
      res.send(text);
    } catch {
      res.status(502).send('');
    }
  });

  // Generic M3U playlist proxy for authorized Adult 18+ M3U URL imports
  app.get('/api/proxy', async (req: Request, res: Response) => {
    const target = String(req.query.url ?? '').trim();
    if (!isValidHttpUrl(target)) {
      res.status(400).send('Invalid URL');
      return;
    }
    try {
      const upstream = await fetchWithRedirects(
        target,
        { 'User-Agent': USER_AGENTS[0], Accept: 'text/plain,application/x-mpegurl,*/*' },
        15000
      );
      if (!upstream.ok) {
        res.status(upstream.status).send('');
        return;
      }
      const text = await upstream.text();
      res.setHeader('Content-Type', 'text/plain; charset=utf-8');
      res.send(text);
    } catch {
      res.status(502).send('');
    }
  });

  // Xtream player_api.php proxy (bypasses browser CORS & HTTP Mixed Content)
  app.post('/api/xtream/call', async (req: Request, res: Response) => {
    const body = req.body as {
      server?: string;
      username?: string;
      password?: string;
      action?: string;
      uaIndex?: number;
      extra?: Record<string, string>;
      timeoutMs?: number;
    };

    const server = (body?.server ?? '').trim().replace(/\/+$/, '');
    const username = body?.username ?? '';
    const password = body?.password ?? '';
    const action = body?.action ?? '';
    const uaIndex = Math.max(0, Math.min(USER_AGENTS.length - 1, body?.uaIndex ?? 0));
    const timeoutMs = Math.min(120000, Math.max(5000, body?.timeoutMs ?? 30000));

    if (!isValidHttpUrl(server) || !username || !password) {
      res.status(400).json({ error: 'بيانات الخادم أو الدخول غير مكتملة' });
      return;
    }

    const params = new URLSearchParams({ username, password });
    if (action) params.set('action', action);
    if (body?.extra) {
      for (const [k, v] of Object.entries(body.extra)) {
        if (v !== undefined && v !== null) params.set(k, String(v));
      }
    }

    const url = `${server}/player_api.php?${params.toString()}`;
    try {
      const upstream = await fetchWithRedirects(
        url,
        {
          Accept: 'application/json',
          'User-Agent': USER_AGENTS[uaIndex],
        },
        timeoutMs
      );
      if (!upstream.ok) {
        res.status(upstream.status).json({ error: `HTTP ${upstream.status}` });
        return;
      }
      const text = await upstream.text();
      res.setHeader('Content-Type', 'application/json; charset=utf-8');
      res.send(text);
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'تعذر الاتصال بالخادم';
      res.status(502).json({ error: msg });
    }
  });

  // Image proxy for HTTP/CORS poster & logo URLs
  app.get('/api/image', async (req: Request, res: Response) => {
    const target = String(req.query.url ?? '').trim();
    if (!isValidHttpUrl(target)) {
      res.status(400).end();
      return;
    }
    try {
      const upstream = await fetchWithRedirects(
        target,
        {
          'User-Agent': 'AL-HUR-AL-ASLI/1.0',
          Accept: 'image/*,*/*;q=0.8',
        },
        15000
      );
      if (!upstream.ok || !upstream.body) {
        res.status(upstream.status || 502).end();
        return;
      }
      const ct = upstream.headers.get('content-type') || 'image/jpeg';
      res.setHeader('Content-Type', ct);
      res.setHeader('Cache-Control', 'public, max-age=86400');
      const buf = Buffer.from(await upstream.arrayBuffer());
      res.send(buf);
    } catch {
      res.status(502).end();
    }
  });

  // Media stream proxy with Range support & HLS playlist URL rewriting
  app.get('/api/media', async (req: Request, res: Response) => {
    const target = String(req.query.url ?? '').trim();
    if (!isValidHttpUrl(target)) {
      res.status(400).send('Invalid URL');
      return;
    }

    const headers: Record<string, string> = {
      'User-Agent': USER_AGENTS[0],
    };
    if (typeof req.headers.range === 'string') {
      headers['Range'] = req.headers.range;
    }

    const controller = new AbortController();
    req.on('close', () => controller.abort());

    try {
      let current = target;
      let upstream: globalThis.Response | null = null;
      for (let hop = 0; hop <= 5; hop++) {
        const r = await fetch(current, {
          method: 'GET',
          headers,
          redirect: 'manual',
          signal: controller.signal,
        });
        if (r.status >= 300 && r.status < 400) {
          const loc = r.headers.get('location');
          if (!loc) {
            upstream = r;
            break;
          }
          current = new URL(loc, current).toString();
          continue;
        }
        upstream = r;
        break;
      }

      if (!upstream) {
        res.status(502).send('Upstream error');
        return;
      }

      const ct = upstream.headers.get('content-type') || 'application/octet-stream';
      const isM3u8 =
        current.toLowerCase().includes('.m3u8') ||
        ct.toLowerCase().includes('mpegurl');

      if (isM3u8 && upstream.ok) {
        const text = await upstream.text();
        const rewritten = text
          .split(/\r?\n/)
          .map((line) => {
            const trimmed = line.trim();
            if (!trimmed) return line;
            if (trimmed.startsWith('#')) {
              return line.replace(/URI="([^"]+)"/g, (_, uri: string) => {
                try {
                  const abs = new URL(uri, current).toString();
                  return `URI="/api/media?url=${encodeURIComponent(abs)}"`;
                } catch {
                  return `URI="${uri}"`;
                }
              });
            }
            try {
              const abs = new URL(trimmed, current).toString();
              return `/api/media?url=${encodeURIComponent(abs)}`;
            } catch {
              return line;
            }
          })
          .join('\n');

        res.status(200);
        res.setHeader('Content-Type', 'application/vnd.apple.mpegurl');
        res.setHeader('Access-Control-Allow-Origin', '*');
        res.send(rewritten);
        return;
      }

      res.status(upstream.status);
      for (const h of [
        'content-type',
        'content-length',
        'content-range',
        'accept-ranges',
      ]) {
        const val = upstream.headers.get(h);
        if (val) res.setHeader(h, val);
      }
      res.setHeader('Access-Control-Allow-Origin', '*');

      if (!upstream.body) {
        res.end();
        return;
      }

      const reader = upstream.body.getReader();
      try {
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          if (!res.writableEnded) {
            res.write(Buffer.from(value));
          } else {
            break;
          }
        }
      } catch {
        // Client disconnected or aborted
      } finally {
        if (!res.writableEnded) res.end();
      }
    } catch {
      if (!res.headersSent) res.status(502).send('Stream unavailable');
    }
  });

  if (process.env.NODE_ENV !== 'production') {
    const vite = await createViteServer({
      server: { middlewareMode: true, hmr: false },
      appType: 'spa',
    });
    app.use((req, res, next) => {
      if (req.path === '/' || req.path.endsWith('.html')) {
        res.setHeader('Cache-Control', 'no-store, no-cache, must-revalidate');
      }
      next();
    });
    app.use(vite.middlewares);
  } else {
    const distDir = path.resolve(__dirname, 'dist');
    app.use(express.static(distDir));
    app.get('*', (_req, res) => {
      res.setHeader('Cache-Control', 'no-store, no-cache, must-revalidate');
      res.sendFile(path.join(distDir, 'index.html'));
    });
  }

  const port = Number(process.env.PORT) || 3000;
  app.listen(port, '0.0.0.0', () => {
    console.log(`ALHUR TV server running at http://0.0.0.0:${port}`);
  });
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
