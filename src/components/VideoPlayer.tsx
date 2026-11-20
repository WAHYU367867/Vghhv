import React, { useCallback, useEffect, useRef, useState } from 'react';
import Hls from 'hls.js';
import {
  ArrowLeft,
  Captions,
  Check,
  Copy,
  Gauge,
  Info,
  List,
  Maximize,
  Minimize,
  Pause,
  Play,
  RefreshCw,
  Repeat,
  RotateCcw,
  RotateCw,
  Search,
  Settings,
  SkipBack,
  SkipForward,
  Sliders,
  Volume2,
  VolumeX,
  X,
} from 'lucide-react';
import { ContentItem, EpisodeItem, Kinds, PlayerLaunchParams } from '../types';
import { XtreamClient } from '../api/xtream';
import { db, PlayerOptions, ResumeStore } from '../storage/db';
import {
  formatTimeMs,
  proxiedImageUrl,
  proxiedMediaUrl,
  strField,
} from '../utils/arabic';

interface VideoPlayerProps {
  params: PlayerLaunchParams;
  client: XtreamClient;
  onClose: () => void;
  onToast?: (msg: string) => void;
}

type ModalType = 'none' | 'channels' | 'audio' | 'subtitles' | 'quality' | 'speed' | 'info' | 'error';

const RESIZE_MODES = [
  { label: 'ملاءمة (Fit)', short: 'Fit', className: 'object-contain' },
  { label: 'تكبير (Zoom)', short: 'Zoom', className: 'object-cover' },
  { label: 'ملء الشاشة (Fill)', short: 'Fill', className: 'object-fill' },
] as const;

const SPEEDS = [0.5, 0.75, 1.0, 1.25, 1.5, 2.0];

export const VideoPlayer: React.FC<VideoPlayerProps> = ({
  params,
  client,
  onClose,
  onToast,
}) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const videoRef = useRef<HTMLVideoElement>(null);
  const hlsRef = useRef<Hls | null>(null);
  const hideTimerRef = useRef<number | null>(null);
  const statusTimerRef = useRef<number | null>(null);
  const resumeAppliedRef = useRef(false);

  const [currentUrl, setCurrentUrl] = useState(params.url);
  const [title, setTitle] = useState(params.title || 'ALHUR TV');
  const [image, setImage] = useState(params.image || '');
  const [contentId, setContentId] = useState(params.contentId || '');
  const [categoryId, setCategoryId] = useState(params.categoryId || '');
  const [categoryLabel, setCategoryLabel] = useState('');
  const [currentEpisodeId, setCurrentEpisodeId] = useState(params.episodeId || '');

  const isLive = params.markKind === Kinds.LIVE;
  const isSeries = Boolean(params.seriesId);

  const [episodes, setEpisodes] = useState<EpisodeItem[]>([]);
  const [epIndex, setEpIndex] = useState(-1);

  const [playing, setPlaying] = useState(false);
  const [buffering, setBuffering] = useState(true);
  const [currentTimeSec, setCurrentTimeSec] = useState(0);
  const [durationSec, setDurationSec] = useState(0);
  const [volume, setVolume] = useState(1);
  const [muted, setMuted] = useState(false);
  const [speed, setSpeed] = useState(1.0);
  const [repeat, setRepeat] = useState(false);
  const [resizeIndex, setResizeIndex] = useState(0);
  const [isFullscreen, setIsFullscreen] = useState(false);

  const [controlsVisible, setControlsVisible] = useState(true);
  const [episodesDrawerOpen, setEpisodesDrawerOpen] = useState(false);
  const [modal, setModal] = useState<ModalType>('none');
  const [statusBanner, setStatusBanner] = useState('');
  const [errorMsg, setErrorMsg] = useState('');
  const [copiedUrl, setCopiedUrl] = useState(false);

  // HLS / track options
  const [hlsLevels, setHlsLevels] = useState<Array<{ index: number; height: number; bitrate: number }>>([]);
  const [selectedLevel, setSelectedLevel] = useState<number>(-1);
  const [audioTracks, setAudioTracks] = useState<Array<{ id: number; name: string; lang: string }>>([]);
  const [selectedAudio, setSelectedAudio] = useState<number>(0);
  const [subtitleTracks, setSubtitleTracks] = useState<Array<{ id: number; name: string; lang: string }>>([]);
  const [selectedSubtitle, setSelectedSubtitle] = useState<number>(-1);

  // Channel list picker
  const [channelQuery, setChannelQuery] = useState('');
  const [channels, setChannels] = useState<ContentItem[]>([]);

  const showStatus = useCallback((msg: string) => {
    setStatusBanner(msg);
    if (statusTimerRef.current) window.clearTimeout(statusTimerRef.current);
    statusTimerRef.current = window.setTimeout(() => setStatusBanner(''), 3200);
  }, []);

  const scheduleHide = useCallback(() => {
    if (hideTimerRef.current) window.clearTimeout(hideTimerRef.current);
    hideTimerRef.current = window.setTimeout(() => {
      if (modal === 'none' && !episodesDrawerOpen && videoRef.current && !videoRef.current.paused) {
        setControlsVisible(false);
      }
    }, 4500);
  }, [modal, episodesDrawerOpen]);

  const revealControls = useCallback(() => {
    setControlsVisible(true);
    scheduleHide();
  }, [scheduleHide]);

  // Save watch progress
  const persistProgress = useCallback(
    (forceZero = false) => {
      const video = videoRef.current;
      if (!video || isLive) return;
      const posMs = forceZero ? 0 : Math.floor((video.currentTime || 0) * 1000);
      const durMs = Math.floor((video.duration || 0) * 1000);
      const saveMs = durMs > 0 && posMs >= durMs - 5000 ? 0 : posMs;

      if (params.markKind && params.markId) {
        void db.saveProgress(params.markKind, params.markId, saveMs);
      }
      if (params.seriesId && currentEpisodeId) {
        const ep = episodes[epIndex];
        const ext = ep?.extension || currentUrl.split('.').pop()?.split('?')[0] || 'mp4';
        if (saveMs > 0) {
          ResumeStore.saveSeries(params.seriesId, currentEpisodeId, saveMs, ext, title);
        } else {
          ResumeStore.clearSeries(params.seriesId);
        }
      }
    },
    [isLive, params.markKind, params.markId, params.seriesId, currentEpisodeId, episodes, epIndex, currentUrl, title]
  );

  // Fetch category name for Live
  useEffect(() => {
    if (!isLive || !categoryId) return;
    db.getCategories(Kinds.LIVE).then((cats) => {
      const found = cats.find((c) => c.categoryId === categoryId);
      if (found) setCategoryLabel(found.categoryName);
    });
  }, [isLive, categoryId]);

  // Load series episodes
  useEffect(() => {
    if (!params.seriesId) return;
    let active = true;
    const loadEps = async () => {
      let list = await db.getSeriesEpisodes(params.seriesId!);
      if (list.length === 0) {
        try {
          const details = await client.details('get_series_info', params.seriesId!);
          const epsObj = details.episodes as Record<string, Array<Record<string, unknown>>> | undefined;
          if (epsObj && typeof epsObj === 'object') {
            const parsed: EpisodeItem[] = [];
            for (const [seasonKey, arr] of Object.entries(epsObj)) {
              if (!Array.isArray(arr)) continue;
              for (const ep of arr) {
                const epId = strField(ep, 'id');
                if (!epId) continue;
                const info = ep.info as Record<string, unknown> | undefined;
                parsed.push({
                  pk: `${params.seriesId}::${epId}`,
                  seriesId: params.seriesId!,
                  seasonKey,
                  episodeId: epId,
                  title: strField(ep, 'title') || `الحلقة ${strField(ep, 'episode_num') || epId}`,
                  imageUrl: strField(info, 'movie_image', 'cover', 'cover_big') || image,
                  extension: strField(ep, 'container_extension') || 'mp4',
                  episodeNumber: strField(ep, 'episode_num'),
                  duration: strField(info, 'duration'),
                });
              }
            }
            if (parsed.length > 0) {
              await db.replaceSeriesEpisodes(params.seriesId!, parsed);
              list = parsed;
            }
          }
        } catch {
          // Ignore error if episodes fetch fails
        }
      }
      if (!active) return;
      list.sort(
        (a, b) =>
          Number(a.seasonKey) - Number(b.seasonKey) ||
          Number(a.episodeNumber) - Number(b.episodeNumber)
      );
      setEpisodes(list);
      const idx = list.findIndex((e) => e.episodeId === currentEpisodeId);
      setEpIndex(idx >= 0 ? idx : 0);
    };
    void loadEps();
    return () => {
      active = false;
    };
  }, [params.seriesId, client, currentEpisodeId, image]);

  // Load channels for channel list modal
  useEffect(() => {
    if (modal !== 'channels') return;
    let active = true;
    db.searchChannels(categoryId, channelQuery, 80, 0).then((list) => {
      if (active) setChannels(list);
    });
    return () => {
      active = false;
    };
  }, [modal, categoryId, channelQuery]);

  // Initialize playback & attach HLS / Video
  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;

    if (hlsRef.current) {
      hlsRef.current.destroy();
      hlsRef.current = null;
    }

    setBuffering(true);
    setErrorMsg('');
    setHlsLevels([]);
    setAudioTracks([]);
    setSubtitleTracks([]);
    resumeAppliedRef.current = false;

    // For live IPTV streams where Xtream defaults to .ts, browsers require HLS (.m3u8)
    let targetRawUrl = currentUrl;
    if (isLive && targetRawUrl.toLowerCase().endsWith('.ts')) {
      targetRawUrl = targetRawUrl.replace(/\.ts$/i, '.m3u8');
    }

    const src = proxiedMediaUrl(targetRawUrl);
    const shouldUseHls = (targetRawUrl.toLowerCase().includes('.m3u8') || isLive) && Hls.isSupported();

    const applySavedPosition = async () => {
      if (resumeAppliedRef.current || isLive) return;
      resumeAppliedRef.current = true;
      let savedMs = 0;
      if (params.markKind === Kinds.MOVIES && params.markId) {
        const mark = await db.getMark(params.markKind, params.markId);
        savedMs = mark?.positionMs ?? 0;
      } else if (params.seriesId && currentEpisodeId) {
        const pt = ResumeStore.seriesPoint(params.seriesId);
        if (pt && pt.episodeId === currentEpisodeId) savedMs = pt.positionMs;
      }
      if (savedMs > 15_000 && videoRef.current) {
        videoRef.current.currentTime = savedMs / 1000;
        showStatus(`تم استئناف التشغيل من ${formatTimeMs(savedMs)}`);
      }
    };

    if (shouldUseHls) {
      const hls = new Hls({
        enableWorker: true,
        lowLatencyMode: isLive,
        backBufferLength: 30,
        maxBufferLength: 30,
      });
      hlsRef.current = hls;
      hls.loadSource(src);
      hls.attachMedia(video);

      hls.on(Hls.Events.MANIFEST_PARSED, (_evt, data) => {
        setHlsLevels(
          data.levels.map((lvl, idx) => ({
            index: idx,
            height: lvl.height || 0,
            bitrate: lvl.bitrate || 0,
          }))
        );
        setSelectedLevel(-1);
        void applySavedPosition();
        video.play().catch(() => {});
      });

      hls.on(Hls.Events.AUDIO_TRACKS_UPDATED, () => {
        setAudioTracks(
          hls.audioTracks.map((t, idx) => ({
            id: idx,
            name: t.name || `مسار ${idx + 1}`,
            lang: t.lang || '',
          }))
        );
        setSelectedAudio(hls.audioTrack);
      });

      hls.on(Hls.Events.SUBTITLE_TRACKS_UPDATED, () => {
        setSubtitleTracks(
          hls.subtitleTracks.map((t, idx) => ({
            id: idx,
            name: t.name || `ترجمة ${idx + 1}`,
            lang: t.lang || '',
          }))
        );
        setSelectedSubtitle(hls.subtitleTrack);
      });

      hls.on(Hls.Events.ERROR, (_evt, data) => {
        if (!data.fatal) return;
        if (data.type === Hls.ErrorTypes.NETWORK_ERROR) {
          hls.startLoad();
        } else if (data.type === Hls.ErrorTypes.MEDIA_ERROR) {
          hls.recoverMediaError();
        } else {
          hls.destroy();
          // Fallback to direct video tag for non-HLS streams
          if (!targetRawUrl.toLowerCase().includes('.m3u8')) {
            video.src = proxiedMediaUrl(currentUrl);
            video.play().catch(() => {});
          } else {
            setBuffering(false);
            setErrorMsg('تعذر تشغيل هذا البث في المتصفح. قد يتطلب تنسيق الفيديو مشغلًا خارجيًا.');
            setModal('error');
          }
        }
      });
    } else {
      video.src = src;
      const onLoadedMeta = () => {
        void applySavedPosition();
      };
      video.addEventListener('loadedmetadata', onLoadedMeta, { once: true });
      video.play().catch(() => {});
    }

    return () => {
      if (hlsRef.current) {
        hlsRef.current.destroy();
        hlsRef.current = null;
      }
    };
  }, [currentUrl, isLive, params.markKind, params.markId, params.seriesId, currentEpisodeId, showStatus]);

  // Periodic progress save
  useEffect(() => {
    const interval = window.setInterval(() => {
      if (playing && !isLive) persistProgress();
    }, 10_000);
    return () => {
      window.clearInterval(interval);
      persistProgress();
    };
  }, [playing, isLive, persistProgress]);

  const playEpisodeAt = (idx: number) => {
    const ep = episodes[idx];
    if (!ep) return;
    persistProgress();
    setEpIndex(idx);
    setCurrentEpisodeId(ep.episodeId);
    setTitle(ep.title);
    if (ep.imageUrl) setImage(ep.imageUrl);
    setCurrentUrl(client.mediaUrl('series', ep.episodeId, ep.extension));
    setEpisodesDrawerOpen(false);
    revealControls();
  };

  const changeChannel = async (dir: number) => {
    const next = await db.channelNeighbor(contentId, categoryId, dir);
    if (!next) {
      showStatus('لا توجد قنوات أخرى في هذا القسم.');
      return;
    }
    setContentId(next.contentId);
    setCategoryId(next.category);
    setTitle(next.name);
    setImage(next.imageUrl);
    setCurrentUrl(client.mediaUrl('live', next.contentId, next.extension));
    revealControls();
  };

  const selectChannelItem = (ch: ContentItem) => {
    setContentId(ch.contentId);
    setCategoryId(ch.category);
    setTitle(ch.name);
    setImage(ch.imageUrl);
    setCurrentUrl(client.mediaUrl('live', ch.contentId, ch.extension));
    setModal('none');
    revealControls();
  };

  const togglePlay = useCallback(() => {
    const video = videoRef.current;
    if (!video) return;
    if (video.paused) {
      video.play().catch(() => {});
    } else {
      video.pause();
    }
    revealControls();
  }, [revealControls]);

  const seekBy = useCallback(
    (deltaSec: number) => {
      const video = videoRef.current;
      if (!video || isLive) return;
      const next = Math.max(0, Math.min(video.duration || 0, (video.currentTime || 0) + deltaSec));
      video.currentTime = next;
      setCurrentTimeSec(next);
      revealControls();
    },
    [isLive, revealControls]
  );

  const toggleFullscreen = useCallback(() => {
    if (!document.fullscreenElement) {
      containerRef.current?.requestFullscreen().catch(() => {});
      setIsFullscreen(true);
    } else {
      document.exitFullscreen().catch(() => {});
      setIsFullscreen(false);
    }
  }, []);

  // Keyboard shortcuts
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (modal === 'channels') {
        if (e.key === 'Escape') setModal('none');
        return;
      }
      if (e.key === 'Escape') {
        if (modal !== 'none') setModal('none');
        else if (episodesDrawerOpen) setEpisodesDrawerOpen(false);
        else {
          persistProgress();
          onClose();
        }
        return;
      }
      if (e.key === ' ' || e.key === 'k') {
        e.preventDefault();
        togglePlay();
      } else if (e.key === 'ArrowRight') {
        seekBy(10);
      } else if (e.key === 'ArrowLeft') {
        seekBy(-10);
      } else if (e.key === 'f') {
        toggleFullscreen();
      } else if (e.key === 'm') {
        const video = videoRef.current;
        if (video) {
          video.muted = !video.muted;
          setMuted(video.muted);
        }
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [modal, episodesDrawerOpen, persistProgress, onClose, togglePlay, seekBy, toggleFullscreen]);

  const hasNextEp = isSeries && epIndex >= 0 && epIndex < episodes.length - 1;
  const hasPrevEp = isSeries && epIndex > 0;
  const showNextEpCard =
    hasNextEp && !isLive && durationSec > 30 && durationSec - currentTimeSec <= 35;

  const resize = RESIZE_MODES[resizeIndex];
  const posterSrc = proxiedImageUrl(image);

  return (
    <div
      ref={containerRef}
      data-player-overlay="true"
      dir="ltr"
      onMouseMove={revealControls}
      className="fixed inset-0 z-50 bg-black text-white select-none overflow-hidden"
    >
      {/* Video Element */}
      <video
        ref={videoRef}
        playsInline
        loop={repeat}
        onClick={() => {
          if (controlsVisible) setControlsVisible(false);
          else revealControls();
        }}
        onPlay={() => {
          setPlaying(true);
          setBuffering(false);
          scheduleHide();
        }}
        onPause={() => {
          setPlaying(false);
          setControlsVisible(true);
        }}
        onWaiting={() => setBuffering(true)}
        onPlaying={() => setBuffering(false)}
        onTimeUpdate={() => {
          const v = videoRef.current;
          if (!v) return;
          setCurrentTimeSec(v.currentTime || 0);
          if (Number.isFinite(v.duration)) setDurationSec(v.duration);
        }}
        onEnded={() => {
          persistProgress(true);
          if (!repeat && hasNextEp) {
            playEpisodeAt(epIndex + 1);
          }
        }}
        onError={() => {
          if (!hlsRef.current) {
            setBuffering(false);
            setErrorMsg('تعذر تشغيل هذا الملف أو البث داخل المتصفح.');
            setModal('error');
          }
        }}
        className={`w-full h-full ${resize.className} bg-black`}
      />

      {/* Buffering Spinner */}
      {buffering && modal !== 'error' && (
        <div className="absolute inset-0 pointer-events-none flex items-center justify-center">
          <div className="w-12 h-12 rounded-full border-4 border-white/20 border-t-[#00D2FF] animate-spin" />
        </div>
      )}

      {/* Status Banner */}
      {statusBanner && (
        <div className="absolute top-20 left-1/2 -translate-x-1/2 z-30 px-4 py-2 rounded-xl bg-black/80 border border-[#00D2FF]/50 text-xs sm:text-sm font-bold text-[#00D2FF] backdrop-blur-md">
          {statusBanner}
        </div>
      )}

      {/* Controls Overlay */}
      <div
        className={`absolute inset-0 z-20 flex flex-col justify-between transition-opacity duration-300 ${
          controlsVisible ? 'opacity-100' : 'opacity-0 pointer-events-none'
        }`}
      >
        {/* Top Bar */}
        <div className="bg-gradient-to-b from-black/90 via-black/55 to-transparent px-4 sm:px-7 pt-4 pb-10 flex items-center justify-between gap-4">
          <div className="flex items-center gap-3 min-w-0">
            <button
              type="button"
              onClick={() => {
                persistProgress();
                onClose();
              }}
              className="w-10 h-10 rounded-full bg-white/10 hover:bg-[#00D2FF] hover:text-[#001018] flex items-center justify-center border border-white/15 transition cursor-pointer shrink-0"
              title="رجوع"
            >
              <ArrowLeft className="w-5 h-5" />
            </button>

            {posterSrc && (
              <img
                src={posterSrc}
                alt={title}
                className="hidden sm:block w-10 h-12 rounded-lg object-cover border border-white/20 shrink-0"
              />
            )}

            <div className="min-w-0 text-left">
              <div className="flex items-center gap-2">
                <span className="text-xs font-extrabold tracking-wider text-[#00D2FF]">ALHUR TV</span>
                <span className="text-white/40">|</span>
                <h2 className="text-sm sm:text-base font-bold text-white truncate">{title}</h2>
              </div>
              <div className="flex items-center gap-2 text-xs text-[#CBD5E1] mt-0.5">
                {isLive && (
                  <span className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded bg-[#FF3131]/20 text-[#FF7B8B] font-bold">
                    <span className="w-2 h-2 rounded-full bg-[#FF3131] animate-pulse" />
                    مباشر
                  </span>
                )}
                {categoryLabel && <span>{categoryLabel}</span>}
                {params.seriesName && <span>{params.seriesName}</span>}
              </div>
            </div>
          </div>

          <div className="flex items-center gap-2 shrink-0">
            <button
              type="button"
              onClick={() => setModal('info')}
              className="w-10 h-10 rounded-full bg-white/10 hover:bg-white/20 flex items-center justify-center border border-white/15 cursor-pointer"
              title="معلومات"
            >
              <Info className="w-5 h-5" />
            </button>

            {(isLive || isSeries) && (
              <button
                type="button"
                onClick={() => {
                  if (isLive) setModal('channels');
                  else setEpisodesDrawerOpen((v) => !v);
                }}
                className="px-3.5 h-10 rounded-full bg-white/10 hover:bg-[#00D2FF] hover:text-[#001018] flex items-center gap-2 border border-white/15 text-xs font-bold transition cursor-pointer"
              >
                <List className="w-4 h-4" />
                <span className="hidden sm:inline">{isLive ? 'القنوات' : 'الحلقات'}</span>
              </button>
            )}
          </div>
        </div>

        {/* Center Action Bar */}
        <div className="flex items-center justify-center gap-5 sm:gap-8">
          {isSeries && (
            <button
              type="button"
              disabled={!hasPrevEp}
              onClick={() => playEpisodeAt(epIndex - 1)}
              className="w-11 h-11 rounded-full bg-black/50 hover:bg-white/20 disabled:opacity-30 flex items-center justify-center border border-white/15 cursor-pointer"
              title="الحلقة السابقة"
            >
              <SkipBack className="w-5 h-5" />
            </button>
          )}

          {!isLive && (
            <button
              type="button"
              onClick={() => seekBy(-10)}
              className="w-12 h-12 rounded-full bg-black/55 hover:bg-white/20 flex items-center justify-center border border-white/15 cursor-pointer"
              title="-10 ثوانٍ"
            >
              <RotateCcw className="w-5 h-5" />
            </button>
          )}

          <button
            type="button"
            onClick={togglePlay}
            className="w-16 h-16 rounded-full bg-[#00D2FF] hover:bg-[#36D5FF] text-[#001018] flex items-center justify-center shadow-[0_0_28px_rgba(0,210,255,0.45)] transition transform hover:scale-105 cursor-pointer"
            title={playing ? 'إيقاف مؤقت' : 'تشغيل'}
          >
            {playing ? <Pause className="w-7 h-7 fill-current" /> : <Play className="w-7 h-7 fill-current ml-0.5" />}
          </button>

          {!isLive && (
            <button
              type="button"
              onClick={() => seekBy(10)}
              className="w-12 h-12 rounded-full bg-black/55 hover:bg-white/20 flex items-center justify-center border border-white/15 cursor-pointer"
              title="+10 ثوانٍ"
            >
              <RotateCw className="w-5 h-5" />
            </button>
          )}

          {isSeries && (
            <button
              type="button"
              disabled={!hasNextEp}
              onClick={() => playEpisodeAt(epIndex + 1)}
              className="w-11 h-11 rounded-full bg-black/50 hover:bg-white/20 disabled:opacity-30 flex items-center justify-center border border-white/15 cursor-pointer"
              title="الحلقة التالية"
            >
              <SkipForward className="w-5 h-5" />
            </button>
          )}
        </div>

        {/* Bottom Area */}
        <div className="bg-gradient-to-t from-black/95 via-black/75 to-transparent px-4 sm:px-7 pt-10 pb-4 space-y-3">
          {/* Next Episode Prompt Card */}
          {showNextEpCard && (
            <div className="flex justify-end">
              <button
                type="button"
                onClick={() => playEpisodeAt(epIndex + 1)}
                className="px-4 py-2.5 rounded-xl bg-[#131722]/95 border border-[#00D2FF] hover:bg-[#00D2FF] hover:text-[#001018] text-xs sm:text-sm font-bold flex items-center gap-2 shadow-xl transition cursor-pointer"
              >
                <SkipForward className="w-4 h-4" />
                <span>الحلقة التالية: {episodes[epIndex + 1]?.title}</span>
              </button>
            </div>
          )}

          {/* Episodes Drawer */}
          {episodesDrawerOpen && isSeries && (
            <div className="p-3 rounded-2xl bg-[#131722]/95 border border-[#253048] backdrop-blur-md space-y-2">
              <div className="flex items-center justify-between px-1">
                <span className="text-xs font-bold text-[#00D2FF]">حلقات {params.seriesName}</span>
                <button
                  type="button"
                  onClick={() => setEpisodesDrawerOpen(false)}
                  className="text-slate-400 hover:text-white cursor-pointer"
                >
                  <X className="w-4 h-4" />
                </button>
              </div>
              <div className="flex items-center gap-2.5 overflow-x-auto no-scrollbar pb-1" dir="rtl">
                {episodes.map((ep, idx) => {
                  const active = idx === epIndex;
                  return (
                    <button
                      key={ep.pk}
                      type="button"
                      onClick={() => playEpisodeAt(idx)}
                      className={`shrink-0 w-44 rounded-xl p-2 text-right border transition cursor-pointer ${
                        active
                          ? 'bg-[#00D2FF]/20 border-[#00D2FF] text-white'
                          : 'bg-[#1C2230] border-[#253048] text-slate-300 hover:border-white/40'
                      }`}
                    >
                      <div className="text-[11px] font-bold text-[#00D2FF]">
                        م{ep.seasonKey} · ح{ep.episodeNumber || idx + 1}
                      </div>
                      <div className="text-xs font-bold truncate mt-0.5">{ep.title}</div>
                    </button>
                  );
                })}
              </div>
            </div>
          )}

          {/* Seek Timeline (VOD) */}
          {!isLive && (
            <div className="flex items-center gap-3">
              <span className="text-xs font-mono text-slate-300 w-14 text-right">
                {formatTimeMs(currentTimeSec * 1000)}
              </span>
              <input
                type="range"
                min={0}
                max={durationSec > 0 ? durationSec : 100}
                step={1}
                value={Math.min(currentTimeSec, durationSec || 100)}
                onChange={(e) => {
                  const val = Number(e.target.value);
                  setCurrentTimeSec(val);
                  if (videoRef.current) videoRef.current.currentTime = val;
                }}
                className="flex-1 accent-[#E50914] h-1.5 cursor-pointer"
              />
              <span className="text-xs font-mono text-slate-400 w-14">
                {durationSec > 0 ? formatTimeMs(durationSec * 1000) : '--:--'}
              </span>
            </div>
          )}

          {/* Tools Row */}
          <div className="flex flex-wrap items-center justify-between gap-2">
            {/* Live switcher or Volume */}
            <div className="flex items-center gap-2">
              {isLive && (
                <div className="flex items-center gap-1.5 mr-2">
                  <button
                    type="button"
                    onClick={() => void changeChannel(-1)}
                    className="px-3 py-1.5 rounded-lg bg-[#1C2230] hover:bg-[#253048] text-xs font-bold border border-[#253048] cursor-pointer"
                  >
                    السابق
                  </button>
                  <button
                    type="button"
                    onClick={() => setModal('channels')}
                    className="px-3 py-1.5 rounded-lg bg-[#00D2FF]/20 hover:bg-[#00D2FF]/35 text-[#00D2FF] text-xs font-bold border border-[#00D2FF]/40 cursor-pointer"
                  >
                    قائمة القنوات
                  </button>
                  <button
                    type="button"
                    onClick={() => void changeChannel(1)}
                    className="px-3 py-1.5 rounded-lg bg-[#1C2230] hover:bg-[#253048] text-xs font-bold border border-[#253048] cursor-pointer"
                  >
                    التالي
                  </button>
                </div>
              )}

              <button
                type="button"
                onClick={() => {
                  const v = videoRef.current;
                  if (!v) return;
                  v.muted = !v.muted;
                  setMuted(v.muted);
                }}
                className="p-2 rounded-lg bg-white/10 hover:bg-white/20 cursor-pointer"
                title="كتم الصوت"
              >
                {muted || volume === 0 ? <VolumeX className="w-4 h-4" /> : <Volume2 className="w-4 h-4" />}
              </button>
              <input
                type="range"
                min={0}
                max={1}
                step={0.05}
                value={muted ? 0 : volume}
                onChange={(e) => {
                  const val = Number(e.target.value);
                  setVolume(val);
                  if (videoRef.current) {
                    videoRef.current.volume = val;
                    videoRef.current.muted = val === 0;
                    setMuted(val === 0);
                  }
                }}
                className="w-20 sm:w-24 accent-[#E50914] h-1 cursor-pointer"
              />
            </div>

            {/* Playback Tools */}
            <div className="flex flex-wrap items-center gap-1.5">
              <button
                type="button"
                onClick={() => setModal('audio')}
                className="px-2.5 py-1.5 rounded-lg bg-white/10 hover:bg-white/20 text-xs font-semibold flex items-center gap-1.5 cursor-pointer"
              >
                <Sliders className="w-3.5 h-3.5 text-[#00D2FF]" />
                <span>الصوت</span>
              </button>

              <button
                type="button"
                onClick={() => setModal('subtitles')}
                className="px-2.5 py-1.5 rounded-lg bg-white/10 hover:bg-white/20 text-xs font-semibold flex items-center gap-1.5 cursor-pointer"
              >
                <Captions className="w-3.5 h-3.5 text-[#00D2FF]" />
                <span>الترجمة</span>
              </button>

              <button
                type="button"
                onClick={() => setModal('quality')}
                className="px-2.5 py-1.5 rounded-lg bg-white/10 hover:bg-white/20 text-xs font-semibold flex items-center gap-1.5 cursor-pointer"
              >
                <Settings className="w-3.5 h-3.5 text-[#00D2FF]" />
                <span>
                  {selectedLevel >= 0 && hlsLevels[selectedLevel]
                    ? `${hlsLevels[selectedLevel].height}p`
                    : 'الجودة'}
                </span>
              </button>

              {!isLive && (
                <button
                  type="button"
                  onClick={() => setModal('speed')}
                  className="px-2.5 py-1.5 rounded-lg bg-white/10 hover:bg-white/20 text-xs font-semibold flex items-center gap-1.5 cursor-pointer"
                >
                  <Gauge className="w-3.5 h-3.5 text-[#00D2FF]" />
                  <span>{speed}x</span>
                </button>
              )}

              <button
                type="button"
                onClick={() => {
                  const next = (resizeIndex + 1) % RESIZE_MODES.length;
                  setResizeIndex(next);
                  showStatus(RESIZE_MODES[next].label);
                }}
                className="px-2.5 py-1.5 rounded-lg bg-white/10 hover:bg-white/20 text-xs font-semibold cursor-pointer"
              >
                {resize.short}
              </button>

              {!isLive && (
                <button
                  type="button"
                  onClick={() => {
                    setRepeat((r) => !r);
                    showStatus(!repeat ? 'تم تفعيل التكرار' : 'تم إيقاف التكرار');
                  }}
                  className={`p-2 rounded-lg border transition cursor-pointer ${
                    repeat
                      ? 'bg-[#00D2FF]/25 border-[#00D2FF] text-[#00D2FF]'
                      : 'bg-white/10 border-transparent hover:bg-white/20 text-white'
                  }`}
                  title="تكرار"
                >
                  <Repeat className="w-3.5 h-3.5" />
                </button>
              )}

              <button
                type="button"
                onClick={toggleFullscreen}
                className="p-2 rounded-lg bg-white/10 hover:bg-white/20 cursor-pointer"
                title="ملء الشاشة"
              >
                {isFullscreen ? <Minimize className="w-4 h-4" /> : <Maximize className="w-4 h-4" />}
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* Picker / Info / Error Modals */}
      {modal !== 'none' && (
        <div
          dir="rtl"
          className="fixed inset-0 z-40 flex items-center justify-center bg-black/70 backdrop-blur-sm p-4"
          onClick={() => modal !== 'error' && setModal('none')}
        >
          <div
            onClick={(e) => e.stopPropagation()}
            className="w-full max-w-md rounded-2xl bg-[#131722] border border-[#253048] shadow-2xl overflow-hidden flex flex-col max-h-[80vh]"
          >
            <div className="flex items-center justify-between px-5 py-3.5 border-b border-[#253048] bg-[#0F131E]">
              <h3 className="text-sm font-extrabold text-white">
                {modal === 'channels'
                  ? 'قائمة القنوات'
                  : modal === 'audio'
                  ? 'مسارات الصوت'
                  : modal === 'subtitles'
                  ? 'الترجمة'
                  : modal === 'quality'
                  ? 'جودة البث'
                  : modal === 'speed'
                  ? 'سرعة التشغيل'
                  : modal === 'info'
                  ? 'معلومات التشغيل'
                  : 'تعذر التشغيل'}
              </h3>
              <button
                type="button"
                onClick={() => setModal('none')}
                className="text-slate-400 hover:text-white cursor-pointer"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="p-4 overflow-y-auto space-y-2">
              {modal === 'channels' && (
                <>
                  <div className="relative mb-2">
                    <Search className="w-4 h-4 text-[#A0AEC0] absolute right-3 top-1/2 -translate-y-1/2" />
                    <input
                      type="text"
                      value={channelQuery}
                      onChange={(e) => setChannelQuery(e.target.value)}
                      placeholder="ابحث عن قناة…"
                      className="w-full rounded-xl bg-[#1C2230] border border-[#253048] focus:border-[#00D2FF] pr-9 pl-3 py-2 text-sm text-white outline-none"
                      autoFocus
                    />
                  </div>
                  {channels.length === 0 ? (
                    <div className="py-8 text-center text-xs text-[#A0AEC0]">لا توجد قنوات.</div>
                  ) : (
                    channels.map((ch) => (
                      <button
                        key={ch.pk}
                        type="button"
                        onClick={() => selectChannelItem(ch)}
                        className={`w-full flex items-center justify-between px-3.5 py-2.5 rounded-xl border text-right transition cursor-pointer ${
                          ch.contentId === contentId
                            ? 'bg-[#00D2FF]/20 border-[#00D2FF] text-white'
                            : 'bg-[#1C2230] border-[#253048] text-slate-200 hover:border-[#00D2FF]/50'
                        }`}
                      >
                        <span className="text-sm font-bold truncate">{ch.name}</span>
                        {ch.contentId === contentId && <Check className="w-4 h-4 text-[#00D2FF]" />}
                      </button>
                    ))
                  )}
                </>
              )}

              {modal === 'audio' && (
                audioTracks.length === 0 ? (
                  <p className="text-xs text-[#A0AEC0] py-4 text-center">المسار الافتراضي قيد التشغيل.</p>
                ) : (
                  audioTracks.map((t) => (
                    <button
                      key={t.id}
                      type="button"
                      onClick={() => {
                        if (hlsRef.current) hlsRef.current.audioTrack = t.id;
                        setSelectedAudio(t.id);
                        setModal('none');
                      }}
                      className={`w-full flex items-center justify-between px-4 py-2.5 rounded-xl border text-sm font-bold cursor-pointer ${
                        selectedAudio === t.id
                          ? 'bg-[#00D2FF]/20 border-[#00D2FF] text-white'
                          : 'bg-[#1C2230] border-[#253048] text-slate-200'
                      }`}
                    >
                      <span>{t.name} {t.lang ? `(${t.lang})` : ''}</span>
                      {selectedAudio === t.id && <Check className="w-4 h-4 text-[#00D2FF]" />}
                    </button>
                  ))
                )
              )}

              {modal === 'subtitles' && (
                <>
                  <button
                    type="button"
                    onClick={() => {
                      if (hlsRef.current) hlsRef.current.subtitleTrack = -1;
                      setSelectedSubtitle(-1);
                      setModal('none');
                    }}
                    className={`w-full flex items-center justify-between px-4 py-2.5 rounded-xl border text-sm font-bold cursor-pointer ${
                      selectedSubtitle === -1
                        ? 'bg-[#00D2FF]/20 border-[#00D2FF] text-white'
                        : 'bg-[#1C2230] border-[#253048] text-slate-200'
                    }`}
                  >
                    <span>إيقاف الترجمة</span>
                    {selectedSubtitle === -1 && <Check className="w-4 h-4 text-[#00D2FF]" />}
                  </button>
                  {subtitleTracks.map((t) => (
                    <button
                      key={t.id}
                      type="button"
                      onClick={() => {
                        if (hlsRef.current) hlsRef.current.subtitleTrack = t.id;
                        setSelectedSubtitle(t.id);
                        setModal('none');
                      }}
                      className={`w-full flex items-center justify-between px-4 py-2.5 rounded-xl border text-sm font-bold cursor-pointer ${
                        selectedSubtitle === t.id
                          ? 'bg-[#00D2FF]/20 border-[#00D2FF] text-white'
                          : 'bg-[#1C2230] border-[#253048] text-slate-200'
                      }`}
                    >
                      <span>{t.name} {t.lang ? `(${t.lang})` : ''}</span>
                      {selectedSubtitle === t.id && <Check className="w-4 h-4 text-[#00D2FF]" />}
                    </button>
                  ))}
                </>
              )}

              {modal === 'quality' && (
                <>
                  <button
                    type="button"
                    onClick={() => {
                      if (hlsRef.current) hlsRef.current.currentLevel = -1;
                      setSelectedLevel(-1);
                      setModal('none');
                    }}
                    className={`w-full flex items-center justify-between px-4 py-2.5 rounded-xl border text-sm font-bold cursor-pointer ${
                      selectedLevel === -1
                        ? 'bg-[#00D2FF]/20 border-[#00D2FF] text-white'
                        : 'bg-[#1C2230] border-[#253048] text-slate-200'
                    }`}
                  >
                    <span>تلقائي (Auto)</span>
                    {selectedLevel === -1 && <Check className="w-4 h-4 text-[#00D2FF]" />}
                  </button>
                  {hlsLevels.map((lvl) => (
                    <button
                      key={lvl.index}
                      type="button"
                      onClick={() => {
                        if (hlsRef.current) hlsRef.current.currentLevel = lvl.index;
                        setSelectedLevel(lvl.index);
                        setModal('none');
                      }}
                      className={`w-full flex items-center justify-between px-4 py-2.5 rounded-xl border text-sm font-bold cursor-pointer ${
                        selectedLevel === lvl.index
                          ? 'bg-[#00D2FF]/20 border-[#00D2FF] text-white'
                          : 'bg-[#1C2230] border-[#253048] text-slate-200'
                      }`}
                    >
                      <span>
                        {lvl.height ? `${lvl.height}p` : `جودة ${lvl.index + 1}`}{' '}
                        {lvl.bitrate ? `· ${(lvl.bitrate / 1_000_000).toFixed(1)} Mbps` : ''}
                      </span>
                      {selectedLevel === lvl.index && <Check className="w-4 h-4 text-[#00D2FF]" />}
                    </button>
                  ))}
                </>
              )}

              {modal === 'speed' &&
                SPEEDS.map((s) => (
                  <button
                    key={s}
                    type="button"
                    onClick={() => {
                      setSpeed(s);
                      if (videoRef.current) videoRef.current.playbackRate = s;
                      setModal('none');
                    }}
                    className={`w-full flex items-center justify-between px-4 py-2.5 rounded-xl border text-sm font-bold cursor-pointer ${
                      speed === s
                        ? 'bg-[#00D2FF]/20 border-[#00D2FF] text-white'
                        : 'bg-[#1C2230] border-[#253048] text-slate-200'
                    }`}
                  >
                    <span>{s}x</span>
                    {speed === s && <Check className="w-4 h-4 text-[#00D2FF]" />}
                  </button>
                ))}

              {modal === 'info' && (
                <div className="space-y-2 text-xs text-slate-300">
                  <div className="p-3 rounded-xl bg-[#1C2230] border border-[#253048]">
                    <div className="text-[#A0AEC0]">العنوان</div>
                    <div className="text-sm font-bold text-white mt-0.5">{title}</div>
                  </div>
                  <div className="p-3 rounded-xl bg-[#1C2230] border border-[#253048] flex justify-between">
                    <span className="text-[#A0AEC0]">النوع</span>
                    <span className="font-bold text-white">
                      {isLive ? 'بث مباشر' : isSeries ? 'مسلسل' : 'فيلم'}
                    </span>
                  </div>
                  <div className="p-3 rounded-xl bg-[#1C2230] border border-[#253048] flex justify-between">
                    <span className="text-[#A0AEC0]">الأبعاد</span>
                    <span className="font-bold text-white">{resize.label}</span>
                  </div>
                </div>
              )}

              {modal === 'error' && (
                <div className="space-y-4">
                  <p className="text-sm text-[#FF7B8B] font-bold">{errorMsg}</p>
                  {PlayerOptions.offerAlternateOnError() && (
                    <div className="p-3 rounded-xl bg-[#1C2230] border border-[#253048] space-y-2">
                      <p className="text-xs text-[#A0AEC0]">
                        يمكنك نسخ رابط البث وفتحه في مشغّل خارجي مثل VLC:
                      </p>
                      <div className="flex items-center gap-2">
                        <input
                          type="text"
                          readOnly
                          dir="ltr"
                          value={currentUrl}
                          className="flex-1 bg-[#0A0C10] border border-[#253048] rounded-lg px-2.5 py-1.5 text-xs text-slate-300 font-mono outline-none"
                        />
                        <button
                          type="button"
                          onClick={() => {
                            navigator.clipboard?.writeText(currentUrl).catch(() => {});
                            setCopiedUrl(true);
                            onToast?.('تم نسخ رابط البث');
                          }}
                          className="px-3 py-1.5 rounded-lg bg-[#00D2FF]/20 text-[#00D2FF] text-xs font-bold flex items-center gap-1 cursor-pointer"
                        >
                          <Copy className="w-3.5 h-3.5" />
                          <span>{copiedUrl ? 'تم' : 'نسخ'}</span>
                        </button>
                      </div>
                    </div>
                  )}
                  <div className="flex items-center justify-end gap-2">
                    <button
                      type="button"
                      onClick={() => {
                        setModal('none');
                        setCurrentUrl((u) => (u.includes('?') ? `${u}&retry=1` : `${u}?retry=1`));
                      }}
                      className="px-4 py-2 rounded-xl bg-[#00D2FF] text-[#001018] text-xs font-extrabold flex items-center gap-1.5 cursor-pointer"
                    >
                      <RefreshCw className="w-3.5 h-3.5" />
                      <span>إعادة المحاولة</span>
                    </button>
                    <button
                      type="button"
                      onClick={onClose}
                      className="px-4 py-2 rounded-xl bg-[#1C2230] text-white text-xs font-bold cursor-pointer"
                    >
                      إغلاق
                    </button>
                  </div>
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
