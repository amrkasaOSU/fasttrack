# FastTrack - common tasks.
#
# On macOS with Colima instead of Docker Desktop, Testcontainers needs to be
# told where the Docker socket is; `docker-env` works that out automatically
# and is a no-op everywhere else (Linux, CI, Docker Desktop).

SHELL := /bin/bash
BACKEND := backend
FRONTEND := frontend
JAVA_HOME ?= $(shell /usr/libexec/java_home -v 21 2>/dev/null || echo /opt/homebrew/opt/openjdk@21)

COLIMA_SOCK := $(HOME)/.colima/default/docker.sock
ifneq ($(wildcard $(COLIMA_SOCK)),)
  DOCKER_ENV := DOCKER_HOST=unix://$(COLIMA_SOCK) TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
else
  DOCKER_ENV :=
endif

MVN := JAVA_HOME=$(JAVA_HOME) $(DOCKER_ENV) mvn -B

.PHONY: help up down logs reset test test-backend test-frontend verify coverage lint typecheck build dev-backend dev-frontend

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

up: ## Start the whole stack (postgres, redis, backend, frontend)
	docker compose up --build -d
	@echo "Frontend: http://localhost:3000   API: http://localhost:8080/swagger-ui.html"

down: ## Stop the stack
	docker compose down

reset: ## Stop the stack and delete all local data volumes
	docker compose down -v

logs: ## Tail all service logs
	docker compose logs -f

dev-backend: ## Run the backend from source (needs postgres + redis running)
	cd $(BACKEND) && JAVA_HOME=$(JAVA_HOME) mvn spring-boot:run

dev-frontend: ## Run the Next.js dev server
	cd $(FRONTEND) && npm run dev

test: test-backend test-frontend ## Run every test

test-backend: ## Backend unit + integration tests (Testcontainers)
	cd $(BACKEND) && $(MVN) test

verify: ## Backend tests plus the 80% JaCoCo coverage gate
	cd $(BACKEND) && $(MVN) verify

coverage: verify ## Print the measured coverage summary
	@python3 scripts/coverage_summary.py

test-frontend: typecheck lint ## Frontend typecheck and lint

typecheck: ## TypeScript, no emit
	cd $(FRONTEND) && npm run typecheck

lint: ## ESLint
	cd $(FRONTEND) && npm run lint

build: ## Production build of both sides
	cd $(BACKEND) && JAVA_HOME=$(JAVA_HOME) mvn -B package -DskipTests
	cd $(FRONTEND) && npm run build
