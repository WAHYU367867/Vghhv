import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ContentItem,
  EpisodeItem,
  Kinds,
  PlayerLaunchParams,
  SavedAccount,
  ScreenRoute,
  UserInfo,
} from './types';
import { SyncManager, XtreamClient } from './api/xtream';
import { resolveServer } from './api/freeCatalog';
import { CredentialStore, db } from './storage/db';
import { DEFAULT_SERVER } from './utils/arabic';
import { LoginScreen } from './screens/LoginScreen';
import { LoadingScreen } from './screens/LoadingScreen';
import { HomeScreen } from './screens/HomeScreen';
import { BrowseScreen } from './screens/BrowseScreen';
import { MovieDetailScreen } from './screens/MovieDetailScreen';
import { SeriesDetailScreen } from './screens/SeriesDetailScreen';
import { EpgScreen } from './screens/EpgScreen';
import { SettingsScreen } from './screens/SettingsScreen';
import { AdultScreen } from './screens/AdultScreen';
import { VideoPlayer } from './components/VideoPlayer';
import { focusInitialTarget, installRemoteNav } from './utils/remoteNav';

class AppErrorBoundary extends React.Component<
  { children: React.ReactNode },
  { hasError: boolean }
> {
  constructor(props: { children: React.ReactNode }) {
    super(props);
    this.state = { hasError: false };
  }

  static getDerivedStateFromError(): { hasError: boolean } {
    return { hasError: true };
  }

  render() {
    if (this.state.hasError) {
      return (
        <div
          dir="rtl"
          className="min-h-screen w-full bg-[#0A0C10] text-white flex items-center justify-center p-6"
        >
          <div className="w-full max-w-md rounded-2xl bg-[#131722] border border-[#253048] p-6 text-center space-y-4">
            <h2 className="text-lg font-extrabold text-white">حدث خطأ غير متوقع في الواجهة</h2>
            <p className="text-xs sm:text-sm text-[#A0AEC0]">
              اضغط على إعادة التحميل للمتابعة بشكل طبيعي.
            </p>
            <button
              type="button"
              onClick={() => window.location.reload()}
              className="px-5 py-2.5 rounded-xl bg-[#00D2FF] text-[#001018] text-xs font-extrabold cursor-pointer"
            >
              إعادة التحميل
            </button>
          </div>
        </div>
      );
    }
    return this.props.children;
  }
}

export const App: React.FC = () => {
  const rawSaved = CredentialStore.rememberLogin() ? CredentialStore.get() : null;
  const initialSaved: SavedAccount | null = rawSaved
    ? {
        ...rawSaved,
        server: resolveServer(rawSaved.server, rawSaved.username, rawSaved.password, DEFAULT_SERVER),
      }
    : null;

  const [account, setAccount] = useState<SavedAccount | null>(initialSaved);
  const [userInfo, setUserInfo] = useState<UserInfo | null>(null);
  const [stack, setStack] = useState<ScreenRoute[]>(() =>
    initialSaved ? [{ name: 'loading' }] : [{ name: 'login' }]
  );
  const [playerParams, setPlayerParams] = useState<PlayerLaunchParams | null>(null);
  const [toast, setToast] = useState<string>('');
  const toastTimerRef = useRef<number | null>(null);

  const showToast = useCallback((msg: string) => {
    setToast(msg);
    if (toastTimerRef.current) window.clearTimeout(toastTimerRef.current);
    toastTimerRef.current = window.setTimeout(() => setToast(''), 3200);
  }, []);

  const currentRoute = stack[stack.length - 1] ?? { name: 'login' };

  const pushRoute = useCallback((route: ScreenRoute) => {
    setStack((prev) => [...prev, route]);
  }, []);

  const popRoute = useCallback(() => {
    setStack((prev) => (prev.length > 1 ? prev.slice(0, -1) : prev));
  }, []);

  const replaceStack = useCallback((route: ScreenRoute) => {
    setStack([route]);
  }, []);

  useEffect(() => {
    return installRemoteNav({ onBack: popRoute });
  }, [popRoute]);

  useEffect(() => {
    if (playerParams) return;
    const t = window.setTimeout(() => {
      focusInitialTarget();
    }, 60);
    return () => window.clearTimeout(t);
  }, [currentRoute.name, playerParams]);

  const client = useMemo(() => {
    if (!account) return null;
    return new XtreamClient(account.server, account.username, account.password);
  }, [account]);

  const handleLogout = useCallback(async () => {
    setPlayerParams(null);
    CredentialStore.clear();
    SyncManager.reset();
    await db.clearAllTables().catch(() => {});
    setAccount(null);
    setUserInfo(null);
    replaceStack({ name: 'login', noAuto: true });
  }, [replaceStack]);

  const playLiveItem = useCallback(
    (item: ContentItem) => {
      if (!client) return;
      setPlayerParams({
        url: client.mediaUrl('live', item.contentId, item.extension),
        title: item.name,
        image: item.imageUrl,
        contentId: item.contentId,
        categoryId: item.category,
        markKind: Kinds.LIVE,
        markId: item.contentId,
      });
    },
    [client]
  );

  const playMovieItem = useCallback(
    (item: ContentItem) => {
      if (!client) return;
      setPlayerParams({
        url: client.mediaUrl('movie', item.contentId, item.extension),
        title: item.name,
        image: item.imageUrl,
        markKind: Kinds.MOVIES,
        markId: item.contentId,
      });
    },
    [client]
  );

  const playMovieDirect = useCallback(
    (url: string, title: string, image: string, vodId: string) => {
      setPlayerParams({
        url,
        title,
        image,
        markKind: Kinds.MOVIES,
        markId: vodId,
      });
    },
    []
  );

  const playSeriesEpisode = useCallback(
    (ep: EpisodeItem, seriesName: string, seriesImage: string) => {
      if (!client) return;
      setPlayerParams({
        url: client.mediaUrl('series', ep.episodeId, ep.extension),
        title: ep.title,
        image: ep.imageUrl || seriesImage,
        seriesId: ep.seriesId,
        seriesName,
        episodeId: ep.episodeId,
        markKind: Kinds.SERIES,
        markId: ep.seriesId,
      });
    },
    [client]
  );

  const renderScreen = () => {
    if (currentRoute.name === 'login' || !account || !client) {
      return (
        <LoginScreen
          addAccount={currentRoute.name === 'login' ? currentRoute.addAccount : false}
          onSuccess={(acc, info) => {
            setAccount(acc);
            setUserInfo(info ?? null);
            replaceStack({ name: 'loading' });
          }}
          onCancelAdd={
            currentRoute.name === 'login' && currentRoute.addAccount && account
              ? popRoute
              : undefined
          }
        />
      );
    }

    if (currentRoute.name === 'loading') {
      return (
        <LoadingScreen
          account={account}
          onReady={() => replaceStack({ name: 'home' })}
        />
      );
    }

    if (currentRoute.name === 'home') {
      return (
        <HomeScreen
          account={account}
          userInfo={userInfo}
          client={client}
          onUpdateUserInfo={(info) => setUserInfo(info)}
          onNavigate={pushRoute}
          onPlayLive={playLiveItem}
          onPlayMovie={playMovieItem}
          onLogout={() => void handleLogout()}
        />
      );
    }

    if (currentRoute.name === 'browse') {
      return (
        <BrowseScreen
          kind={currentRoute.kind}
          account={account}
          focusSearch={currentRoute.focusSearch}
          openCategories={currentRoute.openCategories}
          onBack={popRoute}
          onNavigate={pushRoute}
          onPlayLive={playLiveItem}
        />
      );
    }

    if (currentRoute.name === 'movie') {
      return (
        <MovieDetailScreen
          vodId={currentRoute.vodId}
          title={currentRoute.title}
          image={currentRoute.image}
          extension={currentRoute.extension}
          client={client}
          onBack={popRoute}
          onPlay={playMovieDirect}
          onOpenMovie={(item) =>
            pushRoute({
              name: 'movie',
              vodId: item.contentId,
              title: item.name,
              image: item.imageUrl,
              extension: item.extension,
            })
          }
        />
      );
    }

    if (currentRoute.name === 'series') {
      return (
        <SeriesDetailScreen
          seriesId={currentRoute.seriesId}
          seriesName={currentRoute.seriesName}
          seriesImage={currentRoute.seriesImage}
          client={client}
          onBack={popRoute}
          onPlayEpisode={playSeriesEpisode}
          onOpenSeries={(item) =>
            pushRoute({
              name: 'series',
              seriesId: item.contentId,
              seriesName: item.name,
              seriesImage: item.imageUrl,
            })
          }
        />
      );
    }

    if (currentRoute.name === 'epg') {
      return (
        <EpgScreen
          client={client}
          onBack={popRoute}
          onPlayLive={playLiveItem}
        />
      );
    }

    if (currentRoute.name === 'settings') {
      return (
        <SettingsScreen
          account={account}
          userInfo={userInfo}
          client={client}
          onUpdateUserInfo={(info) => setUserInfo(info)}
          onBack={popRoute}
          onSwitchAccount={(acc, info) => {
            setAccount(acc);
            setUserInfo(info ?? null);
            replaceStack({ name: 'loading' });
          }}
          onOpenAddAccount={() => pushRoute({ name: 'login', addAccount: true })}
          onResetAndReload={() => replaceStack({ name: 'loading' })}
          onLogout={() => void handleLogout()}
          onToast={showToast}
        />
      );
    }

    if (currentRoute.name === 'adult') {
      return (
        <AdultScreen
          onBack={popRoute}
          onPlayChannel={(ch) =>
            setPlayerParams({
              url: ch.streamUrl,
              title: ch.name,
              image: ch.logoUrl,
            })
          }
          onToast={showToast}
        />
      );
    }

    return null;
  };

  return (
    <>
      {renderScreen()}

      {playerParams && client && (
        <VideoPlayer
          params={playerParams}
          client={client}
          onClose={() => setPlayerParams(null)}
          onToast={showToast}
        />
      )}

      {toast && (
        <div
          dir="rtl"
          className="fixed bottom-6 left-1/2 -translate-x-1/2 z-[70] px-5 py-2.5 rounded-xl bg-[#131722]/95 border border-[#00D2FF]/60 text-xs sm:text-sm font-bold text-white shadow-2xl backdrop-blur-md"
        >
          {toast}
        </div>
      )}
    </>
  );
};

const AppWithBoundary: React.FC = () => (
  <AppErrorBoundary>
    <App />
  </AppErrorBoundary>
);

export default AppWithBoundary;
