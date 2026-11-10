import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, authHeaders, eventBody, intakeThresholds } from './config.js';

// Acceptance-path load: 20 events/s for 2 minutes. Keep the consumer disabled
// (MESSAGING_LOCAL_CONSUMER_ENABLED=false) when you only want to measure intake, or enable it with
// real credentials to exercise the ledger sink end to end.
export const options = {
  scenarios: {
    intake: {
      executor: 'constant-arrival-rate',
      rate: 20,
      timeUnit: '1s',
      duration: '2m',
      preAllocatedVUs: 20,
      maxVUs: 100,
    },
  },
  thresholds: intakeThresholds,
};

export default function () {
  const eventId = `lt-${__VU}-${__ITER}-${Date.now()}`;
  const response = http.post(`${BASE_URL}/api/v1/retail/events`, eventBody(eventId), {
    headers: authHeaders,
    tags: { endpoint: 'event-intake' },
  });

  check(response, {
    'accepted with 202': (r) => r.status === 202,
    'echoes the event id': (r) => r.json('eventId') === eventId,
  });
}
