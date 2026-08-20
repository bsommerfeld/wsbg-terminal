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

  // ---- The red warning glyph: same mechanics as the "ⓘ", own text ----
  view.querySelectorAll('.js-warn').forEach(btn => {
    const text = btn.closest('.setting-label')?.querySelector('.setting-warn-text');
    if (!text) return;
    btn.addEventListener('click', () => {
      const reveal = text.hidden;
      text.hidden = !reveal;
      btn.setAttribute('aria-expanded', String(reveal));
    });
  });

  // ---- Config-backed settings (over the socket) ----
  const lang = view.querySelector('.js-language');
  const auto = view.querySelector('.js-auto-update');
  const experimental = view.querySelector('.js-experimental-updates');

  if (lang) lang.addEventListener('change',
      () => socket.send('settings', { command: 'set', key: 'language', value: lang.value }));
  if (auto) auto.addEventListener('change',
      () => socket.send('settings', { command: 'set', key: 'autoUpdate', value: auto.checked }));
  if (experimental) experimental.addEventListener('change',
      () => socket.send('settings', { command: 'set', key: 'experimentalUpdates', value: experimental.checked }));

  // ---- Advanced: which AI runs the terminal ----
  // Every field here writes the same way as the rows above (one 'set' per
  // change, backend echoes the snapshot). Two things are special: the remote
  // block is hidden unless the switch is on, and the model list for it only
  // exists after a successful connection test.
  const aiRemote = view.querySelector('.js-ai-remote');
  const aiToggle = view.querySelector('.js-ai-toggle');
  const aiBody = view.querySelector('#settings-advanced');
  const aiUrl = view.querySelector('.js-ai-url');
  const aiApi = view.querySelector('.js-ai-api');
  const aiModel = view.querySelector('.js-ai-model');
  const aiAuth = view.querySelector('.js-ai-auth');
  const aiCtx = view.querySelector('.js-ai-ctx');
  const aiSlots = view.querySelector('.js-ai-slots');
  const aiPicker = view.querySelector('.js-model-picker');
  const aiTrigger = view.querySelector('.js-ai-model-trigger') || view.querySelector('.js-model-trigger');
  const aiList = view.querySelector('.js-model-list');
  const aiRestart = view.querySelector('.js-restart-cta');
  const aiTest = view.querySelector('.js-ai-test');
  const aiTestResult = view.querySelector('.js-ai-test-result');

  const set = (key, value) => socket.send('settings', { command: 'set', key, value });

  // The category folds; it does not appear and disappear. Collapsed by default
  // because almost nobody needs it.
  if (aiToggle && aiBody) aiToggle.addEventListener('click', () => {
    const open = aiToggle.getAttribute('aria-expanded') === 'true';
    aiToggle.setAttribute('aria-expanded', String(!open));
    aiBody.hidden = open;
  });

  if (aiRemote) aiRemote.addEventListener('change', () => {
    applyEndpointEnabled(aiRemote.checked);
    set('aiEndpointMode', aiRemote.checked ? 'remote' : 'managed');
  });
  // 'change' rather than 'input': one write when the user is done typing, not
  // one per keystroke — each of them persists the file and rebuilds the model
  // handles against a half-typed address.
  if (aiUrl) aiUrl.addEventListener('change', () => set('aiEndpointUrl', aiUrl.value));
  if (aiApi) aiApi.addEventListener('change', () => set('aiEndpointApi', aiApi.value));
  if (aiModel) aiModel.addEventListener('change', () => set('aiEndpointModel', aiModel.value));
  if (aiAuth) aiAuth.addEventListener('change', () => set('aiEndpointAuth', aiAuth.value));
  if (aiCtx) aiCtx.addEventListener('change', () => set('aiEndpointContext', num(aiCtx.value)));
  if (aiSlots) aiSlots.addEventListener('change', () => set('aiEndpointSlots', num(aiSlots.value)));
  // The picker: click opens, click on a row chooses, click anywhere closes.
  if (aiTrigger && aiList) {
    aiTrigger.addEventListener('click', e => {
      e.stopPropagation();
      const open = !aiList.hidden;
      aiList.hidden = open;
      aiTrigger.setAttribute('aria-expanded', String(!open));
    });
    document.addEventListener('click', () => {
      if (!aiList.hidden) {
        aiList.hidden = true;
        aiTrigger.setAttribute('aria-expanded', 'false');
      }
    });
  }
  if (view.querySelector('.js-restart')) {
    view.querySelector('.js-restart').addEventListener('click',
        () => socket.send('settings', { command: 'restart' }));
  }

  if (aiTest) aiTest.addEventListener('click', () => {
    aiTest.disabled = true;
    showTestResult(t('settings.ai.test.running'), null);
    // The values as they stand in the FIELDS, not as stored: the whole point is
    // to check an address before committing to it.
    socket.send('settings', {
      command: 'test-endpoint',
      url: aiUrl ? aiUrl.value : '',
      auth: aiAuth ? aiAuth.value : '',
    });
  });

  socket.on('ai-endpoint-test', payload => {
    if (aiTest) aiTest.disabled = false;
    if (!payload) return;
    const models = payload.models || [];
    fillModelList(models);
    if (payload.ok) {
      // The probe found out which chat API answers, so the field is SET rather
      // than left for the user to guess at. Persisted too - a detection the
      // page knows and the config does not would be worse than no detection.
      if (payload.api && aiApi && aiApi.value !== payload.api) {
        aiApi.value = payload.api;
        set('aiEndpointApi', payload.api);
      }
      const which = payload.api === 'openai' ? 'OpenAI' : 'Ollama';
      // The reason is non-empty on a reachable-but-empty server ("no models
      // installed") — worth saying, and not a failure.
      showTestResult(payload.reason
        ? `${t('settings.ai.test.ok')} (${which}) - ${payload.reason}`
        : `${t('settings.ai.test.ok')} (${which}, ${models.length})`, 'ok');
    } else {
      // The server's own words, appended verbatim.
      showTestResult(`${t('settings.ai.test.fail')}: ${payload.reason || '?'}`, 'fail');
    }
  });

  /**
   * Enables the endpoint fields, disables the managed model choice, or the
   * other way round. Nothing is hidden: the two halves of this category are
   * mutually exclusive, and saying so by dimming keeps the rows where the eye
   * last saw them. The managed model select would otherwise promise a download
   * that an external endpoint makes sure never happens.
   */
  function applyEndpointEnabled(remote) {
    for (const el of [aiUrl, aiApi, aiModel, aiAuth, aiCtx, aiSlots, aiTest]) {
      if (!el) continue;
      el.disabled = !remote;
      el.closest('.setting-row')?.classList.toggle('is-off', !remote);
    }
    if (aiTrigger) {
      aiTrigger.disabled = remote;
      aiTrigger.closest('.setting-row')?.classList.toggle('is-off', remote);
      if (remote && aiList) aiList.hidden = true;
    }
  }

  function showTestResult(text, state) {
    if (!aiTestResult) return;
    aiTestResult.textContent = text;
    // Drop the data-i18n binding: applyStatic() would overwrite the verdict
    // with the generic hint on the next language switch.
    aiTestResult.removeAttribute('data-i18n');
    if (state) aiTestResult.dataset.state = state;
    else delete aiTestResult.dataset.state;
  }

  function fillModelList(models) {
    const list = document.getElementById('ai-endpoint-models');
    if (!list) return;
    list.replaceChildren(...models.map(name => {
      const opt = document.createElement('option');
      opt.value = name;
      return opt;
    }));
  }

  function num(value) {
    const n = parseInt(value, 10);
    return Number.isFinite(n) && n > 0 ? n : 0;
  }

  /** A check for "already on this machine", a download arrow for "on next start". */
  const MARK_HERE = '<svg viewBox="0 0 24 24"><polyline points="20 6 9 17 4 12"/></svg>';
  const MARK_PENDING = '<svg viewBox="0 0 24 24">'
    + '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>'
    + '<polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>';
  const CARET = '<svg class="model-caret" viewBox="0 0 24 24"><polyline points="6 9 12 15 18 9"/></svg>';

  /** One row's inner markup - shared by the closed trigger and the open list. */
  function modelRow(tier, here) {
    const mark = here ? MARK_HERE : MARK_PENDING;
    const cls = here ? 'is-here' : 'is-pending';
    const size = tier.size ? `<span class="model-chip">${tier.size}</span>` : '';
    return `<span class="model-mark ${cls}">${mark}</span>`
      + `<span class="model-name">${tier.name}</span>${size}`
      + `<span class="model-chip is-disk">${tier.diskGb} GB</span>`;
  }

  /**
   * Renders the model picker: which tiers exist, which are already here, and
   * what is running right now.
   *
   * The list offers every tier the installer can fetch, so without the mark a
   * pick looks like it did nothing - the download happens on the next start
   * through the launcher. A tag that is not in the store also resolves at
   * startup to an installed sibling, so choice and running model can disagree;
   * the state line says so, and the restart button ends it.
   */
  function fillModelTiers(payload) {
    if (!aiTrigger || !aiList || !payload.aiModelTiers) return;
    const installed = new Set(payload.aiModelInstalled || []);
    const chosen = payload.aiModelTag || '';
    const auto = { tag: '', name: t('settings.ai.model.auto'), size: '', diskGb: 0 };

    aiList.replaceChildren(...payload.aiModelTiers.map(tier => {
      const li = document.createElement('li');
      li.setAttribute('role', 'option');
      li.dataset.tag = tier.tag;
      li.setAttribute('aria-selected', String(tier.tag === chosen));
      li.innerHTML = modelRow(tier, installed.has(tier.tag));
      li.addEventListener('click', () => {
        set('aiModelTag', tier.tag);
        aiList.hidden = true;
        aiTrigger.setAttribute('aria-expanded', 'false');
      });
      return li;
    }));

    const current = payload.aiModelTiers.find(x => x.tag === chosen)
      || payload.aiModelTiers.find(x => x.tag === payload.aiModelDefault)
      || auto;
    aiTrigger.innerHTML = modelRow(current, installed.has(current.tag)) + CARET;

    const state = view.querySelector('.js-ai-model-state');
    const active = payload.aiModelActive;
    const effective = chosen || payload.aiModelDefault;
    // Only when they differ. Saying "running: X" when X is what you picked is
    // noise; saying it when they diverge is the whole point.
    const diverges = active && effective && active !== effective
      && payload.aiEndpointMode !== 'remote';
    if (state) {
      state.hidden = !diverges;
      if (diverges) state.textContent = `${t('settings.ai.model.running')} ${active}`;
    }
    // The button appears for exactly the situation it fixes: a model that is
    // chosen, not here, and therefore not running - AND only where a restart
    // can do anything about it. The terminal fetches nothing itself; it quits
    // and hands over to the launcher, so without one the button would close
    // the app and start nothing.
    if (aiRestart) {
      aiRestart.hidden = !(diverges && !installed.has(effective) && payload.canRestart);
    }
  }

  // Backend echoes the persisted snapshot on connect + after every change.
  socket.on('settings', payload => {
    if (!payload) return;
    // Re-broadcast for other consumers of the snapshot (the socket allows one
    // handler per topic) — the focus-rail's Schlagzeilen settings sync off this.
    window.dispatchEvent(new CustomEvent('wsbg:settings', { detail: payload }));
    if (lang && payload.language) lang.value = payload.language;
    if (auto && typeof payload.autoUpdate === 'boolean') auto.checked = payload.autoUpdate;
    if (experimental && typeof payload.experimentalUpdates === 'boolean')
      experimental.checked = payload.experimentalUpdates;

    // Advanced. The mode is the RESOLVED one (a half-filled remote setup runs
    // as managed), so the switch always shows what is actually in force.
    const remote = payload.aiEndpointMode === 'remote';
    if (aiRemote) aiRemote.checked = remote;
    applyEndpointEnabled(remote);
    // An endpoint that is actually in use should not be hidden behind a fold -
    // open the category once, so the configuration in force is visible.
    if (remote && aiToggle && aiBody && aiToggle.getAttribute('aria-expanded') !== 'true') {
      aiToggle.setAttribute('aria-expanded', 'true');
      aiBody.hidden = false;
    }
    if (aiUrl) aiUrl.value = payload.aiEndpointUrl || '';
    if (aiApi) aiApi.value = payload.aiEndpointApi || 'ollama';
    if (aiModel) aiModel.value = payload.aiEndpointModel || '';
    if (aiAuth) aiAuth.value = payload.aiEndpointAuth || '';
    // 0 means "default" and renders as the placeholder rather than a literal 0
    // the user then has to think about.
    if (aiCtx) aiCtx.value = payload.aiEndpointContext ? payload.aiEndpointContext : '';
    if (aiSlots) aiSlots.value = payload.aiEndpointSlots ? payload.aiEndpointSlots : '';
    fillModelTiers(payload);
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

  // ---- Launcher renewal (titlebar amber button, isolated from the green one) ----
  // Own topic, own DOM node: the two indicators never overwrite each other —
  // an old hull with a pending app update simply shows both buttons.
  const launcherBtn = document.querySelector('.js-launcher-update');
  socket.on('launcher-update-available', payload => {
    if (launcherBtn) launcherBtn.hidden = !(payload && payload.available);
  });
  if (launcherBtn) launcherBtn.addEventListener('click',
      () => socket.send('launcher-update', { command: 'get' }));

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
