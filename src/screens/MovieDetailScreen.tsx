import React, { useEffect, useState } from 'react';
import { ArrowRight, Heart, Info, Play, Star, X } from 'lucide-react';
import { ContentItem, Kinds } from '../types';
import { XtreamClient } from '../api/xtream';
import { db } from '../storage/db';
import {
  formatDuration,
  formatTimeMs,
  firstImage,
  proxiedImageUrl,
  Rating,
  splitNames,
  strField,
} from '../utils/arabic';
import { PosterCard } from '../components/PosterCard';

interface MovieDetailScreenProps {
  vodId: string;
  title: string;
  image: string;
  extension: string;
  client: XtreamClient;
  onBack: () => void;
  onPlay: (url: string, title: string, image: string, vodId: string) => void;
  onOpenMovie: (item: ContentItem) => void;
}

export const MovieDetailScreen: React.FC<MovieDetailScreenProps> = ({
  vodId,
  title,
  image,
  extension,
  client,
  onBack,
  onPlay,
  onOpenMovie,
}) => {
  const [backdrop, setBackdrop] = useState('');
  const [poster, setPoster] = useState(image);
  const [displayTitle, setDisplayTitle] = useState(title);
  const [plot, setPlot] = useState('');
  const [expandedPlot, setExpandedPlot] = useState(false);
  const [year, setYear] = useState('');
  const [duration, setDuration] = useState('');
  const [genre, setGenre] = useState('');
  const [director, setDirector] = useState('');
  const [country, setCountry] = useState('');
  const [rating, setRating] = useState('');
  const [cast, setCast] = useState<string[]>([]);
  const [resolvedExt, setResolvedExt] = useState(extension || 'mp4');

  const [itemEntity, setItemEntity] = useState<ContentItem | null>(null);
  const [isFavorite, setIsFavorite] = useState(false);
  const [resumeMs, setResumeMs] = useState(0);
  const [similar, setSimilar] = useState<ContentItem[]>([]);
  const [favKeys, setFavKeys] = useState<Set<string>>(new Set());
  const [infoOpen, setInfoOpen] = useState(false);
  const [startingPlay, setStartingPlay] = useState(false);

  useEffect(() => {
    let active = true;
    setBackdrop('');
    setPlot('');
    setExpandedPlot(false);
    setCast([]);

    const loadLocal = async () => {
      const [item, mark, favs] = await Promise.all([
        db.getContentItem(Kinds.MOVIES, vodId),
        db.getMark(Kinds.MOVIES, vodId),
        db.favoriteKeys(),
      ]);
      if (!active) return;
      if (item) {
        setItemEntity(item);
        if (item.imageUrl && !poster) setPoster(item.imageUrl);
        if (item.rating) setRating(item.rating);
        const sim = await db.browse(
          Kinds.MOVIES,
          item.category ? [item.category] : null,
          '',
          15,
          0
        );
        if (active) setSimilar(sim.filter((x) => x.contentId !== vodId).slice(0, 12));
      }
      setIsFavorite(Boolean(mark?.favorite));
      setResumeMs(mark?.positionMs ?? 0);
      setFavKeys(favs);
    };

    const loadDetails = async () => {
      try {
        const res = await client.details('get_vod_info', vodId);
        if (!active) return;
        const info = (res.info as Record<string, unknown> | undefined) ?? {};
        const movieData = (res.movie_data as Record<string, unknown> | undefined) ?? {};
        const nameVal = strField(movieData, 'name') || strField(info, 'name');
        if (nameVal) setDisplayTitle(nameVal);

        const bg = firstImage(info, 'backdrop_path', 'cover_big', 'movie_image', 'cover');
        if (bg) setBackdrop(bg);
        const post = firstImage(info, 'movie_image', 'cover_big', 'cover');
        if (post) setPoster(post);

        setPlot(strField(info, 'plot', 'description', 'overview'));
        const rel = strField(info, 'releasedate', 'releaseDate', 'release_date', 'year');
        const ym = rel.match(/\b(19|20)\d{2}\b/);
        setYear(ym ? ym[0] : rel);
        setDuration(formatDuration(Number(info.duration_secs ?? 0) || 0, strField(info, 'duration')));
        setGenre(strField(info, 'genre', 'genres'));
        setDirector(strField(info, 'director'));
        setCountry(strField(info, 'country'));
        const r = Rating.parse(info.rating, info.rating_5based);
        if (r) setRating(r);
        setCast(splitNames(strField(info, 'cast', 'actors')).slice(0, 12));
        const ext = strField(movieData, 'container_extension') || strField(info, 'container_extension');
        if (ext) setResolvedExt(ext);
      } catch {
        // Keep cached metadata on network error
      }
    };

    void loadLocal();
    void loadDetails();
    return () => {
      active = false;
    };
  }, [vodId, client, poster]);

  const handleToggleFav = async () => {
    const target: ContentItem = itemEntity ?? {
      pk: `${Kinds.MOVIES}::${vodId}`,
      kind: Kinds.MOVIES,
      contentId: vodId,
      name: displayTitle,
      normalizedName: displayTitle,
      category: '',
      imageUrl: poster,
      extension: resolvedExt,
      syncToken: '',
      rating,
      idNum: Number(vodId) || 0,
    };
    const next = await db.toggleFavorite(target);
    setIsFavorite(next);
  };

  const handlePlay = async () => {
    setStartingPlay(true);
    try {
      const url = await client.movieMediaUrl(vodId, resolvedExt);
      onPlay(url, displayTitle, poster, vodId);
    } finally {
      setStartingPlay(false);
    }
  };

  const bgSrc = proxiedImageUrl(backdrop || poster);
  const posterSrc = proxiedImageUrl(poster);

  return (
    <div dir="rtl" className="min-h-screen w-full bg-hex-space text-white relative overflow-x-hidden">
      {/* Cinematic Backdrop */}
      <div className="relative w-full min-h-[420px] sm:min-h-[480px] overflow-hidden">
        {bgSrc && (
          <img
            src={bgSrc}
            alt={displayTitle}
            className="absolute inset-0 w-full h-full object-cover object-center opacity-45 scale-105"
          />
        )}
        <div className="absolute inset-0 bg-gradient-to-t from-[#0A0C10] via-[#0A0C10]/70 to-[#0A0C10]/40" />
        <div className="absolute inset-0 bg-gradient-to-l from-[#0A0C10]/90 via-[#0A0C10]/40 to-transparent" />

        {/* Back Button */}
        <div className="relative z-10 px-4 sm:px-8 pt-4">
          <button
            type="button"
            onClick={onBack}
            className="w-10 h-10 rounded-xl bg-[#131722]/85 hover:bg-[#1C2230] border border-[#253048] hover:border-[#00D2FF] text-white flex items-center justify-center backdrop-blur-md cursor-pointer"
          >
            <ArrowRight className="w-5 h-5" />
          </button>
        </div>

        {/* Hero Details */}
        <div className="relative z-10 max-w-6xl mx-auto px-4 sm:px-8 pt-6 pb-8 flex flex-col min-[480px]:flex-row items-start gap-5 sm:gap-6">
          <div className="w-32 sm:w-52 aspect-[2/3] rounded-2xl overflow-hidden border-2 border-[#253048] shadow-2xl bg-[#131722] shrink-0">
            {posterSrc ? (
              <img src={posterSrc} alt={displayTitle} className="w-full h-full object-cover" />
            ) : (
              <div className="w-full h-full flex items-center justify-center text-[#00D2FF] font-bold">
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
              {duration && (
                <span className="px-2.5 py-1 rounded-lg bg-white/10 text-xs font-bold text-slate-200">
                  {duration}
                </span>
              )}
              {genre && (
                <span className="px-2.5 py-1 rounded-lg bg-[#00D2FF]/15 border border-[#00D2FF]/30 text-xs font-bold text-[#00D2FF]">
                  {genre}
                </span>
              )}
            </div>

            <h1 className="text-2xl sm:text-4xl font-extrabold text-white leading-tight">
              {displayTitle}
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

            {/* Action Buttons */}
            <div className="flex flex-wrap items-center gap-3 pt-2">
              <button
                type="button"
                data-tv-autofocus="true"
                disabled={startingPlay}
                onClick={() => void handlePlay()}
                className="px-6 py-3 rounded-xl bg-[#00D2FF] hover:bg-[#36D5FF] text-[#001018] font-extrabold text-sm flex items-center gap-2 shadow-[0_0_24px_rgba(0,210,255,0.35)] transition cursor-pointer disabled:opacity-60"
              >
                <Play className="w-4 h-4 fill-current" />
                <span>
                  {startingPlay
                    ? 'جارٍ التحضير…'
                    : resumeMs > 15_000
                    ? `استئناف المشاهدة · ${formatTimeMs(resumeMs)}`
                    : 'تشغيل'}
                </span>
              </button>

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

      {/* Cast & Similar */}
      <div className="max-w-6xl mx-auto px-4 sm:px-8 py-6 space-y-8">
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
                  onOpen={onOpenMovie}
                />
              ))}
            </div>
          </section>
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
              <h3 className="text-sm font-extrabold text-white">تفاصيل الفيلم</h3>
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
              {director && (
                <div className="flex justify-between">
                  <span className="text-[#A0AEC0]">المخرج</span>
                  <span className="font-bold text-white">{director}</span>
                </div>
              )}
              {country && (
                <div className="flex justify-between">
                  <span className="text-[#A0AEC0]">الدولة</span>
                  <span className="font-bold text-white">{country}</span>
                </div>
              )}
              {year && (
                <div className="flex justify-between">
                  <span className="text-[#A0AEC0]">سنة الإصدار</span>
                  <span className="font-bold text-white">{year}</span>
                </div>
              )}
              {duration && (
                <div className="flex justify-between">
                  <span className="text-[#A0AEC0]">المدة</span>
                  <span className="font-bold text-white">{duration}</span>
                </div>
              )}
              {genre && (
                <div className="flex justify-between">
                  <span className="text-[#A0AEC0]">التصنيف</span>
                  <span className="font-bold text-white">{genre}</span>
                </div>
              )}
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
