import React, { useMemo, useState } from 'react';
import { Heart, Tv, Film, Clapperboard } from 'lucide-react';
import { ContentItem, Kinds, kindAccent } from '../types';
import { proxiedImageUrl, Rating } from '../utils/arabic';

interface PosterCardProps {
  item: ContentItem;
  isFavorite?: boolean;
  showBadge?: boolean;
  variant?: 'rail' | 'grid';
  onOpen: (item: ContentItem) => void;
  onToggleFavorite?: (item: ContentItem) => void;
}

export const PosterCard: React.FC<PosterCardProps> = ({
  item,
  isFavorite = false,
  showBadge = false,
  variant = 'grid',
  onOpen,
  onToggleFavorite,
}) => {
  const [imgError, setImgError] = useState(false);
  const isLive = item.kind === Kinds.LIVE;
  const imgSrc = useMemo(() => proxiedImageUrl(item.imageUrl), [item.imageUrl]);
  const accent = kindAccent(item.kind);
  const badgeText = Kinds.badge(item.kind);
  const ratingVal = Rating.value(item.rating);

  const widthClass =
    variant === 'rail'
      ? 'w-[132px] sm:w-[144px] shrink-0'
      : 'w-full';

  const aspectClass = isLive ? 'aspect-[1/0.78]' : 'aspect-[2/3]';

  const FallbackIcon =
    item.kind === Kinds.LIVE ? Tv : item.kind === Kinds.MOVIES ? Film : Clapperboard;

  return (
    <div
      role="button"
      tabIndex={0}
      onClick={() => onOpen(item)}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          onOpen(item);
        }
      }}
      onContextMenu={(e) => {
        if (onToggleFavorite) {
          e.preventDefault();
          onToggleFavorite(item);
        }
      }}
      className={`${widthClass} group relative rounded-xl overflow-hidden bg-[#131722] border border-[#253048]/80 cursor-pointer select-none transition-all duration-150 hover:scale-[1.03] hover:border-[#00D2FF] hover:shadow-[0_0_20px_rgba(0,210,255,0.28)] focus:outline-none focus-visible:scale-[1.03] focus-visible:border-[#00D2FF] focus-visible:ring-2 focus-visible:ring-[#00D2FF]`}
    >
      <div className={`relative w-full ${aspectClass} overflow-hidden bg-[#101420]`}>
        {imgSrc && !imgError ? (
          <img
            src={imgSrc}
            alt={item.name}
            loading="lazy"
            decoding="async"
            onError={() => setImgError(true)}
            className={`w-full h-full transition-transform duration-300 group-hover:scale-105 ${
              isLive ? 'object-contain p-2.5 bg-[#0F131F]' : 'object-cover'
            }`}
          />
        ) : (
          <div
            className="w-full h-full flex flex-col items-center justify-center p-3 text-center"
            style={{
              background: `radial-gradient(circle at 50% 25%, ${accent}33 0%, #131722 65%, #0A0C10 100%)`,
            }}
          >
            <div
              className="w-11 h-11 rounded-full flex items-center justify-center mb-2 border"
              style={{ backgroundColor: `${accent}22`, borderColor: `${accent}66`, color: accent }}
            >
              <FallbackIcon className="w-5 h-5" />
            </div>
            <span className="text-[11px] font-bold tracking-widest text-[#00D2FF]/85">ALHUR</span>
          </div>
        )}

        {/* Top-right kind + rating badges */}
        <div className="absolute top-2 right-2 flex flex-wrap items-center gap-1 pointer-events-none">
          {showBadge && badgeText && (
            <span
              className="px-2 py-0.5 rounded-md text-[10px] font-bold text-white shadow-sm backdrop-blur-sm"
              style={{ backgroundColor: `${accent}D9` }}
            >
              {badgeText}
            </span>
          )}
          {ratingVal > 0 && (
            <span className="px-1.5 py-0.5 rounded-md text-[10px] font-bold bg-black/75 text-[#FFB800] border border-[#FFB800]/30 backdrop-blur-sm">
              ★ {item.rating}
            </span>
          )}
        </div>

        {/* Top-left favorite quick toggle */}
        {onToggleFavorite && (
          <button
            type="button"
            title={isFavorite ? 'إزالة من المفضلة' : 'إضافة إلى المفضلة'}
            onClick={(e) => {
              e.stopPropagation();
              onToggleFavorite(item);
            }}
            className={`absolute top-2 left-2 w-7 h-7 rounded-full flex items-center justify-center transition-all border backdrop-blur-sm ${
              isFavorite
                ? 'bg-[#E8384F]/90 border-[#FF7B8B] text-white opacity-100'
                : 'bg-black/60 border-white/20 text-white/80 opacity-0 group-hover:opacity-100 group-focus-visible:opacity-100 hover:bg-[#E8384F]/80 hover:text-white'
            }`}
          >
            <Heart className={`w-3.5 h-3.5 ${isFavorite ? 'fill-current' : ''}`} />
          </button>
        )}

        {/* Bottom gradient + title */}
        <div className="absolute inset-x-0 bottom-0 bg-gradient-to-t from-black/95 via-black/70 to-transparent pt-8 pb-2 px-2.5">
          <p className="text-[12px] sm:text-[12.5px] font-bold text-white leading-snug line-clamp-2 text-right">
            {item.name}
          </p>
        </div>
      </div>
    </div>
  );
};
