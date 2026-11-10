SHELL := /bin/bash
COMPOSE := docker compose
OBS_COMPOSE := docker compose -f compose.yaml -f compose.observability.yaml
MVN := ./mvnw
CLOUDSDK_PYTHON ?= /usr/bin/python3

.PHONY: help auth env key up down restart logs ps build test verify run package clean it versions ci openapi openapi-check retail-schema retail-seed obs-up obs-down obs-logs obs-config loadtest chaos demo-seed

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-12s\033[0m %s\n", $$1, $$2}'

auth: ## Log in to Google Cloud with Application Default Credentials
	gcloud auth application-default login

env: ## Create .env with a generated API key if it does not exist
	@test -f .env || (cp .env.example .env && \
		key=$$(openssl rand -hex 32) && \
		sed -i.bak "s/^API_KEY=.*/API_KEY=$$key/" .env && rm -f .env.bak && \
		echo "Created .env with a generated API_KEY - set GOOGLE_CLOUD_PROJECT")

key: ## Rotate API_KEY, keeping the old key as API_KEY_PREVIOUS
	@test -f .env || cp .env.example .env
	@grep -q '^API_KEY=' .env || echo 'API_KEY=' >> .env
	@grep -q '^API_KEY_PREVIOUS=' .env || echo 'API_KEY_PREVIOUS=' >> .env
	@old=$$(grep '^API_KEY=' .env | cut -d= -f2-); \
		key=$$(openssl rand -hex 32); \
		sed -i.bak "s/^API_KEY=.*/API_KEY=$$key/" .env; \
		if [ -n "$$old" ]; then \
			sed -i.bak "s/^API_KEY_PREVIOUS=.*/API_KEY_PREVIOUS=$$old/" .env; \
		fi; \
		rm -f .env.bak; \
		echo "API_KEY rotated; previous key kept in API_KEY_PREVIOUS"

up: env ## Build and start the stack on loopback
	@set -a; [ -f .env ] && . ./.env; set +a; \
	if [ -z "$$API_KEY" ]; then \
		echo "API_KEY is empty; run 'make key' or set it in .env"; exit 1; \
	fi
	$(COMPOSE) up --build -d
	@set -a; [ -f .env ] && . ./.env; set +a; \
	echo "API: http://localhost:$${API_PORT:-8080}/actuator/health/liveness"

down: ## Stop the stack
	$(COMPOSE) down

restart: ## Rebuild and restart the API
	$(COMPOSE) up --build -d --force-recreate

logs: ## Follow API logs
	$(COMPOSE) logs -f api

ps: ## Show stack status
	$(COMPOSE) ps

build: ## Build the container image
	$(COMPOSE) build

obs-up: env ## Start the API plus Prometheus, Grafana, OTel collector, and the metrics proxy
	@set -a; [ -f .env ] && . ./.env; set +a; \
	if [ -z "$$API_KEY" ]; then echo "API_KEY is empty; run 'make key'"; exit 1; fi
	$(OBS_COMPOSE) up -d --build postgres api metrics-proxy prometheus grafana otel-collector \
		alertmanager tempo loki alloy
	@set -a; [ -f .env ] && . ./.env; set +a; \
	echo "API:        http://localhost:$${API_PORT:-8080}"; \
	echo "Prometheus: http://localhost:$${PROMETHEUS_PORT:-9090}"; \
	echo "Grafana:    http://localhost:$${GRAFANA_PORT:-3000} (admin / $${GRAFANA_ADMIN_PASSWORD:-admin})"; \
	echo "OTLP/HTTP:  http://localhost:$${OTEL_HTTP_PORT:-4318}"; \
	echo "Alertmanager: http://localhost:$${ALERTMANAGER_PORT:-9093}"

obs-down: ## Stop the observability stack and the API
	$(OBS_COMPOSE) down

obs-logs: ## Follow observability logs
	$(OBS_COMPOSE) logs -f prometheus grafana otel-collector alertmanager tempo loki alloy

obs-config: ## Validate Prometheus rules, Grafana dashboard JSON, and the Compose overlay
	docker run --rm --entrypoint promtool -v "$(PWD)/ops/prometheus:/rules:ro" \
		prom/prometheus:v2.55.1 check rules /rules/springtime-alerts.yml
	python3 -m json.tool ops/observability/grafana/dashboards/springtime-overview.json > /dev/null
	docker run --rm --entrypoint amtool -v "$(PWD)/ops/observability/alertmanager/alertmanager.yml:/etc/alertmanager/alertmanager.yml:ro" \
		prom/alertmanager:v0.27.0 check-config /etc/alertmanager/alertmanager.yml
	docker run --rm -v "$(PWD)/ops/observability/alloy/config.alloy:/etc/alloy/config.alloy:ro" \
		grafana/alloy:v1.8.2 fmt /etc/alloy/config.alloy > /dev/null
	bash -n deploy/gcp/*.sh ops/chaos/*.sh ops/demo/*.sh
	$(OBS_COMPOSE) config -q

loadtest: ## Run a k6 scenario (SCRIPT=/scripts/retail-read.js)
	$(OBS_COMPOSE) --profile loadtest run --rm --no-deps \
		-e K6_SCRIPT=$(or $(SCRIPT),/scripts/smoke.js) k6

chaos: ## Run local failure-injection checks (stops/restarts dependencies)
	bash ops/chaos/run-all.sh

demo-seed: ## Seed one run/approval/PO/outbox arc (BASE_URL, ADMIN_API_KEY)
	@: "$${BASE_URL:?set BASE_URL=https://...}"; : "$${ADMIN_API_KEY:?set ADMIN_API_KEY=...}"
	bash ops/demo/seed-decisions.sh

test: ## Run unit and MVC tests (no GCP calls)
	$(MVN) test

it: ## Run the opt-in real-BigQuery integration tests (requires ADC)
	@set -a; [ -f .env ] && . ./.env; set +a; \
	RUN_BIGQUERY_IT=true $(MVN) test -Dtest=BigQueryIntegrationTest

verify: ## Run the full Maven verification
	$(MVN) verify

ci: ## Same verification target used by CI
	$(MVN) -B -ntp verify

run: ## Run locally, loading .env if present
	@set -a; [ -f .env ] && . ./.env; set +a; $(MVN) spring-boot:run

package: ## Build the executable jar
	$(MVN) -DskipTests package

versions: ## Show available dependency and plugin updates
	$(MVN) versions:display-dependency-updates versions:display-plugin-updates

openapi: ## Regenerate the committed OpenAPI v1 contract
	$(MVN) -Dopenapi.write=true -Dtest=OpenApiContractTest#writeContract test

openapi-check: ## Fail if the committed OpenAPI contract drifts from code
	$(MVN) -Dtest=OpenApiContractTest test

retail-schema: ## Apply the forward-only retail BigQuery schema (bq + ADC required)
	@set -a; [ -f .env ] && . ./.env; set +a; \
	: "$${GOOGLE_CLOUD_PROJECT:?set GOOGLE_CLOUD_PROJECT}"; \
	dataset=$${RETAIL_DATASET:-retail_demo}; \
	location=$${BIGQUERY_LOCATION:-US}; \
	bq --location="$$location" mk --dataset "$$GOOGLE_CLOUD_PROJECT:$$dataset" 2>/dev/null || true; \
	for f in db/retail/schema/*.sql; do \
		echo "Applying $$f"; \
		sed -e "s/__PROJECT__/$$GOOGLE_CLOUD_PROJECT/g" -e "s/__DATASET__/$$dataset/g" "$$f" | \
			CLOUDSDK_PYTHON="$(CLOUDSDK_PYTHON)" bq query --use_legacy_sql=false --location="$$location" --format=none || exit 1; \
	done; \
	echo "Retail schema applied to $$GOOGLE_CLOUD_PROJECT.$$dataset"

retail-seed: ## Load the deterministic retail demo data (run retail-schema first)
	@echo "WARNING: retail-seed replaces the demo tables in the configured BigQuery dataset."
	@set -a; [ -f .env ] && . ./.env; set +a; \
	: "$${GOOGLE_CLOUD_PROJECT:?set GOOGLE_CLOUD_PROJECT}"; \
	dataset=$${RETAIL_DATASET:-retail_demo}; \
	location=$${BIGQUERY_LOCATION:-US}; \
	bq --location="$$location" mk --dataset "$$GOOGLE_CLOUD_PROJECT:$$dataset" 2>/dev/null || true; \
	for f in db/retail/seed/*.sql; do \
		echo "Loading $$f"; \
		sed -e "s/__PROJECT__/$$GOOGLE_CLOUD_PROJECT/g" -e "s/__DATASET__/$$dataset/g" "$$f" | \
			CLOUDSDK_PYTHON="$(CLOUDSDK_PYTHON)" bq query --use_legacy_sql=false --location="$$location" --format=none || exit 1; \
	done; \
	echo "Retail demo seeded into $$GOOGLE_CLOUD_PROJECT.$$dataset"

clean: ## Remove build output
	$(MVN) clean
