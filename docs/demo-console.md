# Demo console

A small read-only web UI at **`/demo/index.html`** for walking through the retail domain without
scrolling through Swagger. It is one static page, vanilla JavaScript, no build step, same origin as
the API.

## What it shows

- **Overview:** store KPIs, risk mix, priority actions by days of cover, recent replenishment runs.
- **Inventory:** searchable, sortable inventory-risk table with risk filters.
- **Replenishment:** searchable recommendations, order values, and the filtered total.
- **Decisions:** durable runs, proposal drill-down with decision status, and purchase orders.

The Overview starts with a three-step journey (find risk → review the plan → follow decisions) and a
Guide dialog explaining the flow in plain language. Search, filtering, and sorting are client-side,
so they are instant and do not re-query BigQuery. `/` focuses the active search, `r` refreshes, and
`?` opens the guide.

## Use

Open `/demo/index.html`, paste the **read-only** API key, and pick a store. The key is stored only in
the browser tab's `sessionStorage` and is sent only to the same origin. The page calls GET endpoints
only; there are no write actions and no place to enter the admin key.

## Security and scope

- The static page is public; every data call is authenticated with `X-API-Key`.
- It is intentionally read-only. Creating runs, approving proposals, and submitting POs stay in the
  API/Swagger.
- Retail reads are capped at `RETAIL_MAX_PAGE_SIZE` (default 200); the UI labels truncated results.
- Run proposals load the first 200 and say so when more exist.

## Files

- `src/main/resources/static/demo/index.html`
- `src/main/resources/static/demo/app.css`
- `src/main/resources/static/demo/app.js`
- `SecurityIntegrationTest.demoConsoleIsPublicButTheApiIsNot` proves the page is public while the API
  is not.

Swagger remains the full API reference. The console is the fast path for a reviewer; it is not a
replacement for the contract or for write workflows.

## Hand-off

Give a reviewer the base URL, the read-only key, and this page. For the deeper tour, see
[showcase.md](showcase.md).
