// Shared k6 settings. Override with -e BASE_URL=... -e API_KEY=...
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
export const API_KEY = __ENV.API_KEY || '';
export const STORE_ID = __ENV.STORE_ID || 'ST-001';

export const authHeaders = {
  'X-API-Key': API_KEY,
  'Content-Type': 'application/json',
};

// Thresholds mirror the SLOs in docs/operations/README.md. Tune the latency budget to your
// environment, but keep the failure-rate gate: a load test that cannot fail is a demo.
export const thresholds = {
  http_req_failed: ['rate<0.01'],
  http_req_duration: ['p(95)<2000'],
};

export const intakeThresholds = {
  http_req_failed: ['rate<0.005'],
  http_req_duration: ['p(95)<250'],
};

export function purchaseOrderList() {
  return `${BASE_URL}/api/v1/retail/purchase-orders?limit=10`;
}

export function eventBody(eventId) {
  return JSON.stringify({
    eventId,
    eventType: 'SALE',
    occurredAt: new Date().toISOString(),
    storeId: STORE_ID,
    productId: 'SKU-0001',
    quantity: 1,
    payload: { channel: 'LOADTEST', sourceDocumentId: `LT-${eventId}` },
  });
}
