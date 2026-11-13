(() => {
  'use strict';

  const KEY_STORAGE = 'springtime.readKey';
  const STORE_STORAGE = 'springtime.storeId';

  const readStorage = (key) => {
    try { return sessionStorage.getItem(key) || ''; } catch { return ''; }
  };
  const writeStorage = (key, value) => {
    try { sessionStorage.setItem(key, value); } catch { /* private mode or blocked storage */ }
  };
  const dropStorage = (key) => {
    try { sessionStorage.removeItem(key); } catch { /* ignore */ }
  };

  const state = {
    key: readStorage(KEY_STORAGE),
    storeId: readStorage(STORE_STORAGE),
    loadSequence: 0,
    runSequence: 0,
    stores: [],
    store: null,
    dashboard: null,
    inventory: [],
    inventoryAsOf: null,
    inventoryTruncated: false,
    replenishment: [],
    replenishmentAsOf: null,
    replenishmentTruncated: false,
    runs: [],
    purchaseOrders: [],
    proposals: [],
    selectedRun: null,
    tab: 'overview',
    inventoryQuery: '',
    inventoryRisk: '',
    inventorySort: { key: 'daysOfSupply', dir: 'asc' },
    replenishmentQuery: '',
    replenishmentSort: { key: 'orderValue', dir: 'desc' },
  };

  const $ = (id) => document.getElementById(id);
  const el = {
    store: $('store-select'),
    storeMeta: $('store-meta'),
    asOf: $('as-of'),
    lastUpdated: $('last-updated'),
    connection: $('connection'),
    connectionLabel: $('connection-label'),
    refresh: $('refresh'),
    keyToggle: $('key-toggle'),
    welcome: $('welcome'),
    welcomeKey: $('welcome-key'),
    dashboard: $('dashboard'),
    error: $('error'),
    inventoryBody: $('inventory-body'),
    inventoryMeta: $('inventory-meta'),
    inventorySearch: $('inventory-search'),
    replenishmentBody: $('replenishment-body'),
    replenishmentMeta: $('replenishment-meta'),
    replenishmentSearch: $('replenishment-search'),
    replenishmentTotal: $('replenishment-total'),
    overviewRuns: $('overview-runs'),
    runsBody: $('runs-body'),
    purchaseBody: $('purchase-body'),
    riskBars: $('risk-bars'),
    riskMeta: $('risk-meta'),
    priorityList: $('priority-list'),
    tabInventoryCount: $('tab-inventory-count'),
    tabReplenishmentCount: $('tab-replenishment-count'),
    proposalsCard: $('proposals-card'),
    proposalsTitle: $('proposals-title'),
    proposalsMeta: $('proposals-meta'),
    proposalsBody: $('proposals-body'),
    proposalsClose: $('proposals-close'),
    guideToggle: $('guide-toggle'),
    guideDialog: $('guide-dialog'),
    guideStart: $('guide-start'),
    keyDialog: $('key-dialog'),
    keyForm: $('key-form'),
    keyInput: $('key-input'),
    keyError: $('key-error'),
    keyClear: $('key-clear'),
  };

  class ApiError extends Error {
    constructor(status, problem) {
      super(problem?.detail || `Request failed (${status})`);
      this.status = status;
      this.problem = problem || {};
    }
  }

  const esc = (value) => String(value ?? '').replace(/[&<>"']/g, (ch) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[ch]));

  const number = (value) => value == null ? '—' : new Intl.NumberFormat('en-US').format(value);
  const decimal = (value) => value == null ? '—'
    : new Intl.NumberFormat('en-US', { maximumFractionDigits: 1 }).format(value);
  const money = (value) => value == null || value === '' ? '—'
    : new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(Number(value));
  const day = (value) => {
    if (!value) return '—';
    const date = new Date(String(value).length === 10 ? `${value}T00:00:00` : value);
    return Number.isNaN(date.getTime()) ? String(value)
      : date.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
  };
  const label = (value) => String(value || '—').replace(/_/g, ' ').toLowerCase();
  const badge = (value) => `<span class="badge ${esc(String(value || '').toLowerCase())}">${esc(label(value))}</span>`;

  async function api(path) {
    const response = await fetch(path, { headers: { 'X-API-Key': state.key }, cache: 'no-store' });
    if (!response.ok) {
      let problem = null;
      try { problem = await response.json(); } catch { /* non-JSON error body */ }
      throw new ApiError(response.status, problem);
    }
    return response.json();
  }

  function showError(problem) {
    if (!problem) { el.error.hidden = true; return; }
    el.error.hidden = false;
    el.error.innerHTML =
      `<strong>${esc(problem.title || 'Request failed')}</strong> ` +
      `<span>${esc(problem.detail || '')}</span>` +
      (problem.code ? ` <code>${esc(problem.code)}</code>` : '') +
      (problem.requestId ? ` <span class="small">requestId ${esc(problem.requestId)}</span>` : '');
  }

  function setConnection(kind, text) {
    el.connection.className = `connection ${kind}`;
    el.connectionLabel.textContent = text;
  }

  function openKeyDialog(error) {
    el.keyError.hidden = !error;
    el.keyError.textContent = error || '';
    el.keyInput.value = '';
    if (!el.keyDialog.open) el.keyDialog.showModal();
    el.keyInput.focus();
  }

  function closeKeyDialog() { if (el.keyDialog.open) el.keyDialog.close(); }

  function openGuide() {
    if (!el.guideDialog.open) el.guideDialog.showModal();
    writeStorage('springtime.guideSeen', '1');
  }

  function maybeShowGuide() {
    if (readStorage('springtime.guideSeen') === '1') return;
    if (!el.keyDialog.open) openGuide();
  }

  function showDashboard(show) {
    el.dashboard.hidden = !show;
    el.welcome.hidden = show;
  }

  function resetData() {
    state.dashboard = null;
    state.inventory = [];
    state.replenishment = [];
    state.runs = [];
    state.purchaseOrders = [];
    state.proposals = [];
    state.selectedRun = null;
    state.inventoryAsOf = null;
    state.replenishmentAsOf = null;
    ['kpi-onhand', 'kpi-lowstock', 'kpi-stockout', 'kpi-sales', 'kpi-late', 'kpi-promos']
      .forEach((id) => { $(id).textContent = '—'; });
    el.asOf.textContent = 'As of —';
    el.lastUpdated.textContent = 'Not loaded';
    el.proposalsCard.hidden = true;
    renderOverview();
    renderInventory();
    renderReplenishment();
    renderRuns();
    renderPurchaseOrders();
  }

  function setTab(tab) {
    const changed = state.tab !== tab;
    state.tab = tab;
    document.querySelectorAll('.tab').forEach((button) => {
      const active = button.dataset.tab === tab;
      button.classList.toggle('active', active);
      button.setAttribute('aria-selected', String(active));
      button.tabIndex = active ? 0 : -1;
    });
    document.querySelectorAll('.tab-panel').forEach((panel) => {
      panel.hidden = panel.id !== `tab-${tab}`;
    });
    if (changed) window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  function renderStoreMeta() {
    const store = state.store;
    if (!store) { el.storeMeta.innerHTML = ''; return; }
    el.storeMeta.innerHTML = [
      `${esc(store.city)}, ${esc(store.state)}`,
      esc(store.region),
      esc(store.storeFormat),
      esc(store.dcId),
      esc(store.timeZone),
    ].filter(Boolean).map((part) => `<span class="pill">${part}</span>`).join('');
  }

  function sortRows(rows, sort, numericKeys) {
    const direction = sort.dir === 'asc' ? 1 : -1;
    return [...rows].sort((a, b) => {
      const left = a[sort.key];
      const right = b[sort.key];
      if (left == null) return 1;
      if (right == null) return -1;
      if (numericKeys.includes(sort.key)) return (Number(left) - Number(right)) * direction;
      return String(left).localeCompare(String(right), undefined, { sensitivity: 'base' }) * direction;
    });
  }

  function markSort(table, sort) {
    table.querySelectorAll('th[data-sort]').forEach((th) => {
      const marker = th.querySelector('.sort');
      if (!marker) return;
      const active = th.dataset.sort === sort.key;
      marker.textContent = active ? (sort.dir === 'asc' ? '↑' : '↓') : '';
      th.setAttribute('aria-sort', active ? (sort.dir === 'asc' ? 'ascending' : 'descending') : 'none');
    });
  }

  function matches(item, query, keys) {
    if (!query) return true;
    const needle = query.toLowerCase();
    return keys.some((key) => String(item[key] || '').toLowerCase().includes(needle));
  }

  function emptyRow(colspan, message) {
    return `<tr class="empty-row"><td colspan="${colspan}">${esc(message)}</td></tr>`;
  }

  function renderInventory() {
    const filtered = state.inventory.filter((item) =>
      (!state.inventoryRisk || item.riskLevel === state.inventoryRisk)
      && matches(item, state.inventoryQuery, ['sku', 'productName', 'category']));
    const rows = sortRows(filtered, state.inventorySort, ['onHandUnits', 'availableUnits', 'daysOfSupply']);
    el.tabInventoryCount.textContent = String(state.inventory.length);
    el.inventoryMeta.textContent = `${rows.length} of ${state.inventory.length} rows` +
      (state.inventoryAsOf ? ` · as of ${day(state.inventoryAsOf)}` : '') +
      (state.inventoryTruncated ? ' · truncated at the retail page cap' : '');
    el.inventoryBody.innerHTML = rows.length ? rows.map((item) => `
      <tr>
        <td>${esc(item.sku)}</td>
        <td class="wrap">${esc(item.productName)}<span class="sub">${esc(item.category)}</span></td>
        <td class="num">${number(item.onHandUnits)}</td>
        <td class="num">${number(item.availableUnits)}</td>
        <td class="num">${decimal(item.daysOfSupply)}</td>
        <td>${badge(item.riskLevel)}</td>
      </tr>`).join('') : emptyRow(6, 'No inventory rows match this filter.');
    markSort(document.getElementById('tab-inventory'), state.inventorySort);
  }

  function renderReplenishment() {
    const filtered = state.replenishment.filter((item) =>
      matches(item, state.replenishmentQuery, ['sku', 'productName', 'supplierName', 'reason']));
    const rows = sortRows(filtered, state.replenishmentSort,
      ['recommendedUnits', 'orderValue', 'daysOfSupply']);
    const total = filtered.reduce((sum, item) => sum + Number(item.orderValue || 0), 0);
    el.tabReplenishmentCount.textContent = String(state.replenishment.length);
    el.replenishmentMeta.textContent = `${rows.length} of ${state.replenishment.length} rows` +
      (state.replenishmentAsOf ? ` · as of ${day(state.replenishmentAsOf)}` : '') +
      (state.replenishmentTruncated ? ' · truncated at the retail page cap' : '');
    el.replenishmentTotal.textContent = money(total);
    el.replenishmentBody.innerHTML = rows.length ? rows.map((item) => `
      <tr>
        <td class="wrap">${esc(item.productName)}<span class="sub">${esc(item.sku)}</span></td>
        <td class="wrap">${esc(item.supplierName)}</td>
        <td class="num">${number(item.recommendedUnits)}</td>
        <td class="num">${money(item.orderValue)}</td>
        <td>${esc(item.reason)}</td>
      </tr>`).join('') : emptyRow(5, 'No replenishment rows match this filter.');
    markSort(document.getElementById('tab-replenishment'), state.replenishmentSort);
  }

  function renderOverview() {
    const counts = state.inventory.reduce((map, item) => {
      const key = item.riskLevel || 'UNKNOWN';
      map[key] = (map[key] || 0) + 1;
      return map;
    }, {});
    const order = ['STOCKOUT', 'CRITICAL', 'WATCH', 'NO_DEMAND', 'HEALTHY'];
    const max = Math.max(1, ...Object.values(counts));
    el.riskMeta.textContent = `${state.inventory.length} SKUs`;
    el.riskBars.innerHTML = order.map((risk) => {
      const count = counts[risk] || 0;
      const width = Math.max(count ? 3 : 0, Math.round((count / max) * 100));
      return `<div class="risk-row">
        <span class="label">${esc(label(risk))}</span>
        <div class="risk-track"><div class="risk-fill ${esc(risk.toLowerCase())}" style="width:${width}%"></div></div>
        <span class="count">${count}</span>
      </div>`;
    }).join('');

    const priority = [...state.inventory]
      .filter((item) => item.riskLevel !== 'HEALTHY' && item.daysOfSupply != null)
      .sort((a, b) => Number(a.daysOfSupply) - Number(b.daysOfSupply))
      .slice(0, 5);
    el.priorityList.innerHTML = priority.length ? priority.map((item, index) => `
      <div class="priority-item">
        <span class="priority-rank">${index + 1}</span>
        <div class="priority-copy"><strong>${esc(item.productName)}</strong>
          <span>${esc(item.sku)} · ${number(item.availableUnits)} available</span></div>
        <span class="priority-value">${decimal(item.daysOfSupply)}d</span>
        ${badge(item.riskLevel)}
      </div>`).join('') : '<p class="muted">No urgent actions for this store.</p>';

    const runs = state.runs.slice(0, 5);
    el.overviewRuns.innerHTML = runs.length ? runs.map(runRow).join('')
      : emptyRow(4, 'No replenishment runs yet.');
  }

  function runRow(run) {
    return `<tr>
      <td title="${esc(run.runId)}">${esc(String(run.runId).slice(0, 8))}…</td>
      <td>${badge(run.status)}</td>
      <td class="num">${number(run.proposalCount)}</td>
      <td class="num">${money(run.totalOrderValue)}</td>
    </tr>`;
  }

  function renderRuns() {
    el.runsBody.innerHTML = state.runs.length ? state.runs.map((run) => `
      <tr>
        <td title="${esc(run.runId)}">${esc(String(run.runId).slice(0, 8))}…</td>
        <td>${badge(run.status)}</td>
        <td class="num">${number(run.proposalCount)}</td>
        <td class="num">${money(run.totalOrderValue)}</td>
        <td>${day(run.createdAt)}</td>
        <td><button class="button ghost" type="button" data-run="${esc(run.runId)}">View</button></td>
      </tr>`).join('') : emptyRow(6, 'No replenishment runs for this store.');
  }

  function renderPurchaseOrders() {
    el.purchaseBody.innerHTML = state.purchaseOrders.length ? state.purchaseOrders.map((po) => `
      <tr>
        <td>${esc(po.poNumber)}</td>
        <td class="wrap">${esc(po.supplierName)}</td>
        <td>${badge(po.status)}</td>
        <td class="num">${money(po.totalValue)}</td>
        <td>${day(po.createdAt)}</td>
      </tr>`).join('') : emptyRow(5, 'No purchase orders yet.');
  }

  function decisionText(proposal) {
    const parts = [];
    if (proposal.decidedBy) parts.push(proposal.decidedBy);
    if (proposal.decisionNote) parts.push(proposal.decisionNote);
    if (proposal.orderReference) parts.push(proposal.orderReference);
    return parts.length ? parts.map(esc).join(' · ') : '<span class="muted">—</span>';
  }

  function renderProposals(run) {
    state.selectedRun = run;
    el.proposalsCard.hidden = false;
    el.proposalsTitle.textContent = `Proposals · run ${String(run.runId).slice(0, 8)}…`;
    el.proposalsMeta.textContent = `${state.proposals.length} shown · ${label(run.status)} · ` +
      `${money(run.totalOrderValue)} total`;
    el.proposalsBody.innerHTML = state.proposals.length ? state.proposals.map((proposal) => `
      <tr>
        <td class="wrap">${esc(proposal.productName)}<span class="sub">${esc(proposal.sku)}</span></td>
        <td class="wrap">${esc(proposal.supplierName)}</td>
        <td class="num">${number(proposal.recommendedUnits)}</td>
        <td class="num">${money(proposal.orderValue)}</td>
        <td>${badge(proposal.status)}</td>
        <td class="wrap">${decisionText(proposal)}</td>
      </tr>`).join('') : emptyRow(6, 'No proposals in this run.');
  }

  async function openRun(runId) {
    const run = state.runs.find((candidate) => candidate.runId === runId);
    if (!run) return;
    const sequence = ++state.runSequence;
    showError(null);
    el.proposalsCard.hidden = false;
    el.proposalsTitle.textContent = `Proposals · run ${String(runId).slice(0, 8)}…`;
    el.proposalsMeta.textContent = 'Loading proposals…';
    el.proposalsBody.innerHTML = emptyRow(6, 'Loading proposals…');
    el.proposalsCard.scrollIntoView({ behavior: 'smooth', block: 'start' });
    try {
      const page = await api(`/api/v1/retail/replenishment-runs/${encodeURIComponent(runId)}/proposals?limit=200`);
      if (sequence !== state.runSequence) return;
      state.proposals = page.items || [];
      renderProposals(run);
      if (page.nextPageToken) {
        el.proposalsMeta.textContent += ' · first 200';
      }
    } catch (error) {
      if (sequence !== state.runSequence) return;
      el.proposalsCard.hidden = true;
      showError(error.problem || { title: 'Could not load proposals', detail: error.message });
    }
  }

  function applyPayload(dashboard, inventoryPage, replenishmentPage, runsPage, purchasePage) {
    state.dashboard = dashboard;
    if (inventoryPage) {
      state.inventory = inventoryPage.items || [];
      state.inventoryAsOf = inventoryPage.asOfDate;
      state.inventoryTruncated = Boolean(inventoryPage.truncated);
    }
    if (replenishmentPage) {
      state.replenishment = replenishmentPage.items || [];
      state.replenishmentAsOf = replenishmentPage.asOfDate;
      state.replenishmentTruncated = Boolean(replenishmentPage.truncated);
    }
    if (runsPage) state.runs = runsPage.items || [];
    if (purchasePage) state.purchaseOrders = purchasePage.items || [];

    if (state.dashboard) {
      $('kpi-onhand').textContent = number(state.dashboard.onHandUnits);
      $('kpi-lowstock').textContent = number(state.dashboard.lowStockSkus);
      $('kpi-stockout').textContent = number(state.dashboard.stockoutSkus);
      $('kpi-sales').textContent = money(state.dashboard.netSales28d);
      $('kpi-late').textContent = number(state.dashboard.openLateShipments);
      $('kpi-promos').textContent = number(state.dashboard.activePromotions);
    }
    if (state.inventoryAsOf || state.replenishmentAsOf) {
      el.asOf.textContent = `As of ${day(state.inventoryAsOf || state.replenishmentAsOf)}`;
    }
    el.lastUpdated.textContent = `Updated ${new Date().toLocaleTimeString()}`;
    renderOverview();
    renderInventory();
    renderReplenishment();
    renderRuns();
    renderPurchaseOrders();
  }

  async function loadStores() {
    const page = await api('/api/v1/retail/stores?pageSize=50');
    state.stores = page.items || [];
    if (!state.stores.length) {
      el.store.innerHTML = '<option value="">No stores</option>';
      throw new Error('No stores are available in the dataset.');
    }
    const selected = state.stores.some((store) => store.storeId === state.storeId)
      ? state.storeId : state.stores[0].storeId;
    state.storeId = selected;
    writeStorage(STORE_STORAGE, selected);
    el.store.innerHTML = state.stores.map((store) =>
      `<option value="${esc(store.storeId)}"${store.storeId === selected ? ' selected' : ''}>` +
      `${esc(store.storeId)} — ${esc(store.storeName)} (${esc(store.city)})</option>`).join('');
    el.store.disabled = false;
    state.store = state.stores.find((store) => store.storeId === selected);
    renderStoreMeta();
  }

  async function loadStoreData() {
    const sequence = ++state.loadSequence;
    const store = encodeURIComponent(state.storeId);
    el.lastUpdated.textContent = 'Loading…';
    const results = await Promise.allSettled([
      api(`/api/v1/retail/dashboard?storeId=${store}`),
      api(`/api/v1/retail/inventory?storeId=${store}&pageSize=200`),
      api(`/api/v1/retail/replenishment?storeId=${store}&pageSize=200`),
      api(`/api/v1/retail/replenishment-runs?storeId=${store}&limit=10`),
      api(`/api/v1/retail/purchase-orders?storeId=${store}&limit=10`),
    ]);
    if (sequence !== state.loadSequence) return;
    const failure = results.find((result) => result.status === 'rejected');
    if (failure) {
      const error = failure.reason;
      showError(error.problem || { title: 'Request failed', detail: error.message });
      setConnection('failed', 'API error');
      if (error.status === 401 || error.status === 403) {
        showDashboard(false);
        openKeyDialog(error.status === 401 ? 'Key rejected.' : 'This key is read-only; dead letters and writes need the admin key.');
      }
    } else {
      showError(null);
      setConnection('connected', 'Connected');
    }
    applyPayload(
      results[0].status === 'fulfilled' ? results[0].value : null,
      results[1].status === 'fulfilled' ? results[1].value : null,
      results[2].status === 'fulfilled' ? results[2].value : null,
      results[3].status === 'fulfilled' ? results[3].value : null,
      results[4].status === 'fulfilled' ? results[4].value : null,
    );
  }

  async function refreshAll() {
    el.refresh.disabled = true;
    el.refresh.classList.add('spinning');
    try {
      await loadStores();
      await loadStoreData();
      showDashboard(true);
    } catch (error) {
      showError(error.problem || { title: 'Request failed', detail: error.message });
      setConnection('failed', 'Not connected');
      if (error.status === 401 || error.status === 403) {
        showDashboard(false);
        openKeyDialog('Key rejected.');
      }
    } finally {
      el.refresh.disabled = false;
      el.refresh.classList.remove('spinning');
    }
  }

  async function bootstrap() {
    if (!state.key) {
      showDashboard(false);
      setConnection('', 'Not connected');
      return;
    }
    showDashboard(true);
    await refreshAll();
    if (state.key && el.error.hidden) maybeShowGuide();
  }

  // Tabs
  document.querySelectorAll('.tab').forEach((button) => {
    button.addEventListener('click', () => setTab(button.dataset.tab));
  });

  // Key dialog
  el.guideToggle.addEventListener('click', openGuide);
  el.guideStart.addEventListener('click', async () => {
    el.guideDialog.close();
    if (!state.stores.some((store) => store.storeId === 'ST-001')) return;
    el.store.value = 'ST-001';
    state.storeId = 'ST-001';
    writeStorage(STORE_STORAGE, state.storeId);
    state.store = state.stores.find((store) => store.storeId === state.storeId);
    renderStoreMeta();
    state.runSequence++;
    state.proposals = [];
    state.selectedRun = null;
    el.proposalsCard.hidden = true;
    resetData();
    await loadStoreData();
  });
  document.querySelectorAll('[data-tab-jump]').forEach((button) => {
    button.addEventListener('click', () => setTab(button.dataset.tabJump));
  });

  el.keyToggle.addEventListener('click', () => openKeyDialog());
  el.welcomeKey.addEventListener('click', () => openKeyDialog());
  el.keyForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (event.submitter && event.submitter.value === 'cancel') { closeKeyDialog(); return; }
    const key = el.keyInput.value.trim();
    if (!key) { el.keyError.hidden = false; el.keyError.textContent = 'Enter the read-only key.'; return; }
    el.keyError.hidden = true;
    state.key = key;
    writeStorage(KEY_STORAGE, key);
    closeKeyDialog();
    await bootstrap();
  });
  el.keyClear.addEventListener('click', () => {
    state.key = '';
    dropStorage(KEY_STORAGE);
    closeKeyDialog();
    showDashboard(false);
    setConnection('', 'Not connected');
    showError(null);
    el.store.disabled = true;
    el.store.innerHTML = '<option>Loading…</option>';
    resetData();
  });

  // Filters and sorting
  el.inventorySearch.addEventListener('input', () => {
    state.inventoryQuery = el.inventorySearch.value.trim();
    renderInventory();
  });
  document.querySelectorAll('.segmented [data-risk]').forEach((button) => {
    button.addEventListener('click', () => {
      state.inventoryRisk = button.dataset.risk;
      document.querySelectorAll('.segmented [data-risk]').forEach((candidate) => {
        const selected = candidate === button;
        candidate.classList.toggle('active', selected);
        candidate.setAttribute('aria-pressed', String(selected));
      });
      renderInventory();
    });
  });
  el.replenishmentSearch.addEventListener('input', () => {
    state.replenishmentQuery = el.replenishmentSearch.value.trim();
    renderReplenishment();
  });

  const sortable = [
    { panel: 'tab-inventory', stateKey: 'inventorySort', numeric: ['onHandUnits', 'availableUnits', 'daysOfSupply'], render: renderInventory },
    { panel: 'tab-replenishment', stateKey: 'replenishmentSort', numeric: ['recommendedUnits', 'orderValue', 'daysOfSupply'], render: renderReplenishment },
  ];
  sortable.forEach(({ panel, stateKey, numeric, render }) => {
    document.querySelectorAll(`#${panel} th[data-sort]`).forEach((th) => {
      th.tabIndex = 0;
      th.setAttribute('aria-sort', 'none');
      const sortBy = () => {
        const sort = state[stateKey];
        const key = th.dataset.sort;
        if (sort.key === key) {
          sort.dir = sort.dir === 'asc' ? 'desc' : 'asc';
        } else {
          sort.key = key;
          sort.dir = numeric.includes(key) ? 'desc' : 'asc';
        }
        render();
      };
      th.addEventListener('click', sortBy);
      th.addEventListener('keydown', (event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          sortBy();
        }
      });
    });
  });

  // Run drill-down
  el.runsBody.addEventListener('click', (event) => {
    const button = event.target.closest('[data-run]');
    if (button) openRun(button.dataset.run);
  });
  el.proposalsClose.addEventListener('click', () => {
    el.proposalsCard.hidden = true;
    state.proposals = [];
    state.selectedRun = null;
  });

  // Store and refresh
  el.store.addEventListener('change', async () => {
    state.storeId = el.store.value;
    writeStorage(STORE_STORAGE, state.storeId);
    state.store = state.stores.find((store) => store.storeId === state.storeId);
    renderStoreMeta();
    state.runSequence++;
    state.proposals = [];
    state.selectedRun = null;
    el.proposalsCard.hidden = true;
    resetData();
    await loadStoreData();
  });
  el.refresh.addEventListener('click', refreshAll);

  // Keyboard: / focuses the visible search, r refreshes.
  document.querySelector('.tabs').addEventListener('keydown', (event) => {
    const tabs = [...document.querySelectorAll('.tab')];
    const index = tabs.indexOf(document.activeElement);
    if (index < 0) return;
    let next = index;
    if (event.key === 'ArrowRight') next = (index + 1) % tabs.length;
    else if (event.key === 'ArrowLeft') next = (index - 1 + tabs.length) % tabs.length;
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = tabs.length - 1;
    else return;
    event.preventDefault();
    setTab(tabs[next].dataset.tab);
    tabs[next].focus();
  });

  document.addEventListener('keydown', (event) => {
    if (el.keyDialog.open || el.guideDialog.open) return;
    if (event.metaKey || event.ctrlKey || event.altKey) return;
    const target = event.target;
    const typing = target instanceof HTMLInputElement || target instanceof HTMLSelectElement
      || target instanceof HTMLTextAreaElement;
    if (typing) return;
    if (event.key === '/') {
      const search = state.tab === 'inventory' ? el.inventorySearch
        : state.tab === 'replenishment' ? el.replenishmentSearch : null;
      if (search) { event.preventDefault(); search.focus(); }
    }
    if (event.key.toLowerCase() === 'r' && !el.refresh.disabled) {
      event.preventDefault();
      refreshAll();
    }
    if (event.key === '?') {
      event.preventDefault();
      openGuide();
    }
  });

  bootstrap();
})();
