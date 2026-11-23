// Adds a "Generate tmp password" button to the user details page of the Admin Console.
// Clicking it calls POST {server}/realms/{realm}/tmp-password/users/{id}, which sets a
// temporary password and sends it to the user's phone by SMS.
//
// The Admin Console keeps its access token in memory, so this script reads the
// Bearer token from the console's own API requests (fetch / XHR) and reuses it.

const BUTTON_ID = "tmp-password-generate-btn";
const USER_ROUTE = /\/([^/]+)\/users\/([0-9a-fA-F-]{8,})(?:\/|$)/;

let accessToken = null;

function rememberToken(value) {
  if (typeof value === "string" && value.startsWith("Bearer ")) {
    accessToken = value.substring("Bearer ".length);
  }
}

function headerFrom(headers) {
  if (!headers) return null;
  if (headers instanceof Headers) return headers.get("Authorization");
  if (Array.isArray(headers)) {
    const found = headers.find(([k]) => k.toLowerCase() === "authorization");
    return found ? found[1] : null;
  }
  const key = Object.keys(headers).find((k) => k.toLowerCase() === "authorization");
  return key ? headers[key] : null;
}

const originalFetch = window.fetch;
window.fetch = function (input, init) {
  try {
    rememberToken(headerFrom(init && init.headers));
    if (input instanceof Request) rememberToken(input.headers.get("Authorization"));
  } catch (e) {
    /* never break the console */
  }
  return originalFetch.apply(this, arguments);
};

const originalSetRequestHeader = XMLHttpRequest.prototype.setRequestHeader;
XMLHttpRequest.prototype.setRequestHeader = function (name, value) {
  if (String(name).toLowerCase() === "authorization") rememberToken(value);
  return originalSetRequestHeader.apply(this, arguments);
};

function environment() {
  try {
    return JSON.parse(document.getElementById("environment").textContent);
  } catch (e) {
    return {};
  }
}

function currentUserRoute() {
  const path = (window.location.hash || "").replace(/^#/, "") || window.location.pathname;
  const match = path.match(USER_ROUTE);
  if (!match || match[2] === "add-user") return null;
  return { realm: decodeURIComponent(match[1]), userId: match[2] };
}

function showAlert(variant, title, text) {
  let group = document.getElementById("tmp-password-alerts");
  if (!group) {
    group = document.createElement("ul");
    group.id = "tmp-password-alerts";
    group.className = "pf-v5-c-alert-group pf-m-toast";
    document.body.appendChild(group);
  }
  const item = document.createElement("li");
  item.className = "pf-v5-c-alert-group__item";
  const alert = document.createElement("div");
  alert.className = `pf-v5-c-alert pf-m-${variant}`;
  alert.setAttribute("role", "alert");
  const titleEl = document.createElement("p");
  titleEl.className = "pf-v5-c-alert__title";
  titleEl.textContent = title;
  alert.appendChild(titleEl);
  if (text) {
    const body = document.createElement("div");
    body.className = "pf-v5-c-alert__description";
    body.textContent = text;
    alert.appendChild(body);
  }
  item.appendChild(alert);
  group.appendChild(item);
  setTimeout(() => item.remove(), 8000);
}

async function generate(button) {
  const route = currentUserRoute();
  if (!route) return;
  if (!accessToken) {
    showAlert("danger", "Generate tmp password", "No admin session token yet, reload the page and try again.");
    return;
  }
  if (!window.confirm("Generate a new temporary password and send it to this user by SMS?\n\nThe user's current password will be replaced.")) {
    return;
  }

  const env = environment();
  const base = (env.serverBaseUrl || window.location.origin).replace(/\/$/, "");
  const url = `${base}/realms/${encodeURIComponent(route.realm)}/tmp-password/users/${encodeURIComponent(route.userId)}`;

  button.disabled = true;
  button.classList.add("pf-m-in-progress");
  try {
    const response = await originalFetch(url, {
      method: "POST",
      headers: { Authorization: `Bearer ${accessToken}`, Accept: "application/json" },
    });
    let body = {};
    try {
      body = await response.json();
    } catch (e) {
      /* empty or non-JSON body */
    }
    if (response.ok) {
      const expiry = body.expiresInMinutes ? ` It expires in ${body.expiresInMinutes} minutes.` : "";
      showAlert("success", "Temporary password sent", `SMS sent to ${body.phone || "the user's phone"}.${expiry}`);
    } else {
      showAlert("danger", "Could not generate temporary password", body.error || `HTTP ${response.status}`);
    }
  } catch (e) {
    showAlert("danger", "Could not generate temporary password", String(e));
  } finally {
    button.disabled = false;
    button.classList.remove("pf-m-in-progress");
  }
}

function createButton() {
  const button = document.createElement("button");
  button.id = BUTTON_ID;
  button.type = "button";
  button.className = "pf-v5-c-button pf-m-secondary";
  button.style.marginInlineStart = "1rem";
  button.textContent = "Generate tmp password";
  button.dataset.testid = "generate-tmp-password";
  button.addEventListener("click", () => generate(button));
  return button;
}

function sync() {
  const existing = document.getElementById(BUTTON_ID);
  const route = currentUserRoute();
  if (!route) {
    if (existing) existing.remove();
    return;
  }
  if (existing) return;

  // The user page header: <h1> with the username, inside the first page section.
  const title = document.querySelector("main h1");
  if (!title) return;
  title.insertAdjacentElement("afterend", createButton());
}

new MutationObserver(sync).observe(document.documentElement, { childList: true, subtree: true });
window.addEventListener("hashchange", sync);
window.addEventListener("popstate", sync);
