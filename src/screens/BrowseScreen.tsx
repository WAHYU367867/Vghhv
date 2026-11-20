import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ArrowRight, Calendar, RefreshCw, Search, X } from 'lucide-react';
import { CategoryItem, ContentItem, Kinds, SavedAccount, ScreenRoute, SectionInfo } from '../types';
import { SyncManager } from '../api/xtream';
import { db } from '../storage/db';
import { buildSmartSections } from '../utils/arabic';
import { PosterCard } from '../components/PosterCard';

interface BrowseScreenProps {
  kind: string;
  account: SavedAccount;
  focusSearch?: boolean;
  openCategories?: boolean;
  onBack: () => void;
  onNavigate: (route: ScreenRoute) => void;
  onPlayLive: (item: ContentItem) => void;
}

const PAGE_SIZE = 60;

export const BrowseScreen: React.FC<BrowseScreenProps> = ({
  kind,
  account,
  focusSearch = false,
  onBack,
  onNavigate,
  onPlayLive,
}) => {
  const [query, setQuery] = useState('');
  const [debouncedQuery, setDebouncedQuery] = useState('');
  const [categories, setCategories] = useState<CategoryItem[]>([]);
  const [counts, setCounts] = useState<Record<string, number>>({});
  const [sections, setSections] = useState<SectionInfo[]>([]);
  const [selectedIds, setSelectedIds] = useState<string[] | null>(null);
  const [selectedKey, setSelectedKey] = useState<string>('all');

  const [items, setItems] = useState<ContentItem[]>([]);
  const [totalCount, setTotalCount] = useState(0);
  const [favKeys, setFavKeys] = useState<Set<string>>(new Set());
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [syncing, setSyncing] = useState(false);

  const searchInputRef = useRef<HTMLInputElement>(null);

  const hasSidebar =
    kind !== Kinds.FAVORITES && kind !== Kinds.CONTINUE && kind !== Kinds.ALL;

  useEffect(() => {
    if (focusSearch) {
      window.setTimeout(() => searchInputRef.current?.focus(), 80);
    }
  }, [focusSearch]);

  useEffect(() => {
    const t = window.setTimeout(() => setDebouncedQuery(query), 180);
    return () => window.clearTimeout(t);
  }, [query]);

  const loadCategories = useCallback(async () => {
    if (!hasSidebar) return;
    const [cats, catCounts] = await Promise.all([
      db.getCategories(kind),
      db.categoryCounts(kind),
    ]);
    setCategories(cats);
    setCounts(catCounts);
    setSections(buildSmartSections(kind, cats, catCounts));
  }, [hasSidebar, kind]);

  const loadFirstPage = useCallback(async () => {
    setLoading(true);
    try {
      const favs = await db.favoriteKeys();
      setFavKeys(favs);

      if (kind === Kinds.FAVORITES) {
        const allFavs = await db.favorites();
        const norm = debouncedQuery.trim().toLowerCase();
        const filtered = norm
          ? allFavs.filter(
              (x) =>
                x.name.toLowerCase().includes(norm) ||
                x.normalizedName.includes(norm)
            )
          : allFavs;
        setItems(filtered);
        setTotalCount(filtered.length);
      } else if (kind === Kinds.CONTINUE) {
        const allCont = await db.continueItems();
        const norm = debouncedQuery.trim().toLowerCase();
        const filtered = norm
          ? allCont.filter(
              (x) =>
                x.name.toLowerCase().includes(norm) ||
                x.normalizedName.includes(norm)
            )
          : allCont;
        setItems(filtered);
        setTotalCount(filtered.length);
      } else {
        const [page, count] = await Promise.all([
          db.browse(kind, selectedIds, debouncedQuery, PAGE_SIZE, 0),
          db.countBrowse(kind, selectedIds, debouncedQuery),
        ]);
        setItems(page);
        setTotalCount(count);
      }
    } finally {
      setLoading(false);
    }
  }, [kind, selectedIds, debouncedQuery]);

  const loadMore = async () => {
    if (
      loadingMore ||
      kind === Kinds.FAVORITES ||
      kind === Kinds.CONTINUE ||
      items.length >= totalCount
    ) {
      return;
    }
    setLoadingMore(true);
    try {
      const more = await db.browse(kind, selectedIds, debouncedQuery, PAGE_SIZE, items.length);
      if (more.length > 0) {
        setItems((prev) => [...prev, ...more]);
      }
    } finally {
      setLoadingMore(false);
    }
  };

  useEffect(() => {
    void loadCategories();
  }, [loadCategories]);

  useEffect(() => {
    void loadFirstPage();
  }, [loadFirstPage]);

  useEffect(() => {
    const isContentKind = (Kinds.CONTENT as readonly string[]).includes(kind);
    setSyncing(isContentKind ? SyncManager.isRunning(kind) : false);
    const unsub = SyncManager.add({
      onSyncState(k, running) {
        if (k === kind || kind === Kinds.ALL) {
          setSyncing(running);
          if (!running) {
            void loadCategories();
            void loadFirstPage();
          }
        }
      },
      onCategoriesReady(k) {
        if (k === kind) void loadCategories();
      },
    });
    return unsub;
  }, [kind, loadCategories, loadFirstPage]);

  const openItem = (item: ContentItem) => {
    if (item.kind === Kinds.LIVE) {
      onPlayLive(item);
    } else if (item.kind === Kinds.MOVIES) {
      onNavigate({
        name: 'movie',
        vodId: item.contentId,
        title: item.name,
        image: item.imageUrl,
        extension: item.extension,
      });
    } else if (item.kind === Kinds.SERIES) {
      onNavigate({
        name: 'series',
        seriesId: item.contentId,
        seriesName: item.name,
        seriesImage: item.imageUrl,
      });
    }
  };

  const handleToggleFav = async (item: ContentItem) => {
    await db.toggleFavorite(item);
    setFavKeys(await db.favoriteKeys());
    if (kind === Kinds.FAVORITES) void loadFirstPage();
  };

  const allCategoryCount = useMemo(
    () => Object.values(counts).reduce((a, b) => a + b, 0),
    [counts]
  );

  return (
    <div dir="rtl" className="min-h-screen w-full bg-hex-space flex flex-col">
      {/* Header */}
      <header className="sticky top-0 z-20 bg-[#0A0C10]/90 backdrop-blur-md border-b border-[#253048] px-4 py-3 flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <button
            type="button"
            data-tv-autofocus={!hasSidebar ? 'true' : undefined}
            onClick={onBack}
            className="w-9 h-9 rounded-xl bg-[#131722] hover:bg-[#1C2230] border border-[#253048] hover:border-[#00D2FF] text-white flex items-center justify-center cursor-pointer"
            title="رجوع"
          >
            <ArrowRight className="w-4 h-4" />
          </button>
          <div>
            <div className="flex items-center gap-2">
              <h1 className="text-base sm:text-xl font-extrabold text-white">{Kinds.title(kind)}</h1>
              <span className="px-2.5 py-0.5 rounded-md bg-[#1C2230] border border-[#253048] text-xs font-bold text-[#00D2FF]">
                {totalCount.toLocaleString('en-US')} {Kinds.unit(kind)}
              </span>
            </div>
          </div>
        </div>

        <div className="flex items-center gap-2 flex-1 max-w-md">
          <div className="relative flex-1">
            <Search className="w-4 h-4 text-[#A0AEC0] absolute right-3 top-1/2 -translate-y-1/2" />
            <input
              ref={searchInputRef}
              type="text"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder={`ابحث في ${Kinds.title(kind)}…`}
              className="w-full rounded-xl bg-[#131722] border border-[#253048] focus:border-[#00D2FF] pr-9 pl-8 py-2 text-xs sm:text-sm text-white outline-none"
            />
            {query && (
              <button
                type="button"
                onClick={() => setQuery('')}
                className="absolute left-2.5 top-1/2 -translate-y-1/2 text-slate-400 hover:text-white cursor-pointer"
              >
                <X className="w-3.5 h-3.5" />
              </button>
            )}
          </div>

          {kind === Kinds.LIVE && (
            <button
              type="button"
              onClick={() => onNavigate({ name: 'epg' })}
              className="px-3 py-2 rounded-xl bg-[#131722] hover:bg-[#1C2230] border border-[#253048] hover:border-[#00D2FF] text-xs font-bold text-[#00D2FF] flex items-center gap-1.5 shrink-0 cursor-pointer"
            >
              <Calendar className="w-4 h-4" />
              <span className="hidden sm:inline">دليل البرامج</span>
            </button>
          )}
        </div>
      </header>

      {/* Main Content (Permanent Landscape Split) */}
      <div className="flex-1 flex flex-row gap-3 sm:gap-4 p-3 sm:p-4">
        {/* Categories Sidebar */}
        {hasSidebar && (
          <aside className="flex w-[155px] sm:w-[205px] lg:w-64 shrink-0 flex-col rounded-2xl bg-[#131722]/78 backdrop-blur-sm border border-[#253048]/85 p-2.5 sm:p-3 h-[calc(100vh-5.5rem)] sticky top-[4.5rem] overflow-y-auto no-scrollbar space-y-1">
            <button
              type="button"
              data-tv-autofocus="true"
              onClick={() => {
                setSelectedKey('all');
                setSelectedIds(null);
              }}
              className={`w-full flex items-center justify-between px-3 py-2.5 rounded-xl text-xs font-bold transition cursor-pointer ${
                selectedKey === 'all'
                  ? 'bg-[#00D2FF] text-[#001018]'
                  : 'text-slate-200 hover:bg-[#1C2230]'
              }`}
            >
              <span>الكل</span>
              <span className="opacity-80">{allCategoryCount.toLocaleString('en-US')}</span>
            </button>

            {sections.length > 0 && (
              <div className="pt-2 pb-1">
                <div className="px-2 pb-1 text-[10px] font-bold text-[#00D2FF]">أقسام ذكية</div>
                {sections.map((sec) => {
                  const key = `sec:${sec.key}`;
                  const active = selectedKey === key;
                  return (
                    <button
                      key={key}
                      type="button"
                      onClick={() => {
                        setSelectedKey(key);
                        setSelectedIds(sec.ids);
                      }}
                      className={`w-full flex items-center justify-between px-3 py-2 rounded-xl text-xs font-bold transition cursor-pointer ${
                        active
                          ? 'bg-[#00D2FF] text-[#001018]'
                          : 'text-slate-200 hover:bg-[#1C2230]'
                      }`}
                    >
                      <span className="truncate">
                        {sec.icon} {sec.label}
                      </span>
                      <span className="opacity-80">{sec.count}</span>
                    </button>
                  );
                })}
              </div>
            )}

            {categories.length > 0 && (
              <div className="pt-2 border-t border-[#253048]/70 space-y-0.5">
                <div className="px-2 pb-1 text-[10px] font-bold text-[#A0AEC0]">التصنيفات</div>
                {categories.map((cat) => {
                  const key = `cat:${cat.categoryId}`;
                  const active = selectedKey === key;
                  const cnt = counts[cat.categoryId] || 0;
                  return (
                    <button
                      key={cat.pk}
                      type="button"
                      onClick={() => {
                        setSelectedKey(key);
                        setSelectedIds([cat.categoryId]);
                      }}
                      className={`w-full flex items-center justify-between px-3 py-2 rounded-xl text-xs font-semibold transition cursor-pointer ${
                        active
                          ? 'bg-[#00D2FF] text-[#001018] font-bold'
                          : 'text-slate-300 hover:bg-[#1C2230]'
                      }`}
                    >
                      <span className="truncate text-right">{cat.categoryName}</span>
                      {cnt > 0 && <span className="opacity-75 text-[11px]">{cnt}</span>}
                    </button>
                  );
                })}
              </div>
            )}
          </aside>
        )}

        {/* Grid Area */}
        <section className="flex-1 min-w-0">
          {loading ? (
            <div className="py-20 text-center text-sm text-[#A0AEC0]">جارٍ تحميل المحتوى…</div>
          ) : items.length === 0 ? (
            <div className="rounded-2xl bg-[#131722]/80 backdrop-blur-sm border border-[#253048]/85 p-12 text-center space-y-3">
              <p className="text-sm font-bold text-white">
                {syncing
                  ? 'جارٍ تنزيل المحتوى من السيرفر…'
                  : debouncedQuery
                  ? 'لا توجد نتائج مطابقة للبحث.'
                  : kind === Kinds.FAVORITES
                  ? 'قائمة المفضلة فارغة حاليًا.'
                  : kind === Kinds.CONTINUE
                  ? 'لا توجد عناصر في سجل المتابعة حاليًا.'
                  : 'لا توجد عناصر في هذا التصنيف.'}
              </p>
              {(Kinds.CONTENT as readonly string[]).includes(kind) && !syncing && (
                <button
                  type="button"
                  onClick={() =>
                    void SyncManager.ensure(
                      account.server,
                      account.username,
                      account.password,
                      kind,
                      true
                    )
                  }
                  className="inline-flex items-center gap-2 px-4 py-2 rounded-xl bg-[#00D2FF] text-[#001018] text-xs font-extrabold cursor-pointer"
                >
                  <RefreshCw className="w-3.5 h-3.5" />
                  <span>إعادة التحديث</span>
                </button>
              )}
            </div>
          ) : (
            <>
              <div
                className={`grid gap-3 ${
                  kind === Kinds.LIVE
                    ? 'grid-cols-2 sm:grid-cols-3 md:grid-cols-4 xl:grid-cols-5'
                    : 'grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6'
                }`}
              >
                {items.map((item) => (
                  <PosterCard
                    key={item.pk}
                    item={item}
                    showBadge={kind === Kinds.ALL || kind === Kinds.FAVORITES || kind === Kinds.CONTINUE}
                    isFavorite={favKeys.has(`${item.kind}:${item.contentId}`)}
                    onOpen={openItem}
                    onToggleFavorite={(it) => void handleToggleFav(it)}
                  />
                ))}
              </div>

              {items.length < totalCount &&
                kind !== Kinds.FAVORITES &&
                kind !== Kinds.CONTINUE && (
                  <div className="pt-6 flex justify-center">
                    <button
                      type="button"
                      disabled={loadingMore}
                      onClick={() => void loadMore()}
                      className="px-6 py-2.5 rounded-xl bg-[#131722] hover:bg-[#1C2230] border border-[#253048] hover:border-[#00D2FF] text-xs font-bold text-[#00D2FF] cursor-pointer disabled:opacity-60"
                    >
                      {loadingMore
                        ? 'جارٍ التحميل…'
                        : `عرض المزيد (${items.length} من ${totalCount})`}
                    </button>
                  </div>
                )}
            </>
          )}
        </section>
      </div>
    </div>
  );
};
