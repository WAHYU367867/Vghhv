import React, { useMemo, useState } from 'react';
import {
  ArrowRight,
  Link2,
  Lock,
  Play,
  Plus,
  Search,
  ShieldCheck,
  Trash2,
  Tv,
  X,
} from 'lucide-react';
import { AdultChannel, AdultStore, parseM3uText } from '../storage/adultStore';
import { AdultAgeGateModal } from '../components/AdultAgeGateModal';
import { proxiedImageUrl } from '../utils/arabic';

interface AdultScreenProps {
  onBack: () => void;
  onPlayChannel: (channel: AdultChannel) => void;
  onToast: (msg: string) => void;
}

export const AdultScreen: React.FC<AdultScreenProps> = ({
  onBack,
  onPlayChannel,
  onToast,
}) => {
  const [enabled] = useState(() => AdultStore.isEnabled());
  const [ageVerified, setAgeVerified] = useState(() => AdultStore.isAgeConfirmed());
  const [channels, setChannels] = useState<AdultChannel[]>(() => AdultStore.getChannels());
  const [selectedCategory, setSelectedCategory] = useState('');
  const [query, setQuery] = useState('');

  const [addOpen, setAddOpen] = useState(false);
  const [m3uOpen, setM3uOpen] = useState(false);

  // Single channel form
  const [chName, setChName] = useState('');
  const [chUrl, setChUrl] = useState('');
  const [chCategory, setChCategory] = useState('مصادر مصرح بها');
  const [chLogo, setChLogo] = useState('');
  const [chAuthorized, setChAuthorized] = useState(false);
  const [chError, setChError] = useState('');

  // M3U import form
  const [m3uUrl, setM3uUrl] = useState('');
  const [m3uText, setM3uText] = useState('');
  const [m3uAuthorized, setM3uAuthorized] = useState(false);
  const [m3uBusy, setM3uBusy] = useState(false);
  const [m3uError, setM3uError] = useState('');

  const reloadChannels = () => {
    setChannels(AdultStore.getChannels());
  };

  const categories = useMemo(() => {
    const set = new Set<string>();
    channels.forEach((c) => {
      if (c.category) set.add(c.category);
    });
    return Array.from(set);
  }, [channels]);

  const visibleChannels = useMemo(() => {
    const q = query.trim().toLowerCase();
    return channels.filter((ch) => {
      if (selectedCategory && ch.category !== selectedCategory) return false;
      if (
        q &&
        !ch.name.toLowerCase().includes(q) &&
        !ch.category.toLowerCase().includes(q)
      ) {
        return false;
      }
      return true;
    });
  }, [channels, selectedCategory, query]);

  if (!enabled) {
    return (
      <div
        dir="rtl"
        className="min-h-screen w-full bg-hex-space flex items-center justify-center p-4"
      >
        <div className="w-full max-w-md rounded-2xl bg-[#131722] border border-[#253048] p-6 text-center space-y-4">
          <h2 className="text-lg font-extrabold text-white">قسم Adult 18+ معطّل</h2>
          <p className="text-xs sm:text-sm text-[#A0AEC0]">
            يمكنك تفعيل هذا القسم في أي وقت من الإعدادات ← الأمان والخصوصية.
          </p>
          <button
            type="button"
            onClick={onBack}
            className="px-5 py-2.5 rounded-xl bg-[#00D2FF] text-[#001018] text-xs font-extrabold cursor-pointer"
          >
            رجوع
          </button>
        </div>
      </div>
    );
  }

  if (!ageVerified) {
    return (
      <div dir="rtl" className="min-h-screen w-full bg-hex-space">
        <AdultAgeGateModal
          onConfirm={() => {
            AdultStore.confirmAge();
            setAgeVerified(true);
          }}
          onCancel={onBack}
        />
      </div>
    );
  }

  const handleAddSingle = (e: React.FormEvent) => {
    e.preventDefault();
    if (!chName.trim()) {
      setChError('يرجى إدخال اسم القناة.');
      return;
    }
    if (!AdultStore.isValidStreamUrl(chUrl)) {
      setChError('يرجى إدخال رابط بث صحيح يبدأ بـ http:// أو https://');
      return;
    }
    if (!chAuthorized) {
      setChError('يرجى الإقرار بأن مصدر البث قانوني ومصرح به.');
      return;
    }
    AdultStore.addChannel({
      name: chName,
      streamUrl: chUrl,
      category: chCategory,
      logoUrl: chLogo,
      sourceLabel: 'مصدر مباشر مصرح به',
    });
    reloadChannels();
    setChName('');
    setChUrl('');
    setChLogo('');
    setChAuthorized(false);
    setChError('');
    setAddOpen(false);
    onToast('تمت إضافة القناة إلى قسم Adult 18+');
  };

  const handleImportM3u = async (e: React.FormEvent) => {
    e.preventDefault();
    setM3uError('');
    if (!m3uAuthorized) {
      setM3uError('يرجى الإقرار بأن القائمة من مصدر بث قانوني ومصرح به.');
      return;
    }

    let raw = m3uText.trim();
    if (!raw && m3uUrl.trim()) {
      if (!AdultStore.isValidStreamUrl(m3uUrl)) {
        setM3uError('رابط القائمة غير صالح.');
        return;
      }
      setM3uBusy(true);
      try {
        const res = await fetch(`/api/proxy?url=${encodeURIComponent(m3uUrl.trim())}`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        raw = await res.text();
      } catch {
        try {
          const direct = await fetch(m3uUrl.trim());
          if (!direct.ok) throw new Error(`HTTP ${direct.status}`);
          raw = await direct.text();
        } catch {
          setM3uBusy(false);
          setM3uError('تعذر جلب قائمة M3U. تأكد من الرابط أو الصق محتوى القائمة مباشرة.');
          return;
        }
      }
      setM3uBusy(false);
    }

    if (!raw) {
      setM3uError('أدخل رابط M3U أو الصق محتوى القائمة.');
      return;
    }

    const parsed = parseM3uText(raw, 'قائمة M3U مصرح بها');
    if (parsed.length === 0) {
      setM3uError('لم يتم العثور على روابط بث صالحة داخل القائمة.');
      return;
    }

    const addedCount = AdultStore.importChannels(parsed);
    reloadChannels();
    setM3uUrl('');
    setM3uText('');
    setM3uAuthorized(false);
    setM3uOpen(false);
    onToast(
      addedCount > 0
        ? `تم استيراد ${addedCount} قناة مصرح بها بنجاح`
        : 'القنوات موجودة مسبقاً في القسم'
    );
  };

  return (
    <div dir="rtl" className="min-h-screen w-full bg-hex-space flex flex-col">
      {/* Header */}
      <header className="sticky top-0 z-20 bg-[#0A0C10]/90 backdrop-blur-md border-b border-[#253048] px-4 sm:px-6 py-3 flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <button
            type="button"
            data-tv-autofocus="true"
            onClick={onBack}
            className="w-9 h-9 rounded-xl bg-[#131722] hover:bg-[#1C2230] border border-[#253048] hover:border-[#00D2FF] flex items-center justify-center text-white cursor-pointer"
            title="رجوع"
          >
            <ArrowRight className="w-4 h-4" />
          </button>
          <span className="px-2 py-0.5 rounded-md bg-[#E8384F] text-white text-xs font-black">
            18+
          </span>
          <div>
            <h1 className="text-base sm:text-lg font-extrabold text-white">Adult 18+</h1>
            <p className="text-[11px] text-[#A0AEC0]">
              قسم مستقل · قنوات للبالغين فقط من مصادر قانونية ومصرح بها
            </p>
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <div className="relative">
            <Search className="w-4 h-4 text-[#A0AEC0] absolute right-3 top-1/2 -translate-y-1/2" />
            <input
              type="text"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="ابحث داخل Adult 18+…"
              className="w-44 sm:w-56 rounded-xl bg-[#131722] border border-[#253048] focus:border-[#00D2FF] pr-9 pl-3 py-2 text-xs font-bold text-white outline-none"
            />
          </div>

          <button
            type="button"
            onClick={() => setAddOpen(true)}
            className="px-3.5 py-2 rounded-xl bg-[#00D2FF] hover:bg-[#36D5FF] text-[#001018] text-xs font-extrabold flex items-center gap-1.5 cursor-pointer"
          >
            <Plus className="w-4 h-4" />
            <span>إضافة قناة</span>
          </button>

          <button
            type="button"
            onClick={() => setM3uOpen(true)}
            className="px-3.5 py-2 rounded-xl bg-[#131722] hover:bg-[#1C2230] border border-[#253048] hover:border-[#00D2FF] text-white text-xs font-bold flex items-center gap-1.5 cursor-pointer"
          >
            <Link2 className="w-4 h-4 text-[#00D2FF]" />
            <span>استيراد M3U</span>
          </button>

          <button
            type="button"
            onClick={() => {
              AdultStore.clearAgeConfirmation();
              onBack();
            }}
            className="px-3 py-2 rounded-xl bg-[#131722] hover:bg-[#E8384F]/20 border border-[#253048] hover:border-[#E8384F] text-[#FF7B8B] text-xs font-bold flex items-center gap-1.5 cursor-pointer"
            title="قفل القسم والخروج"
          >
            <Lock className="w-3.5 h-3.5" />
            <span className="hidden sm:inline">قفل القسم</span>
          </button>
        </div>
      </header>

      {/* Category Chips */}
      {categories.length > 0 && (
        <div className="px-4 sm:px-6 py-2.5 border-b border-[#253048]/60 flex items-center gap-2 overflow-x-auto no-scrollbar">
          <button
            type="button"
            onClick={() => setSelectedCategory('')}
            className={`shrink-0 px-3.5 py-1.5 rounded-full text-xs font-extrabold border transition cursor-pointer ${
              selectedCategory === ''
                ? 'bg-[#00D2FF] border-[#00D2FF] text-[#001018]'
                : 'bg-[#131722] border-[#253048] text-slate-300 hover:border-[#00D2FF]'
            }`}
          >
            الكل ({channels.length})
          </button>
          {categories.map((cat) => (
            <button
              key={cat}
              type="button"
              onClick={() => setSelectedCategory(cat)}
              className={`shrink-0 px-3.5 py-1.5 rounded-full text-xs font-extrabold border transition cursor-pointer ${
                selectedCategory === cat
                  ? 'bg-[#00D2FF] border-[#00D2FF] text-[#001018]'
                  : 'bg-[#131722] border-[#253048] text-slate-300 hover:border-[#00D2FF]'
              }`}
            >
              {cat}
            </button>
          ))}
        </div>
      )}

      {/* Body */}
      <main className="flex-1 p-4 sm:p-6 space-y-4">
        {/* Authorized compliance banner */}
        <div className="rounded-2xl bg-[#131722]/85 border border-[#253048] px-4 py-3 flex flex-wrap items-center justify-between gap-2 text-xs text-[#A0AEC0]">
          <div className="flex items-center gap-2">
            <ShieldCheck className="w-4 h-4 text-[#25D9BD] shrink-0" />
            <span>
              تُشغَّل القنوات عبر مشغّل الفيديو الداخلي للتطبيق دون أي تعديل على القنوات العادية أو
              الأفلام والمسلسلات.
            </span>
          </div>
          <span className="text-[11px] font-bold text-[#FF7B8B]">+18 · مصادر مصرح بها فقط</span>
        </div>

        {visibleChannels.length === 0 ? (
          <div className="rounded-2xl bg-[#131722]/85 border border-[#253048] p-8 sm:p-12 text-center space-y-4 max-w-2xl mx-auto mt-6">
            <div className="w-14 h-14 rounded-2xl bg-[#E8384F]/15 border border-[#E8384F]/40 text-[#FF7B8B] flex items-center justify-center mx-auto">
              <Tv className="w-7 h-7" />
            </div>
            <h2 className="text-lg font-extrabold text-white">
              {channels.length === 0
                ? 'لا توجد قنوات مضافة في قسم Adult 18+ بعد'
                : 'لا توجد نتائج مطابقة للبحث'}
            </h2>
            <p className="text-xs sm:text-sm text-[#A0AEC0] leading-relaxed">
              {channels.length === 0
                ? 'أضف قنوات من مصادر بث قانونية ومصرح بها (روابط HLS / MP4 مباشرة أو قوائم M3U مرخّصة) لتشغيلها داخل هذا القسم المستقل.'
                : 'جرّب تعديل كلمة البحث أو اختيار تصنيف آخر.'}
            </p>
            {channels.length === 0 && (
              <div className="flex flex-wrap items-center justify-center gap-2.5 pt-2">
                <button
                  type="button"
                  onClick={() => setAddOpen(true)}
                  className="px-5 py-2.5 rounded-xl bg-[#00D2FF] text-[#001018] text-xs font-extrabold flex items-center gap-1.5 cursor-pointer"
                >
                  <Plus className="w-4 h-4" />
                  <span>إضافة قناة مصرح بها</span>
                </button>
                <button
                  type="button"
                  onClick={() => setM3uOpen(true)}
                  className="px-5 py-2.5 rounded-xl bg-[#1C2230] border border-[#253048] hover:border-[#00D2FF] text-white text-xs font-bold flex items-center gap-1.5 cursor-pointer"
                >
                  <Link2 className="w-4 h-4 text-[#00D2FF]" />
                  <span>استيراد قائمة M3U</span>
                </button>
              </div>
            )}
          </div>
        ) : (
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3.5">
            {visibleChannels.map((ch) => {
              const logo = ch.logoUrl ? proxiedImageUrl(ch.logoUrl) : '';
              return (
                <div
                  key={ch.id}
                  className="rounded-2xl bg-[#131722]/85 border border-[#253048] hover:border-[#00D2FF] p-3.5 flex items-center gap-3 transition group"
                >
                  <button
                    type="button"
                    onClick={() => onPlayChannel(ch)}
                    className="flex-1 min-w-0 flex items-center gap-3 text-right cursor-pointer"
                  >
                    <div className="w-14 h-12 rounded-xl bg-[#0F131F] border border-[#253048] flex items-center justify-center overflow-hidden shrink-0">
                      {logo ? (
                        <img
                          src={logo}
                          alt={ch.name}
                          className="w-full h-full object-contain p-1"
                        />
                      ) : (
                        <span className="text-xs font-black text-[#FF7B8B]">18+</span>
                      )}
                    </div>
                    <div className="min-w-0 flex-1">
                      <div className="text-sm font-extrabold text-white group-hover:text-[#00D2FF] truncate">
                        {ch.name}
                      </div>
                      <div className="flex items-center gap-2 text-[11px] text-[#A0AEC0] mt-0.5 truncate">
                        <span className="text-[#25D9BD] font-bold">{ch.category}</span>
                        <span>·</span>
                        <span className="truncate">{ch.sourceLabel}</span>
                      </div>
                    </div>
                    <span className="w-9 h-9 rounded-full bg-[#00D2FF]/15 text-[#00D2FF] group-hover:bg-[#00D2FF] group-hover:text-[#001018] flex items-center justify-center shrink-0 transition">
                      <Play className="w-4 h-4 fill-current" />
                    </span>
                  </button>

                  <button
                    type="button"
                    onClick={() => {
                      AdultStore.removeChannel(ch.id);
                      reloadChannels();
                      onToast(`تم حذف ${ch.name}`);
                    }}
                    className="w-8 h-8 rounded-lg bg-[#1C2230] hover:bg-[#E8384F]/25 text-[#A0AEC0] hover:text-[#FF7B8B] flex items-center justify-center shrink-0 cursor-pointer"
                    title="حذف القناة"
                  >
                    <Trash2 className="w-3.5 h-3.5" />
                  </button>
                </div>
              );
            })}
          </div>
        )}
      </main>

      {/* Add Single Channel Modal */}
      {addOpen && (
        <div
          data-modal-root="true"
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 backdrop-blur-sm p-4"
          onClick={() => setAddOpen(false)}
        >
          <form
            onSubmit={handleAddSingle}
            onClick={(e) => e.stopPropagation()}
            className="w-full max-w-md rounded-2xl bg-[#131722] border border-[#253048] shadow-2xl overflow-hidden"
          >
            <div className="flex items-center justify-between px-5 py-4 border-b border-[#253048] bg-[#0F131E]">
              <h3 className="text-base font-extrabold text-white">إضافة قناة مصرح بها (+18)</h3>
              <button
                type="button"
                data-modal-close="true"
                onClick={() => setAddOpen(false)}
                className="text-slate-400 hover:text-white cursor-pointer"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="p-5 space-y-3">
              <div>
                <label className="block text-xs font-bold text-[#A0AEC0] mb-1">اسم القناة</label>
                <input
                  type="text"
                  autoFocus
                  value={chName}
                  onChange={(e) => setChName(e.target.value)}
                  placeholder="مثال: قناة البالغين المصرح بها"
                  className="w-full rounded-xl bg-[#1C2230] border border-[#253048] focus:border-[#00D2FF] px-3 py-2.5 text-sm text-white outline-none"
                />
              </div>

              <div>
                <label className="block text-xs font-bold text-[#A0AEC0] mb-1">
                  رابط البث (HLS .m3u8 أو MP4)
                </label>
                <input
                  type="url"
                  dir="ltr"
                  value={chUrl}
                  onChange={(e) => setChUrl(e.target.value)}
                  placeholder="https://example.com/stream/index.m3u8"
                  className="w-full rounded-xl bg-[#1C2230] border border-[#253048] focus:border-[#00D2FF] px-3 py-2.5 text-sm text-white outline-none"
                />
              </div>

              <div className="grid grid-cols-2 gap-2.5">
                <div>
                  <label className="block text-xs font-bold text-[#A0AEC0] mb-1">التصنيف</label>
                  <input
                    type="text"
                    value={chCategory}
                    onChange={(e) => setChCategory(e.target.value)}
                    className="w-full rounded-xl bg-[#1C2230] border border-[#253048] focus:border-[#00D2FF] px-3 py-2 text-xs text-white outline-none"
                  />
                </div>
                <div>
                  <label className="block text-xs font-bold text-[#A0AEC0] mb-1">
                    رابط الشعار (اختياري)
                  </label>
                  <input
                    type="url"
                    dir="ltr"
                    value={chLogo}
                    onChange={(e) => setChLogo(e.target.value)}
                    placeholder="https://..."
                    className="w-full rounded-xl bg-[#1C2230] border border-[#253048] focus:border-[#00D2FF] px-3 py-2 text-xs text-white outline-none"
                  />
                </div>
              </div>

              <label className="flex items-center gap-2.5 rounded-xl bg-[#1C2230] border border-[#253048] p-3 cursor-pointer">
                <input
                  type="checkbox"
                  checked={chAuthorized}
                  onChange={(e) => setChAuthorized(e.target.checked)}
                  className="w-4 h-4 accent-[#00D2FF] cursor-pointer"
                />
                <span className="text-xs font-bold text-slate-200">
                  أقر بأن مصدر البث قانوني ومصرح ببثه
                </span>
              </label>

              {chError && <p className="text-xs font-bold text-[#FF7B8B]">{chError}</p>}

              <div className="flex justify-end gap-2 pt-1">
                <button
                  type="button"
                  onClick={() => setAddOpen(false)}
                  className="px-4 py-2 rounded-xl bg-[#1C2230] text-xs font-bold text-white cursor-pointer"
                >
                  إلغاء
                </button>
                <button
                  type="submit"
                  className="px-5 py-2 rounded-xl bg-[#00D2FF] text-[#001018] text-xs font-extrabold cursor-pointer"
                >
                  حفظ القناة
                </button>
              </div>
            </div>
          </form>
        </div>
      )}

      {/* Import M3U Modal */}
      {m3uOpen && (
        <div
          data-modal-root="true"
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 backdrop-blur-sm p-4"
          onClick={() => setM3uOpen(false)}
        >
          <form
            onSubmit={(e) => void handleImportM3u(e)}
            onClick={(e) => e.stopPropagation()}
            className="w-full max-w-md rounded-2xl bg-[#131722] border border-[#253048] shadow-2xl overflow-hidden"
          >
            <div className="flex items-center justify-between px-5 py-4 border-b border-[#253048] bg-[#0F131E]">
              <h3 className="text-base font-extrabold text-white">استيراد قائمة M3U مصرح بها</h3>
              <button
                type="button"
                data-modal-close="true"
                onClick={() => setM3uOpen(false)}
                className="text-slate-400 hover:text-white cursor-pointer"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="p-5 space-y-3">
              <div>
                <label className="block text-xs font-bold text-[#A0AEC0] mb-1">
                  رابط قائمة M3U / M3U8
                </label>
                <input
                  type="url"
                  dir="ltr"
                  autoFocus
                  value={m3uUrl}
                  onChange={(e) => setM3uUrl(e.target.value)}
                  placeholder="https://example.com/authorized-channels.m3u"
                  className="w-full rounded-xl bg-[#1C2230] border border-[#253048] focus:border-[#00D2FF] px-3 py-2.5 text-sm text-white outline-none"
                />
              </div>

              <div>
                <label className="block text-xs font-bold text-[#A0AEC0] mb-1">
                  أو الصق محتوى M3U مباشرة
                </label>
                <textarea
                  dir="ltr"
                  rows={4}
                  value={m3uText}
                  onChange={(e) => setM3uText(e.target.value)}
                  placeholder={'#EXTM3U\n#EXTINF:-1 group-title="Adult",Channel Name\nhttps://example.com/live.m3u8'}
                  className="w-full rounded-xl bg-[#1C2230] border border-[#253048] focus:border-[#00D2FF] px-3 py-2 text-xs text-white outline-none"
                />
              </div>

              <label className="flex items-center gap-2.5 rounded-xl bg-[#1C2230] border border-[#253048] p-3 cursor-pointer">
                <input
                  type="checkbox"
                  checked={m3uAuthorized}
                  onChange={(e) => setM3uAuthorized(e.target.checked)}
                  className="w-4 h-4 accent-[#00D2FF] cursor-pointer"
                />
                <span className="text-xs font-bold text-slate-200">
                  أقر بأن جميع مصادر هذه القائمة قانونية ومصرح بها
                </span>
              </label>

              {m3uError && <p className="text-xs font-bold text-[#FF7B8B]">{m3uError}</p>}

              <div className="flex justify-end gap-2 pt-1">
                <button
                  type="button"
                  onClick={() => setM3uOpen(false)}
                  className="px-4 py-2 rounded-xl bg-[#1C2230] text-xs font-bold text-white cursor-pointer"
                >
                  إلغاء
                </button>
                <button
                  type="submit"
                  disabled={m3uBusy}
                  className="px-5 py-2 rounded-xl bg-[#00D2FF] text-[#001018] text-xs font-extrabold cursor-pointer disabled:opacity-60"
                >
                  {m3uBusy ? 'جارٍ الاستيراد…' : 'استيراد القنوات'}
                </button>
              </div>
            </div>
          </form>
        </div>
      )}
    </div>
  );
};
