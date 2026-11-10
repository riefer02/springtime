import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, STORE_ID, authHeaders, thresholds } from './config.js';

// Retail read load. Requires a seeded dataset (make retail-schema && make retail-seed). Unknown or
// unseeded stores return 404/503 by design, which the checks surface instead of hiding.
export const options = {
  vus: 10,
  duration: '1m',
  thresholds: {
    ...thresholds,
    http_req_duration: ['p(95)<2000', 'p(99)<4000'],
  },
};

const paths = [
  `/api/v1/retail/inventory?storeId=${STORE_ID}`,
  `/api/v1/retail/replenishment?storeId=${STORE_ID}`,
  `/api/v1/retail/inventory/movements?storeId=${STORE_ID}&limit=50`,
  `/api/v1/retail/dashboard?storeId=${STORE_ID}`,
];

export default function () {
  const path = paths[Math.floor(Math.random() * paths.length)];
  const response = http.get(`${BASE_URL}${path}`, {
    headers: authHeaders,
    tags: { endpoint: path.split('?')[0] },
  });

  check(response, {
    'retail read is 200': (r) => r.status === 200,
    'returns items': (r) => r.json('items') !== undefined,
  });

  sleep(Math.random() * 0.5);
}
