import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  ChevronLeft,
  Clock,
  Film,
  Grid,
  Heart,
  LogOut,
  Search,
  Settings,
  ShieldAlert,
  Tv,
  Clapperboard,
  X,
} from 'lucide-react';
import { ContentItem, Kinds, kindAccent, SavedAccount, ScreenRoute, UserInfo } from '../types';
import { SyncManager, XtreamClient } from '../api/xtream';
import { isFreeServer } from '../api/freeCatalog';
import { db } from '../storage/db';
import { AdultStore } from '../storage/adultStore';
import { Rating } from '../utils/arabic';
import { FeaturedBanner } from '../components/FeaturedBanner';
import { PosterCard } from '../components/PosterCard';
import { AdultAgeGateModal } from '../components/AdultAgeGateModal';

interface HomeScreenProps {
  account: SavedAccount;
  userInfo: UserInfo | null;
  client: XtreamClient;
  onUpdateUserInfo: (info: UserInfo) => void;
  onNavigate: (route: ScreenRoute) => void;
  onPlayLive: (item: ContentItem) => void;
  onPlayMovie: (item: ContentItem) => void;
  onLogout: () => void;
}

export const HomeScreen: React.FC<HomeScreenProps> = ({
  account,
  userInfo,
  client,
  onUpdateUserInfo,
  onNavigate,
  onPlayLive,
  onPlayMovie,
  onLogout,
}) => {
  const [trending, setTrending] = useState<ContentItem[]>([]);
  const [popularMovies, setPopularMovies] = useState<ContentItem[]>([]);
  const [latestMovies, setLatestMovies] = useState<ContentItem[]>([]);
  const [popularSeries, setPopularSeries] = useState<ContentItem[]>([]);
  const [latestSeries, setLatestSeries] = useState<ContentItem[]>([]);
  const [favKeys, setFavKeys] = useState<Set<string>>(new Set());
  const [resumeCount, setResumeCount] = useState(0);
  const [syncRunning, setSyncRunning] = useState(false);

  const [catPickerOpen, setCatPickerOpen] = useState(false);
  const [logoutModalOpen, setLogoutModalOpen] = useState(false);
  const [adultGateOpen, setAdultGateOpen] = useState(false);
  const [adultEnabled] = useState(() => AdultStore.isEnabled());
  const [catBusyKind, setCatBusyKind] = useState<string | null>(null);
  const [catErrorKind, setCatErrorKind] = useState<string | null>(null);

  const popularMoviesRef = useRef<ContentItem[]>([]);
  const popularSeriesRef = useRef<ContentItem[]>([]);
  const lastRefreshRef = useRef(0);

  const loadDashboard = useCallback(async () => {
    const [
      latestMov,
      latestSer,
      trendMov,
      trendSer,
      resumeItems,
      favSet,
    ] = await Promise.all([
      db.latest(Kinds.MOVIES, 20),
      db.latest(Kinds.SERIES, 20),
      db.trending(Kinds.MOVIES, 10),
      db.trending(Kinds.SERIES, 10),
      db.continueItems(),
      db.favoriteKeys(),
    ]);

    const popMov =
      popularMoviesRef.current.length > 0
        ? popularMoviesRef.current
        : await db.randomPosters(Kinds.MOVIES, 20);
    if (popMov.length > 0) popularMoviesRef.current = popMov;

    const popSer =
      popularSeriesRef.current.length > 0
        ? popularSeriesRef.current
        : await db.randomPosters(Kinds.SERIES, 20);
    if (popSer.length > 0) popularSeriesRef.current = popSer;

    const mergedTrend = [...trendMov, ...trendSer]
      .sort((a, b) => Rating.value(b.rating) - Rating.value(a.rating))
      .slice(0, 10);

    setLatestMovies(latestMov);
    setLatestSeries(latestSer);
    setPopularMovies(popMov);
    setPopularSeries(popSer);
    setTrending(mergedTrend);
    setResumeCount(resumeItems.length);
    setFavKeys(favSet);
  }, []);

  useEffect(() => {
    void loadDashboard();
    SyncManager.ensureAll(account.server, account.username, account.password);

    if (!userInfo) {
      client.account().then((res) => {
        if (res?.user_info) onUpdateUserInfo(res.user_info);
      });
    }

    const checkRunning = () => Kinds.CONTENT.some((k) => SyncManager.isRunning(k));
    setSyncRunning(checkRunning());

    const unsub = SyncManager.add({
      onSyncState(_kind, running, loaded) {
        setSyncRunning(running || checkRunning());
        const now = Date.now();
        if (!running || (loaded >= 40 && now - lastRefreshRef.current > 3500)) {
          lastRefreshRef.current = now;
          void loadDashboard();
        }
      },
    });
    return unsub;
  }, [account, client, loadDashboard, onUpdateUserInfo, userInfo]);

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

  const watchNowBanner = (item: ContentItem) => {
    if (item.kind === Kinds.MOVIES) onPlayMovie(item);
    else openItem(item);
  };

  const handleToggleFav = async (item: ContentItem) => {
    await db.toggleFavorite(item);
    setFavKeys(await db.favoriteKeys());
  };

  const openBrowse = async (kind: string, opts?: { focusSearch?: boolean; openCategories?: boolean }) => {
    if (kind !== Kinds.ALL && !(Kinds.CONTENT as readonly string[]).includes(kind)) {
      onNavigate({ name: 'browse', kind, ...opts });
      return;
    }
    const needed = kind === Kinds.ALL ? Kinds.CONTENT : [kind];
    const cached = await SyncManager.categoriesCached(needed);
    if (cached) {
      onNavigate({ name: 'browse', kind, ...opts });
      return;
    }

    setCatBusyKind(kind);
    const ok = await SyncManager.ensureCategories(account.server, account.username, account.password, needed);
    setCatBusyKind(null);
    if (ok) {
      onNavigate({ name: 'browse', kind, ...opts });
    } else {
      setCatErrorKind(kind);
    }
  };

  // Account chips
  const isFree = isFreeServer(account.server);
  const rawStatus = String(userInfo?.status ?? '').trim().toLowerCase();
  const statusLabel = isFree
    ? '● مجاني نشط'
    : rawStatus === 'active'
    ? '● نشط'
    : rawStatus === 'expired'
    ? 'منتهي'
    : rawStatus || 'غير محدد';
  const expSec = Number(userInfo?.exp_date ?? 0);
  const expiryLabel = isFree
    ? 'مفتوح (مجاني)'
    : expSec > 0
    ? new Date(expSec * 1000).toISOString().slice(0, 10)
    : 'غير محدد';
  const maxCons = userInfo?.max_connections ? String(userInfo.max_connections) : '';
  const activeCons = userInfo?.active_cons ? String(userInfo.active_cons) : '0';
  const devicesLabel = isFree ? 'مجاني' : maxCons ? `${activeCons} / ${maxCons}` : '—';

  const navEntries = [
    {
      label: 'البث المباشر',
      sub: 'شاهد القنوات المباشرة',
      icon: Tv,
      accent: '#2FB4FF',
      onClick: () => void openBrowse(Kinds.LIVE),
    },
    {
      label: 'الأفلام',
      sub: 'مكتبة الأفلام',
      icon: Film,
      accent: '#36D5FF',
      onClick: () => void openBrowse(Kinds.MOVIES),
    },
    {
      label: 'المسلسلات',
      sub: 'مسلسلات تلفزيونية',
      icon: Clapperboard,
      accent: '#B05CFF',
      onClick: () => void openBrowse(Kinds.SERIES),
    },
    {
      label: 'متابعة المشاهدة',
      sub: resumeCount > 0 ? `${resumeCount} عنصر لإكماله` : 'أكمل من حيث توقفت',
      icon: Clock,
      accent: '#25D9BD',
      onClick: () => void openBrowse(Kinds.CONTINUE),
    },
    {
      label: 'التصنيفات',
      sub: 'تصفح حسب الفئات',
      icon: Grid,
      accent: '#FFB800',
      onClick: () => setCatPickerOpen(true),
    },
    {
      label: 'المفضلة',
      sub: favKeys.size > 0 ? `${favKeys.size} عنصر` : 'محتواك المفضل',
      icon: Heart,
      accent: '#E8384F',
      onClick: () => void openBrowse(Kinds.FAVORITES),
    },
    ...(adultEnabled
      ? [
          {
            label: 'Adult 18+',
            sub: 'قنوات للبالغين فقط (+18)',
            icon: ShieldAlert,
            accent: '#E8384F',
            onClick: () => setAdultGateOpen(true),
          },
        ]
      : []),
    {
      label: 'الإعدادات',
      sub: 'الحساب والمشغلات',
      icon: Settings,
      accent: '#A0AEC0',
      onClick: () => onNavigate({ name: 'settings' }),
    },
  ];

  const rails = [
    { title: 'أفلام رائجة ★', kind: Kinds.MOVIES, items: popularMovies },
    { title: 'أفلام جديدة', kind: Kinds.MOVIES, items: latestMovies },
    { title: 'مسلسلات رائجة ★', kind: Kinds.SERIES, items: popularSeries },
    { title: 'مسلسلات جديدة', kind: Kinds.SERIES, items: latestSeries },
  ];

  return (
    <div dir="rtl" className="min-h-screen w-full bg-hex-space flex flex-row gap-3 sm:gap-4 p-3 sm:p-4">
      {/* Permanent Right Sidebar (RTL Landscape) */}
      <aside className="w-[152px] sm:w-[192px] lg:w-[215px] shrink-0 rounded-2xl bg-[#131722]/78 backdrop-blur-sm border border-[#253048]/80 p-2.5 sm:p-3 flex flex-col h-[calc(100vh-1.5rem)] sm:h-[calc(100vh-2rem)] sticky top-3 sm:top-4">
        <div className="flex flex-col items-center justify-center gap-2 pb-3 border-b border-[#253048]/70">
          <img
            src="/assets/logo_alhorr_login.png"
            alt="الحر الأصلي"
            className="w-14 h-14 sm:w-18 sm:h-18 lg:w-20 lg:h-20 object-contain"
          />
          <span className="text-[11px] sm:text-xs font-extrabold tracking-widest text-[#00D2FF]">
            ALHUR TV
          </span>
        </div>

        <nav className="mt-2.5 flex flex-col gap-1.5 sm:gap-2 overflow-y-auto no-scrollbar flex-1">
          {navEntries.map((entry, idx) => (
            <button
              key={entry.label}
              type="button"
              data-tv-autofocus={idx === 0 ? 'true' : undefined}
              onClick={entry.onClick}
              className="w-full flex items-center gap-2 sm:gap-2.5 p-2 rounded-xl bg-[#1C2230] hover:bg-[#232B3D] border border-transparent hover:border-[#00D2FF] transition text-right cursor-pointer group focus:outline-none focus-visible:ring-2 focus-visible:ring-[#00D2FF]"
            >
              <div
                className="w-7 h-7 sm:w-8 sm:h-8 rounded-full flex items-center justify-center shrink-0 border"
                style={{
                  backgroundColor: `${entry.accent}24`,
                  borderColor: `${entry.accent}66`,
                  color: entry.accent,
                }}
              >
                <entry.icon className="w-3.5 h-3.5 sm:w-4 sm:h-4" />
              </div>
              <div className="min-w-0">
                <div className="text-xs sm:text-[13px] font-extrabold text-white group-hover:text-[#00D2FF] truncate">
                  {entry.label}
                </div>
                <div className="hidden sm:block text-[10px] text-[#A0AEC0] truncate">{entry.sub}</div>
              </div>
            </button>
          ))}
        </nav>
      </aside>

      {/* Main Column */}
      <main className="flex-1 min-w-0 flex flex-col gap-3 sm:gap-4">
        {/* Top Toolbar (Subscription info + actions) */}
        <header className="rounded-2xl bg-[#131722]/78 backdrop-blur-sm border border-[#253048]/80 px-3 sm:px-4 py-2.5 flex items-center justify-between gap-2.5 sm:gap-3">
          {/* Account Info Chips */}
          <div className="flex-1 min-w-[200px] grid grid-cols-3 rounded-xl bg-[#1C2230] border border-[#253048]/70 divide-x divide-x-reverse divide-[#253048] py-1.5 text-center">
            <div className="px-2">
              <div className="text-[10px] text-[#A0AEC0]">الحالة</div>
              <div
                className={`text-xs font-extrabold mt-0.5 ${
                  isFree || rawStatus === 'active' ? 'text-[#25D9BD]' : 'text-slate-300'
                }`}
              >
                {statusLabel}
              </div>
            </div>
            <div className="px-2">
              <div className="text-[10px] text-[#A0AEC0]">الانتهاء</div>
              <div dir="ltr" className="text-xs font-extrabold text-white mt-0.5">
                {expiryLabel}
              </div>
            </div>
            <div className="px-2">
              <div className="text-[10px] text-[#A0AEC0]">الأجهزة</div>
              <div dir="ltr" className="text-xs font-extrabold text-white mt-0.5">
                {devicesLabel}
              </div>
            </div>
          </div>

          {/* Action Buttons */}
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => void openBrowse(Kinds.ALL, { focusSearch: true })}
              className="px-3.5 py-2 rounded-xl bg-[#1C2230] hover:bg-[#253048] border border-[#253048] hover:border-[#00D2FF] text-xs font-bold text-white flex items-center gap-1.5 transition cursor-pointer"
            >
              <Search className="w-4 h-4 text-[#00D2FF]" />
              <span>البحث</span>
            </button>
            <button
              type="button"
              onClick={() => onNavigate({ name: 'settings' })}
              className="px-3.5 py-2 rounded-xl bg-[#1C2230] hover:bg-[#253048] border border-[#253048] hover:border-[#00D2FF] text-xs font-bold text-white flex items-center gap-1.5 transition cursor-pointer"
            >
              <Settings className="w-4 h-4 text-[#A0AEC0]" />
              <span>الإعدادات</span>
            </button>
            <button
              type="button"
              onClick={() => setLogoutModalOpen(true)}
              className="px-3.5 py-2 rounded-xl bg-[#1C2230] hover:bg-[#E8384F]/20 border border-[#253048] hover:border-[#E8384F] text-xs font-bold text-[#FF7B8B] flex items-center gap-1.5 transition cursor-pointer"
            >
              <LogOut className="w-4 h-4" />
              <span>خروج</span>
            </button>
          </div>
        </header>

        {/* Background sync indicator */}
        {syncRunning && (
          <div className="text-center text-xs font-bold text-[#25D9BD] py-1">
            ⟳ جارٍ تحديث المكتبة في الخلفية…
          </div>
        )}

        {/* Featured Banner */}
        {trending.length > 0 && (
          <FeaturedBanner
            items={trending}
            client={client}
            onWatch={watchNowBanner}
            onDetails={openItem}
          />
        )}

        {/* Content Rails */}
        {rails.map(
          (rail) =>
            rail.items.length > 0 && (
              <section
                key={rail.title}
                className="rounded-2xl bg-[#131722]/78 backdrop-blur-sm border border-[#253048]/80 p-3.5 sm:p-4 space-y-3"
              >
                <div className="flex items-center justify-between">
                  <h3 className="text-base sm:text-lg font-extrabold text-white">{rail.title}</h3>
                  <button
                    type="button"
                    onClick={() => void openBrowse(rail.kind)}
                    className="flex items-center gap-1 text-xs font-bold hover:underline cursor-pointer"
                    style={{ color: kindAccent(rail.kind) }}
                  >
                    <span>عرض الكل</span>
                    <ChevronLeft className="w-4 h-4" />
                  </button>
                </div>

                <div className="flex items-stretch gap-3 overflow-x-auto no-scrollbar pb-1">
                  {rail.items.map((item) => (
                    <PosterCard
                      key={item.pk}
                      item={item}
                      variant="rail"
                      showBadge
                      isFavorite={favKeys.has(`${item.kind}:${item.contentId}`)}
                      onOpen={openItem}
                      onToggleFavorite={(it) => void handleToggleFav(it)}
                    />
                  ))}
                </div>
              </section>
            )
        )}

        {/* Empty initial hint while syncing */}
        {trending.length === 0 &&
          latestMovies.length === 0 &&
          latestSeries.length === 0 && (
            <div className="rounded-2xl bg-[#131722] border border-[#253048] p-10 text-center space-y-3">
              <p className="text-sm font-bold text-white">
                {syncRunning
                  ? 'جارٍ تنزيل مكتبة القنوات والأفلام والمسلسلات…'
                  : 'لم يتم تحميل أي محتوى بعد.'}
              </p>
              <button
                type="button"
                onClick={() =>
                  SyncManager.ensureAll(account.server, account.username, account.password, true)
                }
                className="px-5 py-2.5 rounded-xl bg-[#00D2FF] text-[#001018] text-xs font-extrabold cursor-pointer"
              >
                تحديث المكتبة الآن
              </button>
            </div>
          )}
      </main>

      {/* Category Kind Picker Modal */}
      {catPickerOpen && (
        <div
          data-modal-root="true"
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 backdrop-blur-sm p-4"
          onClick={() => setCatPickerOpen(false)}
        >
          <div
            onClick={(e) => e.stopPropagation()}
            className="w-full max-w-sm rounded-2xl bg-[#131722] border border-[#253048] shadow-2xl overflow-hidden"
          >
            <div className="flex items-center justify-between px-5 py-4 border-b border-[#253048]">
              <h3 className="text-base font-extrabold text-white">تصفح التصنيفات</h3>
              <button
                type="button"
                data-modal-close="true"
                autoFocus
                onClick={() => setCatPickerOpen(false)}
                className="text-slate-400 hover:text-white cursor-pointer"
              >
                <X className="w-4 h-4" />
              </button>
            </div>
            <div className="p-4 space-y-2.5">
              {Kinds.CONTENT.map((k) => (
                <button
                  key={k}
                  type="button"
                  onClick={() => {
                    setCatPickerOpen(false);
                    void openBrowse(k, { openCategories: true });
                  }}
                  className="w-full flex items-center justify-between px-4 py-3 rounded-xl bg-[#1C2230] hover:bg-[#253048] border border-[#253048] hover:border-[#00D2FF] text-sm font-bold text-white transition cursor-pointer"
                >
                  <span>{Kinds.title(k)}</span>
                  <ChevronLeft className="w-4 h-4 text-[#00D2FF]" />
                </button>
              ))}
            </div>
          </div>
        </div>
      )}

      {/* First-time Categories Loading Modal */}
      {catBusyKind && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 backdrop-blur-sm p-4">
          <div className="w-full max-w-sm rounded-2xl bg-[#131722] border border-[#253048] p-6 flex items-center gap-4">
            <span className="w-7 h-7 rounded-full border-2 border-[#00D2FF]/30 border-t-[#00D2FF] animate-spin shrink-0" />
            <div>
              <div className="text-sm font-extrabold text-white">تحميل التصنيفات لأول مرة</div>
              <div className="text-xs text-[#A0AEC0] mt-1">
                جارٍ تجهيز {catBusyKind === Kinds.ALL ? 'التصنيفات' : Kinds.title(catBusyKind)}…
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Categories Error Modal */}
      {catErrorKind && (
        <div
          data-modal-root="true"
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 backdrop-blur-sm p-4"
        >
          <div className="w-full max-w-sm rounded-2xl bg-[#131722] border border-[#253048] p-6 space-y-4">
            <h3 className="text-base font-extrabold text-white">تعذر تحميل التصنيفات</h3>
            <p className="text-xs text-[#A0AEC0] leading-relaxed">
              تعذر جلب التصنيفات من مزود الخدمة. تحقق من الاتصال ثم حاول مرة أخرى.
            </p>
            <div className="flex justify-end gap-2">
              <button
                type="button"
                data-modal-close="true"
                autoFocus
                onClick={() => setCatErrorKind(null)}
                className="px-4 py-2 rounded-xl bg-[#1C2230] text-xs font-bold text-white cursor-pointer"
              >
                إغلاق
              </button>
              <button
                type="button"
                onClick={() => {
                  const k = catErrorKind;
                  setCatErrorKind(null);
                  void openBrowse(k);
                }}
                className="px-4 py-2 rounded-xl bg-[#00D2FF] text-[#001018] text-xs font-extrabold cursor-pointer"
              >
                إعادة المحاولة
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Logout Confirmation Modal */}
      {logoutModalOpen && (
        <div
          data-modal-root="true"
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 backdrop-blur-sm p-4"
        >
          <div className="w-full max-w-sm rounded-2xl bg-[#131722] border border-[#253048] p-6 space-y-4">
            <h3 className="text-base font-extrabold text-white">تسجيل الخروج</h3>
            <p className="text-xs sm:text-sm text-[#A0AEC0]">
              سيتم حذف المحتوى المحفوظ على الجهاز وبيانات الدخول.
            </p>
            <div className="flex justify-end gap-2">
              <button
                type="button"
                data-modal-close="true"
                autoFocus
                onClick={() => setLogoutModalOpen(false)}
                className="px-4 py-2 rounded-xl bg-[#1C2230] text-xs font-bold text-white cursor-pointer"
              >
                إلغاء
              </button>
              <button
                type="button"
                onClick={() => {
                  setLogoutModalOpen(false);
                  onLogout();
                }}
                className="px-4 py-2 rounded-xl bg-[#E8384F] text-white text-xs font-extrabold cursor-pointer"
              >
                خروج
              </button>
            </div>
          </div>
        </div>
      )}
      {/* Adult 18+ Age Confirmation Modal */}
      {adultGateOpen && (
        <AdultAgeGateModal
          onConfirm={() => {
            AdultStore.confirmAge();
            setAdultGateOpen(false);
            onNavigate({ name: 'adult' });
          }}
          onCancel={() => setAdultGateOpen(false)}
        />
      )}
    </div>
  );
};
