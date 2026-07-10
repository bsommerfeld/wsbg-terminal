// Settings view: open/close, plus wiring for every control.
//
// Appearance (theme + "follow system") is client-side (localStorage, via
// theme.js). The rest — headline mode, language,
// auto-update — is config-backed: each change is sent over the socket to the
// SettingsBridge, which persists it and echoes the full snapshot back so the
// controls reflect the stored state on every client.

import { currentTheme, toggleTheme, isFollowingSystem, setFollowSystem } from './theme.js';
import { setLang, t } from '../i18n/i18n.js';

export function initSettings(socket) {
  const main = document.querySelector('.main');
  const view = document.getElementById('settings-view');
  if (!main || !view) return;

  const open = () => { view.hidden = false; main.classList.add('settings-open'); syncAppearance(); };
  const close = () => {
    view.hidden = true;
    main.classList.remove('settings-open');
    // widget-nav restores the view the settings were opened from (a focused
    // widget / the grid), which it parked on the dashboard while we were open.
    window.dispatchEvent(new CustomEvent('wsbg:settingsclosed'));
  };

  document.querySelectorAll('.js-settings-toggle').forEach(b => b.addEventListener('click', open));
  document.querySelectorAll('.js-settings-close').forEach(b => b.addEventListener('click', close));
  document.addEventListener('keydown', e => { if (e.key === 'Escape' && !view.hidden) close(); });

  // ---- Appearance (client-side) ----
  const sw = view.querySelector('.js-theme-switch');
  const sys = view.querySelector('.js-theme-system');

  function syncAppearance() {
    const following = isFollowingSystem();
    if (sys) sys.checked = following;
    if (sw) {
      sw.setAttribute('aria-checked', String(currentTheme() === 'light'));
      sw.setAttribute('aria-disabled', String(following));
    }
  }

  if (sw) sw.addEventListener('click', () => {
    if (isFollowingSystem()) return; // locked while following the OS
    toggleTheme();
    syncAppearance();
  });
  if (sys) sys.addEventListener('change', () => {
    setFollowSystem(sys.checked);
    syncAppearance();
  });
  syncAppearance();

  // ---- Inline "ⓘ" info toggles: reveal the longer explanation on demand ----
  view.querySelectorAll('.js-info').forEach(btn => {
    const more = btn.closest('.setting-label')?.querySelector('.setting-more');
    if (!more) return;
    btn.addEventListener('click', () => {
      const reveal = more.hidden;
      more.hidden = !reveal;
      btn.setAttribute('aria-expanded', String(reveal));
    });
  });

  // ---- Config-backed settings (over the socket) ----
  // (analyzeImages has no control here anymore — it lives in the Schlagzeilen
  // widget's rail settings, wired in widget-rail.js off the same snapshot.)
  const lang = view.querySelector('.js-language');
  const auto = view.querySelector('.js-auto-update');

  if (lang) lang.addEventListener('change',
      () => socket.send('settings', { command: 'set', key: 'language', value: lang.value }));
  if (auto) auto.addEventListener('change',
      () => socket.send('settings', { command: 'set', key: 'autoUpdate', value: auto.checked }));

  // Backend echoes the persisted snapshot on connect + after every change.
  socket.on('settings', payload => {
    if (!payload) return;
    // Re-broadcast for other consumers of the snapshot (the socket allows one
    // handler per topic) — the focus-rail's Schlagzeilen settings sync off this.
    window.dispatchEvent(new CustomEvent('wsbg:settings', { detail: payload }));
    if (lang && payload.language) lang.value = payload.language;
    if (auto && typeof payload.autoUpdate === 'boolean') auto.checked = payload.autoUpdate;
    // Drive the whole UI language off the persisted setting: applies on connect
    // (the backend echoes the snapshot) and live after every change — no restart.
    if (payload.language) setLang(payload.language);
  });

  // ---- Update indicator (titlebar green download button; backend in Phase 5) ----
  const updateBtn = document.querySelector('.js-update-apply');
  socket.on('update-available', payload => {
    if (updateBtn) updateBtn.hidden = !(payload && payload.available);
  });
  if (updateBtn) updateBtn.addEventListener('click',
      () => socket.send('update', { command: 'apply' }));

  // ---- Open the app-data folder ("Zu den Logs") ----
  const openLogsBtn = view.querySelector('.js-open-logs');
  if (openLogsBtn) openLogsBtn.addEventListener('click',
      () => socket.send('settings', { command: 'open-logs' }));

  // ---- Destructive: full data wipe (two-click arm, no OSR-unfriendly confirm()) ----
  // Confirmed → a visual 10-min cooldown that mirrors the server-side gate, then re-arm.
  // The button lives in the Schlagzeilen widget's rail settings (document-wide
  // lookup), not in this view — the wiring stays here beside its socket command.
  armedButton(document.querySelector('.js-clear-data'), {
    armLabel: () => t('settings.data.clear.btn'),
    confirmLabel: () => t('settings.data.clear.confirm'),
    doneLabel: () => t('settings.data.clear.done'),
    cooldownMs: 600000,
    onConfirm: () => socket.send('settings', { command: 'clear-data' }),
  });

  // ---- Destructive: full uninstall (same two-click arm; the app exits) ----
  // No re-enable: the backend shuts the app down and the OS takes over.
  armedButton(view.querySelector('.js-uninstall'), {
    armLabel: () => t('settings.data.uninstall.btn'),
    confirmLabel: () => t('settings.data.uninstall.confirm'),
    busyLabel: () => t('settings.data.uninstall.working'),
    onConfirm: () => socket.send('uninstall', { command: 'apply' }),
  });
}

// Two-click "arm → confirm → disarm" destructive button. First click arms it
// (swaps to confirmLabel + .armed, auto-disarms after armMs). Second click fires
// onConfirm and disables the button: with cooldownMs it shows doneLabel then
// re-arms after the cooldown; without it shows busyLabel and stays disabled.
// Labels are thunks so they follow live language changes. No-op if btn is absent.
function armedButton(btn, opts) {
  if (!btn) return;
  const { armLabel, confirmLabel, doneLabel, busyLabel, armMs = 4000, cooldownMs, onConfirm } = opts;
  let armed = false;
  let armTimer = null;
  const disarm = () => {
    armed = false;
    btn.classList.remove('armed');
    btn.textContent = armLabel();
  };
  btn.addEventListener('click', () => {
    if (btn.disabled) return;
    if (!armed) {
      armed = true;
      btn.classList.add('armed');
      btn.textContent = confirmLabel();
      clearTimeout(armTimer);
      armTimer = setTimeout(disarm, armMs); // un-arm if not confirmed
      return;
    }
    clearTimeout(armTimer);
    armed = false;
    onConfirm();
    btn.disabled = true;
    btn.classList.remove('armed');
    if (cooldownMs) {
      btn.textContent = doneLabel();
      setTimeout(() => { btn.disabled = false; btn.textContent = armLabel(); }, cooldownMs);
    } else {
      btn.textContent = busyLabel();
    }
  });
}
