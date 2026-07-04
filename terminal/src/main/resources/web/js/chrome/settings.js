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
  const redund = view.querySelector('.js-suppress-redundant');
  const lang = view.querySelector('.js-language');
  const auto = view.querySelector('.js-auto-update');

  if (images) images.addEventListener('change',
      () => socket.send('settings', { command: 'set', key: 'analyzeImages', value: images.checked }));
  if (redund) redund.addEventListener('change',
      () => socket.send('settings', { command: 'set', key: 'suppressRedundant', value: redund.checked }));
  if (lang) lang.addEventListener('change',
      () => socket.send('settings', { command: 'set', key: 'language', value: lang.value }));
  if (auto) auto.addEventListener('change',
      () => socket.send('settings', { command: 'set', key: 'autoUpdate', value: auto.checked }));

  // ---- LLM backend ("bring your own API key") ----
  const llmBackend = view.querySelector('.js-llm-backend');
  const llmApiKey = view.querySelector('.js-llm-apikey');
  const llmBaseUrl = view.querySelector('.js-llm-baseurl');
  const llmChatModel = view.querySelector('.js-llm-chatmodel');
  const llmEmbedModel = view.querySelector('.js-llm-embedmodel');
  const llmEmbedBaseUrl = view.querySelector('.js-llm-embedbaseurl');
  const llmEmbedApiKey = view.querySelector('.js-llm-embedapikey');

  // Show only the fields that matter for the chosen backend. The base-URL row is
  // OpenAI-only (Anthropic uses its own fixed endpoint); the separate embed
  // base-URL/key rows are Anthropic-only (Claude has no embeddings API).
  function syncLlmVisibility() {
    const backend = llmBackend ? llmBackend.value : 'ollama';
    const remote = backend !== 'ollama';
    view.querySelectorAll('.js-llm-remote').forEach(el => {
      let show = remote;
      if (el.classList.contains('js-llm-openai')) show = backend === 'openai';
      if (el.classList.contains('js-llm-anthropic')) show = backend === 'anthropic';
      el.hidden = !show;
    });
  }

  const sendLlm = (key, value) => socket.send('settings', { command: 'set', key, value });

  if (llmBackend) llmBackend.addEventListener('change', () => {
    sendLlm('llmBackend', llmBackend.value);
    syncLlmVisibility();
  });
  // Text/secret fields persist on blur (change), not per keystroke.
  if (llmApiKey) llmApiKey.addEventListener('change', () => sendLlm('llmApiKey', llmApiKey.value));
  if (llmBaseUrl) llmBaseUrl.addEventListener('change', () => sendLlm('llmBaseUrl', llmBaseUrl.value));
  if (llmChatModel) llmChatModel.addEventListener('change', () => sendLlm('llmChatModel', llmChatModel.value));
  if (llmEmbedModel) llmEmbedModel.addEventListener('change', () => sendLlm('llmEmbedModel', llmEmbedModel.value));
  if (llmEmbedBaseUrl) llmEmbedBaseUrl.addEventListener('change', () => sendLlm('llmEmbedBaseUrl', llmEmbedBaseUrl.value));
  if (llmEmbedApiKey) llmEmbedApiKey.addEventListener('change', () => sendLlm('llmEmbedApiKey', llmEmbedApiKey.value));

  // Backend echoes the persisted snapshot on connect + after every change.
  socket.on('settings', payload => {
    if (!payload) return;
    if (images && typeof payload.analyzeImages === 'boolean') images.checked = payload.analyzeImages;
    if (redund && typeof payload.suppressRedundant === 'boolean') redund.checked = payload.suppressRedundant;
    if (lang && payload.language) lang.value = payload.language;
    if (auto && typeof payload.autoUpdate === 'boolean') auto.checked = payload.autoUpdate;
    // LLM backend fields — don't clobber a field the user is mid-edit in.
    const fill = (el, val) => {
      if (el && typeof val === 'string' && document.activeElement !== el) el.value = val;
    };
    if (llmBackend && payload.llmBackend) { llmBackend.value = payload.llmBackend; syncLlmVisibility(); }
    fill(llmApiKey, payload.llmApiKey);
    fill(llmBaseUrl, payload.llmBaseUrl);
    fill(llmChatModel, payload.llmChatModel);
    fill(llmEmbedModel, payload.llmEmbedModel);
    fill(llmEmbedBaseUrl, payload.llmEmbedBaseUrl);
    fill(llmEmbedApiKey, payload.llmEmbedApiKey);
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
