import React, { useEffect, useState } from 'react';
import { Eye, EyeOff, Lock, Search, Shield, X } from 'lucide-react';
import { CategoryItem, ContentItem, Kinds } from '../types';
import { db, HiddenStore } from '../storage/db';
import { XtreamClient } from '../api/xtream';

type EntryMode = 'set_password' | 'ask_password' | 'forgot' | 'manager';
type ManagerTab = 'categories' | 'items' | 'hidden';

interface PrivacyModalProps {
  initialMode: EntryMode;
  client: XtreamClient;
  onClose: () => void;
  onChanged: () => void;
  onToast: (msg: string) => void;
}

export const PrivacyModal: React.FC<PrivacyModalProps> = ({
  initialMode,
  client,
  onClose,
  onChanged,
  onToast,
}) => {
  const [mode, setMode] = useState<EntryMode>(initialMode);
  const [pw, setPw] = useState('');
  const [pwConfirm, setPwConfirm] = useState('');
  const [showPw, setShowPw] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  // Manager state
  const [kind, setKind] = useState<string>(Kinds.LIVE);
  const [tab, setTab] = useState<ManagerTab>('categories');
  const [cats, setCats] = useState<Array<{ category: CategoryItem; hidden: boolean }>>([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [searchResults, setSearchResults] = useState<ContentItem[]>([]);
  const [hiddenItemsList, setHiddenItemsList] = useState<Array<{ id: string; name: string }>>([]);
  const [loadingList, setLoadingList] = useState(false);

  const loadManagerData = async (targetKind: string) => {
    setLoadingList(true);
    try {
      const managed = await db.manageCategories(targetKind);
      if (managed.length === 0) {
        await client.syncCategories(targetKind).catch(() => 0);
        setCats(await db.manageCategories(targetKind));
      } else {
        setCats(managed);
      }
      setHiddenItemsList(HiddenStore.hiddenItems(targetKind));
    } finally {
      setLoadingList(false);
    }
  };

  useEffect(() => {
    if (mode === 'manager') {
      void loadManagerData(kind);
    }
  }, [mode, kind]);

  useEffect(() => {
    if (mode !== 'manager' || tab !== 'items') return;
    let active = true;
    const timer = window.setTimeout(async () => {
      const res = await db.browse(kind, null, searchQuery, 50, 0);
      if (active) setSearchResults(res);
    }, 180);
    return () => {
      active = false;
      window.clearTimeout(timer);
    };
  }, [mode, tab, kind, searchQuery]);

  const handleSetPassword = async (e: React.FormEvent) => {
    e.preventDefault();
    if (pw.length < 4) {
      setError('كلمة المرور يجب أن تكون 4 أحرف أو أرقام على الأقل.');
      return;
    }
    if (pw !== pwConfirm) {
      setError('كلمتا المرور غير متطابقتين.');
      return;
    }
    setBusy(true);
    await HiddenStore.setPassword(pw);
    setBusy(false);
    setError('');
    setPw('');
    setPwConfirm('');
    onChanged();
    onToast('تم تعيين كلمة مرور الخصوصية.');
    setMode('manager');
  };

  const handleCheckPassword = async (e: React.FormEvent) => {
    e.preventDefault();
    const lockedSec = HiddenStore.lockedForSeconds();
    if (lockedSec > 0) {
      setError(`محاولات كثيرة خاطئة. انتظر ${lockedSec} ثانية.`);
      return;
    }
    setBusy(true);
    const ok = await HiddenStore.check(pw);
    setBusy(false);
    if (ok) {
      setError('');
      setPw('');
      setMode('manager');
    } else {
      const after = HiddenStore.lockedForSeconds();
      setError(after > 0 ? `تم القفل مؤقتًا لمدة ${after} ثانية.` : 'كلمة المرور غير صحيحة.');
    }
  };

  const handleForgotReset = async () => {
    setBusy(true);
    await db.showEverything();
    setBusy(false);
    onChanged();
    onToast('تمت إعادة تعيين الخصوصية وإظهار جميع الفئات والعناصر.');
    onClose();
  };

  const toggleCategory = async (categoryId: string, currentlyHidden: boolean) => {
    await db.setCategoryHidden(kind, categoryId, !currentlyHidden);
    await loadManagerData(kind);
    onChanged();
  };

  const hideItem = async (item: ContentItem) => {
    await db.setItemHidden(kind, item.contentId, item.name, true);
    setSearchResults((prev) => prev.filter((x) => x.contentId !== item.contentId));
    setHiddenItemsList(HiddenStore.hiddenItems(kind));
    onChanged();
    onToast(`تم إخفاء: ${item.name}`);
  };

  const unhideItem = async (id: string, name: string) => {
    await db.setItemHidden(kind, id, name, false);
    setHiddenItemsList(HiddenStore.hiddenItems(kind));
    onChanged();
    onToast(`تم إظهار: ${name}`);
  };

  return (
    <div
      data-modal-root="true"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 backdrop-blur-sm p-4"
      dir="rtl"
    >
      <div className="w-full max-w-2xl rounded-2xl bg-[#131722] border border-[#253048] shadow-2xl overflow-hidden flex flex-col max-h-[88vh]">
        {/* Header */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-[#253048] bg-[#0F131E]">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-[#00D2FF]/15 border border-[#00D2FF]/45 flex items-center justify-center text-[#00D2FF]">
              <Shield className="w-5 h-5" />
            </div>
            <div>
              <h3 className="text-base font-extrabold text-white">
                {mode === 'set_password'
                  ? 'تعيين كلمة مرور الخصوصية'
                  : mode === 'ask_password'
                  ? 'فتح قفل الخصوصية'
                  : mode === 'forgot'
                  ? 'نسيت كلمة المرور؟'
                  : 'إدارة المحتوى المخفي'}
              </h3>
              <p className="text-xs text-[#A0AEC0]">إخفاء الفئات أو القنوات والأفلام والمسلسلات بكلمة مرور</p>
            </div>
          </div>
          <button
            type="button"
            data-modal-close="true"
            onClick={onClose}
            className="w-8 h-8 rounded-lg bg-[#1C2230] hover:bg-[#253048] text-slate-300 flex items-center justify-center cursor-pointer"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Body */}
        {mode === 'set_password' && (
          <form onSubmit={handleSetPassword} className="p-6 space-y-4">
            <div className="relative">
              <input
                type={showPw ? 'text' : 'password'}
                value={pw}
                onChange={(e) => setPw(e.target.value)}
                placeholder="كلمة المرور (4 أحرف على الأقل)"
                className="w-full rounded-xl bg-[#1C2230] border border-[#253048] focus:border-[#00D2FF] px-4 py-3 text-white text-sm outline-none"
                autoFocus
              />
              <button
                type="button"
                onClick={() => setShowPw((v) => !v)}
                className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400 hover:text-white cursor-pointer"
              >
                {showPw ? <Eye className="w-4 h-4" /> : <EyeOff className="w-4 h-4" />}
              </button>
            </div>
            <input
              type={showPw ? 'text' : 'password'}
              value={pwConfirm}
              onChange={(e) => setPwConfirm(e.target.value)}
              placeholder="تأكيد كلمة المرور"
              className="w-full rounded-xl bg-[#1C2230] border border-[#253048] focus:border-[#00D2FF] px-4 py-3 text-white text-sm outline-none"
            />
            {error && <p className="text-xs font-bold text-[#FF7B8B]">{error}</p>}
            <div className="flex justify-end gap-3 pt-2">
              <button
                type="button"
                onClick={onClose}
                className="px-4 py-2.5 rounded-xl bg-[#1C2230] hover:bg-[#253048] text-slate-200 text-sm font-bold cursor-pointer"
              >
                إلغاء
              </button>
              <button
                type="submit"
                disabled={busy}
                className="px-5 py-2.5 rounded-xl bg-[#00D2FF] hover:bg-[#36D5FF] text-[#001018] text-sm font-extrabold cursor-pointer disabled:opacity-60"
              >
                حفظ ومتابعة
              </button>
            </div>
          </form>
        )}

        {mode === 'ask_password' && (
          <form onSubmit={handleCheckPassword} className="p-6 space-y-4">
            <div className="relative">
              <input
                type={showPw ? 'text' : 'password'}
                value={pw}
                onChange={(e) => setPw(e.target.value)}
                placeholder="أدخل كلمة مرور الخصوصية"
                className="w-full rounded-xl bg-[#1C2230] border border-[#253048] focus:border-[#00D2FF] px-4 py-3 text-white text-sm outline-none"
                autoFocus
              />
              <button
                type="button"
                onClick={() => setShowPw((v) => !v)}
                className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400 hover:text-white cursor-pointer"
              >
                {showPw ? <Eye className="w-4 h-4" /> : <EyeOff className="w-4 h-4" />}
              </button>
            </div>
            {error && <p className="text-xs font-bold text-[#FF7B8B]">{error}</p>}
            <div className="flex items-center justify-between pt-2">
              <button
                type="button"
                onClick={() => setMode('forgot')}
                className="text-xs font-bold text-[#FFB800] hover:underline cursor-pointer"
              >
                نسيت كلمة المرور؟
              </button>
              <div className="flex gap-3">
                <button
                  type="button"
                  onClick={onClose}
                  className="px-4 py-2.5 rounded-xl bg-[#1C2230] hover:bg-[#253048] text-slate-200 text-sm font-bold cursor-pointer"
                >
                  إلغاء
                </button>
                <button
                  type="submit"
                  disabled={busy}
                  className="px-5 py-2.5 rounded-xl bg-[#00D2FF] hover:bg-[#36D5FF] text-[#001018] text-sm font-extrabold flex items-center gap-2 cursor-pointer"
                >
                  <Lock className="w-4 h-4" />
                  <span>فتح</span>
                </button>
              </div>
            </div>
          </form>
        )}

        {mode === 'forgot' && (
          <div className="p-6 space-y-4">
            <p className="text-sm text-slate-300 leading-relaxed">
              سيؤدي إعادة التعيين إلى إزالة كلمة مرور الخصوصية وإظهار جميع الفئات والعناصر المخفية فورًا. هل تريد المتابعة؟
            </p>
            <div className="flex justify-end gap-3 pt-2">
              <button
                type="button"
                onClick={onClose}
                className="px-4 py-2.5 rounded-xl bg-[#1C2230] hover:bg-[#253048] text-slate-200 text-sm font-bold cursor-pointer"
              >
                إلغاء
              </button>
              <button
                type="button"
                disabled={busy}
                onClick={handleForgotReset}
                className="px-5 py-2.5 rounded-xl bg-[#E8384F] hover:bg-[#ff4b62] text-white text-sm font-extrabold cursor-pointer"
              >
                إعادة تعيين وإظهار الكل
              </button>
            </div>
          </div>
        )}

        {mode === 'manager' && (
          <div className="flex flex-col flex-1 min-h-0">
            {/* Kind switcher */}
            <div className="flex items-center gap-2 px-5 pt-3 pb-2 border-b border-[#253048]/60">
              {[Kinds.LIVE, Kinds.MOVIES, Kinds.SERIES].map((k) => (
                <button
                  key={k}
                  type="button"
                  onClick={() => setKind(k)}
                  className={`px-3.5 py-1.5 rounded-lg text-xs font-bold transition cursor-pointer ${
                    kind === k
                      ? 'bg-[#00D2FF] text-[#001018]'
                      : 'bg-[#1C2230] text-slate-300 hover:bg-[#253048]'
                  }`}
                >
                  {Kinds.title(k)}
                </button>
              ))}
            </div>

            {/* Sub-tabs */}
            <div className="flex items-center gap-2 px-5 py-2.5 bg-[#0F131E]/60 border-b border-[#253048]/60">
              {[
                { id: 'categories' as const, label: 'التصنيفات' },
                { id: 'items' as const, label: 'إخفاء بالاسم' },
                { id: 'hidden' as const, label: `المخفي حاليًا (${hiddenItemsList.length})` },
              ].map((t) => (
                <button
                  key={t.id}
                  type="button"
                  onClick={() => setTab(t.id)}
                  className={`px-3 py-1.5 rounded-lg text-xs font-bold transition cursor-pointer ${
                    tab === t.id
                      ? 'bg-[#00D2FF]/20 text-[#7BE6FF] border border-[#00D2FF]/50'
                      : 'text-[#A0AEC0] hover:text-white'
                  }`}
                >
                  {t.label}
                </button>
              ))}
            </div>

            {/* Content */}
            <div className="flex-1 overflow-y-auto p-5 space-y-2">
              {loadingList ? (
                <div className="py-12 text-center text-sm text-[#A0AEC0]">جارٍ التحميل…</div>
              ) : tab === 'categories' ? (
                cats.length === 0 ? (
                  <div className="py-10 text-center text-sm text-[#A0AEC0]">لا توجد تصنيفات متاحة حاليًا.</div>
                ) : (
                  cats.map(({ category, hidden }) => (
                    <div
                      key={category.categoryId}
                      className="flex items-center justify-between px-4 py-3 rounded-xl bg-[#1C2230] border border-[#253048]"
                    >
                      <span className="text-sm font-bold text-white">{category.categoryName}</span>
                      <button
                        type="button"
                        onClick={() => toggleCategory(category.categoryId, hidden)}
                        className={`px-3.5 py-1.5 rounded-lg text-xs font-bold cursor-pointer transition ${
                          hidden
                            ? 'bg-[#25D9BD]/20 text-[#25D9BD] border border-[#25D9BD]/40 hover:bg-[#25D9BD]/30'
                            : 'bg-[#E8384F]/20 text-[#FF7B8B] border border-[#E8384F]/40 hover:bg-[#E8384F]/30'
                        }`}
                      >
                        {hidden ? 'إظهار' : 'إخفاء'}
                      </button>
                    </div>
                  ))
                )
              ) : tab === 'items' ? (
                <div className="space-y-3">
                  <div className="relative">
                    <Search className="w-4 h-4 text-[#A0AEC0] absolute right-3.5 top-1/2 -translate-y-1/2" />
                    <input
                      type="text"
                      value={searchQuery}
                      onChange={(e) => setSearchQuery(e.target.value)}
                      placeholder="ابحث بالاسم لإخفاء قناة أو فيلم أو مسلسل…"
                      className="w-full rounded-xl bg-[#1C2230] border border-[#253048] focus:border-[#00D2FF] pr-10 pl-4 py-2.5 text-sm text-white outline-none"
                    />
                  </div>
                  {searchResults.length === 0 ? (
                    <div className="py-8 text-center text-sm text-[#A0AEC0]">لا توجد نتائج مطابقة.</div>
                  ) : (
                    searchResults.map((item) => (
                      <div
                        key={item.pk}
                        className="flex items-center justify-between px-4 py-2.5 rounded-xl bg-[#1C2230] border border-[#253048]"
                      >
                        <span className="text-sm font-semibold text-white truncate">{item.name}</span>
                        <button
                          type="button"
                          onClick={() => hideItem(item)}
                          className="px-3 py-1 rounded-lg text-xs font-bold bg-[#E8384F]/20 text-[#FF7B8B] border border-[#E8384F]/40 hover:bg-[#E8384F]/35 cursor-pointer shrink-0 mr-2"
                        >
                          إخفاء
                        </button>
                      </div>
                    ))
                  )}
                </div>
              ) : hiddenItemsList.length === 0 ? (
                <div className="py-10 text-center text-sm text-[#A0AEC0]">لا توجد عناصر مخفية في هذا القسم.</div>
              ) : (
                hiddenItemsList.map((hi) => (
                  <div
                    key={hi.id}
                    className="flex items-center justify-between px-4 py-3 rounded-xl bg-[#1C2230] border border-[#253048]"
                  >
                    <span className="text-sm font-bold text-white">{hi.name}</span>
                    <button
                      type="button"
                      onClick={() => unhideItem(hi.id, hi.name)}
                      className="px-3.5 py-1.5 rounded-lg text-xs font-bold bg-[#25D9BD]/20 text-[#25D9BD] border border-[#25D9BD]/40 hover:bg-[#25D9BD]/30 cursor-pointer"
                    >
                      إظهار
                    </button>
                  </div>
                ))
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
};
