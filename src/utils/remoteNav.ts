interface RemoteNavOptions {
  onBack: () => void;
}

const FOCUSABLE_SELECTOR = [
  'button:not(:disabled)',
  'a[href]',
  'input:not(:disabled):not([type="hidden"])',
  'select:not(:disabled)',
  'textarea:not(:disabled)',
  '[tabindex]:not([tabindex="-1"])',
  '[role="button"]',
].join(', ');

function isVisible(el: HTMLElement): boolean {
  if (el.getAttribute('aria-disabled') === 'true') return false;
  const style = window.getComputedStyle(el);
  if (style.display === 'none' || style.visibility === 'hidden' || style.pointerEvents === 'none') {
    return false;
  }
  const rect = el.getBoundingClientRect();
  return rect.width > 1 && rect.height > 1;
}

function getScope(): HTMLElement {
  const modals = document.querySelectorAll<HTMLElement>('[data-modal-root="true"]');
  if (modals.length > 0) {
    return modals[modals.length - 1];
  }
  return document.body;
}

export function focusInitialTarget(container?: HTMLElement): void {
  const scope = container ?? getScope();
  const preferred = scope.querySelector<HTMLElement>('[data-tv-autofocus="true"]');
  if (preferred && isVisible(preferred)) {
    preferred.focus();
    preferred.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' });
    return;
  }
  const list = Array.from(scope.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR)).filter(isVisible);
  if (list.length > 0) {
    list[0].focus();
    list[0].scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' });
  }
}

function findNextFocusable(
  active: HTMLElement,
  direction: 'ArrowUp' | 'ArrowDown' | 'ArrowLeft' | 'ArrowRight',
  scope: HTMLElement
): HTMLElement | null {
  const candidates = Array.from(scope.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR)).filter(
    (el) => el !== active && isVisible(el)
  );
  if (candidates.length === 0) return null;

  const o = active.getBoundingClientRect();
  const ocx = o.left + o.width / 2;
  const ocy = o.top + o.height / 2;

  let best: HTMLElement | null = null;
  let bestScore = Number.POSITIVE_INFINITY;

  for (const el of candidates) {
    const r = el.getBoundingClientRect();
    const rcx = r.left + r.width / 2;
    const rcy = r.top + r.height / 2;

    const dx = rcx - ocx;
    const dy = rcy - ocy;

    let primary = 0;
    let cross = 0;
    let sameBand = false;

    if (direction === 'ArrowRight') {
      if (dx <= 6) continue;
      primary = dx;
      cross = Math.abs(dy);
      sameBand = Math.min(r.bottom, o.bottom) - Math.max(r.top, o.top) > 4;
    } else if (direction === 'ArrowLeft') {
      if (dx >= -6) continue;
      primary = -dx;
      cross = Math.abs(dy);
      sameBand = Math.min(r.bottom, o.bottom) - Math.max(r.top, o.top) > 4;
    } else if (direction === 'ArrowDown') {
      if (dy <= 6) continue;
      primary = dy;
      cross = Math.abs(dx);
      sameBand = Math.min(r.right, o.right) - Math.max(r.left, o.left) > 4;
    } else {
      if (dy >= -6) continue;
      primary = -dy;
      cross = Math.abs(dx);
      sameBand = Math.min(r.right, o.right) - Math.max(r.left, o.left) > 4;
    }

    const weight = sameBand ? 0.45 : 3.2;
    const score = primary + cross * weight;
    if (score < bestScore) {
      bestScore = score;
      best = el;
    }
  }

  return best;
}

export function installRemoteNav({ onBack }: RemoteNavOptions): () => void {
  const onKeyDown = (e: KeyboardEvent) => {
    if (e.defaultPrevented) return;
    // VideoPlayer manages its own D-pad & playback shortcuts when active
    if (document.querySelector('[data-player-overlay="true"]')) return;

    const active = document.activeElement as HTMLElement | null;
    const tag = active?.tagName ?? '';
    const isTextInput =
      tag === 'TEXTAREA' ||
      (tag === 'INPUT' &&
        !['checkbox', 'radio', 'button', 'submit'].includes(
          ((active as HTMLInputElement).type || 'text').toLowerCase()
        )) ||
      Boolean(active?.isContentEditable);

    // Back / Escape / Remote Back
    if (e.key === 'Escape' || e.key === 'BrowserBack' || e.key === 'GoBack' || (e.key === 'Backspace' && !isTextInput)) {
      e.preventDefault();
      const modals = document.querySelectorAll<HTMLElement>('[data-modal-root="true"]');
      if (modals.length > 0) {
        const topModal = modals[modals.length - 1];
        const closeBtn = topModal.querySelector<HTMLElement>('[data-modal-close="true"]');
        if (closeBtn) {
          closeBtn.click();
          return;
        }
      }
      onBack();
      return;
    }

    // D-pad Arrows
    if (
      e.key === 'ArrowUp' ||
      e.key === 'ArrowDown' ||
      e.key === 'ArrowLeft' ||
      e.key === 'ArrowRight'
    ) {
      if (isTextInput && (e.key === 'ArrowLeft' || e.key === 'ArrowRight')) {
        return; // keep caret movement inside text inputs
      }

      e.preventDefault();
      const scope = getScope();
      if (!active || !scope.contains(active) || active === document.body) {
        focusInitialTarget(scope);
        return;
      }

      const next = findNextFocusable(active, e.key, scope);
      if (next) {
        next.focus();
        next.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' });
      }
      return;
    }

    // Center / OK button fallback for custom focusables
    if (e.key === 'Enter' || e.key === 'NumpadEnter') {
      const scope = getScope();
      if (!active || active === document.body || !scope.contains(active)) {
        e.preventDefault();
        focusInitialTarget(scope);
      }
    }
  };

  window.addEventListener('keydown', onKeyDown);
  return () => window.removeEventListener('keydown', onKeyDown);
}
