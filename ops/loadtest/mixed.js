import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, STORE_ID, authHeaders, eventBody, thresholds } from './config.js';

// Mixed workload: mostly reads, some intake. Use this to observe how retail reads behave while the
// event pipeline is busy (separate slot pools, batch queue depth, trace correlation).
export const options = {
  scenarios: {
    reads: {
      executor: 'constant-vus',
      vus: 8,
      duration: '2m',
      exec: 'read',
    },
    intake: {
      executor: 'constant-arrival-rate',
      rate: 5,
      timeUnit: '1s',
      duration: '2m',
      preAllocatedVUs: 10,
      maxVUs: 30,
      exec: 'intake',
    },
  },
  thresholds: {
    ...thresholds,
    'http_req_failed{endpoint:event-intake}': ['rate<0.005'],
    'http_req_duration{endpoint:event-intake}': ['p(95)<250'],
  },
};

export function read() {
  const response = http.get(
    `${BASE_URL}/api/v1/retail/inventory?storeId=${STORE_ID}`,
    { headers: authHeaders, tags: { endpoint: 'retail-read' } },
  );
  check(response, { 'inventory is 200': (r) => r.status === 200 });
  sleep(0.2);
}

export function intake() {
  const eventId = `mix-${__VU}-${__ITER}-${Date.now()}`;
  const response = http.post(`${BASE_URL}/api/v1/retail/events`, eventBody(eventId), {
    headers: authHeaders,
    tags: { endpoint: 'event-intake' },
  });
  check(response, { 'accepted with 202': (r) => r.status === 202 });
}
