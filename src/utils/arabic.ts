import { CategoryItem, Kinds, SectionInfo } from '../types';

export const DEFAULT_SERVER = 'http://2.ztoyou.site/';
export const APP_VERSION = 'v0.8.0';

export const SupportContact = {
  NUMBER: '963958963357',
  DISPLAY: '+963 958 963 357',
  whatsAppUrl(message: string): string {
    return `https://wa.me/${this.NUMBER}?text=${encodeURIComponent(message)}`;
  },
};

async function triggerBlobDownload(endpoint: string, filename: string): Promise<boolean> {
  try {
    const sep = endpoint.includes('?') ? '&' : '?';
    const res = await fetch(`${endpoint}${sep}v=${encodeURIComponent(APP_VERSION)}`, {
      cache: 'no-store',
    });
    if (!res.ok) return false;
    const blob = await res.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 10_000);
    return true;
  } catch {
    return false;
  }
}

export function downloadAndroidApk(): Promise<boolean> {
  return triggerBlobDownload('/api/download-apk', 'alhorr-tv-v0.8.0.apk');
}

export function downloadAndroidProject(): Promise<boolean> {
  return triggerBlobDownload('/api/download-android-project', 'alhorr_tv_updated.zip');
}

export const ArabicSearch = {
  normalize(value: string): string {
    let digits = '';
    for (const ch of value.toLowerCase()) {
      const code = ch.charCodeAt(0);
      if (code >= 0x0660 && code <= 0x0669) {
        digits += String.fromCharCode(48 + (code - 0x0660));
      } else {
        digits += ch;
      }
    }
    return digits
      .replace(/أ/g, 'ا')
      .replace(/إ/g, 'ا')
      .replace(/آ/g, 'ا')
      .replace(/ى/g, 'ي')
      .replace(/ة/g, 'ه')
      .replace(/[\u064B-\u065F]/g, '')
      .replace(/[^\p{L}\p{N}]+/gu, ' ')
      .trim()
      .replace(/\s+/g, ' ');
  },
};

export const Rating = {
  parse(rating: unknown, rating5: unknown): string {
    const ten = Number.parseFloat(String(rating ?? '').trim().replace(',', '.'));
    const five = Number.parseFloat(String(rating5 ?? '').trim().replace(',', '.'));
    let v = 0;
    if (Number.isFinite(ten) && ten > 0) v = ten;
    else if (Number.isFinite(five) && five > 0) v = five * 2;
    else return '';
    return Math.min(10, v).toFixed(1);
  },
  value(r: string): number {
    const n = Number.parseFloat(r);
    return Number.isFinite(n) ? n : 0;
  },
};

export function cleanRating(raw: unknown): string {
  const value = Number.parseFloat(String(raw ?? '').trim().replace(',', '.'));
  return Number.isFinite(value) && value > 0 ? value.toFixed(1) : '';
}

export function strField(obj: Record<string, unknown> | null | undefined, ...keys: string[]): string {
  if (!obj) return '';
  for (const key of keys) {
    const raw = obj[key];
    if (raw === undefined || raw === null) continue;
    const val = String(raw).trim();
    if (val && val.toLowerCase() !== 'null') return val;
  }
  return '';
}

export function firstImage(obj: Record<string, unknown> | null | undefined, ...keys: string[]): string {
  if (!obj) return '';
  for (const key of keys) {
    const val = obj[key];
    if (Array.isArray(val)) {
      for (const item of val) {
        const c = String(item ?? '').trim();
        if (/^https?:\/\//i.test(c)) return c;
      }
    } else if (typeof val === 'string') {
      const c = val.trim();
      if (/^https?:\/\//i.test(c)) return c;
    }
  }
  return '';
}

export function splitNames(raw: string): string[] {
  return Array.from(
    new Set(
      raw
        .split(/[,،/|]/)
        .map((s) => s.trim())
        .filter((s) => s.length > 0 && s.toLowerCase() !== 'null')
    )
  );
}

function secondsFromClock(text: string): number {
  const parts = text.trim().split(':');
  if (parts.length !== 3) return 0;
  const h = Number.parseInt(parts[0], 10);
  const m = Number.parseInt(parts[1], 10);
  const s = Number.parseInt(parts[2], 10) || 0;
  if (!Number.isFinite(h) || !Number.isFinite(m)) return 0;
  return h * 3600 + m * 60 + s;
}

export function formatDuration(seconds: number, clock: string): string {
  const total = seconds > 0 ? seconds : secondsFromClock(clock);
  if (total <= 0) return '';
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  if (h > 0 && m > 0) return `${h} س ${m} د`;
  if (h > 0) return `${h} س`;
  if (m > 0) return `${m} د`;
  return '';
}

export function formatMinutes(seconds: number, clock: string): string {
  const total = seconds > 0 ? seconds : secondsFromClock(clock);
  const mins = Math.floor(total / 60);
  return mins > 0 ? `${mins} دقيقة` : '';
}

export function formatTimeMs(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}

export function decodeEpgText(raw: string): string {
  const text = raw.trim();
  if (!text) return '';
  if (text.length % 4 === 0 && /^[A-Za-z0-9+/=]+$/.test(text)) {
    try {
      const bin = atob(text);
      const bytes = new Uint8Array(bin.length);
      for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
      const decoded = new TextDecoder('utf-8', { fatal: true }).decode(bytes).trim();
      if (decoded && !/[\x00-\x08\x0B\x0C\x0E-\x1F]/.test(decoded)) {
        return decoded;
      }
    } catch {
      // Not valid base64 UTF-8, return raw text
    }
  }
  return text;
}

export function buildMediaUrl(
  server: string,
  kind: string,
  username: string,
  password: string,
  id: string,
  extension: string
): string {
  const cleanExtension = extension
    .trim()
    .replace(/^\.+/, '')
    .split('?')[0] || (kind === 'live' ? 'm3u8' : 'mp4');
  const cleanId = id.trim().split('?')[0].split('#')[0];
  const base = server.trim().replace(/\/+$/, '');
  return `${base}/${kind}/${encodeURIComponent(username)}/${encodeURIComponent(password)}/${cleanId}.${cleanExtension}`;
}

export function proxiedImageUrl(url?: string): string {
  const clean = (url ?? '').trim();
  if (!clean || clean.toLowerCase() === 'null' || !/^https?:\/\//i.test(clean)) return '';
  return `/api/image?url=${encodeURIComponent(clean.replace(/ /g, '%20'))}`;
}

export function proxiedMediaUrl(url: string): string {
  const clean = url.trim();
  if (!clean || !/^https?:\/\//i.test(clean)) return '';
  return `/api/media?url=${encodeURIComponent(clean)}`;
}

// Smart sections matching Sections.kt
interface SectionDef {
  key: string;
  label: string;
  icon: string;
  groups: string[][];
}

const ARABIC = ['عربي', 'عرب', 'arab', 'سوري', 'مصري', 'خليجي', 'لبناني', 'كويتي', 'سعودي', 'اردني', 'syria', 'egypt', 'gulf'];
const FOREIGN = ['اجنبي', 'foreign', 'english', 'انجليزي', 'امريكي', 'مترجم', 'dubbed', 'hollywood', 'usa', 'uk '];
const TURKISH = ['تركي', 'turk'];
const INDIAN = ['هندي', 'hindi', 'india', 'bollywood'];
const KOREAN = ['كوري', 'korea'];
const ANIME = ['انمي', 'anime', 'كرتون', 'cartoon'];
const KIDS = ['اطفال', 'kids', 'children'];
const DOC = ['وثائقي', 'documentary'];
const NETFLIX = ['netflix', 'نتفليكس', 'نتفلكس'];
const SHAHID = ['shahid', 'shahed', 'شاهد'];

const SERIES_DEFS: SectionDef[] = [
  { key: 'ramadan2026', label: 'رمضان 2026', icon: '🌙', groups: [['رمضان', 'ramadan', 'ramadhan'], ['2026']] },
  { key: 'ramadan', label: 'رمضان', icon: '🌙', groups: [['رمضان', 'ramadan', 'ramadhan']] },
  { key: 'arabic', label: 'عربي', icon: '🎭', groups: [ARABIC] },
  { key: 'shahid', label: 'شاهد', icon: '▶', groups: [SHAHID] },
  { key: 'netflix', label: 'نتفليكس', icon: 'N', groups: [NETFLIX] },
  { key: 'turkish', label: 'تركي', icon: '🇹🇷', groups: [TURKISH] },
  { key: 'foreign', label: 'أجنبي', icon: '🌍', groups: [FOREIGN] },
  { key: 'korean', label: 'كوري', icon: '🇰🇷', groups: [KOREAN] },
  { key: 'indian', label: 'هندي', icon: '🇮🇳', groups: [INDIAN] },
  { key: 'anime', label: 'أنمي وكرتون', icon: '✨', groups: [ANIME] },
  { key: 'doc', label: 'وثائقي', icon: '🎞', groups: [DOC] },
];

const MOVIES_DEFS: SectionDef[] = [
  { key: 'new', label: 'جديد 2026', icon: '🔥', groups: [['2026', 'جديد', 'new']] },
  { key: 'arabic', label: 'أفلام عربية', icon: '🎭', groups: [ARABIC] },
  { key: 'foreign', label: 'أفلام أجنبية', icon: '🌍', groups: [FOREIGN] },
  { key: 'netflix', label: 'نتفليكس', icon: 'N', groups: [NETFLIX] },
  { key: 'turkish', label: 'تركي', icon: '🇹🇷', groups: [TURKISH] },
  { key: 'indian', label: 'هندي', icon: '🇮🇳', groups: [INDIAN] },
  { key: 'korean', label: 'كوري', icon: '🇰🇷', groups: [KOREAN] },
  { key: 'anime', label: 'أنمي وكرتون', icon: '✨', groups: [ANIME] },
  { key: 'kids', label: 'أطفال', icon: '🧸', groups: [KIDS] },
  { key: 'doc', label: 'وثائقي', icon: '🎞', groups: [DOC] },
];

const LIVE_DEFS: SectionDef[] = [
  { key: 'arabic', label: 'عربية', icon: '🎭', groups: [ARABIC] },
  { key: 'sports', label: 'رياضة', icon: '⚽', groups: [['رياضه', 'sport', 'bein', 'ssc', 'كاس', 'دوري']] },
  { key: 'news', label: 'أخبار', icon: '📰', groups: [['اخبار', 'news', 'الجزيره', 'العربيه']] },
  { key: 'movies', label: 'أفلام', icon: '🎬', groups: [['افلام', 'movie', 'cinema', 'سينما']] },
  { key: 'kids', label: 'أطفال', icon: '🧸', groups: [[...KIDS, ...ANIME]] },
  { key: 'foreign', label: 'أجنبية', icon: '🌍', groups: [FOREIGN] },
  { key: 'religious', label: 'دينية', icon: '🕌', groups: [['قران', 'quran', 'اسلام', 'islam', 'دينيه']] },
  { key: 'music', label: 'موسيقى', icon: '🎵', groups: [['موسيقي', 'music', 'اغاني']] },
];

export function buildSmartSections(
  kind: string,
  categories: CategoryItem[],
  counts: Record<string, number>
): SectionInfo[] {
  const defs =
    kind === Kinds.SERIES ? SERIES_DEFS :
    kind === Kinds.MOVIES ? MOVIES_DEFS :
    kind === Kinds.LIVE ? LIVE_DEFS : [];
  const normalized = categories.map((c) => ({
    cat: c,
    norm: ArabicSearch.normalize(c.categoryName),
  }));
  const out: SectionInfo[] = [];
  for (const def of defs) {
    const ids = normalized
      .filter(({ norm }) => def.groups.every((group) => group.some((kw) => norm.includes(kw))))
      .map(({ cat }) => cat.categoryId);
    const total = ids.reduce((acc, id) => acc + (counts[id] || 0), 0);
    if (ids.length > 0 && total > 0) {
      out.push({ key: def.key, label: def.label, icon: def.icon, ids, count: total });
    }
  }
  return out;
}
