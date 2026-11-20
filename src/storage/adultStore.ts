export interface AdultChannel {
  id: string;
  name: string;
  streamUrl: string;
  category: string;
  logoUrl: string;
  sourceLabel: string;
  addedAt: number;
}

const SETTINGS_KEY = 'alhorr_adult_settings_v1';
const CHANNELS_KEY = 'alhorr_adult_channels_v1';

interface AdultSettings {
  enabled: boolean;
}

let sessionAgeConfirmed = false;
const memoryFallback = new Map<string, string>();

function safeGet(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return memoryFallback.get(key) ?? null;
  }
}

function safeSet(key: string, value: string): void {
  memoryFallback.set(key, value);
  try {
    localStorage.setItem(key, value);
  } catch {
    // Ignore storage errors in restricted contexts
  }
}

function safeRemove(key: string): void {
  memoryFallback.delete(key);
  try {
    localStorage.removeItem(key);
  } catch {
    // Ignore storage errors
  }
}

function readSettings(): AdultSettings {
  try {
    const raw = safeGet(SETTINGS_KEY);
    if (!raw) return { enabled: true };
    const parsed = JSON.parse(raw) as Partial<AdultSettings>;
    return {
      enabled: typeof parsed.enabled === 'boolean' ? parsed.enabled : true,
    };
  } catch {
    return { enabled: true };
  }
}

function writeSettings(next: AdultSettings): void {
  safeSet(SETTINGS_KEY, JSON.stringify(next));
}

function isValidHttpUrl(raw: string): boolean {
  try {
    const u = new URL(raw.trim());
    return u.protocol === 'http:' || u.protocol === 'https:';
  } catch {
    return false;
  }
}

export function parseM3uText(rawText: string, sourceLabel: string): AdultChannel[] {
  const lines = rawText.split(/\r?\n/);
  const result: AdultChannel[] = [];
  let pendingName = '';
  let pendingLogo = '';
  let pendingCategory = 'مصادر مصرح بها';

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();
    if (!line) continue;

    if (line.startsWith('#EXTINF:')) {
      const logoMatch = line.match(/tvg-logo="([^"]*)"/i);
      const groupMatch = line.match(/group-title="([^"]*)"/i);
      const commaIdx = line.lastIndexOf(',');
      pendingLogo = logoMatch?.[1]?.trim() || '';
      pendingCategory = groupMatch?.[1]?.trim() || 'مصادر مصرح بها';
      pendingName = commaIdx >= 0 ? line.slice(commaIdx + 1).trim() : '';
      continue;
    }

    if (line.startsWith('#')) continue;

    if (isValidHttpUrl(line)) {
      const fallbackName = `قناة ${result.length + 1}`;
      result.push({
        id: `adult_${Date.now()}_${Math.random().toString(36).slice(2, 8)}_${result.length}`,
        name: pendingName || fallbackName,
        streamUrl: line,
        category: pendingCategory || 'مصادر مصرح بها',
        logoUrl: isValidHttpUrl(pendingLogo) ? pendingLogo : '',
        sourceLabel,
        addedAt: Date.now(),
      });
    }

    pendingName = '';
    pendingLogo = '';
    pendingCategory = 'مصادر مصرح بها';
  }

  return result;
}

export const AdultStore = {
  isEnabled(): boolean {
    return readSettings().enabled;
  },

  setEnabled(enabled: boolean): void {
    writeSettings({ enabled });
    if (!enabled) {
      sessionAgeConfirmed = false;
    }
  },

  isAgeConfirmed(): boolean {
    return sessionAgeConfirmed;
  },

  confirmAge(): void {
    sessionAgeConfirmed = true;
  },

  clearAgeConfirmation(): void {
    sessionAgeConfirmed = false;
  },

  isValidStreamUrl(url: string): boolean {
    return isValidHttpUrl(url);
  },

  getChannels(): AdultChannel[] {
    try {
      const raw = safeGet(CHANNELS_KEY);
      if (!raw) return [];
      const arr = JSON.parse(raw) as AdultChannel[];
      if (!Array.isArray(arr)) return [];
      return arr.filter(
        (c) =>
          c &&
          typeof c.id === 'string' &&
          typeof c.name === 'string' &&
          typeof c.streamUrl === 'string' &&
          isValidHttpUrl(c.streamUrl)
      );
    } catch {
      return [];
    }
  },

  saveChannels(list: AdultChannel[]): void {
    safeSet(CHANNELS_KEY, JSON.stringify(list));
  },

  addChannel(input: {
    name: string;
    streamUrl: string;
    category?: string;
    logoUrl?: string;
    sourceLabel?: string;
  }): AdultChannel {
    const existing = this.getChannels();
    const cleanUrl = input.streamUrl.trim();
    const item: AdultChannel = {
      id: `adult_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`,
      name: input.name.trim(),
      streamUrl: cleanUrl,
      category: input.category?.trim() || 'مصادر مصرح بها',
      logoUrl: input.logoUrl && isValidHttpUrl(input.logoUrl) ? input.logoUrl.trim() : '',
      sourceLabel: input.sourceLabel?.trim() || 'مصدر مباشر مصرح به',
      addedAt: Date.now(),
    };
    const deduped = existing.filter((c) => c.streamUrl !== cleanUrl);
    this.saveChannels([item, ...deduped]);
    return item;
  },

  importChannels(items: AdultChannel[]): number {
    if (items.length === 0) return 0;
    const existing = this.getChannels();
    const seenUrls = new Set(existing.map((c) => c.streamUrl));
    const fresh = items.filter((item) => {
      if (!isValidHttpUrl(item.streamUrl) || seenUrls.has(item.streamUrl)) return false;
      seenUrls.add(item.streamUrl);
      return true;
    });
    if (fresh.length > 0) {
      this.saveChannels([...fresh, ...existing]);
    }
    return fresh.length;
  },

  removeChannel(id: string): void {
    const next = this.getChannels().filter((c) => c.id !== id);
    this.saveChannels(next);
  },

  clearChannels(): void {
    safeRemove(CHANNELS_KEY);
  },
};
