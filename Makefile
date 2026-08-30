# Thin wrappers over the commands you would otherwise have to remember.
.DEFAULT_GOAL := help
SHELL := /bin/bash

COMPOSE := docker compose
GRADLE  := ./gradlew

.PHONY: help
help: ## Show this help
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}'

# ---------------------------------------------------------------------------------------------
# Local environment
# ---------------------------------------------------------------------------------------------
.PHONY: up
up: ## Start every dependency and the app
	$(COMPOSE) up -d --build

.PHONY: deps
deps: ## Start only the dependencies (for ./gradlew bootRun)
	$(COMPOSE) up -d postgres redis kafka opensearch minio

.PHONY: observability
observability: ## Start Prometheus and Grafana as well (http://localhost:3000, admin/admin)
	$(COMPOSE) --profile observability up -d

.PHONY: down
down: ## Stop everything, keep the data
	$(COMPOSE) down

.PHONY: clean
clean: ## Stop everything and delete the volumes
	$(COMPOSE) down -v
	$(GRADLE) clean

.PHONY: logs
logs: ## Tail the application log
	$(COMPOSE) logs -f app

.PHONY: ps
ps: ## Show container status
	$(COMPOSE) ps

# ---------------------------------------------------------------------------------------------
# Build and test
# ---------------------------------------------------------------------------------------------
.PHONY: build
build: ## Compile everything and run the tests
	$(GRADLE) build

.PHONY: test
test: ## Run the tests (integration tests skip when Docker is unavailable)
	$(GRADLE) test

.PHONY: verify
verify: deps ## Run the full suite including the Testcontainers integration tests
	REFERRALHUB_FORCE_DOCKER=true $(GRADLE) test

.PHONY: coverage
coverage: ## Produce the JaCoCo reports
	$(GRADLE) test jacocoTestReport
	@echo "Reports: modules/*/build/reports/jacoco/test/html/index.html"

.PHONY: arch
arch: ## Run the architecture rules only
	$(GRADLE) :app:test --tests '*ArchitectureTest*'

.PHONY: properties
properties: ## Run the property-based tests only
	$(GRADLE) test --tests '*Properties*'

.PHONY: mutation
mutation: ## Mutation testing on the decision-logic modules (slow: several minutes)
	$(GRADLE) :dedup:pitest :trust:pitest :referral:pitest :search:pitest
	@echo "Reports: modules/*/build/reports/pitest/index.html"

.PHONY: e2e
e2e: deps ## Run the end-to-end pipeline test against real containers
	REFERRALHUB_FORCE_DOCKER=true $(GRADLE) :app:test --tests '*EndToEndPipelineIT*'

.PHONY: seed-corpus
seed-corpus: deps ## Seed a realistic synthetic corpus (200 companies x 25 postings)
	$(GRADLE) :app:bootRun --args=' \
		--referralhub.loadgen.seed-corpus=true \
		--referralhub.ingestion.crawl-enabled=false'

.PHONY: loadtest
loadtest: deps ## Seed a corpus, then drive concurrent searches and report latency
	$(GRADLE) :app:bootRun --args=' \
		--referralhub.loadgen.seed-corpus=true \
		--referralhub.loadgen.drive-searches=true \
		--referralhub.ingestion.crawl-enabled=false'

.PHONY: bench
bench: ## Run the JMH benchmark suite (slow: several minutes)
	$(GRADLE) :benchmarks:jmh
	@echo "Results: modules/benchmarks/build/reports/jmh/results.json"

.PHONY: run
run: deps ## Run the app on the host against containerised dependencies
	$(GRADLE) :app:bootRun

# ---------------------------------------------------------------------------------------------
# Operations
# ---------------------------------------------------------------------------------------------
.PHONY: secrets
secrets: ## Print a fresh pair of secrets for .env
	@echo "REFERRALHUB_STORAGE_ENCRYPTIONKEY=$$(openssl rand -base64 32)"
	@echo "REFERRALHUB_STORAGE_URLSIGNINGSECRET=$$(openssl rand -hex 32)"
	@echo "REFERRALHUB_AUTH_JWTSECRET=$$(openssl rand -base64 48)"

.PHONY: seed
seed: ## Register a few real public ATS boards with the crawler
	@./scripts/seed-boards.sh

.PHONY: smoke
smoke: ## Check that the running stack answers
	@./scripts/smoke-test.sh

.PHONY: image
image: ## Build the container image
	docker build -f docker/Dockerfile -t referralhub:local .
