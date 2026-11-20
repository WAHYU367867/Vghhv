export const Kinds = {
  LIVE: 'get_live_streams',
  MOVIES: 'get_vod_streams',
  SERIES: 'get_series',
  FAVORITES: 'favorites',
  CONTINUE: 'continue',
  ALL: 'all',
  CONTENT: ['get_live_streams', 'get_vod_streams', 'get_series'] as const,

  title(kind: string): string {
    switch (kind) {
      case this.LIVE: return 'البث المباشر';
      case this.MOVIES: return 'الأفلام';
      case this.SERIES: return 'المسلسلات';
      case this.FAVORITES: return 'المفضلة';
      case this.CONTINUE: return 'متابعة المشاهدة';
      default: return 'البحث الشامل';
    }
  },

  unit(kind: string): string {
    switch (kind) {
      case this.LIVE: return 'قناة';
      case this.MOVIES: return 'فيلم';
      case this.SERIES: return 'مسلسل';
      default: return 'مقطع';
    }
  },

  badge(kind: string): string {
    switch (kind) {
      case this.LIVE: return 'مباشر';
      case this.MOVIES: return 'فيلم';
      case this.SERIES: return 'مسلسل';
      default: return '';
    }
  },
} as const;

export const UI_COLORS = {
  BG: '#0A0C10',
  PANEL: '#131722',
  PANEL2: '#1C2230',
  STROKE: '#253048',
  NEON: '#00D2FF',
  NEON_DEEP: '#0A8FB5',
  LIVE_RED: '#FF3131',
  LIVE: '#2FB4FF',
  MOVIE: '#E8384F',
  SERIES: '#B05CFF',
  BLUE: '#4F8CFF',
  PINK: '#E8559E',
  GREEN: '#25D9BD',
  TEAL: '#18E0C0',
  ELECTRIC: '#36D5FF',
  GOLD: '#FFB800',
  MUTED: '#A0AEC0',
  MUTED_DIM: '#64748B',
  RED: '#FF7B8B',
  VIOLET: '#00D2FF',
  MAGENTA: '#36D5FF',
  CYAN: '#2FD8FF',
} as const;

export function kindAccent(kind: string): string {
  switch (kind) {
    case Kinds.LIVE: return UI_COLORS.LIVE;
    case Kinds.MOVIES: return UI_COLORS.MOVIE;
    case Kinds.SERIES: return UI_COLORS.SERIES;
    case Kinds.FAVORITES: return UI_COLORS.PINK;
    case Kinds.CONTINUE: return UI_COLORS.LIVE;
    default: return UI_COLORS.BLUE;
  }
}

export interface ContentItem {
  pk: string; // `${kind}::${contentId}`
  kind: string;
  contentId: string;
  name: string;
  normalizedName: string;
  category: string;
  imageUrl: string;
  extension: string;
  syncToken: string;
  rating: string;
  idNum: number;
}

export interface CategoryItem {
  pk: string; // `${kind}::${categoryId}`
  kind: string;
  categoryId: string;
  categoryName: string;
  parentId: string;
}

export interface EpisodeItem {
  pk: string; // `${seriesId}::${episodeId}`
  seriesId: string;
  seasonKey: string;
  episodeId: string;
  title: string;
  imageUrl: string;
  extension: string;
  episodeNumber: string;
  duration?: string;
}

export interface UserMark {
  pk: string; // `${contentKind}::${contentId}`
  contentKind: string;
  contentId: string;
  favorite: boolean;
  followed: boolean;
  positionMs: number;
  updatedAt: number;
}

export interface SeriesPoint {
  episodeId: string;
  positionMs: number;
  extension: string;
  title: string;
}

export interface SavedAccount {
  server: string;
  username: string;
  password: string;
}

export interface UserInfo {
  auth?: number;
  status?: string;
  exp_date?: string | number;
  max_connections?: string | number;
  active_cons?: string | number;
  username?: string;
  [key: string]: unknown;
}

export interface EpgEntry {
  title: string;
  description: string;
  startMs: number;
  endMs: number;
}

export interface SectionInfo {
  key: string;
  label: string;
  icon: string;
  ids: string[];
  count: number;
}

export type RefreshMode = 'six_hours' | 'daily' | 'manual';

export interface PlayerLaunchParams {
  url: string;
  title: string;
  image?: string;
  contentId?: string;
  categoryId?: string;
  markKind?: string;
  markId?: string;
  seriesId?: string;
  seriesName?: string;
  episodeId?: string;
}

export type ScreenRoute =
  | { name: 'login'; addAccount?: boolean; noAuto?: boolean }
  | { name: 'loading' }
  | { name: 'home' }
  | { name: 'browse'; kind: string; focusSearch?: boolean; openCategories?: boolean }
  | { name: 'movie'; vodId: string; title: string; image: string; extension: string }
  | { name: 'series'; seriesId: string; seriesName: string; seriesImage: string }
  | { name: 'epg' }
  | { name: 'settings' }
  | { name: 'adult' };
