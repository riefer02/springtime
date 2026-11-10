import http from 'k6/http';
import { check, sleep } from 'k6';
import { API_KEY, BASE_URL, authHeaders, purchaseOrderList, thresholds } from './config.js';

// Minimal sanity run: proves the stack answers, auth works, and the page envelope is intact.
// `make loadtest` uses this file by default.
export const options = {
  vus: 1,
  duration: '15s',
  thresholds,
};

export default function () {
  const liveness = http.get(`${BASE_URL}/actuator/health/liveness`);
  check(liveness, { 'liveness is 200': (response) => response.status === 200 });

  if (!API_KEY) {
    return;
  }

  const list = http.get(purchaseOrderList(), { headers: authHeaders });
  check(list, {
    'purchase-order list is 200': (response) => response.status === 200,
    'list is a page envelope': (response) => response.json('items') !== undefined,
  });

  sleep(1);
}
