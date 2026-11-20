import React, { useEffect, useState } from 'react';
import { Kinds, SavedAccount } from '../types';
import { SyncManager } from '../api/xtream';

interface LoadingScreenProps {
  account: SavedAccount;
  onReady: () => void;
}

export const LoadingScreen: React.FC<LoadingScreenProps> = ({ account, onReady }) => {
  const [counts, setCounts] = useState<Record<string, number>>({
    [Kinds.LIVE]: 0,
    [Kinds.MOVIES]: 0,
    [Kinds.SERIES]: 0,
  });
  const [doneKinds, setDoneKinds] = useState<Record<string, boolean>>({});
  const [phaseText, setPhaseText] = useState('جاري تحميل سيرفر ALHUR..TV');

  useEffect(() => {
    let finished = false;
    const finishOnce = () => {
      if (finished) return;
      finished = true;
      onReady();
    };

    const unsub = SyncManager.add({
      onSyncState(kind, running, loaded) {
        if (loaded > 0) {
          setCounts((prev) => ({ ...prev, [kind]: loaded }));
        }
        if (!running) {
          setDoneKinds((prev) => {
            const next = { ...prev, [kind]: true };
            if (Kinds.CONTENT.every((k) => next[k] || !SyncManager.isRunning(k))) {
              window.setTimeout(finishOnce, 250);
            }
            return next;
          });
        }
      },
    });

    SyncManager.ensureAll(account.server, account.username, account.password);

    // Check quickly if content is already cached & fresh
    const quickCheck = window.setTimeout(() => {
      const anyRunning = Kinds.CONTENT.some((k) => SyncManager.isRunning(k));
      if (!anyRunning) finishOnce();
    }, 600);

    // Hard cap so large IPTV catalogs continue syncing cleanly on HomeScreen
    const maxWait = window.setTimeout(() => {
      setPhaseText('جارٍ استكمال المزامنة في الخلفية…');
      finishOnce();
    }, 11000);

    return () => {
      unsub();
      window.clearTimeout(quickCheck);
      window.clearTimeout(maxWait);
    };
  }, [account, onReady]);

  // Also advance early once substantial items arrive
  useEffect(() => {
    const total = Object.values(counts).reduce((a, b) => a + b, 0);
    if (total >= 400 && Object.keys(doneKinds).length >= 1) {
      const t = window.setTimeout(onReady, 350);
      return () => window.clearTimeout(t);
    }
  }, [counts, doneKinds, onReady]);

  return (
    <div dir="rtl" className="min-h-screen w-full bg-hex-space flex flex-col items-center justify-center p-6 text-center">
      <div className="w-full max-w-md flex flex-col items-center">
        <img
          src="/assets/logo_alhorr_login.png"
          alt="ALHUR TV"
          className="w-36 h-36 sm:w-44 sm:h-44 object-contain animate-breathe drop-shadow-[0_0_32px_rgba(0,210,255,0.35)]"
        />

        <h1 className="mt-6 text-lg sm:text-xl font-extrabold text-white">{phaseText}</h1>
        <p className="mt-1.5 text-xs text-[#A0AEC0]">يرجى الانتظار لحظات بينما يتم تجهيز القنوات والمكتبة</p>

        {/* Indeterminate Neon Bar */}
        <div className="mt-6 w-full h-1.5 rounded-full bg-[#1C2230] overflow-hidden border border-[#253048]">
          <div className="w-1/2 h-full rounded-full bg-gradient-to-l from-[#00D2FF] via-[#7BE6FF] to-[#0A8FB5] animate-indeterminate" />
        </div>

        {/* Live section sync pills */}
        <div className="mt-6 w-full grid grid-cols-3 gap-2.5">
          {Kinds.CONTENT.map((k) => {
            const loaded = counts[k] || SyncManager.loaded(k);
            const done = doneKinds[k];
            return (
              <div
                key={k}
                className="px-3 py-2.5 rounded-xl bg-[#131722]/78 backdrop-blur-sm border border-[#253048]/85 text-center"
              >
                <div className="text-[11px] font-bold text-[#A0AEC0]">{Kinds.title(k)}</div>
                <div className="text-xs font-extrabold text-[#00D2FF] mt-1">
                  {done ? 'جاهز ✓' : loaded > 0 ? `${loaded.toLocaleString('en-US')}` : '…'}
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
};
