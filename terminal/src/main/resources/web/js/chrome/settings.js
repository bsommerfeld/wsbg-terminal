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
  const close = () => { view.hidden = true; main.classList.remove('settings-open'); };

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
  const images = view.querySelector('.js-analyze-images');
  const lang = view.querySelector('.js-language');
  const auto = view.querySelector('.js-auto-update');

  if (images) images.addEventListener('change',
      () => socket.send('settings', { command: 'set', key: 'analyzeImages', value: images.checked }));
  if (lang) lang.addEventListener('change',
      () => socket.send('settings', { command: 'set', key: 'language', value: lang.value }));
  if (auto) auto.addEventListener('change',
      () => socket.send('settings', { command: 'set', key: 'autoUpdate', value: auto.checked }));

  // Backend echoes the persisted snapshot on connect + after every change.
  socket.on('settings', payload => {
    if (!payload) return;
    if (images && typeof payload.analyzeImages === 'boolean') images.checked = payload.analyzeImages;
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
  const clearBtn = view.querySelector('.js-clear-data');
  if (clearBtn) {
    let armed = false;
    let armTimer = null;
    const disarm = () => {
      armed = false;
      clearBtn.classList.remove('armed');
      clearBtn.textContent = t('settings.data.clear.btn');
    };
    clearBtn.addEventListener('click', () => {
      if (clearBtn.disabled) return;
      if (!armed) {
        armed = true;
        clearBtn.classList.add('armed');
        clearBtn.textContent = t('settings.data.clear.confirm');
        clearTimeout(armTimer);
        armTimer = setTimeout(disarm, 4000); // un-arm if not confirmed
        return;
      }
      clearTimeout(armTimer);
      disarm();
      socket.send('settings', { command: 'clear-data' });
      // Visual 10-min cooldown that mirrors the server-side gate.
      clearBtn.disabled = true;
      clearBtn.textContent = t('settings.data.clear.done');
      setTimeout(() => { clearBtn.disabled = false; clearBtn.textContent = t('settings.data.clear.btn'); }, 600000);
    });
  }

  // ---- Destructive: full uninstall (same two-click arm; the app exits) ----
  const uninstallBtn = view.querySelector('.js-uninstall');
  if (uninstallBtn) {
    let armed = false;
    let armTimer = null;
    const disarm = () => {
      armed = false;
      uninstallBtn.classList.remove('armed');
      uninstallBtn.textContent = t('settings.data.uninstall.btn');
    };
    uninstallBtn.addEventListener('click', () => {
      if (uninstallBtn.disabled) return;
      if (!armed) {
        armed = true;
        uninstallBtn.classList.add('armed');
        uninstallBtn.textContent = t('settings.data.uninstall.confirm');
        clearTimeout(armTimer);
        armTimer = setTimeout(disarm, 4000); // un-arm if not confirmed
        return;
      }
      clearTimeout(armTimer);
      socket.send('uninstall', { command: 'apply' });
      // No re-enable: the backend shuts the app down and the OS takes over.
      uninstallBtn.disabled = true;
      uninstallBtn.classList.remove('armed');
      uninstallBtn.textContent = t('settings.data.uninstall.working');
    });
  }
}
