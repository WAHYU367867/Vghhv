import React, { useEffect, useRef, useState } from 'react';
import { ChevronLeft, ChevronRight, Info, Play } from 'lucide-react';
import { ContentItem, Kinds } from '../types';
import { XtreamClient } from '../api/xtream';
import { firstImage, formatDuration, proxiedImageUrl, Rating, strField } from '../utils/arabic';

interface FeaturedMeta {
  backdrop: string;
  plot: string;
  year: string;
  genres: string;
  duration: string;
  seasons: string;
  rating: string;
}

interface FeaturedBannerProps {
  items: ContentItem[];
  client: XtreamClient;
  onWatch: (item: ContentItem) => void;
  onDetails: (item: ContentItem) => void;
}

export const FeaturedBanner: React.FC<FeaturedBannerProps> = ({
  items,
  client,
  onWatch,
  onDetails,
}) => {
  const [index, setIndex] = useState(0);
  const [paused, setPaused] = useState(false);
  const [metaMap, setMetaMap] = useState<Record<string, FeaturedMeta>>({});
  const fetchedRef = useRef<Set<string>>(new Set());

  const safeIndex = items.length > 0 ? index % items.length : 0;
  const current = items[safeIndex];

  useEffect(() => {
    if (items.length <= 1 || paused) return;
    const timer = window.setInterval(() => {
      setIndex((prev) => (prev + 1) % items.length);
    }, 9000);
    return () => window.clearInterval(timer);
  }, [items.length, paused]);

  useEffect(() => {
    if (!current) return;
    const key = current.pk;
    if (fetchedRef.current.has(key)) return;
    fetchedRef.current.add(key);

    let active = true;
    const action = current.kind === Kinds.SERIES ? 'get_series_info' : 'get_vod_info';
    client
      .details(action, current.contentId)
      .then((res) => {
        if (!active) return;
        const info = (res.info as Record<string, unknown> | undefined) ?? res;
        const backdrop = firstImage(info, 'backdrop_path', 'cover_big', 'movie_image', 'cover');
        const plot = strField(info, 'plot', 'description', 'overview');
        const release = strField(info, 'releasedate', 'releaseDate', 'release_date', 'year');
        const yearMatch = release.match(/\b(19|20)\d{2}\b/);
        const year = yearMatch ? yearMatch[0] : '';
        const genres = strField(info, 'genre', 'genres');
        const secs = Number(info.duration_secs ?? 0) || 0;
        const clock = strField(info, 'duration');
        const duration = current.kind === Kinds.MOVIES ? formatDuration(secs, clock) : '';
        const epsObj = res.episodes as Record<string, unknown> | unknown[] | undefined;
        const seasonCount = Array.isArray(res.seasons)
          ? res.seasons.length
          : epsObj && typeof epsObj === 'object' && !Array.isArray(epsObj)
          ? Object.keys(epsObj).length
          : 0;
        const seasons = current.kind === Kinds.SERIES && seasonCount > 0 ? `${seasonCount} مواسم` : '';
        const rating = Rating.parse(info.rating, info.rating_5based) || current.rating;

        setMetaMap((prev) => ({
          ...prev,
          [key]: { backdrop, plot, year, genres, duration, seasons, rating },
        }));
      })
      .catch(() => {
        // Keep basic ContentItem fields if details request fails
      });

    return () => {
      active = false;
    };
  }, [client, current]);

  if (!current) return null;

  const meta = metaMap[current.pk];
  const bgUrl = proxiedImageUrl(meta?.backdrop || current.imageUrl);
  const posterUrl = proxiedImageUrl(current.imageUrl);
  const rating = meta?.rating || current.rating;

  const chips = [
    rating ? `★ ${rating}` : '',
    current.kind === Kinds.SERIES ? 'مسلسل' : 'فيلم',
    meta?.year || '',
    meta?.duration || meta?.seasons || '',
    meta?.genres ? meta.genres.split(/[,،/]/)[0]?.trim() : '',
  ].filter(Boolean);

  return (
    <section
      onMouseEnter={() => setPaused(true)}
      onMouseLeave={() => setPaused(false)}
      className="relative w-full h-[220px] sm:h-[285px] lg:h-[310px] rounded-2xl overflow-hidden border border-[#253048] bg-[#131722] shadow-2xl select-none"
    >
      {/* Backdrop */}
      {bgUrl && (
        <img
          src={bgUrl}
          alt={current.name}
          className="absolute inset-0 w-full h-full object-cover object-center scale-[1.02] transition-opacity duration-500"
        />
      )}

      {/* RTL Cinematic Scrims matching FeaturedBanner.kt */}
      <div className="absolute inset-0 bg-gradient-to-l from-[#0A0C10]/95 via-[#0A0C10]/75 to-[#0A0C10]/25" />
      <div className="absolute inset-0 bg-gradient-to-t from-[#0A0C10] via-transparent to-[#0A0C10]/40" />

      {/* Content */}
      <div className="relative z-10 h-full flex items-center justify-between gap-5 px-5 sm:px-8 py-5">
        <div className="flex-1 min-w-0 flex flex-col justify-center gap-2.5 max-w-2xl">
          <div className="flex items-center gap-2 flex-wrap">
            <span className="px-2.5 py-0.5 rounded-md text-[11px] font-bold bg-[#FFB800]/20 text-[#FFB800] border border-[#FFB800]/40">
              رائج الآن ★
            </span>
            {chips.map((chip, idx) => (
              <span
                key={idx}
                className="px-2 py-0.5 rounded-md text-[11px] font-semibold bg-white/10 text-slate-200 backdrop-blur-sm"
              >
                {chip}
              </span>
            ))}
          </div>

          <h2 className="text-xl sm:text-3xl font-extrabold text-white leading-tight line-clamp-2 drop-shadow">
            {current.name}
          </h2>

          {meta?.plot && (
            <p className="text-xs sm:text-sm text-[#CBD5E1] line-clamp-2 leading-relaxed max-w-xl">
              {meta.plot}
            </p>
          )}

          <div className="flex items-center gap-3 pt-1">
            <button
              type="button"
              onClick={() => onWatch(current)}
              className="flex items-center gap-2 px-5 py-2.5 rounded-xl bg-[#00D2FF] hover:bg-[#36D5FF] text-[#001018] font-extrabold text-sm shadow-[0_0_20px_rgba(0,210,255,0.35)] transition cursor-pointer focus:outline-none focus-visible:ring-2 focus-visible:ring-white"
            >
              <Play className="w-4 h-4 fill-current" />
              <span>شاهد الآن</span>
            </button>

            <button
              type="button"
              onClick={() => onDetails(current)}
              className="flex items-center gap-2 px-4 py-2.5 rounded-xl bg-[#1C2230]/85 hover:bg-[#253048] text-white font-bold text-sm border border-[#253048] hover:border-[#00D2FF]/60 backdrop-blur-sm transition cursor-pointer focus:outline-none focus-visible:ring-2 focus-visible:ring-[#00D2FF]"
            >
              <Info className="w-4 h-4 text-[#00D2FF]" />
              <span>التفاصيل</span>
            </button>
          </div>
        </div>

        {/* Left Poster Preview */}
        {posterUrl && (
          <div
            onClick={() => onDetails(current)}
            className="block w-[108px] sm:w-[132px] lg:w-[162px] aspect-[2/3] rounded-xl overflow-hidden border-2 border-[#253048] hover:border-[#00D2FF] shadow-2xl shrink-0 cursor-pointer transition"
          >
            <img src={posterUrl} alt={current.name} className="w-full h-full object-cover" />
          </div>
        )}
      </div>

      {/* Controls & Dots */}
      {items.length > 1 && (
        <>
          <button
            type="button"
            aria-label="الشريحة السابقة"
            onClick={() => setIndex((prev) => (prev - 1 + items.length) % items.length)}
            className="flex absolute right-2.5 top-1/2 -translate-y-1/2 z-20 w-8 h-8 rounded-full bg-black/50 hover:bg-[#00D2FF] text-white hover:text-[#001018] items-center justify-center border border-white/15 transition cursor-pointer"
          >
            <ChevronRight className="w-4 h-4" />
          </button>
          <button
            type="button"
            aria-label="الشريحة التالية"
            onClick={() => setIndex((prev) => (prev + 1) % items.length)}
            className="flex absolute left-2.5 top-1/2 -translate-y-1/2 z-20 w-8 h-8 rounded-full bg-black/50 hover:bg-[#00D2FF] text-white hover:text-[#001018] items-center justify-center border border-white/15 transition cursor-pointer"
          >
            <ChevronLeft className="w-4 h-4" />
          </button>

          <div className="absolute bottom-3 left-1/2 -translate-x-1/2 z-20 flex items-center gap-1.5 px-3 py-1 rounded-full bg-black/45 backdrop-blur-sm">
            {items.map((it, i) => (
              <button
                key={it.pk}
                type="button"
                aria-label={`شريحة ${i + 1}`}
                onClick={() => setIndex(i)}
                className={`h-1.5 rounded-full transition-all cursor-pointer ${
                  i === safeIndex ? 'w-5 bg-[#00D2FF]' : 'w-1.5 bg-white/35 hover:bg-white/60'
                }`}
              />
            ))}
          </div>
        </>
      )}
    </section>
  );
};
