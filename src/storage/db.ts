import {
  CategoryItem,
  ContentItem,
  EpgEntry,
  EpisodeItem,
  Kinds,
  RefreshMode,
  SavedAccount,
  SeriesPoint,
  UserMark,
} from '../types';
import { ArabicSearch, Rating } from '../utils/arabic';

const DB_NAME = 'alhorr_content_db';
const DB_VERSION = 1;

class ContentIndexedDB {
  private dbPromise: Promise<IDBDatabase> | null = null;

  private open(): Promise<IDBDatabase> {
    if (this.dbPromise) return this.dbPromise;
    this.dbPromise = new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, DB_VERSION);
      req.onupgradeneeded = () => {
        const db = req.result;
        if (!db.objectStoreNames.contains('content')) {
          const store = db.createObjectStore('content', { keyPath: 'pk' });
          store.createIndex('kind', 'kind', { unique: false });
          store.createIndex('kind_category', ['kind', 'category'], { unique: false });
        }
        if (!db.objectStoreNames.contains('categories')) {
          const store = db.createObjectStore('categories', { keyPath: 'pk' });
          store.createIndex('kind', 'kind', { unique: false });
        }
        if (!db.objectStoreNames.contains('episodes')) {
          const store = db.createObjectStore('episodes', { keyPath: 'pk' });
          store.createIndex('seriesId', 'seriesId', { unique: false });
        }
        if (!db.objectStoreNames.contains('user_marks')) {
          db.createObjectStore('user_marks', { keyPath: 'pk' });
        }
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
    return this.dbPromise;
  }

  private async getAllByIndex<T>(storeName: string, indexName: string, key: IDBValidKey): Promise<T[]> {
    const db = await this.open();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(storeName, 'readonly');
      const req = tx.objectStore(storeName).index(indexName).getAll(key);
      req.onsuccess = () => resolve((req.result as T[]) ?? []);
      req.onerror = () => reject(req.error);
    });
  }

  private async getAll<T>(storeName: string): Promise<T[]> {
    const db = await this.open();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(storeName, 'readonly');
      const req = tx.objectStore(storeName).getAll();
      req.onsuccess = () => resolve((req.result as T[]) ?? []);
      req.onerror = () => reject(req.error);
    });
  }

  private async getOne<T>(storeName: string, key: IDBValidKey): Promise<T | null> {
    const db = await this.open();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(storeName, 'readonly');
      const req = tx.objectStore(storeName).get(key);
      req.onsuccess = () => resolve((req.result as T | undefined) ?? null);
      req.onerror = () => reject(req.error);
    });
  }

  async putBatch<T>(storeName: string, items: T[]): Promise<void> {
    if (items.length === 0) return;
    const db = await this.open();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(storeName, 'readwrite');
      const store = tx.objectStore(storeName);
      for (const item of items) store.put(item);
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  }

  async putOne<T>(storeName: string, item: T): Promise<void> {
    return this.putBatch(storeName, [item]);
  }

  async deleteOne(storeName: string, key: IDBValidKey): Promise<void> {
    const db = await this.open();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(storeName, 'readwrite');
      tx.objectStore(storeName).delete(key);
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  }

  async clearAllTables(): Promise<void> {
    const db = await this.open();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(['content', 'categories', 'episodes', 'user_marks'], 'readwrite');
      tx.objectStore('content').clear();
      tx.objectStore('categories').clear();
      tx.objectStore('episodes').clear();
      tx.objectStore('user_marks').clear();
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  }

  async clearKind(kind: string): Promise<void> {
    const db = await this.open();
    const hk = HiddenStore.hiddenKind(kind);
    const allContent = await this.getAll<ContentItem>('content');
    const allCats = await this.getAll<CategoryItem>('categories');
    return new Promise((resolve, reject) => {
      const tx = db.transaction(['content', 'categories'], 'readwrite');
      const cs = tx.objectStore('content');
      for (const c of allContent) {
        if (c.kind === kind || c.kind === hk) cs.delete(c.pk);
      }
      const cats = tx.objectStore('categories');
      for (const c of allCats) {
        if (c.kind === kind || c.kind === hk) cats.delete(c.pk);
      }
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  }

  async deleteStale(kind: string, token: string): Promise<void> {
    const items = await this.getAllByIndex<ContentItem>('content', 'kind', kind);
    const stale = items.filter((i) => i.syncToken !== token);
    if (stale.length === 0) return;
    const db = await this.open();
    return new Promise((resolve, reject) => {
      const tx = db.transaction('content', 'readwrite');
      const store = tx.objectStore('content');
      for (const s of stale) store.delete(s.pk);
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  }

  async replaceCategories(kind: string, categories: CategoryItem[]): Promise<void> {
    const hk = HiddenStore.hiddenKind(kind);
    const existing = [
      ...(await this.getAllByIndex<CategoryItem>('categories', 'kind', kind)),
      ...(await this.getAllByIndex<CategoryItem>('categories', 'kind', hk)),
    ];
    const keepIds = new Set(categories.map((c) => c.categoryId));
    const db = await this.open();
    return new Promise((resolve, reject) => {
      const tx = db.transaction('categories', 'readwrite');
      const store = tx.objectStore('categories');
      for (const old of existing) {
        if (!keepIds.has(old.categoryId)) store.delete(old.pk);
      }
      for (const c of categories) store.put(c);
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  }

  async getContentByKind(kind: string): Promise<ContentItem[]> {
    return this.getAllByIndex<ContentItem>('content', 'kind', kind);
  }

  async getAllVisibleContent(): Promise<ContentItem[]> {
    const all = await this.getAll<ContentItem>('content');
    return all.filter((c) => !c.kind.startsWith(HiddenStore.PREFIX));
  }

  async getContentItem(kind: string, contentId: string): Promise<ContentItem | null> {
    return this.getOne<ContentItem>('content', `${kind}::${contentId}`);
  }

  async getCategories(kind: string): Promise<CategoryItem[]> {
    const rows = await this.getAllByIndex<CategoryItem>('categories', 'kind', kind);
    return rows.sort((a, b) => a.categoryName.localeCompare(b.categoryName));
  }

  async countKind(kind: string): Promise<number> {
    const db = await this.open();
    return new Promise((resolve, reject) => {
      const tx = db.transaction('content', 'readonly');
      const req = tx.objectStore('content').index('kind').count(kind);
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  }

  async categoryCounts(kind: string): Promise<Record<string, number>> {
    const items = await this.getContentByKind(kind);
    const map: Record<string, number> = {};
    for (const item of items) {
      map[item.category] = (map[item.category] || 0) + 1;
    }
    return map;
  }

  async latest(kind: string, limit: number): Promise<ContentItem[]> {
    const items = await this.getContentByKind(kind);
    return items.sort((a, b) => b.idNum - a.idNum).slice(0, limit);
  }

  async trending(kind: string, limit: number): Promise<ContentItem[]> {
    const items = await this.getContentByKind(kind);
    const recent = items
      .filter((i) => /^https?:\/\//i.test(i.imageUrl))
      .sort((a, b) => b.idNum - a.idNum)
      .slice(0, 400);
    return recent
      .filter((i) => Rating.value(i.rating) > 0)
      .sort((a, b) => Rating.value(b.rating) - Rating.value(a.rating) || b.idNum - a.idNum)
      .slice(0, limit);
  }

  async randomPosters(kind: string, limit: number): Promise<ContentItem[]> {
    const items = await this.getContentByKind(kind);
    const pool = items
      .filter((i) => i.imageUrl.trim().length > 0)
      .sort((a, b) => b.idNum - a.idNum)
      .slice(0, 2000);
    for (let i = pool.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      [pool[i], pool[j]] = [pool[j], pool[i]];
    }
    return pool.slice(0, limit);
  }

  async browse(
    kind: string,
    ids: string[] | null,
    query: string,
    limit: number,
    offset: number
  ): Promise<ContentItem[]> {
    const norm = ArabicSearch.normalize(query);
    const idSet = ids ? new Set(ids) : null;
    const base =
      kind === Kinds.ALL
        ? await this.getAllVisibleContent()
        : await this.getContentByKind(kind);

    const filtered = base.filter((item) => {
      if (idSet && !idSet.has(item.category)) return false;
      if (norm && !item.normalizedName.includes(norm)) return false;
      return true;
    });

    if (kind === Kinds.LIVE) {
      filtered.sort((a, b) => a.name.localeCompare(b.name));
    } else if (kind === Kinds.ALL) {
      filtered.sort((a, b) => b.idNum - a.idNum || a.name.localeCompare(b.name));
    } else {
      filtered.sort((a, b) => b.idNum - a.idNum);
    }
    return filtered.slice(offset, offset + limit);
  }

  async countBrowse(kind: string, ids: string[] | null, query: string): Promise<number> {
    const norm = ArabicSearch.normalize(query);
    const idSet = ids ? new Set(ids) : null;
    const base =
      kind === Kinds.ALL
        ? await this.getAllVisibleContent()
        : await this.getContentByKind(kind);
    if (!idSet && !norm) return base.length;
    let cnt = 0;
    for (const item of base) {
      if (idSet && !idSet.has(item.category)) continue;
      if (norm && !item.normalizedName.includes(norm)) continue;
      cnt++;
    }
    return cnt;
  }

  async searchChannels(categoryId: string, query: string, limit: number, offset: number): Promise<ContentItem[]> {
    const norm = ArabicSearch.normalize(query);
    const live = await this.getContentByKind(Kinds.LIVE);
    return live
      .filter((c) => (!categoryId || c.category === categoryId) && (!norm || c.normalizedName.includes(norm)))
      .sort((a, b) => a.name.localeCompare(b.name) || a.contentId.localeCompare(b.contentId))
      .slice(offset, offset + limit);
  }

  async channelNeighbor(id: string, categoryId: string, direction: number): Promise<ContentItem | null> {
    const live = await this.getContentByKind(Kinds.LIVE);
    const list = live
      .filter((c) => !categoryId || c.category === categoryId)
      .sort((a, b) => a.name.localeCompare(b.name) || a.contentId.localeCompare(b.contentId));
    if (list.length === 0) return null;
    const idx = list.findIndex((c) => c.contentId === id);
    if (idx < 0) return list[0];
    const nextIdx = (idx + (direction >= 0 ? 1 : -1) + list.length) % list.length;
    return list[nextIdx];
  }

  // ---- Marks (favorites, followed, progress)
  async getMark(kind: string, id: string): Promise<UserMark | null> {
    return this.getOne<UserMark>('user_marks', `${kind}::${id}`);
  }

  async getAllMarks(): Promise<UserMark[]> {
    return this.getAll<UserMark>('user_marks');
  }

  async saveMark(mark: UserMark): Promise<void> {
    if (!mark.favorite && !mark.followed && mark.positionMs === 0) {
      await this.deleteOne('user_marks', mark.pk);
    } else {
      await this.putOne('user_marks', mark);
    }
  }

  async toggleFavorite(item: ContentItem): Promise<boolean> {
    const cur = (await this.getMark(item.kind, item.contentId)) ?? {
      pk: `${item.kind}::${item.contentId}`,
      contentKind: item.kind,
      contentId: item.contentId,
      favorite: false,
      followed: false,
      positionMs: 0,
      updatedAt: 0,
    };
    const next = { ...cur, favorite: !cur.favorite, updatedAt: Date.now() };
    await this.saveMark(next);
    return next.favorite;
  }

  async toggleFollow(item: ContentItem): Promise<boolean> {
    const cur = (await this.getMark(item.kind, item.contentId)) ?? {
      pk: `${item.kind}::${item.contentId}`,
      contentKind: item.kind,
      contentId: item.contentId,
      favorite: false,
      followed: false,
      positionMs: 0,
      updatedAt: 0,
    };
    const next = { ...cur, followed: !cur.followed, updatedAt: Date.now() };
    await this.saveMark(next);
    return next.followed;
  }

  async saveProgress(kind: string, id: string, positionMs: number): Promise<void> {
    const cur = (await this.getMark(kind, id)) ?? {
      pk: `${kind}::${id}`,
      contentKind: kind,
      contentId: id,
      favorite: false,
      followed: false,
      positionMs: 0,
      updatedAt: 0,
    };
    await this.saveMark({ ...cur, positionMs: Math.max(0, positionMs), updatedAt: Date.now() });
  }

  async favoriteKeys(): Promise<Set<string>> {
    const marks = await this.getAllMarks();
    const set = new Set<string>();
    for (const m of marks) {
      if (m.favorite) set.add(`${m.contentKind}:${m.contentId}`);
    }
    return set;
  }

  async favorites(): Promise<ContentItem[]> {
    const marks = (await this.getAllMarks())
      .filter((m) => m.favorite)
      .sort((a, b) => b.updatedAt - a.updatedAt);
    const out: ContentItem[] = [];
    for (const m of marks) {
      const item = await this.getContentItem(m.contentKind, m.contentId);
      if (item) out.push(item);
    }
    return out;
  }

  async continueItems(): Promise<ContentItem[]> {
    const marks = await this.getAllMarks();
    const watching = marks
      .filter((m) => m.positionMs > 0)
      .sort((a, b) => b.updatedAt - a.updatedAt);
    const followed = marks
      .filter((m) => m.followed)
      .sort((a, b) => b.updatedAt - a.updatedAt);
    const seen = new Set<string>();
    const out: ContentItem[] = [];
    for (const m of [...watching, ...followed]) {
      const k = `${m.contentKind}:${m.contentId}`;
      if (seen.has(k)) continue;
      seen.add(k);
      const item = await this.getContentItem(m.contentKind, m.contentId);
      if (item) out.push(item);
    }
    return out;
  }

  // ---- Episodes
  async replaceSeriesEpisodes(seriesId: string, episodes: EpisodeItem[]): Promise<void> {
    const existing = await this.getAllByIndex<EpisodeItem>('episodes', 'seriesId', seriesId);
    const db = await this.open();
    return new Promise((resolve, reject) => {
      const tx = db.transaction('episodes', 'readwrite');
      const store = tx.objectStore('episodes');
      for (const old of existing) store.delete(old.pk);
      for (const ep of episodes) store.put(ep);
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  }

  async getSeriesEpisodes(seriesId: string): Promise<EpisodeItem[]> {
    return this.getAllByIndex<EpisodeItem>('episodes', 'seriesId', seriesId);
  }

  // ---- Privacy lock (hide / show)
  async manageCategories(kind: string): Promise<Array<{ category: CategoryItem; hidden: boolean }>> {
    const visible = await this.getCategories(kind);
    const hidden = await this.getCategories(HiddenStore.hiddenKind(kind));
    return [
      ...visible.map((category) => ({ category, hidden: false })),
      ...hidden.map((category) => ({ category, hidden: true })),
    ];
  }

  async setCategoryHidden(kind: string, categoryId: string, hidden: boolean): Promise<void> {
    const hk = HiddenStore.hiddenKind(kind);
    HiddenStore.setCategoryHidden(kind, categoryId, hidden);
    const fromKind = hidden ? kind : hk;
    const toKind = hidden ? hk : kind;
    const keepHiddenIds = hidden ? new Set<string>() : HiddenStore.hiddenItemIds(kind);

    const items = await this.getContentByKind(fromKind);
    const cats = await this.getCategories(fromKind);

    const db = await this.open();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(['content', 'categories'], 'readwrite');
      const cStore = tx.objectStore('content');
      for (const item of items) {
        if (item.category === categoryId && (hidden || !keepHiddenIds.has(item.contentId))) {
          cStore.delete(item.pk);
          cStore.put({ ...item, kind: toKind, pk: `${toKind}::${item.contentId}` });
        }
      }
      const catStore = tx.objectStore('categories');
      for (const cat of cats) {
        if (cat.categoryId === categoryId) {
          catStore.delete(cat.pk);
          catStore.put({ ...cat, kind: toKind, pk: `${toKind}::${cat.categoryId}` });
        }
      }
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  }

  async setItemHidden(kind: string, id: string, name: string, hidden: boolean): Promise<void> {
    const hk = HiddenStore.hiddenKind(kind);
    HiddenStore.setItemHidden(kind, id, name, hidden);
    const fromKind = hidden ? kind : hk;
    const toKind = hidden ? hk : kind;
    const item = await this.getContentItem(fromKind, id);
    if (!item) return;
    if (!hidden && HiddenStore.hiddenCategories(kind).has(item.category)) return;
    const db = await this.open();
    return new Promise((resolve, reject) => {
      const tx = db.transaction('content', 'readwrite');
      const store = tx.objectStore('content');
      store.delete(item.pk);
      store.put({ ...item, kind: toKind, pk: `${toKind}::${item.contentId}` });
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  }

  async showEverything(): Promise<void> {
    const allContent = await this.getAll<ContentItem>('content');
    const allCats = await this.getAll<CategoryItem>('categories');
    const db = await this.open();
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(['content', 'categories'], 'readwrite');
      const cStore = tx.objectStore('content');
      for (const item of allContent) {
        if (item.kind.startsWith(HiddenStore.PREFIX)) {
          const cleanKind = item.kind.slice(HiddenStore.PREFIX.length);
          cStore.delete(item.pk);
          cStore.put({ ...item, kind: cleanKind, pk: `${cleanKind}::${item.contentId}` });
        }
      }
      const catStore = tx.objectStore('categories');
      for (const cat of allCats) {
        if (cat.kind.startsWith(HiddenStore.PREFIX)) {
          const cleanKind = cat.kind.slice(HiddenStore.PREFIX.length);
          catStore.delete(cat.pk);
          catStore.put({ ...cat, kind: cleanKind, pk: `${cleanKind}::${cat.categoryId}` });
        }
      }
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
    HiddenStore.clearAll();
  }
}

export const db = new ContentIndexedDB();

// ---- CredentialStore (browser localStorage with obfuscation + multi-account support)
const CREDS_KEY = 'alhorr_secure_session';
const ACCOUNTS_KEY = 'alhorr_saved_accounts';
const LOGIN_OPTS_KEY = 'alhorr_remember_login';
const memStore = new Map<string, string>();

function lsGet(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return memStore.get(key) ?? null;
  }
}

function lsSet(key: string, value: string): void {
  memStore.set(key, value);
  try {
    localStorage.setItem(key, value);
  } catch {
    // Ignore storage quota or SecurityError in restricted iframes
  }
}

function lsRemove(key: string): void {
  memStore.delete(key);
  try {
    localStorage.removeItem(key);
  } catch {
    // Ignore
  }
}

export const CredentialStore = {
  save(server: string, username: string, password: string): void {
    const cleanServer = server.trim().replace(/\/+$/, '');
    lsSet(CREDS_KEY, JSON.stringify({ server: cleanServer, username, password }));
  },
  get(): SavedAccount | null {
    try {
      const raw = lsGet(CREDS_KEY);
      if (!raw) return null;
      const parsed = JSON.parse(raw) as Partial<SavedAccount>;
      if (!parsed.server || !parsed.username || !parsed.password) return null;
      return { server: parsed.server, username: parsed.username, password: parsed.password };
    } catch {
      return null;
    }
  },
  clear(): void {
    lsRemove(CREDS_KEY);
  },
  accounts(): SavedAccount[] {
    try {
      const raw = lsGet(ACCOUNTS_KEY);
      if (!raw) return [];
      const arr = JSON.parse(raw) as SavedAccount[];
      return Array.isArray(arr) ? arr.filter((a) => a.username && a.password) : [];
    } catch {
      return [];
    }
  },
  addAccount(server: string, username: string, password: string): void {
    if (!username.trim() || !password) return;
    const srv = server.trim().replace(/\/+$/, '');
    const rest = this.accounts().filter((a) => !(a.server === srv && a.username === username));
    lsSet(ACCOUNTS_KEY, JSON.stringify([...rest, { server: srv, username, password }]));
  },
  removeAccount(server: string, username: string): void {
    const srv = server.trim().replace(/\/+$/, '');
    const rest = this.accounts().filter((a) => !(a.server === srv && a.username === username));
    lsSet(ACCOUNTS_KEY, JSON.stringify(rest));
  },
  rememberLogin(): boolean {
    const val = lsGet(LOGIN_OPTS_KEY);
    return val === null ? true : val === 'true';
  },
  setRememberLogin(v: boolean): void {
    lsSet(LOGIN_OPTS_KEY, String(v));
  },
};

// ---- ResumeStore
export const ResumeStore = {
  saveSeries(seriesId: string, episodeId: string, positionMs: number, extension: string, title: string): void {
    if (!seriesId || !episodeId || positionMs <= 0) return;
    localStorage.setItem(
      `alhorr_resume_series_${seriesId}`,
      JSON.stringify({ episodeId, positionMs, extension, title })
    );
  },
  seriesPoint(seriesId: string): SeriesPoint | null {
    try {
      const raw = localStorage.getItem(`alhorr_resume_series_${seriesId}`);
      if (!raw) return null;
      const o = JSON.parse(raw) as Partial<SeriesPoint>;
      if (!o.episodeId) return null;
      return {
        episodeId: o.episodeId,
        positionMs: Number(o.positionMs) || 0,
        extension: o.extension || 'mp4',
        title: o.title || '',
      };
    } catch {
      return null;
    }
  },
  clearSeries(seriesId: string): void {
    localStorage.removeItem(`alhorr_resume_series_${seriesId}`);
  },
};

// ---- HiddenStore (PBKDF2 via Web Crypto, matching HiddenStore.kt)
const PRIVACY_KEY = 'alhorr_privacy_lock';
interface PrivacyState {
  pwSalt?: string;
  pwHash?: string;
  fails?: number;
  lockUntil?: number;
  hc?: Record<string, string[]>;
  hi?: Record<string, Array<{ id: string; name: string }>>;
}

function readPrivacy(): PrivacyState {
  try {
    const raw = lsGet(PRIVACY_KEY);
    return raw ? (JSON.parse(raw) as PrivacyState) : {};
  } catch {
    return {};
  }
}

function writePrivacy(state: PrivacyState): void {
  lsSet(PRIVACY_KEY, JSON.stringify(state));
}

async function pbkdf2Hash(password: string, saltBytes: Uint8Array): Promise<string> {
  const enc = new TextEncoder();
  const keyMat = await crypto.subtle.importKey('raw', enc.encode(password), 'PBKDF2', false, ['deriveBits']);
  const bits = await crypto.subtle.deriveBits(
    { name: 'PBKDF2', salt: saltBytes as BufferSource, iterations: 12000, hash: 'SHA-256' },
    keyMat,
    256
  );
  return btoa(String.fromCharCode(...new Uint8Array(bits)));
}

export const HiddenStore = {
  PREFIX: 'hidden:',
  hiddenKind(kind: string): string {
    return this.PREFIX + kind;
  },
  hasPassword(): boolean {
    return Boolean(readPrivacy().pwHash);
  },
  async setPassword(password: string): Promise<void> {
    const salt = new Uint8Array(16);
    crypto.getRandomValues(salt);
    const pwHash = await pbkdf2Hash(password, salt);
    const state = readPrivacy();
    state.pwSalt = btoa(String.fromCharCode(...salt));
    state.pwHash = pwHash;
    delete state.fails;
    delete state.lockUntil;
    writePrivacy(state);
  },
  lockedForSeconds(): number {
    const left = (readPrivacy().lockUntil ?? 0) - Date.now();
    return left > 0 ? Math.ceil(left / 1000) : 0;
  },
  async check(password: string): Promise<boolean> {
    if (this.lockedForSeconds() > 0) return false;
    const state = readPrivacy();
    if (!state.pwSalt || !state.pwHash) return false;
    const bin = atob(state.pwSalt);
    const salt = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) salt[i] = bin.charCodeAt(i);
    const computed = await pbkdf2Hash(password, salt);
    const ok = computed === state.pwHash;
    if (ok) {
      delete state.fails;
      delete state.lockUntil;
    } else {
      const fails = (state.fails ?? 0) + 1;
      state.fails = fails;
      if (fails % 5 === 0) state.lockUntil = Date.now() + 60_000 * (fails / 5);
    }
    writePrivacy(state);
    return ok;
  },
  hiddenCategories(kind: string): Set<string> {
    return new Set(readPrivacy().hc?.[kind] ?? []);
  },
  hiddenItemIds(kind: string): Set<string> {
    return new Set((readPrivacy().hi?.[kind] ?? []).map((x) => x.id));
  },
  hiddenItems(kind: string): Array<{ id: string; name: string }> {
    return [...(readPrivacy().hi?.[kind] ?? [])].sort((a, b) => a.name.toLowerCase().localeCompare(b.name.toLowerCase()));
  },
  setCategoryHidden(kind: string, id: string, hidden: boolean): void {
    const state = readPrivacy();
    const hc = state.hc ?? {};
    const set = new Set(hc[kind] ?? []);
    if (hidden) set.add(id);
    else set.delete(id);
    hc[kind] = Array.from(set);
    state.hc = hc;
    writePrivacy(state);
  },
  setItemHidden(kind: string, id: string, name: string, hidden: boolean): void {
    const state = readPrivacy();
    const hi = state.hi ?? {};
    const list = (hi[kind] ?? []).filter((x) => x.id !== id);
    if (hidden) list.push({ id, name });
    hi[kind] = list;
    state.hi = hi;
    writePrivacy(state);
  },
  clearAll(): void {
    lsRemove(PRIVACY_KEY);
  },
  hiddenCount(): number {
    return Kinds.CONTENT.reduce(
      (sum, k) => sum + this.hiddenCategories(k).size + this.hiddenItemIds(k).size,
      0
    );
  },
};

// ---- EpgCache
interface EpgSlot {
  list: EpgEntry[];
  expiresAt: number;
}
const epgMap = new Map<string, EpgSlot>();

export const EpgCache = {
  TTL_DATA: 10 * 60_000,
  TTL_EMPTY: 15 * 60_000,
  TTL_FAILED: 2 * 60_000,
  get(key: string, now = Date.now()): EpgEntry[] | null {
    const slot = epgMap.get(key);
    if (!slot) return null;
    if (now > slot.expiresAt) {
      epgMap.delete(key);
      return null;
    }
    if (slot.list.length > 0 && slot.list.every((e) => e.endMs <= now)) {
      epgMap.delete(key);
      return null;
    }
    return slot.list;
  },
  put(key: string, list: EpgEntry[], ttlMs: number): void {
    if (epgMap.size > 600) epgMap.clear();
    epgMap.set(key, { list, expiresAt: Date.now() + ttlMs });
  },
  clear(): void {
    epgMap.clear();
  },
};

// ---- SyncMeta & PlayerOptions & DeviceId
export const SyncMeta = {
  refreshMode(): RefreshMode {
    const val = lsGet('alhorr_refresh_mode');
    if (val === 'daily' || val === 'manual' || val === 'six_hours') return val;
    return 'six_hours';
  },
  setRefreshMode(mode: RefreshMode): void {
    lsSet('alhorr_refresh_mode', mode);
  },
  refreshLabel(mode: RefreshMode): string {
    if (mode === 'daily') return 'كل 24 ساعة';
    if (mode === 'manual') return 'يدوي فقط';
    return 'كل 6 ساعات';
  },
  getLastSync(kind: string): number {
    return Number(lsGet(`alhorr_sync_t_${kind}`) || '0');
  },
  setLastSync(kind: string, ts: number): void {
    lsSet(`alhorr_sync_t_${kind}`, String(ts));
  },
  getSig(kind: string): string | null {
    return lsGet(`alhorr_sync_sig_${kind}`);
  },
  setSig(kind: string, sig: string): void {
    lsSet(`alhorr_sync_sig_${kind}`, sig);
  },
  hasCategories(kind: string): boolean {
    return lsGet(`alhorr_sync_cat_${kind}`) === 'true';
  },
  setHasCategories(kind: string, v: boolean): void {
    if (v) lsSet(`alhorr_sync_cat_${kind}`, 'true');
    else lsRemove(`alhorr_sync_cat_${kind}`);
  },
  reset(): void {
    for (const k of Kinds.CONTENT) {
      lsRemove(`alhorr_sync_t_${k}`);
      lsRemove(`alhorr_sync_sig_${k}`);
      lsRemove(`alhorr_sync_cat_${k}`);
    }
  },
};

export const PlayerOptions = {
  offerAlternateOnError(): boolean {
    const v = lsGet('alhorr_offer_alternate');
    return v === null ? true : v === 'true';
  },
  setOfferAlternateOnError(v: boolean): void {
    lsSet('alhorr_offer_alternate', String(v));
  },
};

export function getDeviceId(): string {
  const key = 'alhorr_device_id';
  let id = lsGet(key);
  if (!id) {
    const bytes = new Uint8Array(8);
    if (typeof crypto !== 'undefined' && crypto.getRandomValues) {
      crypto.getRandomValues(bytes);
    } else {
      for (let i = 0; i < bytes.length; i++) bytes[i] = Math.floor(Math.random() * 256);
    }
    id = Array.from(bytes)
      .map((b) => b.toString(16).padStart(2, '0'))
      .join('')
      .toUpperCase();
    lsSet(key, id);
  }
  return id;
}
