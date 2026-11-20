import React, { useState } from 'react';
import { AlertTriangle, ShieldAlert, X } from 'lucide-react';

interface AdultAgeGateModalProps {
  onConfirm: () => void;
  onCancel: () => void;
}

export const AdultAgeGateModal: React.FC<AdultAgeGateModalProps> = ({
  onConfirm,
  onCancel,
}) => {
  const [checked, setChecked] = useState(false);
  const [error, setError] = useState('');

  const handleConfirm = () => {
    if (!checked) {
      setError('يرجى تأكيد أن عمرك 18 عاماً فأكثر للمتابعة.');
      return;
    }
    onConfirm();
  };

  return (
    <div
      dir="rtl"
      data-modal-root="true"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 backdrop-blur-sm p-4"
      onClick={onCancel}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className="w-full max-w-md rounded-2xl bg-[#131722] border border-[#E8384F]/60 shadow-[0_0_40px_rgba(232,56,79,0.22)] overflow-hidden"
      >
        <div className="flex items-center justify-between px-5 py-4 border-b border-[#253048] bg-[#0F131E]">
          <div className="flex items-center gap-2.5">
            <span className="px-2 py-0.5 rounded-md bg-[#E8384F] text-white text-xs font-black tracking-wide">
              18+
            </span>
            <h3 className="text-base font-extrabold text-white">تحذير: قسم للبالغين فقط</h3>
          </div>
          <button
            type="button"
            data-modal-close="true"
            onClick={onCancel}
            className="text-slate-400 hover:text-white cursor-pointer"
            title="إغلاق"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className="p-5 space-y-4">
          <div className="flex items-start gap-3 rounded-xl bg-[#E8384F]/12 border border-[#E8384F]/40 p-3.5">
            <AlertTriangle className="w-5 h-5 text-[#FF7B8B] shrink-0 mt-0.5" />
            <p className="text-xs sm:text-sm text-slate-200 leading-relaxed">
              أنت على وشك الدخول إلى قسم <strong className="text-white">Adult 18+</strong> المخصص
              للبالغين فقط (18 عاماً فأكثر). لن يتم عرض أي محتوى قبل تأكيد عمرك صراحةً.
            </p>
          </div>

          <div className="flex items-start gap-2.5 rounded-xl bg-[#1C2230] border border-[#253048] p-3 text-xs text-[#A0AEC0] leading-relaxed">
            <ShieldAlert className="w-4 h-4 text-[#00D2FF] shrink-0 mt-0.5" />
            <span>
              القسم مستقل تماماً عن القنوات العادية والأفلام والمسلسلات، ويسمح بإضافة قنوات من
              مصادر بث قانونية ومصرح بها فقط.
            </span>
          </div>

          <label className="flex items-center gap-3 rounded-xl bg-[#1C2230] border border-[#253048] hover:border-[#00D2FF]/60 p-3 cursor-pointer">
            <input
              type="checkbox"
              checked={checked}
              onChange={(e) => {
                setChecked(e.target.checked);
                if (e.target.checked) setError('');
              }}
              className="w-4 h-4 accent-[#E8384F] cursor-pointer shrink-0"
            />
            <span className="text-xs sm:text-sm font-bold text-white">
              أؤكد أن عمري 18 عاماً فأكثر وأتحمل مسؤولية الدخول
            </span>
          </label>

          {error && (
            <div className="text-xs font-bold text-[#FF7B8B] text-center">{error}</div>
          )}

          <div className="flex items-center justify-end gap-2 pt-1">
            <button
              type="button"
              autoFocus
              onClick={onCancel}
              className="px-4 py-2.5 rounded-xl bg-[#1C2230] hover:bg-[#253048] border border-[#253048] text-xs font-bold text-white cursor-pointer"
            >
              إلغاء والرجوع
            </button>
            <button
              type="button"
              onClick={handleConfirm}
              className="px-5 py-2.5 rounded-xl bg-[#E8384F] hover:brightness-110 text-white text-xs font-extrabold cursor-pointer shadow-lg"
            >
              تأكيد العمر والدخول (+18)
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};
