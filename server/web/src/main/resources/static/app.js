// Every page: the browser's time zone for the server, the live stream, and geofence alerts.
(() => {
  'use strict';

  // The server shows dates in this zone; "Hoy" in the history is today here, not on the server.
  try {
    const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
    if (zone && !document.cookie.split('; ').includes('pt-tz=' + encodeURIComponent(zone))) {
      document.cookie = 'pt-tz=' + encodeURIComponent(zone) + '; Path=/; Max-Age=31536000; SameSite=Strict; Secure';
    }
  } catch (e) {
    // An old browser without Intl: the server's zone it is.
  }

  document.addEventListener('focusin', (event) => {
    if (event.target.matches && event.target.matches('[data-select-on-focus]')) event.target.select();
  });

  if (!document.body.dataset.signedIn) return;

  const timeFormat = new Intl.DateTimeFormat('es-MX', { hour: '2-digit', minute: '2-digit' });

  // Alerts: a card in the corner that stays until closed, and a browser notification if allowed.

  function toast(title, detail) {
    const box = document.getElementById('toasts');
    if (!box) return;
    const card = document.createElement('div');
    card.className = 'toast';
    card.setAttribute('role', 'status');
    const strong = document.createElement('strong');
    strong.textContent = title;
    const small = document.createElement('span');
    small.className = 'toast-detail';
    small.textContent = detail;
    const close = document.createElement('button');
    close.type = 'button';
    close.setAttribute('aria-label', 'Cerrar aviso');
    close.textContent = '×';
    close.addEventListener('click', () => card.remove());
    card.append(strong, close, small);
    box.prepend(card);
    while (box.children.length > 5) box.lastElementChild.remove();
  }

  /** Every tab shows the card; only the tab holding the stream raises the system notification, once. */
  function showAlert(alert, notify) {
    const title = alert.device + (alert.transition === 'ENTER' ? ' entró en ' : ' salió de ') + alert.geofence;
    const detail = 'A las ' + timeFormat.format(new Date(alert.occurredAt));
    toast(title, detail);
    if (notify && 'Notification' in window && Notification.permission === 'granted') {
      try {
        new Notification(title, { body: detail, tag: alert.device + '|' + alert.geofence + '|' + alert.occurredAt });
      } catch (e) {
        // Chrome on Android only notifies through a service worker; the card is enough there.
      }
    }
  }

  const notifyButton = document.getElementById('notify-enable');
  function updateNotifyButton() {
    if (!notifyButton) return;
    notifyButton.hidden = !('Notification' in window) || !window.isSecureContext || Notification.permission !== 'default';
  }
  if (notifyButton) {
    notifyButton.addEventListener('click', () => {
      Notification.requestPermission().then(updateNotifyButton, updateNotifyButton);
    });
    updateNotifyButton();
  }

  // The device list of the Dispositivos page follows the stream, but never under the owner's hands: a
  // refresh skipped because a confirmation is open or a button has focus runs once they are done, and
  // one that comes too soon after another runs when the pause is over.
  const REFRESH_PAUSE_MS = 5000;
  let lastRefresh = 0;
  let pendingRefresh = false;
  let refreshTimer = 0;
  function refreshDevices() {
    const section = document.getElementById('devices');
    if (!section || !window.htmx) return;
    if (section.querySelector('details[open]') || section.contains(document.activeElement)) {
      pendingRefresh = true;
      return;
    }
    const wait = lastRefresh + REFRESH_PAUSE_MS - Date.now();
    if (wait > 0) {
      if (!refreshTimer) refreshTimer = window.setTimeout(() => { refreshTimer = 0; refreshDevices(); }, wait);
      return;
    }
    pendingRefresh = false;
    lastRefresh = Date.now();
    window.htmx.trigger(section, 'pt-refresh');
  }
  ['toggle', 'focusout'].forEach((type) => {
    document.addEventListener(type, () => {
      if (pendingRefresh) window.setTimeout(refreshDevices, 0);
    }, true);
  });

  function deliver(message, notify) {
    if (message.type === 'devices') {
      document.dispatchEvent(new CustomEvent('pt:devices', { detail: message.data }));
      refreshDevices();
    } else if (message.type === 'alert') {
      message.data.forEach((alert) => showAlert(alert, notify));
      document.body.dispatchEvent(new CustomEvent('pt-alert'));
    }
  }

  function goToSignIn() {
    window.location.href = '/login?next=' + encodeURIComponent(window.location.pathname + window.location.search);
  }

  // The live stream. Browsers keep at most six HTTP/1.1 connections to a server and each stream holds
  // one, so the tabs share a single stream: the tab holding the "pt-live" lock opens it and passes every
  // event to the others; when that tab closes, another takes the lock. EventSource retries by itself
  // after a dropped connection, but not after an answer other than 200: then /session tells whether
  // this browser is still signed in.
  const channel = 'BroadcastChannel' in window ? new BroadcastChannel('pt-live') : null;

  function lead() {
    let source = null;
    function connect() {
      source = new EventSource('/live');
      ['devices', 'alert'].forEach((type) => {
        source.addEventListener(type, (event) => {
          const message = { type, data: JSON.parse(event.data) };
          deliver(message, true);
          if (channel) channel.postMessage(message);
        });
      });
      // The session the stream began with ended. Another tab may have changed the password and hold
      // a new cookie; only a browser without any session goes to sign in.
      source.addEventListener('signed-out', () => {
        source.close();
        checkSession();
      });
      source.addEventListener('error', () => {
        if (source.readyState === EventSource.CLOSED) checkSession();
      });
    }
    function checkSession() {
      fetch('/session', { credentials: 'same-origin', cache: 'no-store' })
        .then((response) => {
          if (response.status === 401) {
            if (channel) channel.postMessage({ type: 'signed-out' });
            goToSignIn();
          } else {
            window.setTimeout(connect, 2000);
          }
        })
        .catch(() => window.setTimeout(connect, 10000));
    }
    connect();
    window.addEventListener('pagehide', () => source && source.close());
  }

  if (channel && navigator.locks) {
    channel.addEventListener('message', (event) => {
      if (event.data.type === 'signed-out') goToSignIn();
      else deliver(event.data, false);
    });
    // Held until this tab closes: the promise never settles.
    navigator.locks.request('pt-live', () => new Promise(lead));
  } else {
    lead();
  }
  // Restored from the back-forward cache: start over, so the stream and the lock are this page's again.
  window.addEventListener('pageshow', (event) => {
    if (event.persisted) window.location.reload();
  });
})();
