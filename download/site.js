(() => {
  'use strict';
  if (window.lucide) window.lucide.createIcons({ attrs: { 'aria-hidden': 'true', focusable: 'false' } });
  const menu = document.querySelector('.mobile-nav');
  if (menu) {
    menu.addEventListener('click', event => { if (event.target.closest('a')) menu.open = false; });
    document.addEventListener('keydown', event => { if (event.key === 'Escape') menu.open = false; });
    document.addEventListener('click', event => { if (!menu.contains(event.target)) menu.open = false; });
  }
  const measurementId = document.querySelector('meta[name="ga4-measurement-id"]')?.content || '';
  const configured = /^G-[A-Z0-9]{6,20}$/.test(measurementId);
  const production = location.origin === 'https://vanishr-download.vercel.app';
  const panel = document.querySelector('#analytics-consent');
  const consentKey = 'vanishr.website.analytics.v1';
  const maxAge = 180 * 24 * 60 * 60 * 1000;
  let enabled = false;
  let choice = null;
  try {
    const saved = JSON.parse(localStorage.getItem(consentKey) || 'null');
    if (saved && typeof saved.allowed === 'boolean' && Number.isFinite(saved.at)
        && saved.at <= Date.now() && saved.at > Date.now() - maxAge) choice = saved.allowed;
  } catch { choice = null; }
  const privacySignal = navigator.globalPrivacyControl === true || navigator.doNotTrack === '1';
  if (privacySignal) choice = false;
  const consent = analytics => ({ analytics_storage: analytics, ad_storage: 'denied', ad_user_data: 'denied', ad_personalization: 'denied' });
  function updateStatus() {
    for (const status of document.querySelectorAll('[data-consent-status]')) {
      status.hidden = status.hasAttribute('data-consent-note') && configured && !privacySignal && choice !== true;
      status.textContent = !configured ? 'Analytics is not available.'
        : privacySignal ? 'Analytics is off because your browser requests privacy.'
        : choice === true ? 'Analytics is on. Choose No thanks to turn it off.'
        : 'Analytics is off.';
    }
    const allow = document.querySelector('[data-consent-allow]');
    if (allow) allow.disabled = !configured || privacySignal;
  }
  function startAnalytics() {
    if (enabled || !configured || !production || privacySignal || choice !== true) return;
    enabled = true;
    window['ga-disable-' + measurementId] = false;
    window.dataLayer = window.dataLayer || [];
    window.gtag = function () { window.dataLayer.push(arguments); };
    window.gtag('consent', 'default', consent('denied'));
    window.gtag('consent', 'update', consent('granted'));
    window.gtag('set', 'ads_data_redaction', true);
    window.gtag('set', 'url_passthrough', false);
    window.gtag('js', new Date());
    let referrer = '';
    try { if (document.referrer) referrer = new URL(document.referrer).origin + '/'; } catch { referrer = ''; }
    const canonical = document.querySelector('link[rel="canonical"]')?.href;
    const pageUrl = canonical && new URL(canonical).origin === location.origin ? canonical : location.origin + location.pathname;
    window.gtag('config', measurementId, {
      send_page_view: false, allow_google_signals: false, allow_ad_personalization_signals: false,
      cookie_expires: 15_552_000, cookie_update: false, cookie_flags: 'SameSite=Lax;Secure',
      page_location: pageUrl, page_referrer: referrer, ignore_referrer: false
    });
    window.gtag('event', 'page_view', { page_title: document.title, page_location: pageUrl, page_referrer: referrer });
    const script = document.createElement('script'); script.async = true;
    script.src = 'https://www.googletagmanager.com/gtag/js?id=' + encodeURIComponent(measurementId);
    script.id = 'vanishr-google-analytics'; document.head.appendChild(script);
  }
  function clearAnalyticsCookies() {
    for (const cookie of document.cookie.split(';')) {
      const name = cookie.split('=')[0].trim();
      if (!/^_ga(?:_|$)/.test(name)) continue;
      for (const domain of ['', '; Domain=' + location.hostname, '; Domain=.' + location.hostname]) {
        document.cookie = name + '=; Max-Age=0; Path=/; SameSite=Lax; Secure' + domain;
      }
    }
  }
  function choose(allowed) {
    choice = allowed && configured && !privacySignal;
    try { localStorage.setItem(consentKey, JSON.stringify({ allowed: choice, at: Date.now() })); } catch { }
    if (!choice) {
      const wasEnabled = enabled;
      window['ga-disable-' + measurementId] = true;
      if (window.gtag) window.gtag('consent', 'update', consent('denied'));
      document.querySelector('#vanishr-google-analytics')?.remove();
      clearAnalyticsCookies(); enabled = false;
      if (panel) panel.hidden = true;
      updateStatus();
      if (wasEnabled) location.reload();
    } else { startAnalytics(); if (panel) panel.hidden = true; updateStatus(); }
    resizeConsent();
  }
  function resizeConsent() {
    document.documentElement.style.setProperty('--consent-space', panel && !panel.hidden ? panel.offsetHeight + 'px' : '0px');
  }
  document.querySelector('[data-consent-allow]')?.addEventListener('click', () => choose(true));
  document.querySelector('[data-consent-deny]')?.addEventListener('click', () => choose(false));
  for (const button of document.querySelectorAll('[data-consent-open]')) {
    button.addEventListener('click', () => { if (panel) { panel.hidden = false; updateStatus(); resizeConsent(); panel.querySelector('[data-consent-deny]')?.focus(); } });
  }
  if (panel) {
    panel.hidden = choice !== null || !configured;
    new ResizeObserver(resizeConsent).observe(panel);
  }
  updateStatus(); resizeConsent(); startAnalytics();
  document.addEventListener('click', event => {
    const link = event.target.closest('a[data-analytics-download]');
    if (!link || !enabled || choice !== true || privacySignal || !window.gtag) return;
    const kind = link.dataset.analyticsDownload;
    if (!['android_apk', 'application_source', 'libsignal_source'].includes(kind)) return;
    window.gtag('event', 'file_download', { file_type: kind, link_url: link.origin + link.pathname, transport_type: 'beacon' });
  });
})();