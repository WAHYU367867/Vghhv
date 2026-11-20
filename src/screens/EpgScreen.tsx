import React, { useCallback, useEffect, useState } from 'react';
import { ArrowRight, Play, RefreshCw, Tv } from 'lucide-react';
import { CategoryItem, ContentItem, EpgEntry, Kinds } from '../types';
import { XtreamClient } from '../api/xtream';
import { db, EpgCache } from '../storage/db';
import { proxiedImageUrl } from '../utils/arabic';

interface EpgScreenProps {
  client: XtreamClient;
  onBack: () => void;
  onPlayLive: (item: ContentItem) => void;
}

const PAGE_LIMIT = 40;

function formatClock(ms: number): string {
  return new Date(ms).toLocaleTimeString('en-US', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });
}

export const EpgScreen: React.FC<EpgScreenProps> = ({ client, onBack, onPlayLive }) => {
  const [categories, setCategories] = useState<CategoryItem[]>([]);
  const [selectedCat, setSelectedCat] = useState<string>('');
  const [channels, setChannels] = useState<ContentItem[]>([]);
  const [epgMap, setEpgMap] = useState<Record<string, EpgEntry[]>>({});
  const [selectedChannel, setSelectedChannel] = useState<ContentItem | null>(null);
  const [nowMs, setNowMs] = useState(() => Date.now());
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const t = window.setInterval(() => setNowMs(Date.now()), 30_000);
    return () => window.clearInterval(t);
  }, []);

  const fetchEpgBatch = useCallback(
    async (list: ContentItem[]) => {
      const updates: Record<string, EpgEntry[]> = {};
      const missing: ContentItem[] = [];
      const now = Date.now();

      for (const ch of list) {
        const cached = EpgCache.get(ch.contentId, now);
        if (cached) updates[ch.contentId] = cached;
        else missing.push(ch);
      }
      if (Object.keys(updates).length > 0) {
        setEpgMap((prev) => ({ ...prev, ...updates }));
      }

      const concurrency = 4;
      for (let i = 0; i < missing.length; i += concurrency) {
        const slice = missing.slice(i, i + concurrency);
        await Promise.all(
          slice.map(async (ch) => {
            try {
              const entries = await client.shortEpg(ch.contentId, 6);
              EpgCache.put(
                ch.contentId,
                entries,
                entries.length > 0 ? EpgCache.TTL_DATA : EpgCache.TTL_EMPTY
              );
              setEpgMap((prev) => ({ ...prev, [ch.contentId]: entries }));
            } catch {
              EpgCache.put(ch.contentId, [], EpgCache.TTL_FAILED);
              setEpgMap((prev) => ({ ...prev, [ch.contentId]: [] }));
            }
          })
        );
      }
    },
    [client]
  );

  const loadChannels = useCallback(
    async (catId: string) => {
      setLoading(true);
      try {
        const [cats, list] = await Promise.all([
          db.getCategories(Kinds.LIVE),
          db.searchChannels(catId, '', PAGE_LIMIT, 0),
        ]);
        setCategories(cats);
        setChannels(list);
        if (list.length > 0 && !selectedChannel) {
          setSelectedChannel(list[0]);
        }
        void fetchEpgBatch(list);
      } finally {
        setLoading(false);
      }
    },
    [fetchEpgBatch, selectedChannel]
  );

  useEffect(() => {
    void loadChannels(selectedCat);
  }, [selectedCat, loadChannels]);

  const handleRefresh = () => {
    EpgCache.clear();
    setEpgMap({});
    void loadChannels(selectedCat);
  };

  const selectedList = selectedChannel ? epgMap[selectedChannel.contentId] ?? [] : [];

  return (
    <div dir="rtl" className="min-h-screen w-full bg-hex-space flex flex-col">
      {/* Header */}
      <header className="sticky top-0 z-20 bg-[#0A0C10]/90 backdrop-blur-md border-b border-[#253048] px-4 py-3 flex items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <button
            type="button"
            onClick={onBack}
            className="w-9 h-9 rounded-xl bg-[#131722] hover:bg-[#1C2230] border border-[#253048] hover:border-[#00D2FF] text-white flex items-center justify-center cursor-pointer"
          >
            <ArrowRight className="w-4 h-4" />
          </button>
          <h1 className="text-lg font-extrabold text-white">دليل البرامج</h1>
        </div>

        <button
          type="button"
          onClick={handleRefresh}
          className="px-3.5 py-2 rounded-xl bg-[#131722] hover:bg-[#1C2230] border border-[#253048] hover:border-[#00D2FF] text-xs font-bold text-[#00D2FF] flex items-center gap-1.5 cursor-pointer"
        >
          <RefreshCw className="w-3.5 h-3.5" />
          <span>تحديث</span>
        </button>
      </header>

      {/* Category Chips */}
      <div className="px-4 py-3 flex items-center gap-2 overflow-x-auto no-scrollbar border-b border-[#253048]/60">
        <button
          type="button"
          onClick={() => setSelectedCat('')}
          className={`shrink-0 px-3.5 py-1.5 rounded-full text-xs font-bold border cursor-pointer ${
            selectedCat === ''
              ? 'bg-[#00D2FF] text-[#001018] border-[#00D2FF]'
              : 'bg-[#131722] text-slate-300 border-[#253048]'
          }`}
        >
          الكل
        </button>
        {categories.map((c) => (
          <button
            key={c.pk}
            type="button"
            onClick={() => setSelectedCat(c.categoryId)}
            className={`shrink-0 px-3.5 py-1.5 rounded-full text-xs font-bold border cursor-pointer ${
              selectedCat === c.categoryId
                ? 'bg-[#00D2FF] text-[#001018] border-[#00D2FF]'
                : 'bg-[#131722] text-slate-300 border-[#253048]'
            }`}
          >
            {c.categoryName}
          </button>
        ))}
      </div>

      {/* Main Split (Landscape) */}
      <div className="flex-1 grid grid-cols-1 min-[560px]:grid-cols-12 gap-4 p-4">
        {/* Channels Column */}
        <div className="min-[560px]:col-span-7 space-y-2.5">
          {loading ? (
            <div className="py-16 text-center text-sm text-[#A0AEC0]">جارٍ تحميل دليل البرامج…</div>
          ) : channels.length === 0 ? (
            <div className="rounded-2xl bg-[#131722] border border-[#253048] p-10 text-center text-sm text-[#A0AEC0]">
              لا توجد قنوات في هذا التصنيف.
            </div>
          ) : (
            channels.map((ch, idx) => {
              const entries = epgMap[ch.contentId] ?? [];
              const cur = entries.find((e) => e.startMs <= nowMs && e.endMs > nowMs);
              const next = entries.find((e) => e.startMs > nowMs);
              const pct = cur
                ? Math.min(100, Math.max(0, ((nowMs - cur.startMs) / (cur.endMs - cur.startMs)) * 100))
                : 0;
              const remMin = cur ? Math.max(1, Math.ceil((cur.endMs - nowMs) / 60_000)) : 0;
              const logo = proxiedImageUrl(ch.imageUrl);
              const active = selectedChannel?.contentId === ch.contentId;

              return (
                <div
                  key={ch.pk}
                  role="button"
                  tabIndex={0}
                  data-tv-autofocus={idx === 0 ? 'true' : undefined}
                  onFocus={() => setSelectedChannel(ch)}
                  onClick={() => setSelectedChannel(ch)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      setSelectedChannel(ch);
                    }
                  }}
                  className={`rounded-2xl p-3.5 border transition cursor-pointer flex items-center gap-3.5 backdrop-blur-sm ${
                    active
                      ? 'bg-[#182034]/88 border-[#00D2FF]'
                      : 'bg-[#131722]/78 border-[#253048]/85 hover:border-[#00D2FF]/60'
                  }`}
                >
                  <div className="w-14 h-12 rounded-xl bg-[#0F131F] border border-[#253048] flex items-center justify-center overflow-hidden shrink-0 p-1">
                    {logo ? (
                      <img src={logo} alt={ch.name} className="w-full h-full object-contain" />
                    ) : (
                      <Tv className="w-5 h-5 text-[#2FB4FF]" />
                    )}
                  </div>

                  <div className="flex-1 min-w-0 space-y-1">
                    <div className="flex items-center justify-between gap-2">
                      <h3 className="text-sm font-extrabold text-white truncate">{ch.name}</h3>
                      {cur && (
                        <span className="text-[11px] font-bold text-[#25D9BD] shrink-0">
                          متبقي {remMin} د
                        </span>
                      )}
                    </div>
                    <p className="text-xs text-[#CBD5E1] truncate">
                      {cur ? `الآن: ${cur.title}` : 'لا توجد بيانات برنامج حالي'}
                    </p>
                    {cur && (
                      <div className="w-full h-1.5 rounded-full bg-[#1C2230] overflow-hidden">
                        <div
                          className="h-full bg-[#00D2FF] rounded-full"
                          style={{ width: `${pct}%` }}
                        />
                      </div>
                    )}
                    {next && (
                      <p className="text-[11px] text-[#A0AEC0] truncate">
                        التالي ({formatClock(next.startMs)}): {next.title}
                      </p>
                    )}
                  </div>

                  <button
                    type="button"
                    onClick={(e) => {
                      e.stopPropagation();
                      onPlayLive(ch);
                    }}
                    className="w-9 h-9 rounded-full bg-[#00D2FF]/20 hover:bg-[#00D2FF] text-[#00D2FF] hover:text-[#001018] flex items-center justify-center shrink-0 transition cursor-pointer"
                    title="مشاهدة القناة"
                  >
                    <Play className="w-4 h-4 fill-current" />
                  </button>
                </div>
              );
            })
          )}
        </div>

        {/* Selected Channel Schedule Panel */}
        <aside className="min-[560px]:col-span-5">
          <div className="sticky top-20 rounded-2xl bg-[#131722]/82 backdrop-blur-sm border border-[#253048]/85 p-5 space-y-4">
            {selectedChannel ? (
              <>
                <div className="flex items-center justify-between gap-3 pb-3 border-b border-[#253048]">
                  <h2 className="text-base font-extrabold text-white truncate">
                    {selectedChannel.name}
                  </h2>
                  <button
                    type="button"
                    onClick={() => onPlayLive(selectedChannel)}
                    className="px-4 py-2 rounded-xl bg-[#00D2FF] hover:bg-[#36D5FF] text-[#001018] text-xs font-extrabold flex items-center gap-1.5 shrink-0 cursor-pointer"
                  >
                    <Play className="w-3.5 h-3.5 fill-current" />
                    <span>مشاهدة</span>
                  </button>
                </div>

                {selectedList.length === 0 ? (
                  <p className="py-8 text-center text-xs text-[#A0AEC0]">
                    لا تتوفر جدول برامج تفصيلية لهذه القناة.
                  </p>
                ) : (
                  <div className="space-y-2.5 max-h-[60vh] overflow-y-auto no-scrollbar">
                    {selectedList.map((e, idx) => {
                      const isNow = e.startMs <= nowMs && e.endMs > nowMs;
                      return (
                        <div
                          key={idx}
                          className={`p-3 rounded-xl border ${
                            isNow
                              ? 'bg-[#00D2FF]/15 border-[#00D2FF]'
                              : 'bg-[#1C2230] border-[#253048]'
                          }`}
                        >
                          <div className="flex items-center justify-between text-[11px] font-bold">
                            <span dir="ltr" className="text-[#00D2FF] font-mono">
                              {formatClock(e.startMs)} - {formatClock(e.endMs)}
                            </span>
                            {isNow && <span className="text-[#25D9BD]">يُعرض الآن</span>}
                          </div>
                          <div className="text-xs sm:text-sm font-extrabold text-white mt-1">
                            {e.title}
                          </div>
                          {e.description && (
                            <p className="text-[11px] text-[#A0AEC0] mt-1 leading-relaxed">
                              {e.description}
                            </p>
                          )}
                        </div>
                      );
                    })}
                  </div>
                )}
              </>
            ) : (
              <div className="py-12 text-center text-xs text-[#A0AEC0]">
                اختر قناة لعرض جدول برامجها.
              </div>
            )}
          </div>
        </aside>
      </div>
    </div>
  );
};
