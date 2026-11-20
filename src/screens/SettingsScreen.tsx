import React, { useEffect, useState } from 'react';
import {
  ArrowRight,
  Calendar,
  ChevronLeft,
  Download,
  Film,
  Grid,
  Info,
  LogOut,
  Monitor,
  PlayCircle,
  RefreshCw,
  Router,
  Settings,
  Shield,
  ShieldAlert,
  Trash2,
  Tv,
  User,
  UserPlus,
  Users,
  Clapperboard,
  X,
} from 'lucide-react';
import { RefreshMode, SavedAccount, UserInfo } from '../types';
import { SyncManager, XtreamClient } from '../api/xtream';
import { isFreeServer, resolveServer } from '../api/freeCatalog';
import {
  CredentialStore,
  db,
  EpgCache,
  getDeviceId,
  HiddenStore,
  PlayerOptions,
  SyncMeta,
} from '../storage/db';
import { AdultStore } from '../storage/adultStore';
import {
  APP_VERSION,
  DEFAULT_SERVER,
  downloadAndroidApk,
  downloadAndroidProject,
  SupportContact,
} from '../utils/arabic';
import { PrivacyModal } from '../components/PrivacyModal';

interface SettingsScreenProps {
  account: SavedAccount;
  userInfo: UserInfo | null;
  client: XtreamClient;
  onUpdateUserInfo: (info: UserInfo) => void;
  onBack: () => void;
  onSwitchAccount: (account: SavedAccount, info?: UserInfo) => void;
  onOpenAddAccount: () => void;
  onResetAndReload: () => void;
  onLogout: () => void;
  onToast: (msg: string) => void;
}

export const SettingsScreen: React.FC<SettingsScreenProps> = ({
  account,
  userInfo,
  client,
  onUpdateUserInfo,
  onBack,
  onSwitchAccount,
  onOpenAddAccount,
  onResetAndReload,
  onLogout,
  onToast,
}) => {
  // Matches SettingsActivity.kt: tab 0 = الحساب (settings panel), tab 1 = الإعدادات (account info cards, default open), tab 2 = المشغلات
  const [tab, setTab] = useState<0 | 1 | 2>(1);
  const [refreshMode, setRefreshMode] = useState<RefreshMode>(() => SyncMeta.refreshMode());
  const [rememberLogin, setRememberLogin] = useState(() => CredentialStore.rememberLogin());
  const [offerAlternate, setOfferAlternate] = useState(() => PlayerOptions.offerAlternateOnError());
  const [savedAccounts, setSavedAccounts] = useState<SavedAccount[]>(() => CredentialStore.accounts());
  const [privacyLocked, setPrivacyLocked] = useState(() => HiddenStore.hasPassword());
  const [hiddenCount, setHiddenCount] = useState(() => HiddenStore.hiddenCount());
  const [adultEnabled, setAdultEnabled] = useState(() => AdultStore.isEnabled());
  const [adultCount] = useState(() => AdultStore.getChannels().length);

  const [privacyMode, setPrivacyMode] = useState<
    'set_password' | 'ask_password' | 'forgot' | 'manager' | null
  >(null);
  const [accountsOpen, setAccountsOpen] = useState(false);
  const [resetConfirmOpen, setResetConfirmOpen] = useState(false);
  const [logoutConfirmOpen, setLogoutConfirmOpen] = useState(false);
  const [downloadingAsset, setDownloadingAsset] = useState<'apk' | 'zip' | null>(null);

  const deviceId = getDeviceId();

  useEffect(() => {
    if (!userInfo) {
      client.account().then((res) => {
        if (res?.user_info) onUpdateUserInfo(res.user_info);
      });
    }
    if (CredentialStore.rememberLogin()) {
      CredentialStore.addAccount(account.server, account.username, account.password);
      setSavedAccounts(CredentialStore.accounts());
    }
  }, [account, client, onUpdateUserInfo, userInfo]);

  const refreshPrivacyMeta = () => {
    setPrivacyLocked(HiddenStore.hasPassword());
    setHiddenCount(HiddenStore.hiddenCount());
  };

  const isFree = isFreeServer(account.server);
  const rawStatus = String(userInfo?.status ?? '').trim().toLowerCase();
  const statusText = isFree
    ? 'نشط (الباقة المجانية)'
    : rawStatus === 'active'
    ? 'نشط'
    : rawStatus === 'expired'
    ? 'منتهي'
    : rawStatus === 'banned'
    ? 'محظور'
    : rawStatus === 'disabled'
    ? 'معطّل'
    : rawStatus || 'غير متاح';
  const statusColor =
    isFree || rawStatus === 'active' ? '#18E0C0' : rawStatus ? '#FF7B8B' : '#A0AEC0';

  const expEpoch = Number(userInfo?.exp_date ?? 0);
  const expiryDate = isFree
    ? 'مفتوح (مجاني)'
    : expEpoch > 0
    ? new Date(expEpoch * 1000).toISOString().slice(0, 10)
    : 'غير متاح';
  const daysLeft =
    expEpoch > 0 ? Math.floor((expEpoch * 1000 - Date.now()) / 86_400_000) : null;
  const expirySub = isFree
    ? 'قنوات عربية وأفلام ومسلسلات مجانية'
    : daysLeft === null
    ? ''
    : daysLeft >= 0
    ? `متبقي ${daysLeft} يوم`
    : 'انتهى الاشتراك';

  const devicesText = isFree
    ? 'مفتوح'
    : `${userInfo?.active_cons ?? '0'} / ${userInfo?.max_connections ?? '—'}`;
  const profileInitial = account.username.trim()[0]?.toUpperCase() || '؟';

  const infoCards = [
    {
      icon: User,
      tint: '#00D2FF',
      label: 'اسم المستخدم',
      value: account.username || 'غير متاح',
      ltr: true,
    },
    {
      icon: Monitor,
      tint: '#2FD8FF',
      label: 'الأجهزة',
      value: devicesText,
      ltr: true,
    },
    {
      icon: Shield,
      tint: '#18E0C0',
      label: 'الحالة',
      value: statusText,
      color: statusColor,
      ltr: false,
    },
    {
      icon: Router,
      tint: '#2FD8FF',
      label: 'معرف الجهاز',
      value: deviceId,
      ltr: true,
    },
    {
      icon: Calendar,
      tint: '#FFB800',
      label: 'تاريخ الانتهاء',
      value: expiryDate,
      sub: expirySub,
      ltr: false,
    },
    {
      icon: Info,
      tint: '#4F8CFF',
      label: 'إصدار التطبيق',
      value: APP_VERSION,
      ltr: true,
    },
  ];

  return (
    <div dir="rtl" className="min-h-screen w-full bg-hex-space flex flex-col p-3 sm:p-5 gap-3 sm:gap-4">
      <div className="flex-1 flex flex-row gap-3 sm:gap-4 min-h-0">
        {/* Permanent Right Sidebar (RTL Landscape) */}
        <aside className="w-[155px] sm:w-[205px] lg:w-60 shrink-0 rounded-2xl bg-[#131722]/80 backdrop-blur-sm border border-[#253048] p-3 sm:p-4 flex flex-col">
          <div className="flex flex-col items-center gap-2 sm:gap-3 pb-3 sm:pb-4 border-b border-[#253048]">
            <img
              src="/assets/logo_alhorr_login.png"
              alt="الحر الأصلي"
              className="w-14 h-14 sm:w-20 sm:h-20 lg:w-24 lg:h-24 object-contain"
            />
            <span className="text-[11px] sm:text-xs font-extrabold text-[#00D2FF] tracking-wide text-center">
              ALHUR TV · الحر الأصلي
            </span>
          </div>

          <nav className="mt-3 sm:mt-4 flex flex-col gap-2 overflow-y-auto no-scrollbar flex-1">
            {[
              { id: 0 as const, label: 'الحساب', icon: User },
              { id: 1 as const, label: 'الإعدادات', icon: Settings },
              { id: 2 as const, label: 'المشغلات', icon: PlayCircle },
            ].map((item) => (
              <button
                key={item.id}
                type="button"
                data-tv-autofocus={tab === item.id ? 'true' : undefined}
                onClick={() => setTab(item.id)}
                className={`w-full flex items-center gap-2.5 sm:gap-3 px-3 sm:px-4 py-2.5 sm:py-3 rounded-xl text-xs sm:text-sm font-extrabold border transition cursor-pointer ${
                  tab === item.id
                    ? 'bg-[#1C2230] border-[#00D2FF] text-[#00D2FF] shadow-[0_0_14px_rgba(0,210,255,0.2)]'
                    : 'bg-transparent border-transparent text-[#A0AEC0] hover:bg-[#1C2230]/60 hover:text-white'
                }`}
              >
                <item.icon className="w-4 h-4 shrink-0" />
                <span className="truncate">{item.label}</span>
              </button>
            ))}

            <button
              type="button"
              onClick={onBack}
              className="w-full mt-auto flex items-center gap-2.5 sm:gap-3 px-3 sm:px-4 py-2.5 sm:py-3 rounded-xl text-xs sm:text-sm font-extrabold text-[#A0AEC0] hover:bg-[#1C2230] hover:text-white transition cursor-pointer"
            >
              <ArrowRight className="w-4 h-4 shrink-0" />
              <span>رجوع</span>
            </button>
          </nav>
        </aside>

        {/* Active Panel */}
        <main className="flex-1 rounded-2xl bg-[#131722]/80 backdrop-blur-sm border border-[#253048] p-4 sm:p-7 flex flex-col justify-between overflow-y-auto">
          {tab === 1 && (
            <div className="space-y-6">
              <div className="flex items-center gap-3">
                <div className="w-9 h-9 rounded-full bg-[#00D2FF]/15 border border-[#00D2FF]/50 text-[#00D2FF] flex items-center justify-center">
                  <User className="w-4 h-4" />
                </div>
                <h1 className="text-xl font-extrabold text-white">معلومات الحساب</h1>
              </div>

              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3.5">
                {infoCards.map((c) => (
                  <div
                    key={c.label}
                    className="rounded-2xl bg-[#1C2230] border border-[#253048] px-4 py-3.5 flex items-center gap-3"
                  >
                    <div
                      className="w-9 h-9 rounded-full flex items-center justify-center shrink-0 border"
                      style={{
                        backgroundColor: `${c.tint}24`,
                        borderColor: `${c.tint}66`,
                        color: c.tint,
                      }}
                    >
                      <c.icon className="w-4 h-4" />
                    </div>
                    <span className="text-xs font-semibold text-[#A0AEC0] shrink-0">{c.label}</span>
                    <div className="flex-1 min-w-0 text-left">
                      <div
                        dir={c.ltr ? 'ltr' : 'rtl'}
                        className="text-sm sm:text-base font-extrabold truncate"
                        style={{ color: c.color || '#FFFFFF' }}
                      >
                        {c.value}
                      </div>
                      {c.sub && <div className="text-[11px] text-[#A0AEC0]">{c.sub}</div>}
                    </div>
                  </div>
                ))}
              </div>

              <div className="grid grid-cols-3 gap-2.5 sm:gap-3 pt-2">
                <button
                  type="button"
                  onClick={() => setResetConfirmOpen(true)}
                  className="py-3 sm:py-3.5 px-3 sm:px-4 rounded-2xl bg-gradient-to-b from-[#1A2D42] to-[#121E2E] border border-[#00D2FF]/50 hover:border-[#00D2FF] hover:brightness-110 text-white font-extrabold text-xs sm:text-sm flex items-center justify-center gap-2 shadow-lg transition cursor-pointer"
                >
                  <RefreshCw className="w-4 h-4 text-[#00D2FF]" />
                  <span>إعادة تعيين الكود</span>
                </button>

                <button
                  type="button"
                  onClick={() => setAccountsOpen(true)}
                  className="py-3 sm:py-3.5 px-3 sm:px-4 rounded-2xl bg-gradient-to-b from-[#1C2538] to-[#131926] border border-[#00D2FF]/50 hover:border-[#00D2FF] hover:brightness-110 text-white font-extrabold text-xs sm:text-sm flex items-center justify-center gap-2 shadow-lg transition cursor-pointer"
                >
                  <Users className="w-4 h-4 text-[#00D2FF]" />
                  <span>الحسابات ({savedAccounts.length})</span>
                </button>

                <button
                  type="button"
                  onClick={onOpenAddAccount}
                  className="py-3 sm:py-3.5 px-3 sm:px-4 rounded-2xl bg-gradient-to-b from-[#123638] to-[#0C2326] border border-[#18E0C0]/55 hover:border-[#18E0C0] hover:brightness-110 text-white font-extrabold text-xs sm:text-sm flex items-center justify-center gap-2 shadow-lg transition cursor-pointer"
                >
                  <UserPlus className="w-4 h-4 text-[#18E0C0]" />
                  <span>إضافة حساب</span>
                </button>
              </div>
            </div>
          )}

          {tab === 0 && (
            <div className="space-y-6">
              <div className="flex items-center gap-3">
                <div className="w-9 h-9 rounded-full bg-[#00D2FF]/15 border border-[#00D2FF]/50 text-[#00D2FF] flex items-center justify-center">
                  <User className="w-4 h-4" />
                </div>
                <h1 className="text-xl font-extrabold text-white">الحساب</h1>
              </div>

              {/* Profile summary */}
              <div className="rounded-2xl bg-[#1C2230] border border-[#253048] p-4 flex items-center gap-4">
                <div className="w-14 h-14 rounded-full bg-[#00D2FF]/20 border-2 border-[#00D2FF] text-white text-xl font-extrabold flex items-center justify-center shrink-0">
                  {profileInitial}
                </div>
                <div className="min-w-0">
                  <div dir="ltr" className="text-base font-extrabold text-white text-right truncate">
                    {account.username}
                  </div>
                  <div className="text-xs font-bold mt-0.5" style={{ color: statusColor }}>
                    {statusText} · ينتهي {expiryDate}
                  </div>
                </div>
              </div>

              {/* Library & Refresh */}
              <div className="space-y-2.5">
                <h2 className="text-xs font-extrabold text-[#00D2FF]">المكتبة والتحديث</h2>

                <div className="rounded-2xl bg-[#1C2230] border border-[#253048] p-4 space-y-3">
                  <div>
                    <div className="text-sm font-extrabold text-white">تحديث المكتبة تلقائيًا</div>
                    <div className="text-xs text-[#A0AEC0]">
                      تُحفظ العناصر على الجهاز وتظهر الدفعات أثناء التنزيل
                    </div>
                  </div>
                  <div className="grid grid-cols-3 gap-2">
                    {(['six_hours', 'daily', 'manual'] as RefreshMode[]).map((mode) => {
                      const active = refreshMode === mode;
                      return (
                        <button
                          key={mode}
                          type="button"
                          onClick={() => {
                            SyncMeta.setRefreshMode(mode);
                            setRefreshMode(mode);
                            onToast(`تم ضبط التحديث: ${SyncMeta.refreshLabel(mode)}`);
                          }}
                          className={`py-2 px-3 rounded-xl text-xs font-extrabold border transition cursor-pointer ${
                            active
                              ? 'bg-gradient-to-l from-[#00D2FF] to-[#0A8FB5] border-white/80 text-[#001018]'
                              : 'bg-[#131722] border-[#253048] text-[#A0AEC0] hover:text-white'
                          }`}
                        >
                          {SyncMeta.refreshLabel(mode)}
                        </button>
                      );
                    })}
                  </div>
                </div>

                <button
                  type="button"
                  onClick={() => {
                    SyncManager.ensureAll(account.server, account.username, account.password, true);
                    onToast('بدأ تحديث المكتبة في الخلفية؛ ستظهر العناصر تدريجيًا.');
                  }}
                  className="w-full rounded-2xl bg-[#1C2230] hover:bg-[#232B3D] border border-[#253048] hover:border-[#00D2FF] p-4 flex items-center justify-between transition cursor-pointer text-right"
                >
                  <div className="flex items-center gap-3">
                    <div className="w-9 h-9 rounded-xl bg-[#2FD8FF]/15 text-[#2FD8FF] flex items-center justify-center">
                      <RefreshCw className="w-4 h-4" />
                    </div>
                    <div>
                      <div className="text-sm font-extrabold text-white">تحديث المكتبة الآن</div>
                      <div className="text-xs text-[#A0AEC0]">القنوات والأفلام والمسلسلات</div>
                    </div>
                  </div>
                  <ChevronLeft className="w-5 h-5 text-[#64748B]" />
                </button>

                <button
                  type="button"
                  onClick={() => {
                    EpgCache.clear();
                    onToast('تم تنظيف بيانات دليل البرامج المؤقتة.');
                  }}
                  className="w-full rounded-2xl bg-[#1C2230] hover:bg-[#232B3D] border border-[#253048] hover:border-[#00D2FF] p-4 flex items-center justify-between transition cursor-pointer text-right"
                >
                  <div className="flex items-center gap-3">
                    <div className="w-9 h-9 rounded-xl bg-[#FFB800]/15 text-[#FFB800] flex items-center justify-center">
                      <Grid className="w-4 h-4" />
                    </div>
                    <div>
                      <div className="text-sm font-extrabold text-white">تنظيف الكاش</div>
                      <div className="text-xs text-[#A0AEC0]">بيانات الدليل المؤقتة فقط، دون المكتبة أو المفضلة</div>
                    </div>
                  </div>
                  <ChevronLeft className="w-5 h-5 text-[#64748B]" />
                </button>
              </div>

              {/* Security & Privacy */}
              <div className="space-y-2.5">
                <h2 className="text-xs font-extrabold text-[#00D2FF]">الأمان والخصوصية</h2>

                <button
                  type="button"
                  onClick={() =>
                    setPrivacyMode(privacyLocked ? 'ask_password' : 'set_password')
                  }
                  className="w-full rounded-2xl bg-[#1C2230] hover:bg-[#232B3D] border border-[#253048] hover:border-[#00D2FF] p-4 flex items-center justify-between transition cursor-pointer text-right"
                >
                  <div className="flex items-center gap-3">
                    <div className="w-9 h-9 rounded-xl bg-[#00D2FF]/15 text-[#00D2FF] flex items-center justify-center">
                      <Shield className="w-4 h-4" />
                    </div>
                    <div>
                      <div className="text-sm font-extrabold text-white">إخفاء المحتوى</div>
                      <div className="text-xs text-[#A0AEC0]">
                        {!privacyLocked
                          ? 'أخفِ فئات أو قنوات أو أفلام أو مسلسلات بكلمة مرور'
                          : hiddenCount > 0
                          ? `${hiddenCount} عنصر / فئة مخفية`
                          : 'لا يوجد شيء مخفي'}
                      </div>
                    </div>
                  </div>
                  <span
                    className={`text-xs font-extrabold ${
                      privacyLocked ? 'text-[#18E0C0]' : 'text-[#A0AEC0]'
                    }`}
                  >
                    {privacyLocked ? 'مفعّل' : 'غير مفعّل'}
                  </span>
                </button>

                {privacyLocked && (
                  <button
                    type="button"
                    onClick={() => setPrivacyMode('forgot')}
                    className="w-full rounded-2xl bg-[#1C2230] hover:bg-[#232B3D] border border-[#253048] hover:border-[#FFB800] p-4 flex items-center justify-between transition cursor-pointer text-right"
                  >
                    <div className="flex items-center gap-3">
                      <div className="w-9 h-9 rounded-xl bg-[#FFB800]/15 text-[#FFB800] flex items-center justify-center">
                        <Info className="w-4 h-4" />
                      </div>
                      <div>
                        <div className="text-sm font-extrabold text-white">نسيت كلمة المرور؟</div>
                        <div className="text-xs text-[#A0AEC0]">إعادة التعيين وحذف كل المخفي</div>
                      </div>
                    </div>
                    <ChevronLeft className="w-5 h-5 text-[#64748B]" />
                  </button>
                )}

                <label className="w-full rounded-2xl bg-[#1C2230] border border-[#253048] p-4 flex items-center justify-between cursor-pointer">
                  <div className="flex items-center gap-3">
                    <div className="w-9 h-9 rounded-xl bg-[#18E0C0]/15 text-[#18E0C0] flex items-center justify-center">
                      <Shield className="w-4 h-4" />
                    </div>
                    <div>
                      <div className="text-sm font-extrabold text-white">حفظ تسجيل الدخول</div>
                      <div className="text-xs text-[#A0AEC0]">لتسجيل الدخول تلقائيًا عند فتح التطبيق</div>
                    </div>
                  </div>
                  <input
                    type="checkbox"
                    checked={rememberLogin}
                    onChange={(e) => {
                      const v = e.target.checked;
                      setRememberLogin(v);
                      CredentialStore.setRememberLogin(v);
                      if (v) {
                        CredentialStore.save(account.server, account.username, account.password);
                        CredentialStore.addAccount(account.server, account.username, account.password);
                      } else {
                        CredentialStore.clear();
                      }
                    }}
                    className="w-5 h-5 accent-[#00D2FF] cursor-pointer"
                  />
                </label>

                <label className="w-full rounded-2xl bg-[#1C2230] border border-[#253048] p-4 flex items-center justify-between cursor-pointer">
                  <div className="flex items-center gap-3">
                    <div className="w-9 h-9 rounded-xl bg-[#E8384F]/15 text-[#FF7B8B] flex items-center justify-center">
                      <ShieldAlert className="w-4 h-4" />
                    </div>
                    <div>
                      <div className="text-sm font-extrabold text-white">قسم Adult 18+</div>
                      <div className="text-xs text-[#A0AEC0]">
                        {adultEnabled
                          ? `مفعّل في القائمة الرئيسية · ${adultCount} قناة مضافة`
                          : 'معطّل ومخفي عن القائمة الرئيسية'}
                      </div>
                    </div>
                  </div>
                  <input
                    type="checkbox"
                    checked={adultEnabled}
                    onChange={(e) => {
                      const v = e.target.checked;
                      setAdultEnabled(v);
                      AdultStore.setEnabled(v);
                      onToast(
                        v
                          ? 'تم تفعيل قسم Adult 18+ في القائمة الرئيسية.'
                          : 'تم تعطيل قسم Adult 18+ وإخفاؤه.'
                      );
                    }}
                    className="w-5 h-5 accent-[#E8384F] cursor-pointer"
                  />
                </label>
              </div>

              {/* Support */}
              <div className="space-y-2.5">
                <h2 className="text-xs font-extrabold text-[#00D2FF]">الدعم والمعلومات</h2>
                <a
                  href={SupportContact.whatsAppUrl(
                    'السلام عليكم، أريد الاستفسار عن تجديد اشتراك تطبيق ALHUR TV.'
                  )}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="w-full rounded-2xl bg-[#1C2230] hover:bg-[#232B3D] border border-[#253048] hover:border-[#25D9BD] p-4 flex items-center justify-between transition"
                >
                  <div className="flex items-center gap-3">
                    <div className="w-9 h-9 rounded-xl bg-[#25D9BD]/15 text-[#25D9BD] flex items-center justify-center">
                      <Info className="w-4 h-4" />
                    </div>
                    <div>
                      <div className="text-sm font-extrabold text-white">تواصل معنا عبر واتساب</div>
                      <div className="text-xs text-[#A0AEC0]">
                        الدعم الفني وتجديد الاشتراك · {SupportContact.DISPLAY}
                      </div>
                    </div>
                  </div>
                  <ChevronLeft className="w-5 h-5 text-[#64748B]" />
                </a>

                <button
                  type="button"
                  disabled={downloadingAsset !== null}
                  onClick={async () => {
                    setDownloadingAsset('apk');
                    const ok = await downloadAndroidApk();
                    setDownloadingAsset(null);
                    onToast(
                      ok
                        ? `بدأ تحميل alhorr-tv-${APP_VERSION}.apk.`
                        : 'تعذر تحميل ملف APK، حاول مرة أخرى.'
                    );
                  }}
                  className="w-full rounded-2xl bg-[#1C2230] hover:bg-[#232B3D] border border-[#25D9BD]/50 hover:border-[#25D9BD] p-4 flex items-center justify-between transition cursor-pointer text-right disabled:opacity-60"
                >
                  <div className="flex items-center gap-3">
                    <div className="w-9 h-9 rounded-xl bg-[#25D9BD]/15 text-[#25D9BD] flex items-center justify-center">
                      <Download className="w-4 h-4" />
                    </div>
                    <div>
                      <div className="text-sm font-extrabold text-white">
                        {downloadingAsset === 'apk'
                          ? 'جارٍ تجهيز ملف APK للتحميل…'
                          : `تحميل ملف APK الجاهز للتثبيت (${APP_VERSION})`}
                      </div>
                      <div className="text-xs text-[#A0AEC0]">
                        نسخة أندرويد مجمّعة وموقّعة للتثبيت المباشر على التلفاز والهاتف
                      </div>
                    </div>
                  </div>
                  <ChevronLeft className="w-5 h-5 text-[#64748B]" />
                </button>

                <button
                  type="button"
                  disabled={downloadingAsset !== null}
                  onClick={async () => {
                    setDownloadingAsset('zip');
                    const ok = await downloadAndroidProject();
                    setDownloadingAsset(null);
                    onToast(
                      ok
                        ? `بدأ تحميل alhorr_tv_updated.zip (${APP_VERSION}).`
                        : 'تعذر تحميل ملف المشروع، حاول مرة أخرى.'
                    );
                  }}
                  className="w-full rounded-2xl bg-[#1C2230] hover:bg-[#232B3D] border border-[#253048] hover:border-[#00D2FF] p-4 flex items-center justify-between transition cursor-pointer text-right disabled:opacity-60"
                >
                  <div className="flex items-center gap-3">
                    <div className="w-9 h-9 rounded-xl bg-[#00D2FF]/15 text-[#00D2FF] flex items-center justify-center">
                      <Download className="w-4 h-4" />
                    </div>
                    <div>
                      <div className="text-sm font-extrabold text-white">
                        {downloadingAsset === 'zip'
                          ? 'جارٍ تجهيز الملف للتحميل…'
                          : `تحميل كود الأندرويد المحدث (${APP_VERSION})`}
                      </div>
                      <div className="text-xs text-[#A0AEC0]">
                        ملف ZIP جاهز للتجميع وبناء APK عبر Android Studio أو Manus AI
                      </div>
                    </div>
                  </div>
                  <ChevronLeft className="w-5 h-5 text-[#64748B]" />
                </button>
              </div>

              <button
                type="button"
                onClick={() => setLogoutConfirmOpen(true)}
                className="w-full py-3.5 rounded-2xl bg-gradient-to-b from-[#381620] to-[#220D14] border border-[#E8384F]/60 hover:border-[#E8384F] text-white font-extrabold text-sm flex items-center justify-center gap-2 cursor-pointer"
              >
                <LogOut className="w-4 h-4 text-[#FF7B8B]" />
                <span>تسجيل الخروج</span>
              </button>
            </div>
          )}

          {tab === 2 && (
            <div className="space-y-6">
              <div className="flex items-center gap-3">
                <div className="w-9 h-9 rounded-full bg-[#00D2FF]/15 border border-[#00D2FF]/50 text-[#00D2FF] flex items-center justify-center">
                  <PlayCircle className="w-4 h-4" />
                </div>
                <h1 className="text-xl font-extrabold text-white">المشغلات</h1>
              </div>

              <div className="rounded-2xl bg-[#1C2230] border border-[#253048] p-5 space-y-2">
                <h3 className="text-sm font-extrabold text-[#FFB800]">المشغّل الداخلي</h3>
                <p className="text-xs text-[#A0AEC0]">المشغّل الافتراضي للبث المباشر والأفلام والمسلسلات</p>
                <p className="text-xs sm:text-sm text-slate-200 pt-1 leading-relaxed">
                  يعمل التطبيق بمشغّل HLS.js وHTML5 المدمج، ويدعم روابط HLS وروابط الفيديو المباشرة مع اختيار الجودة والسرعة والترجمة.
                </p>
              </div>

              <div className="rounded-2xl bg-[#1C2230] border border-[#253048] p-5 space-y-3">
                <h3 className="text-sm font-extrabold text-[#FFB800]">المشغّل البديل</h3>
                <p className="text-xs text-[#A0AEC0]">للبث الذي لا يستطيع المشغّل الداخلي فك ترميزه</p>
                <label className="flex items-center justify-between gap-3 pt-1 cursor-pointer">
                  <span className="text-xs sm:text-sm font-bold text-white">
                    عرض خيار نسخ رابط البث للمشغّل البديل عند فشل التشغيل
                  </span>
                  <input
                    type="checkbox"
                    checked={offerAlternate}
                    onChange={(e) => {
                      const v = e.target.checked;
                      setOfferAlternate(v);
                      PlayerOptions.setOfferAlternateOnError(v);
                    }}
                    className="w-5 h-5 accent-[#FFB800] cursor-pointer"
                  />
                </label>
                <p className="text-xs text-[#A0AEC0]">
                  عند الفشل يمكنك نسخ الرابط وفتحه في VLC أو أي مشغّل فيديو مثبّت على جهازك.
                </p>
              </div>
            </div>
          )}
        </main>
      </div>

      {/* Bottom Slogan Bar */}
      <footer className="flex items-center justify-between px-4 py-2 rounded-2xl bg-[#131722] border border-[#253048]/80 text-xs">
        <div className="flex items-center gap-4 sm:gap-6 text-[#A0AEC0]">
          <span className="flex items-center gap-1.5">
            <Clapperboard className="w-4 h-4 text-[#4F8CFF]" />
            مسلسلات
          </span>
          <span className="flex items-center gap-1.5">
            <Film className="w-4 h-4 text-[#FFB800]" />
            أفلام
          </span>
          <span className="flex items-center gap-1.5">
            <Tv className="w-4 h-4 text-[#00D2FF]" />
            بث مباشر
          </span>
        </div>

        <span className="font-extrabold bg-gradient-to-l from-[#00D2FF] via-[#7BE6FF] to-[#18E0C0] bg-clip-text text-transparent">
          ◆ مشاهدة بلا حدود ◆
        </span>

        <span className="text-[#A0AEC0] font-bold">الحر الأصلي</span>
      </footer>

      {/* Privacy Modal */}
      {privacyMode && (
        <PrivacyModal
          initialMode={privacyMode}
          client={client}
          onClose={() => setPrivacyMode(null)}
          onChanged={refreshPrivacyMeta}
          onToast={onToast}
        />
      )}

      {/* Accounts Modal */}
      {accountsOpen && (
        <div
          data-modal-root="true"
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 backdrop-blur-sm p-4"
          onClick={() => setAccountsOpen(false)}
        >
          <div
            onClick={(e) => e.stopPropagation()}
            className="w-full max-w-md rounded-2xl bg-[#131722] border border-[#253048] shadow-2xl overflow-hidden"
          >
            <div className="flex items-center justify-between px-5 py-4 border-b border-[#253048]">
              <h3 className="text-sm font-extrabold text-white">الحسابات المحفوظة</h3>
              <button
                type="button"
                data-modal-close="true"
                autoFocus
                onClick={() => setAccountsOpen(false)}
                className="text-slate-400 hover:text-white cursor-pointer"
              >
                <X className="w-4 h-4" />
              </button>
            </div>
            <div className="p-4 space-y-2.5 max-h-[65vh] overflow-y-auto">
              {savedAccounts.length === 0 ? (
                <p className="py-6 text-center text-xs text-[#A0AEC0]">لا توجد حسابات محفوظة.</p>
              ) : (
                savedAccounts.map((acc) => {
                  const accServer = resolveServer(
                    acc.server,
                    acc.username,
                    acc.password,
                    DEFAULT_SERVER
                  );
                  const accIsFree = isFreeServer(accServer);
                  const isCurrent =
                    accServer === account.server &&
                    acc.username.trim().toLowerCase() === account.username.trim().toLowerCase();
                  return (
                    <div
                      key={`${acc.server}::${acc.username}`}
                      className={`p-3 rounded-xl border flex items-center justify-between gap-2 ${
                        isCurrent
                          ? 'bg-[#00D2FF]/15 border-[#00D2FF]'
                          : 'bg-[#1C2230] border-[#253048]'
                      }`}
                    >
                      <div className="min-w-0">
                        <div dir="ltr" className="text-sm font-extrabold text-white text-right truncate">
                          {acc.username}
                        </div>
                        <div
                          dir="rtl"
                          className="text-[11px] text-[#A0AEC0] text-right truncate"
                        >
                          {accIsFree ? 'حساب مجاني — القنوات والأفلام والمسلسلات' : 'حساب مدفوع محفوظ'}
                        </div>
                      </div>
                      <div className="flex items-center gap-1.5 shrink-0">
                        {!isCurrent && (
                          <button
                            type="button"
                            onClick={async () => {
                              setAccountsOpen(false);
                              SyncManager.reset();
                              await db.clearAllTables().catch(() => {});
                              CredentialStore.save(accServer, acc.username, acc.password);
                              const c = new XtreamClient(accServer, acc.username, acc.password);
                              const res = await c.account();
                              if (!res?.user_info) {
                                onToast('تعذر الاتصال بالسيرفر لهذا الحساب.');
                              }
                              onSwitchAccount(
                                { server: accServer, username: acc.username, password: acc.password },
                                res?.user_info
                              );
                            }}
                            className="px-3 py-1.5 rounded-lg bg-[#00D2FF] text-[#001018] text-xs font-extrabold cursor-pointer"
                          >
                            دخول
                          </button>
                        )}
                        <button
                          type="button"
                          onClick={() => {
                            CredentialStore.removeAccount(acc.server, acc.username);
                            setSavedAccounts(CredentialStore.accounts());
                          }}
                          className="p-1.5 rounded-lg bg-[#E8384F]/20 text-[#FF7B8B] hover:bg-[#E8384F]/35 cursor-pointer"
                          title="حذف"
                        >
                          <Trash2 className="w-3.5 h-3.5" />
                        </button>
                      </div>
                    </div>
                  );
                })
              )}
            </div>
          </div>
        </div>
      )}

      {/* Reset Confirm */}
      {resetConfirmOpen && (
        <div
          data-modal-root="true"
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 backdrop-blur-sm p-4"
        >
          <div className="w-full max-w-sm rounded-2xl bg-[#131722] border border-[#253048] p-6 space-y-4">
            <h3 className="text-base font-extrabold text-white">إعادة تعيين الكود</h3>
            <p className="text-xs text-[#A0AEC0]">
              سيتم مسح المكتبة المحفوظة وإعادة مزامنة القنوات والأفلام والمسلسلات من السيرفر.
            </p>
            <div className="flex justify-end gap-2">
              <button
                type="button"
                data-modal-close="true"
                autoFocus
                onClick={() => setResetConfirmOpen(false)}
                className="px-4 py-2 rounded-xl bg-[#1C2230] text-xs font-bold text-white cursor-pointer"
              >
                إلغاء
              </button>
              <button
                type="button"
                onClick={async () => {
                  setResetConfirmOpen(false);
                  SyncManager.reset();
                  await db.clearAllTables().catch(() => {});
                  onResetAndReload();
                }}
                className="px-4 py-2 rounded-xl bg-[#00D2FF] text-[#001018] text-xs font-extrabold cursor-pointer"
              >
                تأكيد
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Logout Confirm */}
      {logoutConfirmOpen && (
        <div
          data-modal-root="true"
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 backdrop-blur-sm p-4"
        >
          <div className="w-full max-w-sm rounded-2xl bg-[#131722] border border-[#253048] p-6 space-y-4">
            <h3 className="text-base font-extrabold text-white">تسجيل الخروج</h3>
            <p className="text-xs text-[#A0AEC0]">
              سيتم حذف المحتوى المحفوظ على الجهاز وبيانات الدخول.
            </p>
            <div className="flex justify-end gap-2">
              <button
                type="button"
                data-modal-close="true"
                autoFocus
                onClick={() => setLogoutConfirmOpen(false)}
                className="px-4 py-2 rounded-xl bg-[#1C2230] text-xs font-bold text-white cursor-pointer"
              >
                إلغاء
              </button>
              <button
                type="button"
                onClick={() => {
                  setLogoutConfirmOpen(false);
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
    </div>
  );
};
