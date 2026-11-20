import { CategoryItem, ContentItem, EpgEntry, Kinds, UserInfo } from '../types';
import { db, HiddenStore, SyncMeta } from '../storage/db';
import { ArabicSearch, buildMediaUrl, decodeEpgText, Rating } from '../utils/arabic';
import {
  freeAccountInfo,
  freeCategories,
  freeDetails,
  freeItems,
  isFreeServer,
  resolveFreeMediaUrl,
} from './freeCatalog';

async function callXtream<T = unknown>(
  server: string,
  username: string,
  password: string,
  action = '',
  extra: Record<string, string> = {},
  uaIndex = 0,
  timeoutMs = 30000
): Promise<T> {
  const res = await fetch('/api/xtream/call', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ server, username, password, action, extra, uaIndex, timeoutMs }),
  });
  if (!res.ok) {
    const err = (await res.json().catch(() => ({}))) as { error?: string };
    throw new Error(err.error || `HTTP ${res.status}`);
  }
  return (await res.json()) as T;
}

function normalizeListResponse(raw: unknown): Array<Record<string, unknown>> {
  if (Array.isArray(raw)) return raw as Array<Record<string, unknown>>;
  if (raw && typeof raw === 'object') return Object.values(raw) as Array<Record<string, unknown>>;
  return [];
}

function parseStreamItem(
  item: Record<string, unknown>,
  kind: string,
  token: string,
  hiddenCats: Set<string>,
  hiddenIds: Set<string>,
  hiddenKind: string
): ContentItem | null {
  const id = String(item.stream_id ?? item.series_id ?? item.id ?? '').trim();
  const name = String(item.name ?? item.title ?? 'بدون عنوان').trim();
  if (!id || !name || name === 'null') return null;
  const category = String(item.category_id ?? item.category_name ?? '');
  const imageUrl = String(item.stream_icon ?? item.cover ?? item.cover_big ?? item.movie_image ?? '');
  const extension = String(item.container_extension ?? '');
  const rating = Rating.parse(item.rating, item.rating_5based);
  const rowKind = hiddenIds.has(id) || hiddenCats.has(category) ? hiddenKind : kind;
  const idNum = Number.parseInt(id, 10);
  return {
    pk: `${rowKind}::${id}`,
    kind: rowKind,
    contentId: id,
    name,
    normalizedName: ArabicSearch.normalize(name),
    category,
    imageUrl: imageUrl === 'null' ? '' : imageUrl,
    extension: extension === 'null' ? '' : extension,
    syncToken: token,
    rating,
    idNum: Number.isFinite(idNum) ? idNum : 0,
  };
}

export class XtreamClient {
  private server: string;
  private username: string;
  private password: string;

  constructor(server: string, username: string, password: string) {
    this.server = server.trim().replace(/\/+$/, '');
    this.username = username;
    this.password = password;
  }

  private get isFree(): boolean {
    return isFreeServer(this.server);
  }

  async account(): Promise<{ user_info?: UserInfo; server_info?: Record<string, unknown>; auth?: number } | null> {
    if (this.isFree) return freeAccountInfo();
    try {
      return await callXtream(this.server, this.username, this.password, '', {}, 0, 15000);
    } catch {
      return null;
    }
  }

  async details(action: 'get_vod_info' | 'get_series_info', id: string): Promise<Record<string, unknown>> {
    if (this.isFree) return freeDetails(action, id);
    const key = action === 'get_vod_info' ? 'vod_id' : 'series_id';
    return await callXtream(this.server, this.username, this.password, action, { [key]: id }, 0, 35000);
  }

  async movieMediaUrl(id: string, fallbackExtension: string): Promise<string> {
    if (this.isFree) return resolveFreeMediaUrl('movie', id, fallbackExtension);
    try {
      const details = await this.details('get_vod_info', id);
      const movieData = details.movie_data as Record<string, unknown> | undefined;
      const info = details.info as Record<string, unknown> | undefined;
      const candidates = [movieData?.container_extension, info?.container_extension, fallbackExtension];
      const ext = candidates.find((c) => typeof c === 'string' && c.trim() && c !== 'null') as string | undefined;
      return buildMediaUrl(this.server, 'movie', this.username, this.password, id, ext || 'mp4');
    } catch {
      return buildMediaUrl(this.server, 'movie', this.username, this.password, id, fallbackExtension || 'mp4');
    }
  }

  mediaUrl(kind: 'live' | 'movie' | 'series', id: string, extension: string): string {
    if (this.isFree) return resolveFreeMediaUrl(kind, id, extension);
    return buildMediaUrl(this.server, kind, this.username, this.password, id, extension);
  }

  async shortEpg(streamId: string, limit = 8): Promise<EpgEntry[]> {
    if (this.isFree) return [];
    const raw = await callXtream<{ epg_listings?: Array<Record<string, unknown>> }>(
      this.server,
      this.username,
      this.password,
      'get_short_epg',
      { stream_id: streamId, limit: String(Math.max(1, Math.min(20, limit))) },
      0,
      20000
    );
    const arr = Array.isArray(raw?.epg_listings) ? raw.epg_listings : [];
    const list: EpgEntry[] = [];
    for (const o of arr) {
      const startEpoch = Number(o.start_timestamp || 0);
      const stopEpoch = Number(o.stop_timestamp || 0);
      const startMs = startEpoch > 0 ? startEpoch * 1000 : Date.parse(String(o.start || '').replace(' ', 'T')) || 0;
      const endMs = stopEpoch > 0 ? stopEpoch * 1000 : Date.parse(String(o.end || '').replace(' ', 'T')) || 0;
      if (startMs <= 0 || endMs <= startMs) continue;
      list.push({
        title: decodeEpgText(String(o.title || '')),
        description: decodeEpgText(String(o.description || '')),
        startMs,
        endMs,
      });
    }
    return list.sort((a, b) => a.startMs - b.startMs);
  }

  async syncCategories(kind: string): Promise<number> {
    if (this.isFree) {
      const hiddenCats = HiddenStore.hiddenCategories(kind);
      const hiddenKind = HiddenStore.hiddenKind(kind);
      const items = await freeCategories(kind, hiddenCats, hiddenKind);
      if (items.length > 0) {
        await db.replaceCategories(kind, items);
      }
      return items.length;
    }

    const catAction =
      kind === Kinds.LIVE ? 'get_live_categories' :
      kind === Kinds.MOVIES ? 'get_vod_categories' :
      kind === Kinds.SERIES ? 'get_series_categories' : '';
    if (!catAction) throw new Error('نوع غير مدعوم');

    const raw = await callXtream(this.server, this.username, this.password, catAction, {}, 0, 30000);
    const arr = normalizeListResponse(raw);
    const hiddenCats = HiddenStore.hiddenCategories(kind);
    const hiddenKind = HiddenStore.hiddenKind(kind);
    const items: CategoryItem[] = [];
    for (const obj of arr) {
      const id = String(obj.category_id ?? obj.id ?? '').trim();
      const name = String(obj.category_name ?? obj.name ?? '').trim();
      if (id && name) {
        const k = hiddenCats.has(id) ? hiddenKind : kind;
        items.push({
          pk: `${k}::${id}`,
          kind: k,
          categoryId: id,
          categoryName: name,
          parentId: String(obj.parent_id ?? ''),
        });
      }
    }
    if (items.length > 0) {
      await db.replaceCategories(kind, items);
    }
    return items.length;
  }

  async sync(
    kind: string,
    uaIndex = 0,
    onProgress?: (loaded: number) => void,
    categoryId?: string,
    token: string = crypto.randomUUID(),
    seenIds: Set<string> = new Set(),
    baseSaved = 0,
    finalize = true,
    timeoutMs = 120000
  ): Promise<number> {
    const hiddenCats = HiddenStore.hiddenCategories(kind);
    const hiddenIds = HiddenStore.hiddenItemIds(kind);
    const hiddenKind = HiddenStore.hiddenKind(kind);

    if (this.isFree) {
      const items = await freeItems(kind, token, hiddenCats, hiddenIds, hiddenKind);
      let saved = 0;
      for (let i = 0; i < items.length; i += 500) {
        const chunk = items.slice(i, i + 500);
        await db.putBatch('content', chunk);
        saved += chunk.length;
        onProgress?.(baseSaved + saved);
      }
      if (saved > 0 && finalize) {
        await db.deleteStale(kind, token);
        await db.deleteStale(hiddenKind, token);
      }
      return saved;
    }

    const extra: Record<string, string> = categoryId ? { category_id: categoryId } : {};
    const raw = await callXtream(this.server, this.username, this.password, kind, extra, uaIndex, timeoutMs);
    const list = normalizeListResponse(raw);

    let saved = 0;
    const batch: ContentItem[] = [];
    for (const obj of list) {
      const parsed = parseStreamItem(obj, kind, token, hiddenCats, hiddenIds, hiddenKind);
      if (!parsed || seenIds.has(parsed.contentId)) continue;
      seenIds.add(parsed.contentId);
      batch.push(parsed);
      if (batch.length >= 500) {
        const copy = batch.splice(0, batch.length);
        await db.putBatch('content', copy);
        saved += copy.length;
        onProgress?.(baseSaved + saved);
      }
    }
    if (batch.length > 0) {
      await db.putBatch('content', batch);
      saved += batch.length;
      onProgress?.(baseSaved + saved);
    }
    if (saved > 0 && finalize) {
      await db.deleteStale(kind, token);
      await db.deleteStale(hiddenKind, token);
    }
    return saved;
  }

  async syncByCategories(
    kind: string,
    uaIndex = 0,
    onProgress?: (loaded: number) => void
  ): Promise<number> {
    if (this.isFree) return this.sync(kind, uaIndex, onProgress);
    const cats = [
      ...(await db.getCategories(kind)),
      ...(await db.getCategories(HiddenStore.hiddenKind(kind))),
    ];
    const ids = Array.from(new Set(cats.map((c) => c.categoryId)));
    if (ids.length === 0) throw new Error('لا توجد تصنيفات');
    const token = crypto.randomUUID();
    const seen = new Set<string>();
    let total = 0;
    let lastErr: unknown = null;
    for (const id of ids) {
      try {
        const n = await this.sync(kind, uaIndex, onProgress, id, token, seen, total, false, 45000);
        total += n;
      } catch (e) {
        lastErr = e;
      }
    }
    if (total > 0) return total;
    throw lastErr || new Error('الخادم أعاد قائمة فارغة');
  }
}

export interface SyncListener {
  onSyncState?: (kind: string, running: boolean, loaded: number, ok: boolean | null) => void;
  onCategoriesReady?: (kind: string) => void;
}

class SyncManagerImpl {
  private listeners = new Set<SyncListener>();
  private active = new Map<string, number>();
  private errors = new Map<string, string>();
  private finished = new Map<string, number>();

  add(listener: SyncListener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  isRunning(kind: string): boolean {
    return this.active.has(kind);
  }

  loaded(kind: string): number {
    return this.active.get(kind) ?? 0;
  }

  lastError(kind: string): string | undefined {
    return this.errors.get(kind);
  }

  finishedAt(kind: string): number {
    return this.finished.get(kind) ?? 0;
  }

  private publish(kind: string, running: boolean, loaded: number, ok: boolean | null) {
    for (const l of this.listeners) {
      l.onSyncState?.(kind, running, loaded, ok);
    }
  }

  private publishCategories(kind: string) {
    for (const l of this.listeners) {
      l.onCategoriesReady?.(kind);
    }
  }

  private refreshIntervalMs(): number {
    const mode = SyncMeta.refreshMode();
    if (mode === 'daily') return 24 * 60 * 60 * 1000;
    if (mode === 'manual') return 0;
    return 6 * 60 * 60 * 1000;
  }

  ensureAll(server: string, user: string, pass: string, force = false): void {
    for (const kind of Kinds.CONTENT) {
      void this.ensure(server, user, pass, kind, force);
    }
  }

  async categoriesCached(kinds: readonly string[] = Kinds.CONTENT): Promise<boolean> {
    for (const kind of kinds) {
      const hasMeta = SyncMeta.hasCategories(kind);
      if (hasMeta) continue;
      const vis = await db.getCategories(kind);
      const hid = await db.getCategories(HiddenStore.hiddenKind(kind));
      if (vis.length === 0 && hid.length === 0) return false;
    }
    return true;
  }

  async ensureCategories(
    server: string,
    user: string,
    pass: string,
    kinds: readonly string[] = Kinds.CONTENT
  ): Promise<boolean> {
    const client = new XtreamClient(server, user, pass);
    let allOk = true;
    for (const kind of kinds) {
      const vis = await db.getCategories(kind);
      const hid = await db.getCategories(HiddenStore.hiddenKind(kind));
      if (SyncMeta.hasCategories(kind) || vis.length > 0 || hid.length > 0) continue;
      try {
        const count = await client.syncCategories(kind);
        if (count > 0) SyncMeta.setHasCategories(kind, true);
      } catch {
        allOk = false;
      }
    }
    return allOk;
  }

  async ensure(server: string, user: string, pass: string, kind: string, force = false): Promise<void> {
    if (!server.trim() || !(Kinds.CONTENT as readonly string[]).includes(kind)) return;
    if (this.active.has(kind)) return;
    this.active.set(kind, 0);
    let announced = false;
    let success = false;
    let total = 0;

    try {
      const client = new XtreamClient(server, user, pass);
      const signature = `${server.trim().replace(/\/+$/, '').toLowerCase()}|${user.trim().toLowerCase()}`;
      const oldSig = SyncMeta.getSig(kind);
      if (oldSig && oldSig !== signature) {
        await db.clearKind(kind);
        SyncMeta.setLastSync(kind, 0);
        SyncMeta.setHasCategories(kind, false);
      }
      if (oldSig !== signature) SyncMeta.setSig(kind, signature);

      const last = SyncMeta.getLastSync(kind);
      const interval = this.refreshIntervalMs();
      const count = await db.countKind(kind);
      const fresh = !force && last > 0 && (interval === 0 || Date.now() - last < interval) && count > 0;
      if (fresh) return;

      announced = true;
      this.publish(kind, true, 0, null);

      const hadCats = (await db.getCategories(kind)).length > 0;
      try {
        const catCount = await client.syncCategories(kind);
        if (catCount > 0) SyncMeta.setHasCategories(kind, true);
        if (!hadCats && catCount > 0) this.publishCategories(kind);
      } catch {
        // continue to stream sync
      }

      const progress = (loaded: number) => {
        this.active.set(kind, loaded);
        this.publish(kind, true, loaded, null);
      };

      try {
        total = await client.sync(kind, 0, progress, undefined, crypto.randomUUID(), new Set(), 0, true, 30000);
        success = total > 0;
      } catch {
        try {
          total = await client.syncByCategories(kind, 0, progress);
          success = total > 0;
        } catch {
          for (let attempt = 1; attempt <= 2; attempt++) {
            await new Promise((r) => setTimeout(r, 1500 * attempt));
            try {
              total = await client.sync(kind, attempt, progress);
              success = total > 0;
              if (success) break;
            } catch (err) {
              if (attempt === 2) {
                this.errors.set(kind, err instanceof Error ? err.message.slice(0, 60) : 'خطأ في الاتصال');
              }
            }
          }
        }
      }

      if (success) {
        this.errors.delete(kind);
        SyncMeta.setLastSync(kind, Date.now());
      } else if (!this.errors.has(kind)) {
        this.errors.set(kind, total === 0 ? 'الخادم أعاد قائمة فارغة' : 'خطأ في الاتصال');
      }
    } catch {
      success = false;
      this.errors.set(kind, 'خطأ غير متوقع');
    } finally {
      this.active.delete(kind);
      if (announced) {
        this.finished.set(kind, Date.now());
        this.publish(kind, false, total, success);
      }
    }
  }

  reset(): void {
    SyncMeta.reset();
    this.errors.clear();
    this.finished.clear();
  }
}

export const SyncManager = new SyncManagerImpl();
