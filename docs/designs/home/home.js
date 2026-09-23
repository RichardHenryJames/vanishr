(() => {
  'use strict';

  const icons = () => globalThis.lucide?.createIcons({ attrs: { 'aria-hidden': 'true' } });
  const icon = name => `<i data-lucide="${name}"></i>`;
  const escape = value => String(value).replace(/[&<>"']/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character]);

  if (document.body.dataset.page === 'compare') {
    const names = { a: 'Compact Classic', b: 'Search First', c: 'Bottom Actions' };
    const select = choice => {
      for (const study of document.querySelectorAll('[data-option]')) study.dataset.selected = String(study.dataset.option === choice);
      for (const button of document.querySelectorAll('[data-choose]')) button.setAttribute('aria-pressed', String(button.dataset.choose === choice));
      document.querySelector('#decision-text').textContent = choice
        ? `${choice.toUpperCase()} / ${names[choice]} selected for review. Tell me your choice in chat; the Android app is unchanged.`
        : 'Try search, open a chat, or tap the new-chat action. Your Android app has not changed.';
      document.querySelector('#clear-choice').hidden = !choice;
    };
    document.querySelectorAll('[data-choose]').forEach(button => button.addEventListener('click', () => select(button.dataset.choose)));
    document.querySelector('#clear-choice').addEventListener('click', () => select(null));
    let previewScreen = 'home';
    const frames = [...document.querySelectorAll('.phone-frame iframe')];
    const sendScreen = frame => frame.contentWindow?.postMessage({ type: 'vanishr:preview-screen', screen: previewScreen }, '*');
    document.querySelectorAll('[data-preview-screen]').forEach(button => button.addEventListener('click', () => {
      previewScreen = button.dataset.previewScreen;
      document.querySelectorAll('[data-preview-screen]').forEach(control => control.setAttribute('aria-pressed', String(control === button)));
      frames.forEach(sendScreen);
    }));
    frames.forEach(frame => frame.addEventListener('load', () => sendScreen(frame)));
    document.querySelector('#reset-all-previews').addEventListener('click', () => {
      previewScreen = 'home'; select(null);
      document.querySelectorAll('[data-preview-screen]').forEach(control => control.setAttribute('aria-pressed', String(control.dataset.previewScreen === 'home')));
      for (const frame of frames) frame.src = frame.getAttribute('src');
      document.querySelector('#review-action-status').textContent = '';
    });
    globalThis.addEventListener('message', event => {
      const frame = frames.find(candidate => candidate.contentWindow === event.source);
      if (!frame || event.data?.type !== 'vanishr:preview-status' || typeof event.data.message !== 'string' || event.data.message.length > 1200) return;
      document.querySelector('#review-action-status').textContent = `${frame.closest('[data-option]').dataset.option.toUpperCase()}: ${event.data.message}`;
    });
    icons();
    return;
  }

  const variant = document.body.dataset.design;
  if (!['a', 'b', 'c'].includes(variant)) return;
  if (new URLSearchParams(location.search).get('embed') === '1') document.body.classList.add('embedded');
  const app = document.querySelector('#app');
  const people = [
    ['aarav', 'Aarav Mehta', 'aarav_m'], ['maya', 'Maya Chen', 'maya_c'], ['leena', 'Leena Shah', 'leena_s'],
    ['noah', 'Noah Williams', 'noah_w'], ['ishaan', 'Ishaan Kapoor', 'ishaan_k'], ['ravi', 'Ravi Kapoor', 'ravi_k'],
    ['zoya', 'Zoya Malik', 'zoya_m'], ['june', 'June Park', 'june_p'], ['ellis', 'Ellis Reed', 'ellis_r'],
    ['tara', 'Tara Rao', 'tara_r'], ['sam', 'Sam Lewis', 'sam_l'], ['amina', 'Amina Ali', 'amina_a'],
    ['leo', 'Leo Santos', 'leo_s'], ['ines', 'Ines Costa', 'ines_c'], ['omar', 'Omar Khan', 'omar_k'],
    ['nina', 'Nina Patel', 'nina_p'], ['kai', 'Kai Tan', 'kai_t'], ['mira', 'Mira Roy', 'mira_r'],
    ['ben', 'Ben Clarke', 'ben_c'], ['sara', 'Sara Bell', 'sara_b'], ['yuki', 'Yuki Ito', 'yuki_i'],
    ['dev', 'Dev Shah', 'dev_s'], ['lena', 'Lena Fischer', 'lena_f']
  ].map(([id, name, handle]) => ({ id, name, profileName: name, handle, type: 'direct', tone: 'blue', unread: 0 }));
  const chats = [
    { id: 'aarav', name: 'Aarav Mehta', handle: 'aarav_m', initials: 'AM', tone: 'green', unread: 2, time: 'Now', type: 'direct' },
    { id: 'weekend', name: 'Weekend plans', initials: 'WP', tone: 'blue', unread: 5, time: '9:38', type: 'group', members: 8 },
    { id: 'maya', name: 'Maya Chen', handle: 'maya_c', initials: 'MC', tone: 'coral', unread: 1, time: '9:24', type: 'direct' },
    { id: 'design', name: 'Design circle', initials: 'DC', tone: 'green', unread: 0, time: '8:56', type: 'group', members: 24 },
    { id: 'leena', name: 'Leena Shah', handle: 'leena_s', initials: 'LS', tone: 'gold', unread: 0, time: '8:12', type: 'direct' },
    { id: 'noah', name: 'Noah Williams', handle: 'noah_w', initials: 'NW', tone: 'blue', unread: 0, time: 'Yesterday', type: 'direct' },
    { id: 'mountain', name: 'Mountain trip', initials: 'MT', tone: 'gold', unread: 0, time: 'Yesterday', type: 'group', members: 6, invite: true },
    { id: 'ishaan', name: 'Ishaan Kapoor', handle: 'ishaan_k', initials: 'IK', tone: 'green', unread: 0, time: 'Yesterday', type: 'direct' }
  ];
  for (const chat of chats) if (chat.type === 'direct') chat.profileName = chat.name;
  for (const chat of chats.filter(value => value.type === 'group')) {
    chat.ownerId = chat.id === 'design' ? 'self' : chat.id === 'mountain' ? 'ravi' : 'aarav';
    const memberIds = chat.id === 'design' ? ['self', ...people.map(person => person.id)]
      : chat.id === 'mountain' ? ['ravi', 'aarav', 'maya', 'leena', 'noah', 'self']
      : ['self', 'aarav', 'maya', 'leena', 'noah', 'ishaan', 'ravi', 'zoya'];
    chat.roster = memberIds.map(id => ({ id, state: chat.invite && id === 'self' ? 'INVITED' : 'ACTIVE' }));
    chat.members = chat.roster.length;
  }
  const state = { filter: 'all', query: '', searchOpen: false, profile: 'Alex Rivera', username: 'alex_r', notifications: false, expiry: '1 hour', active: null, modal: null, messages: new Map(), drafts: new Map(), previousFocus: null, photo: null, photoRequest: 0 };
  const initial = name => Array.from(name.trim())[0]?.toLocaleUpperCase() || '?';
  const button = (symbol, label, action, extra = '') => `<button type="button" class="icon-button ${extra}" aria-label="${label}" title="${label}" data-action="${action}">${icon(symbol)}</button>`;
  const profileButton = () => `<button class="profile-button" type="button" aria-label="My profile" title="My profile" data-action="profile">${escape(initial(state.profile))}</button>`;
  const avatar = chat => `<span class="avatar ${chat.type === 'group' ? 'group-avatar' : ''}" data-tone="${chat.tone}" aria-hidden="true">${chat.type === 'group' ? icon('users-round') : escape(initial(chat.name))}</span>`;
  const actionRow = (symbol, label, action, danger = false) => `<button type="button" class="action-row ${danger ? 'danger-action' : ''}" data-action="${action}">${icon(symbol)}<span>${label}</span>${icon('chevron-right')}</button>`;
  const secondary = (text, action, extra = '') => `<button type="button" class="secondary-button ${extra}" data-action="${action}">${text}</button>`;
  const validName = (value, maximum = 40) => value.length > 0 && value.length <= maximum && !/[\u0000-\u001f\u007f-\u009f]/u.test(value);
  const prototypeNotice = document.createElement('aside');
  prototypeNotice.className = 'prototype-disclosure';
  prototypeNotice.setAttribute('aria-label', 'Prototype boundaries');
  prototypeNotice.innerHTML = '<strong>HTML design preview, not the Android app.</strong><span>Fictional data; edits stay in this tab. Account, security and Android system actions are simulated.</span><p id="preview-action-status" role="status"></p><button type="button" id="reset-preview">Reset sample data</button>';
  app.after(prototypeNotice);
  prototypeNotice.querySelector('#reset-preview').addEventListener('click', () => location.reload());
  const reviewStatus = message => {
    prototypeNotice.querySelector('#preview-action-status').textContent = message;
    if (globalThis.parent !== globalThis) globalThis.parent.postMessage({ type: 'vanishr:preview-status', message }, '*');
  };
  const flowNav = document.createElement('nav');
  flowNav.className = 'preview-flow'; flowNav.setAttribute('aria-label', 'Preview screens');
  flowNav.innerHTML = [['home', 'messages-square', 'Chats'], ['chat', 'message-circle', 'Chat'], ['contact', 'user-round', 'Contact'], ['profile', 'circle-user-round', 'Me']].map(([screen, symbol, label]) => `<button type="button" data-review-screen="${screen}" aria-label="Preview: ${label === 'Me' ? 'My profile' : label === 'Contact' ? 'Contact profile' : label}" aria-pressed="${screen === 'home'}" title="${label === 'Me' ? 'My profile' : label === 'Contact' ? 'Contact profile' : label}">${icon(symbol)}<span>${label}</span></button>`).join('');
  app.before(flowNav);
  const currentPerson = id => id === 'self' ? { id, name: state.profile, handle: state.username, tone: 'blue' } : people.find(person => person.id === id);
  const groupCount = chat => chat.roster.filter(member => member.state === 'ACTIVE').length;
  const groupStatus = chat => chat.invite ? 'Invitation' : chat.waiting ? 'Waiting for owner' : `${groupCount(chat)} members`;
  const groupCanSend = chat => chat.type !== 'group' || !chat.invite && !chat.waiting && groupCount(chat) > 1;
  const search = placeholder => `<label class="search-field">${icon('search')}<input type="search" aria-label="Search chats" placeholder="${placeholder}" value="${escape(state.query)}" autocomplete="off" spellcheck="false">${button('x', 'Clear search', 'clear-search', 'clear-search')}</label>`;
  const tabs = () => `<div class="tab-bar" role="tablist" aria-label="Chat filters">${[['all', 'All'], ['unread', 'Unread'], ['group', 'Groups']].map(([filter, label]) => `<button class="filter-tab" role="tab" data-filter="${filter}" aria-selected="${state.filter === filter}">${label}${filter === 'unread' ? `<span class="tab-count">${chats.filter(chat => chat.unread).length}</span>` : ''}</button>`).join('')}${button('square-pen', 'New conversation', 'new', 'toolbar-compose')}</div>`;

  function renderHome() {
    closeSheet();
    state.active = null;
    let header;
    if (variant === 'a') {
      header = `<header class="app-toolbar"><h1 class="wordmark">vanishr<span class="connection-dot" title="Connected" aria-label="Connected"></span></h1>${button('users-round', 'New group', 'new-group')}${button('square-pen', 'New conversation', 'new', 'toolbar-compose')}${profileButton()}</header><div class="search-row">${search('Search chats')}${button('list-filter', 'Unread chats', 'unread', 'filter-toggle')}</div>`;
    } else if (variant === 'b') {
      header = `<header class="search-toolbar"><span class="mini-brand" aria-label="Vanishr" title="Vanishr">v</span>${search('Search Vanishr')}${profileButton()}</header>${tabs()}`;
    } else {
      header = `<header class="app-toolbar"><h1 class="wordmark">vanishr<span class="connection-dot" title="Connected" aria-label="Connected"></span></h1>${button(state.searchOpen ? 'x' : 'search', state.searchOpen ? 'Close search' : 'Search chats', 'toggle-search')}${profileButton()}</header>${state.searchOpen ? `<div class="search-row">${search('Search chats')}${button('list-filter', 'Unread chats', 'unread', 'filter-toggle')}</div>` : ''}`;
    }
    app.innerHTML = `<div class="status-bar" aria-hidden="true"><span>9:41</span><span class="status-symbols">${icon('signal')}${icon('wifi')}${icon('battery-full')}</span></div><section class="home-screen">${header}<div class="chat-list" aria-label="Conversations"></div>${variant === 'c'
      ? `<nav class="bottom-dock" aria-label="Chat navigation"><button class="dock-tab" data-filter="all" aria-selected="${state.filter !== 'group'}">${icon('messages-square')}<span>Chats</span></button><button class="dock-create" aria-label="New conversation" title="New conversation" data-action="new">${icon('plus')}</button><button class="dock-tab" data-filter="group" aria-selected="${state.filter === 'group'}">${icon('users-round')}<span>Groups</span></button></nav>`
      : ''}</section><div class="home-bottom" aria-hidden="true"><span class="gesture-bar"></span></div>`;
    renderRows();
    const input = app.querySelector('input[type="search"]');
    input?.addEventListener('input', () => { state.query = input.value; renderRows(); });
    updateFilters();
    icons();
  }

  function renderRows() {
    const query = state.query.toLocaleLowerCase().trim();
    const visible = chats.filter(chat => (state.filter === 'all' || state.filter === 'unread' && chat.unread > 0 || state.filter === 'group' && chat.type === 'group') && `${chat.name} ${chat.handle ?? ''}`.toLocaleLowerCase().includes(query));
    app.querySelector('.chat-list').innerHTML = visible.length ? visible.map(chat => `<button type="button" class="chat-row ${chat.unread ? 'unread' : ''}" data-chat="${chat.id}" aria-label="${escape(chat.name)}${chat.unread ? `, ${chat.unread} unread` : ''}${chat.invite ? ', invitation' : ''}">${avatar(chat)}<span class="chat-identity"><span class="chat-name">${escape(chat.name)}</span><span class="chat-meta">${chat.type === 'group' ? icon(chat.invite ? 'mail' : 'users-round') : ''}<span>${chat.type === 'group' ? groupStatus(chat) : `@${escape(chat.handle)}`}</span></span></span><span class="chat-trailing">${chat.unread ? `<span class="unread-count">${chat.unread}</span>` : ''}${icon('chevron-right')}</span></button>`).join('') : `<div class="empty-list">${icon('search')}<p>${state.filter === 'unread' && !query ? 'All caught up' : 'No matching conversations'}</p></div>`;
    const clear = app.querySelector('[data-action="clear-search"]');
    if (clear) clear.hidden = !state.query;
    icons();
  }

  function updateFilters() {
    app.querySelectorAll('[data-filter]').forEach(control => control.setAttribute('aria-selected', String(control.dataset.filter === state.filter)));
    app.querySelectorAll('[data-action="unread"]').forEach(control => control.setAttribute('aria-pressed', String(state.filter === 'unread')));
  }

  function openSheet(title, content, footer = '') {
    closeSheet();
    state.previousFocus = document.activeElement;
    const layer = document.createElement('div');
    layer.className = 'modal-layer';
    layer.innerHTML = `<section class="sheet" role="dialog" aria-modal="true" aria-labelledby="sheet-title"><div class="sheet-handle" aria-hidden="true"></div><header class="sheet-heading"><h2 id="sheet-title">${escape(title)}</h2>${button('x', 'Close dialog', 'close-sheet')}</header>${content}${footer ? `<div class="sheet-footer">${footer}</div>` : ''}</section>`;
    layer.addEventListener('click', event => { if (event.target === layer) closeSheet(); });
    app.append(layer);
    state.modal = layer;
    app.querySelector('.home-screen, .conversation-screen, .sign-in-screen').inert = true;
    icons();
    layer.querySelector('input:not([type="checkbox"]), button')?.focus({ preventScroll: true });
  }

  function closeSheet() {
    state.photoRequest++;
    if (state.photo) URL.revokeObjectURL(state.photo.url);
    state.photo = null;
    state.modal?.remove();
    state.modal = null;
    const screen = app.querySelector('.home-screen, .conversation-screen, .sign-in-screen');
    if (screen) screen.inert = false;
    app.querySelector('[data-action="options"]')?.setAttribute('aria-expanded', 'false');
    if (state.previousFocus?.isConnected) state.previousFocus.focus({ preventScroll: true });
    state.previousFocus = null;
  }

  const primary = (text, action, symbol = 'check', disabled = false) => `<button type="button" class="primary-button" data-action="${action}" ${disabled ? 'disabled' : ''}>${icon(symbol)}${text}</button>`;

  function newConversation() {
    openSheet('New conversation', `<button class="action-row" data-action="new-contact">${icon('user-round-plus')}<span>Add contact</span>${icon('chevron-right')}</button><button class="action-row" data-action="new-group">${icon('users-round')}<span>New group</span>${icon('chevron-right')}</button>`);
  }

  function newContact(handle = '') {
    openSheet('Add contact', `<label class="sheet-label" for="contact-name">Username</label><input class="sheet-input" id="contact-name" placeholder="@username" value="${escape(handle)}" autocomplete="off" maxlength="33"><p id="contact-result" class="saved-label" aria-live="polite"></p>`, `${secondary('Cancel', 'close-sheet')}${primary('Find', 'find-contact', 'search')}`);
    reviewStatus('Contact lookup is limited to fictional preview contacts. Try ravi_k or june_p; no relay lookup or real identity verification is performed.');
  }

  function verifyContact(person) {
    state.candidate = person;
    openSheet('Verify contact', `<div class="profile-summary">${avatar(person)}<div><h3>${escape(person.name)}</h3><p>@${escape(person.handle)}</p></div></div><p>Safety number</p><code class="identity-code" aria-label="Contact safety number">8C21F0A4 91BD7E62 A0539D18 2E6F403C 7B1259DA C4840F61 396ED702 5A8C130F</code><label class="trust-check"><input type="checkbox" id="verify-contact"><span>I compared this with their Profile &gt; Verify identity in person or through another trusted channel.</span></label>`, `${secondary('Cancel', 'close-sheet')}${primary('Add contact', 'add-verified-contact', 'user-round-plus', true)}`);
    reviewStatus('The safety number is a fictional fixture. Checking this box demonstrates the Android confirmation flow; it does not verify a real identity.');
  }

  function newGroup() {
    openSheet('New group', '<label class="sheet-label" for="group-name">Group name</label><input class="sheet-input" id="group-name" placeholder="Group name" maxlength="64" autocomplete="off"><p class="field-status" id="group-name-status" role="status"></p><label class="trust-check"><input type="checkbox" id="group-owner-consent"><span>I will verify the identity of everyone I invite.</span></label>', `${secondary('Cancel', 'close-sheet')}${primary('Create', 'create-group', 'plus', true)}`);
  }

  function inviteMembers(chat) {
    state.managedGroup = chat;
    const available = chats.filter(candidate => candidate.type === 'direct' && !chat.roster.some(member => member.id === candidate.id));
    if (!available.length || chat.roster.length >= 200) {
      groupInfo(chat);
      reviewStatus(available.length ? 'This sample group has reached its 200-member limit.' : 'No more verified sample contacts to invite. Add a contact first, as in Android.');
      return;
    }
    openSheet('Invite members', available.map(person => `<label class="contact-option">${avatar(person)}<span>${escape(person.name)} (@${escape(person.handle)})</span><input type="checkbox" name="group-member" value="${person.id}" aria-label="Invite ${escape(person.name)}"></label>`).join(''), `${secondary('Cancel', 'close-sheet')}${primary('Invite', 'invite-members', 'user-round-plus')}`);
  }

  function groupInfo(chat = state.active) {
    if (!chat || chat.type !== 'group') return;
    if (chat.invite) { invitation(chat); return; }
    state.managedGroup = chat;
    const owner = chat.ownerId === 'self';
    openSheet('Group info', `<div class="group-summary"><div><h3>${escape(chat.name)}</h3><p>${chat.roster.length} / 200 members</p></div>${owner ? button('plus', 'Invite members', 'show-invite-members') : ''}</div><div class="member-list">${chat.roster.map(member => {
      const person = currentPerson(member.id);
      return `<div class="member-row">${avatar(person)}<div><strong>${escape(person.name)}${member.id === 'self' ? ' (you)' : ''}</strong><span>${member.id === chat.ownerId ? 'Owner' : member.state === 'INVITED' ? 'Invited' : 'Member'} / @${escape(person.handle)}</span></div>${owner && member.id !== 'self' ? `<button type="button" class="icon-button" data-action="remove-member" data-member="${member.id}" aria-label="Remove ${escape(person.name)}" title="Remove ${escape(person.name)}">${icon('x')}</button>` : ''}</div>`;
    }).join('')}</div><div class="profile-actions">${actionRow('log-out', owner ? 'Close group' : 'Leave group', 'leave-group', true)}</div>`, secondary('Done', 'close-sheet'));
  }

  function confirmGroupExit() {
    const chat = state.managedGroup;
    const owner = chat.ownerId === 'self';
    const command = owner ? 'Close group' : 'Leave group';
    openSheet(`${command}?`, `<p>${owner ? 'Everyone will lose access to this group. This action cannot be undone.' : 'Your local group content will be removed. A new invitation is required to rejoin.'}</p>`, `${secondary('Cancel', 'close-sheet')}${primary(command, 'confirm-leave-group', 'log-out')}`);
  }

  function profile() {
    openSheet('My profile', `<div class="profile-summary"><span class="avatar" id="profile-avatar" data-tone="blue">${escape(initial(state.profile))}</span><div><h3 id="profile-heading">${escape(state.profile)}</h3><p id="profile-handle">@${escape(state.username)}</p></div></div><label class="sheet-label" for="profile-name">Display name</label><div class="inline-field"><input class="sheet-input" id="profile-name" value="${escape(state.profile)}" maxlength="40" autocomplete="off" aria-describedby="profile-name-status">${button('check', 'Save name', 'save-name')}</div><p class="field-status" id="profile-name-status" role="status"></p><label class="sheet-label" for="profile-username">Username</label><div class="inline-field username-field"><span aria-hidden="true">@</span><input class="sheet-input" id="profile-username" value="${escape(state.username)}" maxlength="32" autocomplete="off" spellcheck="false" aria-describedby="profile-username-status">${button('check', 'Save username', 'save-username')}</div><p class="field-status" id="profile-username-status" role="status"></p><div class="profile-actions">${actionRow('message-circle', 'Share username', 'share-username')}${actionRow('shield-check', 'Verify identity', 'identity')}</div><div class="profile-actions"><label class="notification-setting"><span>New message notifications</span><input type="checkbox" role="switch" id="notifications" ${state.notifications ? 'checked' : ''}></label>${actionRow('arrow-up', 'Check for updates', 'updates')}${actionRow('file-text', 'Licenses &amp; source', 'licenses')}</div><div class="profile-actions">${actionRow('log-out', 'Sign out', 'sign-out', true)}</div>`, secondary('Close', 'close-sheet'));
  }

  function feedback(inputId, message, error = false) {
    const field = app.querySelector(`#${inputId}`);
    field.setAttribute('aria-invalid', String(error));
    const label = app.querySelector(`#${inputId}-status`);
    label.textContent = message;
    label.dataset.error = String(error);
  }

  function contactProfile() {
    const chat = state.active;
    if (!chat || chat.type !== 'direct') return;
    openSheet('Contact profile', `<div class="profile-summary">${avatar(chat)}<div><h3>${escape(chat.profileName || chat.handle)}</h3><p aria-label="Contact username">@${escape(chat.handle)}</p></div></div><label class="sheet-label" for="contact-display-name">Display name</label><input class="sheet-input" id="contact-display-name" value="${escape(chat.name)}" maxlength="40" autocomplete="off" aria-describedby="contact-display-name-status"><p class="field-status" id="contact-display-name-status" role="status"></p>`, `${secondary('Use profile name', 'use-profile-name', 'neutral-action')}${secondary('Cancel', 'close-sheet')}${primary('Save', 'save-contact-name')}`);
    reviewStatus('Contact Display name changes only your local nickname. The other person\'s shared name and username remain unchanged.');
  }

  function refreshChatIdentity() {
    const chat = state.active;
    if (!chat) return;
    app.querySelector('.conversation-toolbar .chat-name').textContent = chat.name;
    const mark = app.querySelector('.conversation-toolbar .avatar');
    if (chat.type === 'direct') mark.textContent = initial(chat.name);
  }

  function conversationOptions() {
    if (!state.active) return;
    closeSheet();
    state.previousFocus = document.activeElement;
    const layer = document.createElement('div');
    layer.className = 'menu-layer';
    const items = state.active.type === 'group'
      ? [['Group info', 'group-info'], ['Disappearing messages', 'expiry']]
      : [['Profile', 'contact-profile'], ['Contact identity', 'contact-identity'], ['Disappearing messages', 'expiry'], ['Remove contact', 'remove-contact']];
    layer.innerHTML = `<div class="conversation-menu" role="menu" aria-label="Conversation options">${items.map(([label, action]) => `<button type="button" role="menuitem" data-action="${action}" ${action === 'remove-contact' ? 'class="danger-action"' : ''}>${label}</button>`).join('')}</div>`;
    layer.addEventListener('click', event => { if (event.target === layer) closeSheet(); });
    app.append(layer); state.modal = layer;
    app.querySelector('[data-action="options"]').setAttribute('aria-expanded', 'true');
    app.querySelector('.conversation-screen').inert = true;
    layer.querySelector('button').focus({ preventScroll: true });
  }

  function expiryDialog() {
    openSheet('Disappearing messages', `<fieldset class="expiry-options"><legend class="sr-only">Message expiry</legend>${['View once', '1 hour', '6 hours', '24 hours'].map(value => `<label class="radio-option"><input type="radio" name="expiry" value="${value}" ${state.expiry === value ? 'checked' : ''}><span>${value}</span></label>`).join('')}</fieldset>`, secondary('Cancel', 'close-sheet'));
  }

  function removeContact() {
    openSheet('Remove verified contact?', '<p>Local content and the pinned identity will be erased.</p>', `${secondary('Cancel', 'close-sheet')}${primary('Remove', 'confirm-remove-contact', 'trash-2')}`);
  }

  function signOut() {
    openSheet('Sign out of this device?', '<p>Your chats, contacts and device identity stay encrypted on this phone. Signing in to the same account restores them. Disappearing messages still expire on their original schedule.</p>', `${secondary('Cancel', 'close-sheet')}${primary('Sign out', 'confirm-sign-out', 'log-out')}`);
  }

  function showSignedOut() {
    closeSheet(); state.active = null; state.notifications = false;
    app.innerHTML = `<section class="sign-in-screen"><h1 class="wordmark">vanishr</h1><h2>Welcome back.</h2><label class="sheet-label" for="sample-username">Username</label><input class="sheet-input" id="sample-username" value="${escape(state.username)}" readonly><label class="sheet-label" for="sample-password">Password</label><input class="sheet-input" id="sample-password" type="password" value="fictional-preview" readonly>${primary('Sign in', 'sample-sign-in', 'log-in')}${secondary('Continue with Google', 'sample-sign-in')}</section>`;
    icons();
    reviewStatus('Sign-out is simulated. This is a sample return screen, not a complete authentication design. Sign in returns to your unchanged sample chats; no credentials are requested or transmitted.');
  }

  function showIdentity(own) {
    openSheet(own ? 'Safety number' : state.active.name, `<code class="identity-code" aria-label="${own ? 'Device safety number' : 'Contact identity'}">${own ? '8C21F0A4 91BD7E62 A0539D18 2E6F403C 7B1259DA C4840F61 396ED702 5A8C130F' : '11111111-2222-4333-8444-555555555555:\nBXN5bnRoZXRpYy1kZXNpZ24tcHJldmlldy1pZGVudGl0eQ=='}</code>`, secondary('Close', 'close-sheet'));
    reviewStatus('These identity strings are fictional placeholders. Only the Android app performs identity verification and encryption.');
  }

  function licenses(agpl = false) {
    openSheet(agpl ? 'GNU AGPL v3' : 'Licenses & source', `<iframe class="legal-document" title="${agpl ? 'GNU AGPL v3 license' : 'Bundled third-party notices'}" src="../../../android/app/src/main/assets/legal/${agpl ? 'LICENSE' : 'NOTICES'}.txt" sandbox></iframe>`, `${agpl ? '' : secondary('AGPL v3', 'agpl')}${secondary('Close', 'close-sheet')}`);
  }

  function invitation(chat) {
    state.invitation = chat;
    const owner = currentPerson(chat.ownerId);
    const verified = chats.some(person => person.type === 'direct' && person.id === chat.ownerId);
    openSheet('Group invitation', `<h3>${escape(chat.name)}</h3><p>From ${escape(owner.name)}</p><p>@${escape(owner.handle)}</p>${verified ? '' : actionRow('shield-check', 'Verify owner', 'verify-owner')}<label class="trust-check"><input type="checkbox" id="trust-owner" ${verified ? '' : 'disabled'}><span>I trust this verified owner to approve group members.</span></label>${actionRow('x', 'Decline invitation', 'decline-invite', true)}`, `${secondary('Later', 'close-sheet')}${primary('Accept', 'accept-invite', 'check', true)}`);
    reviewStatus('Group membership, owner verification and encrypted invitation delivery are simulated. In the app, the owner must be independently verified before accepting.');
  }

  function attachments() {
    if (!state.active) return;
    openSheet('Send an image', `${actionRow('image', 'Choose image', 'photo-library')}${actionRow('camera', 'Take photo', 'camera')}<input id="photo-library-input" type="file" accept="image/jpeg,image/png,image/webp" aria-label="Choose photo" data-photo-input hidden><input id="camera-input" type="file" accept="image/*" capture="environment" aria-label="Take photo" data-photo-input hidden><p id="photo-error" class="photo-error" role="status"></p>`, secondary('Cancel', 'close-sheet'));
    reviewStatus('Android opens its photo picker or camera. This HTML preview uses a browser file input; selected photos stay in this tab and are not uploaded or encrypted.');
  }

  async function previewPhoto(file) {
    const modal = state.modal;
    const chat = state.active;
    if (!file || !modal || !chat) return;
    const error = app.querySelector('#photo-error');
    if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type)) { error.textContent = 'Choose a JPG, PNG or WebP photo.'; return; }
    if (!file.size || file.size > 10 * 1024 * 1024) { error.textContent = 'Choose a photo under 10 MB.'; return; }
    error.textContent = '';
    const request = ++state.photoRequest;
    const url = URL.createObjectURL(file);
    const image = new Image();
    image.src = url;
    try { await image.decode(); }
    catch {
      URL.revokeObjectURL(url);
      if (modal === state.modal && request === state.photoRequest) error.textContent = 'This photo could not be opened.';
      return;
    }
    if (modal !== state.modal || chat !== state.active || request !== state.photoRequest) { URL.revokeObjectURL(url); return; }
    openSheet('Send photo', `<figure class="photo-preview"><img src="${url}" alt="Selected photo"></figure>`, `<button type="button" class="secondary-button" data-action="close-sheet">Cancel</button>${primary('Send', 'send-photo', 'arrow-up')}`);
    state.photo = { url, chatId: chat.id };
  }

  function openChat(chat) {
    if (chat.invite) { invitation(chat); return; }
    chat.unread = 0;
    closeSheet();
    state.active = chat;
    if (!state.messages.has(chat.id)) state.messages.set(chat.id, chat.type === 'direct'
      ? [{ text: 'Coffee at 4?', outgoing: false }, { text: 'Sounds good. Same place?', outgoing: true }, { text: 'Yes, see you there.', outgoing: false }]
      : [{ text: 'Saturday morning works for everyone?', outgoing: false }, { text: 'Works for me.', outgoing: true }]);
    app.innerHTML = `<div class="status-bar" aria-hidden="true"><span>9:41</span><span class="status-symbols">${icon('signal')}${icon('wifi')}${icon('battery-full')}</span></div><section class="conversation-screen"><header class="conversation-toolbar">${button('arrow-left', 'Back', 'back')}${avatar(chat)}<span class="chat-identity"><span class="chat-name">${escape(chat.name)}</span><span class="chat-meta">${chat.type === 'group' ? `${chat.members} members` : `@${escape(chat.handle)}`}</span><span class="chat-connection">Connected</span></span>${button('ellipsis-vertical', 'Conversation options', 'options')}</header><div class="message-list" aria-live="polite"></div><div class="composer-area"><div class="composer-settings"><span class="verified-state">${icon('shield-check')}${chat.type === 'group' ? 'Private group' : 'Verified'}</span><button type="button" class="expiry-line" data-action="expiry" aria-label="Message expiry: ${state.expiry}">${icon('clock-3')}<span>${state.expiry}</span></button></div><form class="composer">${button('plus', 'Attach', 'attach', 'attach-button')}<input aria-label="Message" placeholder="Message" autocomplete="off" maxlength="4096"><button class="icon-button" type="submit" aria-label="Send" title="Send">${icon('arrow-up')}</button></form></div></section><div class="home-bottom" aria-hidden="true"><span class="gesture-bar"></span></div>`;
    app.querySelector('[data-action="options"]').setAttribute('aria-haspopup', 'menu');
    app.querySelector('[data-action="options"]').setAttribute('aria-expanded', 'false');
    if (chat.type === 'group') app.querySelector('.conversation-toolbar .chat-meta').textContent = groupStatus(chat);
    app.querySelector('.composer input').value = state.drafts.get(chat.id) || '';
    if (!groupCanSend(chat)) {
      for (const control of app.querySelectorAll('.composer input, .composer button')) control.disabled = true;
    }
    renderMessages();
    app.querySelector('.composer').addEventListener('submit', event => {
      event.preventDefault();
      const input = app.querySelector('.composer input');
      if (!input.value.trim() || !groupCanSend(chat)) return;
      state.messages.get(chat.id).push({ text: input.value.trim(), outgoing: true, expiry: state.expiry, delivery: 'QUEUED' });
      input.value = '';
      state.drafts.delete(chat.id);
      renderMessages();
      input.focus();
    });
    icons();
  }

  function renderMessages() {
    const list = app.querySelector('.message-list');
    const messages = state.messages.get(state.active.id);
    if (!messages.length) {
      list.innerHTML = `<div class="empty-list">${icon('shield-check')}<p>${state.active.type === 'group' ? 'Your private group' : 'Just the two of you'}</p></div>`;
    } else {
      list.innerHTML = `<div class="date-marker">Today</div>${messages.map((message, index) => {
        const group = state.active.type === 'group';
        const sender = group && !message.outgoing ? `<strong class="message-sender">${escape(currentPerson(state.active.roster.find(member => member.id !== 'self').id).name)}</strong>` : '';
        const status = message.delivery || 'READ';
        const mark = message.outgoing && !group ? `<span role="img" aria-label="${status}" title="${status}">${icon(status === 'QUEUED' ? 'check' : 'check-check')}</span>` : '';
        const receipt = group && message.outgoing ? `<small>${status === 'QUEUED' ? 'QUEUED' : `1 delivered / 1 of ${Math.max(1, groupCount(state.active) - 1)} read`}</small>` : '';
        return `<div class="message ${message.outgoing ? 'outgoing' : ''}" data-message-index="${index}" tabindex="0">${sender}${message.expiry === 'View once' ? `<span class="once-message">${icon('eye')}View-once message</span>` : message.photo ? `<button type="button" class="message-photo" data-action="view-photo" data-photo-index="${index}" aria-label="Open photo">${icon('image')}<span>Photo</span></button>` : escape(message.text)}<small>9:41 / ${message.expiry || '1 hour'} ${mark}</small>${receipt}</div>`;
      }).join('')}`;
    }
    icons();
    list.scrollTop = list.scrollHeight;
  }

  app.addEventListener('change', event => {
    const consentActions = { 'trust-owner': 'accept-invite', 'group-owner-consent': 'create-group', 'verify-contact': 'add-verified-contact' };
    if (consentActions[event.target.id]) {
      app.querySelector(`[data-action="${consentActions[event.target.id]}"]`).disabled = !event.target.checked;
      return;
    }
    if (event.target.matches('input[name="expiry"]')) {
      state.expiry = event.target.value;
      closeSheet();
      const control = app.querySelector('.expiry-line');
      control.querySelector('span').textContent = state.expiry;
      control.setAttribute('aria-label', `Message expiry: ${state.expiry}`);
      return;
    }
    if (event.target.id === 'notifications') {
      state.notifications = event.target.checked;
      reviewStatus('Notification preference changed in this preview only. Android notification permissions and FCM are not connected here.');
      return;
    }
    if (!event.target.matches('[data-photo-input]')) return;
    const file = event.target.files[0];
    event.target.value = '';
    void previewPhoto(file);
  });

  app.addEventListener('input', event => {
    if (['profile-name', 'profile-username', 'contact-display-name'].includes(event.target.id)) feedback(event.target.id, '');
    if (event.target.matches('.composer input') && state.active) state.drafts.set(state.active.id, event.target.value);
  });

  function messageActions(index) {
    if (!state.active || !state.messages.get(state.active.id)?.[index]) return;
    state.deletingMessage = index;
    openSheet('Delete message?', '', `${secondary('Cancel', 'close-sheet')}${primary('Delete', 'delete-message', 'trash-2')}`);
  }

  function contextAction(target) {
    const row = target.closest('[data-message-index], [data-chat]');
    if (!row) return false;
    if (row.dataset.messageIndex !== undefined) messageActions(Number(row.dataset.messageIndex));
    else {
      const chat = chats.find(candidate => candidate.id === row.dataset.chat);
      if (chat.type === 'group') groupInfo(chat);
      else { state.removingContact = chat; removeContact(); }
    }
    return true;
  }

  let holdTimer;
  let holdOrigin;
  let suppressClick = false;
  const cancelHold = () => { clearTimeout(holdTimer); holdOrigin = null; };
  app.addEventListener('pointerdown', event => {
    if (event.button !== 0 || !event.target.closest('[data-chat], [data-message-index]')) return;
    holdOrigin = { horizontal: event.clientX, vertical: event.clientY };
    holdTimer = setTimeout(() => { suppressClick = contextAction(event.target); }, 650);
  });
  app.addEventListener('pointermove', event => {
    if (holdOrigin && Math.hypot(event.clientX - holdOrigin.horizontal, event.clientY - holdOrigin.vertical) > 10) cancelHold();
  });
  for (const type of ['pointerup', 'pointercancel', 'pointerleave']) app.addEventListener(type, cancelHold);
  app.addEventListener('contextmenu', event => { if (contextAction(event.target)) { cancelHold(); event.preventDefault(); } });

  app.addEventListener('click', event => {
    if (suppressClick) { suppressClick = false; return; }
    const chatButton = event.target.closest('[data-chat]');
    if (chatButton) { openChat(chats.find(chat => chat.id === chatButton.dataset.chat)); return; }
    const filter = event.target.closest('[data-filter]');
    if (filter) { state.filter = filter.dataset.filter; updateFilters(); renderRows(); return; }
    const action = event.target.closest('[data-action]')?.dataset.action;
    switch (action) {
      case 'new': newConversation(); break;
      case 'new-contact': newContact(); break;
      case 'new-group': newGroup(); break;
      case 'profile': profile(); break;
      case 'options': conversationOptions(); break;
      case 'contact-profile': contactProfile(); break;
      case 'contact-identity': showIdentity(false); break;
      case 'identity': showIdentity(true); break;
      case 'expiry': expiryDialog(); break;
      case 'remove-contact': state.removingContact = state.active; removeContact(); break;
      case 'confirm-remove-contact': {
        const chat = state.removingContact;
        for (const message of state.messages.get(chat.id) || []) if (message.photo) URL.revokeObjectURL(message.photo);
        state.messages.delete(chat.id); chats.splice(chats.indexOf(chat), 1);
        renderHome(); reviewStatus('Only the fictional contact in this tab was removed. The Android app is unchanged.'); break;
      }
      case 'save-contact-name': {
        const value = app.querySelector('#contact-display-name').value.trim();
        if (!validName(value)) { feedback('contact-display-name', 'Enter a name of 1-40 characters', true); break; }
        state.active.name = value; closeSheet(); refreshChatIdentity(); break;
      }
      case 'use-profile-name': state.active.name = state.active.profileName || state.active.handle; closeSheet(); refreshChatIdentity(); break;
      case 'share-username':
        openSheet('Vanishr username', `<p class="share-value">@${escape(state.username)}</p>`, secondary('Close', 'close-sheet'));
        reviewStatus('Android opens its system share sheet with this username. This preview does not send, copy or share anything.'); break;
      case 'updates': closeSheet(); reviewStatus('Check for updates: Android requests the signed APK update feed. This local preview makes no update request and cannot determine whether your installed app is current.'); break;
      case 'licenses': licenses(); break;
      case 'agpl': licenses(true); break;
      case 'sign-out': signOut(); break;
      case 'confirm-sign-out': showSignedOut(); break;
      case 'sample-sign-in': renderHome(); reviewStatus('Returned to the fictional account. No authentication request was sent.'); break;
      case 'delete-message': {
        const messages = state.messages.get(state.active.id);
        const removed = messages.splice(state.deletingMessage, 1)[0];
        if (removed?.photo) URL.revokeObjectURL(removed.photo);
        closeSheet(); renderMessages(); break;
      }
      case 'close-sheet': closeSheet(); break;
      case 'attach': attachments(); break;
      case 'photo-library': app.querySelector('#photo-library-input')?.click(); break;
      case 'camera': app.querySelector('#camera-input')?.click(); break;
      case 'send-photo': {
        if (!state.photo || state.photo.chatId !== state.active?.id) break;
        state.messages.get(state.active.id).push({ photo: state.photo.url, outgoing: true, expiry: state.expiry, delivery: 'QUEUED' });
        state.photo = null;
        closeSheet(); renderMessages(); app.querySelector('.composer input').focus(); break;
      }
      case 'view-photo': {
        const index = Number(event.target.closest('[data-photo-index]').dataset.photoIndex);
        const message = state.messages.get(state.active?.id)?.[index];
        if (message?.photo) openSheet('Photo', `<figure class="photo-preview"><img src="${message.photo}" alt="Attached photo"></figure>`, primary('Done', 'close-sheet'));
        break;
      }
      case 'back': renderHome(); break;
      case 'unread': state.filter = state.filter === 'unread' ? 'all' : 'unread'; updateFilters(); renderRows(); break;
      case 'toggle-search': state.searchOpen = !state.searchOpen; if (!state.searchOpen) state.query = ''; renderHome(); app.querySelector('input[type="search"]')?.focus(); break;
      case 'clear-search': state.query = ''; app.querySelector('input[type="search"]').value = ''; renderRows(); app.querySelector('input[type="search"]').focus(); break;
      case 'find-contact': {
        const input = app.querySelector('#contact-name');
        const value = input.value.trim().replace(/^@/, '').toLocaleLowerCase();
        const found = chats.find(chat => chat.handle === value);
        const candidate = people.find(person => person.handle === value);
        if (/^[a-z0-9_]{3,32}$/.test(value) && !found && candidate) verifyContact(candidate);
        else app.querySelector('#contact-result').textContent = !/^[a-z0-9_]{3,32}$/.test(value) ? 'Enter a username of 3-32 letters, numbers or underscores' : found ? `${found.name} is already a verified contact.` : 'The requested account or item was not found.';
        break;
      }
      case 'add-verified-contact': {
        if (!app.querySelector('#verify-contact')?.checked) break;
        chats.push({ ...state.candidate }); renderHome();
        reviewStatus('Added a fictional verified contact. No real identity was checked or trusted.'); break;
      }
      case 'verify-owner': newContact(currentPerson(state.invitation.ownerId).handle); break;
      case 'create-group': {
        const input = app.querySelector('#group-name');
        if (!app.querySelector('#group-owner-consent')?.checked) break;
        if (!validName(input.value.trim(), 64)) { feedback('group-name', 'Enter a name of 1-64 characters', true); break; }
        const chat = { id: `group-${chats.length}`, name: input.value.trim(), tone: 'blue', unread: 0, type: 'group', ownerId: 'self', roster: [{ id: 'self', state: 'ACTIVE' }], members: 1 };
        chats.unshift(chat); state.messages.set(chat.id, []); openChat(chat); inviteMembers(chat); break;
      }
      case 'show-invite-members': inviteMembers(state.managedGroup); break;
      case 'invite-members': {
        const selected = [...app.querySelectorAll('input[name="group-member"]:checked')].map(input => input.value);
        if (!selected.length) break;
        const chat = state.managedGroup;
        if (chat.roster.length + selected.length > 200) { reviewStatus('The 200-member limit includes the owner and pending invitations.'); break; }
        chat.roster.push(...selected.map(id => ({ id, state: 'INVITED' }))); chat.members = chat.roster.length;
        groupInfo(chat); reviewStatus('Invitations are simulated and remain Invited. The real app waits for recipients to accept and for the owner to approve the new membership.'); break;
      }
      case 'remove-member': {
        const chat = state.managedGroup;
        if (chat.ownerId !== 'self') break;
        state.removingMember = event.target.closest('[data-member]').dataset.member;
        openSheet('Remove member?', `<p>${escape(currentPerson(state.removingMember).name)}</p>`, `${secondary('Cancel', 'close-sheet')}${primary('Remove', 'confirm-remove-member', 'x')}`); break;
      }
      case 'confirm-remove-member': {
        const chat = state.managedGroup;
        chat.roster = chat.roster.filter(member => member.id !== state.removingMember); chat.members = chat.roster.length;
        if (state.active === chat) openChat(chat);
        groupInfo(chat); reviewStatus('Only the sample roster changed. Real membership removal, relay revocation and encryption-key rotation are not performed here.'); break;
      }
      case 'leave-group': confirmGroupExit(); break;
      case 'confirm-leave-group': {
        const chat = state.managedGroup;
        for (const message of state.messages.get(chat.id) || []) if (message.photo) URL.revokeObjectURL(message.photo);
        state.messages.delete(chat.id); chats.splice(chats.indexOf(chat), 1); renderHome();
        reviewStatus('The fictional group was removed from this preview only. No real group was changed.'); break;
      }
      case 'save-name': {
        const value = app.querySelector('#profile-name').value.trim();
        if (!validName(value)) { feedback('profile-name', 'Enter a name of 1-40 characters', true); break; }
        state.profile = value; app.querySelector('#profile-name').value = value; app.querySelector('#profile-heading').textContent = value;
        app.querySelector('#profile-avatar').textContent = initial(value); app.querySelector('.profile-button').textContent = initial(value);
        feedback('profile-name', 'Saved'); reviewStatus('Display name saved only in this preview. In Android it is shared account metadata, unlike a local contact nickname.'); break;
      }
      case 'save-username': {
        const value = app.querySelector('#profile-username').value.trim().replace(/^@/, '').toLocaleLowerCase();
        if (!/^[a-z0-9_]{3,32}$/.test(value)) { feedback('profile-username', 'Use 3-32 lowercase letters, numbers or underscores', true); break; }
        if (chats.some(chat => chat.handle === value)) { feedback('profile-username', 'That username is already taken. Choose another username.', true); break; }
        state.username = value; app.querySelector('#profile-username').value = value; app.querySelector('#profile-handle').textContent = `@${value}`;
        feedback('profile-username', 'Saved'); reviewStatus('Username saved in this tab only. Real uniqueness and server saves are not performed; sample-contact handles demonstrate the taken-name state.'); break;
      }
      case 'accept-invite': if (app.querySelector('#trust-owner')?.checked) {
        const chat = state.invitation; chat.invite = false; chat.waiting = true;
        chat.roster.find(member => member.id === 'self').state = 'ACTIVE';
        state.messages.set(chat.id, []); openChat(chat);
        reviewStatus('This is the real waiting state after acceptance: sending stays disabled until the owner is online and approves membership. No owner response is simulated automatically.');
      } break;
      case 'decline-invite': chats.splice(chats.indexOf(state.invitation), 1); closeSheet(); renderHome(); break;
      case 'group-info': groupInfo(); break;
    }
  });

  document.addEventListener('keydown', event => {
    if (event.key === 'F10' && event.shiftKey) { if (contextAction(event.target)) event.preventDefault(); return; }
    if (state.modal?.querySelector('[role="menu"]') && ['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
      const items = [...state.modal.querySelectorAll('[role="menuitem"]')];
      const index = items.indexOf(document.activeElement);
      const next = event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1 : (index + (event.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length;
      event.preventDefault(); items[next].focus(); return;
    }
    if (event.key === 'Escape') { if (state.modal) closeSheet(); else if (state.active) renderHome(); }
    if (event.key !== 'Tab' || !state.modal) return;
    const controls = [...state.modal.querySelectorAll('button:not([disabled]), input:not([disabled]), a[href]')].filter(control => !control.hidden && control.getClientRects().length);
    const first = controls[0]; const last = controls.at(-1);
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
  });

  globalThis.addEventListener('pagehide', () => {
    if (state.photo) URL.revokeObjectURL(state.photo.url);
    for (const messages of state.messages.values()) for (const message of messages) if (message.photo) URL.revokeObjectURL(message.photo);
  });

  function previewScreen(screen) {
    if (!['home', 'chat', 'contact', 'profile'].includes(screen)) return;
    flowNav.querySelectorAll('button').forEach(control => control.setAttribute('aria-pressed', String(control.dataset.reviewScreen === screen)));
    if (screen === 'home' || screen === 'profile') {
      renderHome(); if (screen === 'profile') profile();
    } else {
      const chat = chats.find(candidate => candidate.id === 'aarav') || chats.find(candidate => candidate.type === 'direct');
      if (!chat) { renderHome(); return; }
      openChat(chat); if (screen === 'contact') contactProfile();
    }
  }
  flowNav.addEventListener('click', event => {
    const control = event.target.closest('[data-review-screen]');
    if (control) previewScreen(control.dataset.reviewScreen);
  });
  globalThis.addEventListener('message', event => {
    if (event.source === globalThis.parent && globalThis.parent !== globalThis && event.data?.type === 'vanishr:preview-screen') previewScreen(event.data.screen);
  });
  previewScreen(new URLSearchParams(location.search).get('screen') || 'home');
})();