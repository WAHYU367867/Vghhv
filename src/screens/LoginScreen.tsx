import React, { useEffect, useState } from 'react';
import {
  CheckSquare,
  Download,
  Eye,
  EyeOff,
  HelpCircle,
  Lock,
  MessageCircle,
  Play,
  Server,
  ShieldCheck,
  Square,
  Tv,
  User,
  X,
} from 'lucide-react';
import { SavedAccount, UserInfo } from '../types';
import { XtreamClient, SyncManager } from '../api/xtream';
import {
  FREE_PASS,
  FREE_SERVER,
  FREE_USER,
  freeAccountInfo,
  isFreeCredentials,
  isFreeServer,
} from '../api/freeCatalog';
import { CredentialStore, db } from '../storage/db';
import {
  APP_VERSION,
  DEFAULT_SERVER,
  downloadAndroidApk,
  downloadAndroidProject,
  SupportContact,
} from '../utils/arabic';

interface LoginScreenProps {
  addAccount?: boolean;
  onSuccess: (account: SavedAccount, userInfo?: UserInfo) => void;
  onCancelAdd?: () => void;
}

export const LoginScreen: React.FC<LoginScreenProps> = ({
  addAccount = false,
  onSuccess,
  onCancelAdd,
}) => {
  const [username, setUsername] = useState(() => (addAccount ? '' : CredentialStore.get()?.username || ''));
  const [password, setPassword] = useState(() => (addAccount ? '' : CredentialStore.get()?.password || ''));
  const [remember, setRemember] = useState(() => CredentialStore.rememberLogin());
  const [showPassword, setShowPassword] = useState(false);
  const [loading, setLoading] = useState(false);
  const [status, setStatus] = useState<{ kind: 'hint' | 'loading' | 'error' | 'warning'; text: string }>({
    kind: 'hint',
    text: 'تُحفظ بيانات الدخول مشفّرة على هذا الجهاز فقط',
  });
  const [helpOpen, setHelpOpen] = useState(false);
  const [downloadingAsset, setDownloadingAsset] = useState<'apk' | 'zip' | null>(null);

  useEffect(() => {
    if (addAccount) {
      setUsername('');
      setPassword('');
    }
  }, [addAccount]);

  const handleConnect = async (e?: React.FormEvent) => {
    e?.preventDefault();
    const user = username.trim();
    const pass = password;
    const isFree = isFreeCredentials(user, pass);
    const savedSrv = CredentialStore.get()?.server || DEFAULT_SERVER;
    const targetServer = isFree
      ? FREE_SERVER
      : isFreeServer(savedSrv) || !savedSrv
      ? DEFAULT_SERVER
      : savedSrv.replace(/\/+$/, '');

    if (!user) {
      setStatus({ kind: 'error', text: 'أدخل اسم المستخدم للمتابعة.' });
      return;
    }
    if (!pass) {
      setStatus({ kind: 'error', text: 'أدخل كلمة المرور للمتابعة.' });
      return;
    }

    setLoading(true);
    setStatus({
      kind: 'loading',
      text: isFree ? 'جارٍ تفعيل الباقة المجانية…' : 'جارٍ التحقق من بيانات الدخول…',
    });

    try {
      const account = isFree
        ? freeAccountInfo()
        : await new XtreamClient(targetServer, user, pass).account();
      const info = account?.user_info;
      const auth = account ? Number(info?.auth ?? account.auth ?? 0) : 0;
      const active = info?.status ? String(info.status).toLowerCase() === 'active' : true;

      if (auth === 1 && active) {
        const prev = CredentialStore.get();
        const switchingAccount =
          addAccount ||
          (prev !== null &&
            (prev.username.trim().toLowerCase() !== user.toLowerCase() ||
              isFreeServer(prev.server) !== isFree));
        if (switchingAccount) {
          SyncManager.reset();
          await db.clearAllTables().catch(() => {});
        }
        const savedUser = isFree ? FREE_USER : user;
        const savedPass = isFree ? FREE_PASS : pass;
        CredentialStore.setRememberLogin(remember);
        if (remember) {
          CredentialStore.save(targetServer, savedUser, savedPass);
          CredentialStore.addAccount(targetServer, savedUser, savedPass);
        } else {
          CredentialStore.clear();
        }
        onSuccess({ server: targetServer, username: savedUser, password: savedPass }, info);
      } else {
        setStatus({
          kind: 'error',
          text:
            account === null
              ? 'تعذر الاتصال بالخادم. تحقق من اتصال الإنترنت ثم حاول مرة أخرى.'
              : 'بيانات الدخول غير صحيحة أو الحساب غير نشط.',
        });
      }
    } catch {
      setStatus({
        kind: 'error',
        text: 'تعذر الاتصال بالخادم. تحقق من اتصال الإنترنت ثم حاول مرة أخرى.',
      });
    } finally {
      setLoading(false);
    }
  };

  return (
    <div dir="rtl" className="min-h-screen w-full bg-login-screen flex items-center justify-center p-3 sm:p-6">
      <div className="w-full max-w-5xl rounded-3xl border border-[#253048]/80 bg-[#0A0F22]/55 backdrop-blur-md shadow-[0_24px_80px_rgba(0,0,0,0.65)] overflow-hidden grid grid-cols-1 min-[520px]:grid-cols-12">
        {/* Right Column (43%): Login Form Card */}
        <div className="min-[520px]:col-span-5 p-5 sm:p-8 bg-[#0F1528]/82 border-b min-[520px]:border-b-0 min-[520px]:border-l border-[#253048]/70 flex flex-col justify-between">
          <div className="space-y-6">
            <div className="flex items-start justify-between">
              <div>
                <h1 className="text-2xl sm:text-3xl font-extrabold bg-gradient-to-l from-[#00D2FF] via-[#7BE6FF] to-white bg-clip-text text-transparent">
                  الحر الأصلي
                </h1>
                <p className="text-xs sm:text-sm text-[#A0AEC0] mt-1">
                  {addAccount ? 'إضافة حساب اشتراك جديد' : 'أدخل بيانات اشتراكك للمتابعة'}
                </p>
              </div>
              {addAccount && onCancelAdd && (
                <button
                  type="button"
                  onClick={onCancelAdd}
                  className="px-3 py-1.5 rounded-lg bg-[#1C2230] hover:bg-[#253048] text-xs font-bold text-slate-300 cursor-pointer"
                >
                  إلغاء
                </button>
              )}
            </div>

            <form onSubmit={handleConnect} className="space-y-4">
              {/* Username */}
              <div>
                <label className="block text-xs font-bold text-[#A0AEC0] mb-1.5">اسم المستخدم</label>
                <div className="relative flex items-center rounded-xl bg-[#181E30] border border-[#253048] focus-within:border-[#00D2FF] focus-within:ring-2 focus-within:ring-[#00D2FF]/25 transition">
                  <User className="w-4 h-4 text-[#00D2FF] mr-3.5 shrink-0" />
                  <input
                    type="text"
                    dir="ltr"
                    data-tv-autofocus="true"
                    value={username}
                    onChange={(e) => setUsername(e.target.value)}
                    placeholder="Username"
                    autoComplete="username"
                    className="w-full bg-transparent px-3 py-3 text-sm text-white placeholder:text-slate-500 outline-none text-left"
                  />
                </div>
              </div>

              {/* Password */}
              <div>
                <label className="block text-xs font-bold text-[#A0AEC0] mb-1.5">كلمة المرور</label>
                <div className="relative flex items-center rounded-xl bg-[#181E30] border border-[#253048] focus-within:border-[#00D2FF] focus-within:ring-2 focus-within:ring-[#00D2FF]/25 transition">
                  <Lock className="w-4 h-4 text-[#00D2FF] mr-3.5 shrink-0" />
                  <input
                    type={showPassword ? 'text' : 'password'}
                    dir="ltr"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    placeholder="Password"
                    autoComplete="current-password"
                    className="w-full bg-transparent px-3 py-3 text-sm text-white placeholder:text-slate-500 outline-none text-left"
                  />
                  <button
                    type="button"
                    onClick={() => setShowPassword((v) => !v)}
                    aria-label={showPassword ? 'إخفاء كلمة المرور' : 'إظهار كلمة المرور'}
                    className="pl-3 pr-2 text-slate-400 hover:text-[#00D2FF] transition cursor-pointer"
                  >
                    {showPassword ? <Eye className="w-4 h-4" /> : <EyeOff className="w-4 h-4" />}
                  </button>
                </div>
              </div>

              {/* Remember checkbox */}
              <button
                type="button"
                onClick={() => setRemember((r) => !r)}
                className="flex items-center gap-2.5 text-xs sm:text-sm text-slate-200 hover:text-white cursor-pointer"
              >
                {remember ? (
                  <CheckSquare className="w-4 h-4 text-[#00D2FF]" />
                ) : (
                  <Square className="w-4 h-4 text-[#64748B]" />
                )}
                <span>حفظ بيانات الدخول على هذا الجهاز</span>
              </button>

              {/* Status row */}
              {status.kind !== 'hint' && (
                <div
                  className={`flex items-center gap-2.5 px-3.5 py-2.5 rounded-xl text-xs font-bold border ${
                    status.kind === 'loading'
                      ? 'bg-[#00D2FF]/15 border-[#00D2FF]/45 text-[#7BE6FF]'
                      : status.kind === 'error'
                      ? 'bg-[#E8384F]/15 border-[#E8384F]/40 text-[#FF7A90]'
                      : 'bg-[#FFB800]/15 border-[#FFB800]/40 text-[#F5D477]'
                  }`}
                >
                  {status.kind === 'loading' && (
                    <span className="w-4 h-4 rounded-full border-2 border-[#7BE6FF]/30 border-t-[#7BE6FF] animate-spin shrink-0" />
                  )}
                  <span>{status.text}</span>
                </div>
              )}

              {/* Submit button */}
              <button
                type="submit"
                disabled={loading}
                className="w-full py-3.5 rounded-xl bg-gradient-to-l from-[#00D2FF] to-[#0A8FB5] hover:from-[#36D5FF] hover:to-[#00D2FF] text-[#001018] font-extrabold text-sm sm:text-base shadow-[0_0_24px_rgba(0,210,255,0.32)] transition cursor-pointer disabled:opacity-60"
              >
                {loading ? 'جارٍ الدخول…' : 'دخول'}
              </button>
            </form>
          </div>

          {/* Help, Downloads & Contact */}
          <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-2 pt-6 mt-6 border-t border-[#253048]/60 text-xs">
            <button
              type="button"
              onClick={() => setHelpOpen(true)}
              className="flex items-center gap-1.5 text-[#A0AEC0] hover:text-[#00D2FF] font-bold transition whitespace-nowrap shrink-0 cursor-pointer"
            >
              <HelpCircle className="w-4 h-4" />
              <span>مساعدة</span>
            </button>
            <button
              type="button"
              disabled={downloadingAsset !== null}
              onClick={async () => {
                setDownloadingAsset('apk');
                const ok = await downloadAndroidApk();
                setDownloadingAsset(null);
                if (!ok) {
                  setStatus({ kind: 'error', text: 'تعذر تحميل ملف APK الآن، حاول مرة أخرى.' });
                }
              }}
              className="flex items-center gap-1.5 text-[#25D9BD] hover:text-[#7BE6FF] font-extrabold transition whitespace-nowrap shrink-0 cursor-pointer disabled:opacity-60"
            >
              <Download className="w-4 h-4" />
              <span>
                {downloadingAsset === 'apk'
                  ? 'جارٍ التحميل…'
                  : `تحميل APK (${APP_VERSION})`}
              </span>
            </button>
            <button
              type="button"
              disabled={downloadingAsset !== null}
              onClick={async () => {
                setDownloadingAsset('zip');
                const ok = await downloadAndroidProject();
                setDownloadingAsset(null);
                if (!ok) {
                  setStatus({ kind: 'error', text: 'تعذر تحميل ملف المشروع الآن، حاول مرة أخرى.' });
                }
              }}
              className="flex items-center gap-1.5 text-[#00D2FF] hover:text-[#7BE6FF] font-bold transition whitespace-nowrap shrink-0 cursor-pointer disabled:opacity-60"
            >
              <Download className="w-4 h-4" />
              <span>{downloadingAsset === 'zip' ? 'جارٍ التحميل…' : 'كود المصدر ZIP'}</span>
            </button>
            <a
              href={SupportContact.whatsAppUrl(
                'السلام عليكم، أريد الاستفسار عن الحصول على حساب في تطبيق الحر الأصلي.'
              )}
              target="_blank"
              rel="noopener noreferrer"
              className="flex items-center gap-1.5 text-[#A0AEC0] hover:text-[#25D9BD] font-bold transition whitespace-nowrap shrink-0"
            >
              <MessageCircle className="w-4 h-4" />
              <span>تواصل معنا</span>
            </a>
          </div>
        </div>

        {/* Left Column (57%): Brand Showcase */}
        <div className="min-[520px]:col-span-7 p-5 sm:p-8 flex flex-col items-center justify-between text-center bg-gradient-to-br from-[#0B1228]/30 to-[#060A18]/45">
          <div className="w-full flex items-center justify-between text-[11px] text-[#A0AEC0]">
            <span className="px-2.5 py-1 rounded-md bg-white/5 border border-white/10 font-bold tracking-wider">
              AL-HUR AL-ASLI | IPTV
            </span>
            <span dir="ltr" className="font-mono text-[#00D2FF]">
              {APP_VERSION}
            </span>
          </div>

          <div className="my-6 flex flex-col items-center">
            <img
              src="/assets/logo_alhorr_login.png"
              alt="ALHUR TV"
              className="w-36 h-36 sm:w-44 sm:h-44 object-contain drop-shadow-[0_0_28px_rgba(0,210,255,0.28)]"
            />
            <h2 className="mt-4 text-xl sm:text-2xl font-extrabold text-white">مشاهدة بلا حدود</h2>
            <p className="mt-1.5 text-xs sm:text-sm text-[#A0AEC0] max-w-md">
              تجربة بث سينمائية للقنوات المباشرة وأحدث الأفلام والمسلسلات العربية والعالمية
            </p>
          </div>

          <div className="w-full grid grid-cols-2 gap-3 text-right">
            {[
              { icon: Tv, color: '#2FB4FF', title: 'بث مباشر مستقر', desc: 'قنوات رياضية وإخبارية وعربية' },
              { icon: Play, color: '#E8384F', title: 'أفلام ومسلسلات', desc: 'تحديثات مستمرة وجودة عالية' },
              { icon: ShieldCheck, color: '#25D9BD', title: 'حماية وخصوصية', desc: 'قفل الفئات والمحتوى بكلمة مرور' },
              { icon: Server, color: '#FFB800', title: 'متابعة ذكية', desc: 'استكمال المشاهدة من حيث توقفت' },
            ].map((f, idx) => (
              <div
                key={idx}
                className="flex items-start gap-2.5 p-3 rounded-xl bg-[#131722]/75 border border-[#253048]/70"
              >
                <div
                  className="w-8 h-8 rounded-lg flex items-center justify-center shrink-0"
                  style={{ backgroundColor: `${f.color}22`, color: f.color }}
                >
                  <f.icon className="w-4 h-4" />
                </div>
                <div className="min-w-0">
                  <div className="text-xs font-bold text-white">{f.title}</div>
                  <div className="text-[11px] text-[#A0AEC0] truncate mt-0.5">{f.desc}</div>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* Help & Server Config Modal */}
      {helpOpen && (
        <div
          data-modal-root="true"
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 backdrop-blur-sm p-4"
        >
          <div className="w-full max-w-md rounded-2xl bg-[#131722] border border-[#253048] shadow-2xl overflow-hidden">
            <div className="flex items-center justify-between px-5 py-4 border-b border-[#253048] bg-[#0F131E]">
              <h3 className="text-sm font-extrabold text-white">مساعدة في تسجيل الدخول</h3>
              <button
                type="button"
                data-modal-close="true"
                autoFocus
                onClick={() => setHelpOpen(false)}
                className="text-slate-400 hover:text-white cursor-pointer"
              >
                <X className="w-4 h-4" />
              </button>
            </div>
            <div className="p-5 space-y-4 text-xs sm:text-sm text-slate-300 leading-relaxed">
              <ul className="space-y-1.5 list-disc pr-4">
                <li>أدخل اسم المستخدم وكلمة المرور كما زوّدك بهما مزود الخدمة للاشتراك المدفوع.</li>
                <li>
                  للباقة المجانية (قنوات عربية مجانية + أفلام ومسلسلات مفتوحة): اسم المستخدم{' '}
                  <span dir="ltr" className="font-mono font-bold text-[#00D2FF]">{FREE_USER}</span> وكلمة المرور{' '}
                  <span dir="ltr" className="font-mono font-bold text-[#00D2FF]">{FREE_PASS}</span>.
                </li>
                <li>تأكد من اتصال الجهاز بالإنترنت وانتبه للأحرف الكبيرة والصغيرة.</li>
                <li>للحصول على حساب مدفوع تواصل معنا عبر واتساب: {SupportContact.DISPLAY}</li>
              </ul>

              <div className="flex items-center justify-between pt-2">
                <a
                  href={SupportContact.whatsAppUrl(
                    'السلام عليكم، أحتاج مساعدة في تسجيل الدخول إلى تطبيق الحر الأصلي.'
                  )}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="px-4 py-2 rounded-xl bg-[#25D9BD] text-[#001018] text-xs font-extrabold"
                >
                  تواصل عبر واتساب
                </a>
                <button
                  type="button"
                  onClick={() => setHelpOpen(false)}
                  className="px-4 py-2 rounded-xl bg-[#1C2230] hover:bg-[#253048] text-white text-xs font-bold cursor-pointer"
                >
                  إغلاق
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
