import React, { useEffect, useMemo, useState } from 'react';
import { ArrowRight, Check, Heart, Info, Play, Plus, Star, X } from 'lucide-react';
import { ContentItem, EpisodeItem, Kinds, SeriesPoint } from '../types';
import { XtreamClient } from '../api/xtream';
import { db, ResumeStore } from '../storage/db';
import {
  firstImage,
  formatTimeMs,
  proxiedImageUrl,
  Rating,
  splitNames,
  strField,
} from '../utils/arabic';
import { PosterCard } from '../components/PosterCard';

interface SeriesDetailScreenProps {
  seriesId: string;
  seriesName: string;
  seriesImage: string;
  client: XtreamClient;
  onBack: () => void;
  onPlayEpisode: (ep: EpisodeItem, seriesName: string, seriesImage: string) => void;
  onOpenSeries: (item: ContentItem) => void;
}

export const SeriesDetailScreen: React.FC<SeriesDetailScreenProps> = ({
  seriesId,
  seriesName,
  seriesImage,
  client,
  onBack,
  onPlayEpisode,
  onOpenSeries,
}) => {
  const [displayName, setDisplayName] = useState(seriesName);
  const [poster, setPoster] = useState(seriesImage);
  const [backdrop, setBackdrop] = useState('');
  const [plot, setPlot] = useState('');
  const [expandedPlot, setExpandedPlot] = useState(false);
  const [year, setYear] = useState('');
  const [genre, setGenre] = useState('');
  const [rating, setRating] = useState('');
  const [cast, setCast] = useState<string[]>([]);

  const [episodes, setEpisodes] = useState<EpisodeItem[]>([]);
  const [selectedSeason, setSelectedSeason] = useState<string>('');
  const [showAllEps, setShowAllEps] = useState(false);
  const [loadingEps, setLoadingEps] = useState(true);

  const [itemEntity, setItemEntity] = useState<ContentItem | null>(null);
  const [isFavorite, setIsFavorite] = useState(false);
  const [isFollowed, setIsFollowed] = useState(false);
  const [resumePoint, setResumePoint] = useState<SeriesPoint | null>(null);
  const [similar, setSimilar] = useState<ContentItem[]>([]);
  const [favKeys, setFavKeys] = useState<Set<string>>(new Set());
  const [infoOpen, setInfoOpen] = useState(false);

  useEffect(() => {
    let active = true;
    setLoadingEps(true);
    setShowAllEps(false);
    setResumePoint(ResumeStore.seriesPoint(seriesId));

    const loadLocal = async () => {
      const [item, mark, favs, cachedEps] = await Promise.all([
        db.getContentItem(Kinds.SERIES, seriesId),
        db.getMark(Kinds.SERIES, seriesId),
        db.favoriteKeys(),
        db.getSeriesEpisodes(seriesId),
      ]);
      if (!active) return;
      if (item) {
        setItemEntity(item);
        if (item.imageUrl && !poster) setPoster(item.imageUrl);
        if (item.rating) setRating(item.rating);
        const sim = await db.browse(
          Kinds.SERIES,
          item.category ? [item.category] : null,
          '',
          15,
          0
        );
        if (active) setSimilar(sim.filter((x) => x.contentId !== seriesId).slice(0, 12));
      }
      setIsFavorite(Boolean(mark?.favorite));
      setIsFollowed(Boolean(mark?.followed));
      setFavKeys(favs);
      if (cachedEps.length > 0) {
        setEpisodes(cachedEps);
      }
    };

    const loadDetails = async () => {
      try {
        const res = await client.details('get_series_info', seriesId);
        if (!active) return;
        const info = (res.info as Record<string, unknown> | undefined) ?? {};
        const n = strField(info, 'name');
        if (n) setDisplayName(n);
        const bg = firstImage(info, 'backdrop_path', 'cover_big', 'cover');
        if (bg) setBackdrop(bg);
        const post = firstImage(info, 'cover', 'cover_big');
        if (post) setPoster(post);
        setPlot(strField(info, 'plot', 'description', 'overview'));
        const rel = strField(info, 'releaseDate', 'releasedate', 'release_date', 'year');
        const ym = rel.match(/\b(19|20)\d{2}\b/);
        setYear(ym ? ym[0] : rel);
        setGenre(strField(info, 'genre', 'genres'));
        const r = Rating.parse(info.rating, info.rating_5based);
        if (r) setRating(r);
        setCast(splitNames(strField(info, 'cast', 'actors')).slice(0, 12));

        const epsObj = res.episodes as Record<string, Array<Record<string, unknown>>> | undefined;
        if (epsObj && typeof epsObj === 'object') {
          const parsed: EpisodeItem[] = [];
          for (const [seasonKey, arr] of Object.entries(epsObj)) {
            if (!Array.isArray(arr)) continue;
            for (const ep of arr) {
              const epId = strField(ep, 'id');
              if (!epId) continue;
              const epInfo = ep.info as Record<string, unknown> | undefined;
              parsed.push({
                pk: `${seriesId}::${epId}`,
                seriesId,
                seasonKey,
                episodeId: epId,
                title:
                  strField(ep, 'title') || `الحلقة ${strField(ep, 'episode_num') || epId}`,
                imageUrl:
                  firstImage(epInfo, 'movie_image', 'cover', 'cover_big') || post || poster,
                extension: strField(ep, 'container_extension') || 'mp4',
                episodeNumber: strField(ep, 'episode_num'),
                duration: strField(epInfo, 'duration'),
              });
            }
          }
          if (parsed.length > 0) {
            await db.replaceSeriesEpisodes(seriesId, parsed);
            if (active) setEpisodes(parsed);
          }
        }
      } catch {
        // Keep cached episodes if network fails
      } finally {
        if (active) setLoadingEps(false);
      }
    };

    void loadLocal();
    void loadDetails();
    return () => {
      active = false;
    };
  }, [seriesId, client, poster]);

  const seasons = useMemo(() => {
    const keys = Array.from(new Set(episodes.map((e) => e.seasonKey)));
    return keys.sort((a, b) => Number(a) - Number(b));
  }, [episodes]);

  useEffect(() => {
    if (seasons.length > 0 && (!selectedSeason || !seasons.includes(selectedSeason))) {
      setSelectedSeason(seasons[0]);
    }
  }, [seasons, selectedSeason]);

  const seasonEpisodes = useMemo(() => {
    return episodes
      .filter((e) => e.seasonKey === selectedSeason)
      .sort((a, b) => Number(a.episodeNumber) - Number(b.episodeNumber));
  }, [episodes, selectedSeason]);

  const visibleEpisodes = showAllEps ? seasonEpisodes : seasonEpisodes.slice(0, 24);

  const ensureEntity = (): ContentItem =>
    itemEntity ?? {
      pk: `${Kinds.SERIES}::${seriesId}`,
      kind: Kinds.SERIES,
      contentId: seriesId,
      name: displayName,
      normalizedName: displayName,
      category: '',
      imageUrl: poster,
      extension: '',
      syncToken: '',
      rating,
      idNum: Number(seriesId) || 0,
    };

  const handleToggleFav = async () => {
    setIsFavorite(await db.toggleFavorite(ensureEntity()));
  };

  const handleToggleFollow = async () => {
    setIsFollowed(await db.toggleFollow(ensureEntity()));
  };

  const handlePrimaryPlay = () => {
    if (resumePoint) {
      const target = episodes.find((e) => e.episodeId === resumePoint.episodeId);
      if (target) {
        onPlayEpisode(target, displayName, poster);
        return;
      }
    }
    const first = seasonEpisodes[0] ?? episodes[0];
    if (first) onPlayEpisode(first, displayName, poster);
  };

  const bgSrc = proxiedImageUrl(backdrop || poster);
  const posterSrc = proxiedImageUrl(poster);

  return (
    <div dir="rtl" className="min-h-screen w-full bg-hex-space text-white relative overflow-x-hidden">
      {/* Cinematic Backdrop */}
      <div className="relative w-full min-h-[380px] overflow-hidden">
        {bgSrc && (
          <img
            src={bgSrc}
            alt={displayName}
            className="absolute inset-0 w-full h-full object-cover object-center opacity-40 scale-105"
          />
        )}
        <div className="absolute inset-0 bg-gradient-to-t from-[#0A0C10] via-[#0A0C10]/70 to-[#0A0C10]/40" />
        <div className="absolute inset-0 bg-gradient-to-l from-[#0A0C10]/90 via-[#0A0C10]/40 to-transparent" />

        <div className="relative z-10 px-4 sm:px-8 pt-4">
          <button
            type="button"
            onClick={onBack}
            className="w-10 h-10 rounded-xl bg-[#131722]/85 hover:bg-[#1C2230] border border-[#253048] hover:border-[#00D2FF] text-white flex items-center justify-center backdrop-blur-md cursor-pointer"
          >
            <ArrowRight className="w-5 h-5" />
          </button>
        </div>

        <div className="relative z-10 max-w-6xl mx-auto px-4 sm:px-8 pt-6 pb-8 flex flex-col min-[480px]:flex-row items-start gap-5 sm:gap-6">
          <div className="w-32 sm:w-48 aspect-[2/3] rounded-2xl overflow-hidden border-2 border-[#253048] shadow-2xl bg-[#131722] shrink-0">
            {posterSrc ? (
              <img src={posterSrc} alt={displayName} className="w-full h-full object-cover" />
            ) : (
              <div className="w-full h-full flex items-center justify-center text-[#B05CFF] font-bold">
                ALHUR TV
              </div>
            )}
          </div>

          <div className="flex-1 min-w-0 space-y-4">
            <div className="flex flex-wrap items-center gap-2">
              {rating && (
                <span className="px-2.5 py-1 rounded-lg bg-[#FFB800]/20 border border-[#FFB800]/40 text-[#FFB800] text-xs font-extrabold">
                  ★ {rating}
                </span>
              )}
              {year && (
                <span className="px-2.5 py-1 rounded-lg bg-white/10 text-xs font-bold text-slate-200">
                  {year}
                </span>
              )}
              {seasons.length > 0 && (
                <span className="px-2.5 py-1 rounded-lg bg-[#B05CFF]/20 border border-[#B05CFF]/40 text-xs font-bold text-[#E2B8FF]">
                  {seasons.length} مواسم
                </span>
              )}
              {genre && (
                <span className="px-2.5 py-1 rounded-lg bg-[#00D2FF]/15 border border-[#00D2FF]/30 text-xs font-bold text-[#00D2FF]">
                  {genre}
                </span>
              )}
            </div>

            <h1 className="text-2xl sm:text-4xl font-extrabold text-white leading-tight">
              {displayName}
            </h1>

            {plot && (
              <div className="space-y-1 max-w-3xl">
                <p
                  className={`text-xs sm:text-sm text-[#CBD5E1] leading-relaxed ${
                    expandedPlot ? '' : 'line-clamp-3'
                  }`}
                >
                  {plot}
                </p>
                {plot.length > 160 && (
                  <button
                    type="button"
                    onClick={() => setExpandedPlot((v) => !v)}
                    className="text-xs font-bold text-[#00D2FF] hover:underline cursor-pointer"
                  >
                    {expandedPlot ? 'عرض أقل' : 'عرض المزيد'}
                  </button>
                )}
              </div>
            )}

            <div className="flex flex-wrap items-center gap-3 pt-2">
              {episodes.length > 0 && (
                <button
                  type="button"
                  data-tv-autofocus="true"
                  onClick={handlePrimaryPlay}
                  className="px-6 py-3 rounded-xl bg-[#00D2FF] hover:bg-[#36D5FF] text-[#001018] font-extrabold text-sm flex items-center gap-2 shadow-[0_0_24px_rgba(0,210,255,0.35)] transition cursor-pointer"
                >
                  <Play className="w-4 h-4 fill-current" />
                  <span>
                    {resumePoint
                      ? `متابعة · ${formatTimeMs(resumePoint.positionMs)}`
                      : 'تشغيل الحلقة الأولى'}
                  </span>
                </button>
              )}

              <button
                type="button"
                onClick={() => void handleToggleFav()}
                className={`px-4 py-3 rounded-xl border text-sm font-bold flex items-center gap-2 transition cursor-pointer ${
                  isFavorite
                    ? 'bg-[#E8384F]/20 border-[#E8384F] text-[#FF7B8B]'
                    : 'bg-[#131722] border-[#253048] hover:border-[#00D2FF] text-white'
                }`}
              >
                {isFavorite ? (
                  <Heart className="w-4 h-4 fill-current" />
                ) : (
                  <Star className="w-4 h-4" />
                )}
                <span>{isFavorite ? 'في المفضلة' : 'المفضلة'}</span>
              </button>

              <button
                type="button"
                onClick={() => void handleToggleFollow()}
                className={`px-4 py-3 rounded-xl border text-sm font-bold flex items-center gap-2 transition cursor-pointer ${
                  isFollowed
                    ? 'bg-[#25D9BD]/20 border-[#25D9BD] text-[#25D9BD]'
                    : 'bg-[#131722] border-[#253048] hover:border-[#00D2FF] text-white'
                }`}
              >
                {isFollowed ? <Check className="w-4 h-4" /> : <Plus className="w-4 h-4" />}
                <span>{isFollowed ? 'ضمن المتابعة' : 'متابعة'}</span>
              </button>

              <button
                type="button"
                onClick={() => setInfoOpen(true)}
                className="px-4 py-3 rounded-xl bg-[#131722] hover:bg-[#1C2230] border border-[#253048] hover:border-[#00D2FF] text-sm font-bold text-white flex items-center gap-2 transition cursor-pointer"
              >
                <Info className="w-4 h-4 text-[#00D2FF]" />
                <span>معلومات</span>
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* Landscape Split: Seasons & Episodes + Cast & Similar */}
      <div className="max-w-6xl mx-auto px-4 sm:px-8 py-6 grid grid-cols-1 min-[640px]:grid-cols-12 gap-6 items-start">
        <div className={cast.length > 0 || similar.length > 0 ? 'min-[640px]:col-span-7 space-y-6' : 'min-[640px]:col-span-12 space-y-6'}>
        {seasons.length > 0 && (
          <div className="flex items-center gap-2 overflow-x-auto no-scrollbar pb-1">
            {seasons.map((s) => (
              <button
                key={s}
                type="button"
                onClick={() => {
                  setSelectedSeason(s);
                  setShowAllEps(false);
                }}
                className={`shrink-0 px-4 py-2 rounded-xl text-xs sm:text-sm font-extrabold border transition cursor-pointer ${
                  selectedSeason === s
                    ? 'bg-[#00D2FF] border-[#00D2FF] text-[#001018] shadow-[0_0_16px_rgba(0,210,255,0.35)]'
                    : 'bg-[#131722] border-[#253048] text-slate-300 hover:border-[#00D2FF]'
                }`}
              >
                الموسم {s}
              </button>
            ))}
          </div>
        )}

        {loadingEps && episodes.length === 0 ? (
          <div className="py-12 text-center text-sm text-[#A0AEC0]">جارٍ تحميل الحلقات…</div>
        ) : visibleEpisodes.length === 0 ? (
          <div className="rounded-2xl bg-[#131722] border border-[#253048] p-8 text-center text-sm text-[#A0AEC0]">
            لا توجد حلقات متاحة لهذا المسلسل حاليًا.
          </div>
        ) : (
          <div className="space-y-4">
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3.5">
              {visibleEpisodes.map((ep, idx) => {
                const thumb = proxiedImageUrl(ep.imageUrl || poster);
                const isResume = resumePoint?.episodeId === ep.episodeId;
                return (
                  <button
                    key={ep.pk}
                    type="button"
                    onClick={() => onPlayEpisode(ep, displayName, poster)}
                    className={`group rounded-2xl overflow-hidden bg-[#131722] border text-right transition cursor-pointer flex flex-col ${
                      isResume
                        ? 'border-[#00D2FF] ring-1 ring-[#00D2FF]/40'
                        : 'border-[#253048] hover:border-[#00D2FF]'
                    }`}
                  >
                    <div className="relative w-full aspect-video bg-[#0F131F] overflow-hidden">
                      {thumb && (
                        <img
                          src={thumb}
                          alt={ep.title}
                          loading="lazy"
                          className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
                        />
                      )}
                      <div className="absolute inset-0 bg-black/35 group-hover:bg-black/15 flex items-center justify-center transition">
                        <div className="w-11 h-11 rounded-full bg-[#00D2FF]/90 text-[#001018] flex items-center justify-center shadow-lg">
                          <Play className="w-5 h-5 fill-current" />
                        </div>
                      </div>
                      {ep.duration && (
                        <span className="absolute bottom-2 left-2 px-2 py-0.5 rounded bg-black/75 text-[10px] font-bold text-white">
                          {ep.duration}
                        </span>
                      )}
                      {isResume && (
                        <span className="absolute top-2 right-2 px-2 py-0.5 rounded bg-[#00D2FF] text-[#001018] text-[10px] font-extrabold">
                          متابعة · {formatTimeMs(resumePoint.positionMs)}
                        </span>
                      )}
                    </div>
                    <div className="p-3">
                      <div className="text-[11px] font-bold text-[#B05CFF]">
                        الحلقة {ep.episodeNumber || idx + 1}
                      </div>
                      <div className="text-xs sm:text-sm font-extrabold text-white line-clamp-1 mt-0.5">
                        {ep.title}
                      </div>
                    </div>
                  </button>
                );
              })}
            </div>

            {!showAllEps && seasonEpisodes.length > 24 && (
              <div className="flex justify-center pt-2">
                <button
                  type="button"
                  onClick={() => setShowAllEps(true)}
                  className="px-5 py-2.5 rounded-xl bg-[#131722] hover:bg-[#1C2230] border border-[#253048] hover:border-[#00D2FF] text-xs font-bold text-[#00D2FF] cursor-pointer"
                >
                  عرض المزيد من الحلقات ({seasonEpisodes.length - 24})
                </button>
              </div>
            )}
          </div>
        )}

        </div>

        {(cast.length > 0 || similar.length > 0) && (
          <div className="min-[640px]:col-span-5 space-y-6">
            {cast.length > 0 && (
              <section className="space-y-3">
                <h2 className="text-base sm:text-lg font-extrabold text-white">أبطال العمل</h2>
                <div className="flex items-center gap-3 overflow-x-auto no-scrollbar pb-1">
                  {cast.map((actor) => {
                    const initials = actor
                      .split(/\s+/)
                      .slice(0, 2)
                      .map((w) => w[0] || '')
                      .join('')
                      .toUpperCase();
                    return (
                      <div
                        key={actor}
                        className="shrink-0 w-28 p-3 rounded-xl bg-[#131722] border border-[#253048] flex flex-col items-center text-center gap-2"
                      >
                        <div className="w-11 h-11 rounded-full bg-[#00D2FF]/15 border border-[#00D2FF]/40 text-[#00D2FF] font-extrabold text-xs flex items-center justify-center">
                          {initials || '★'}
                        </div>
                        <span className="text-xs font-bold text-slate-200 line-clamp-1">{actor}</span>
                      </div>
                    );
                  })}
                </div>
              </section>
            )}

            {similar.length > 0 && (
              <section className="space-y-3">
                <h2 className="text-base sm:text-lg font-extrabold text-white">قد يعجبك أيضًا</h2>
                <div className="flex items-stretch gap-3 overflow-x-auto no-scrollbar pb-2">
                  {similar.map((sim) => (
                    <PosterCard
                      key={sim.pk}
                      item={sim}
                      variant="rail"
                      isFavorite={favKeys.has(`${sim.kind}:${sim.contentId}`)}
                      onOpen={onOpenSeries}
                    />
                  ))}
                </div>
              </section>
            )}
          </div>
        )}
      </div>

      {/* Info Modal */}
      {infoOpen && (
        <div
          data-modal-root="true"
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 backdrop-blur-sm p-4"
          onClick={() => setInfoOpen(false)}
        >
          <div
            onClick={(e) => e.stopPropagation()}
            className="w-full max-w-md rounded-2xl bg-[#131722] border border-[#253048] shadow-2xl overflow-hidden"
          >
            <div className="flex items-center justify-between px-5 py-4 border-b border-[#253048]">
              <h3 className="text-sm font-extrabold text-white">معلومات المسلسل</h3>
              <button
                type="button"
                data-modal-close="true"
                autoFocus
                onClick={() => setInfoOpen(false)}
                className="text-slate-400 hover:text-white cursor-pointer"
              >
                <X className="w-4 h-4" />
              </button>
            </div>
            <div className="p-5 space-y-2.5 text-xs sm:text-sm text-slate-300">
              {year && (
                <div className="flex justify-between">
                  <span className="text-[#A0AEC0]">سنة الإصدار</span>
                  <span className="font-bold text-white">{year}</span>
                </div>
              )}
              {genre && (
                <div className="flex justify-between">
                  <span className="text-[#A0AEC0]">التصنيف</span>
                  <span className="font-bold text-white">{genre}</span>
                </div>
              )}
              <div className="flex justify-between">
                <span className="text-[#A0AEC0]">المواسم</span>
                <span className="font-bold text-white">{seasons.length || 1}</span>
              </div>
              {plot && (
                <p className="pt-2 border-t border-[#253048] text-xs leading-relaxed text-slate-300">
                  {plot}
                </p>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
