# Waypoint developer tasks. Run `make help` for the target list.
SHELL := /bin/bash
.DEFAULT_GOAL := help

# Prefer the checked-in Maven wrapper; fall back to a system `mvn`.
MVN ?= $(shell if [ -x ./mvnw ]; then echo ./mvnw; elif command -v mvn >/dev/null 2>&1; then echo mvn; else echo ./mvnw; fi)

.PHONY: help build test verify gate p4 verify-docs results check clean

help:
	@echo "Waypoint make targets:"
	@echo "  make build        Compile and package target/waypoint-0.1.0.jar (Maven; javac fallback)"
	@echo "  make test         Run the JUnit 5 test suite (Maven)"
	@echo "  make verify       Maven verify: compile + tests + jar"
	@echo "  make gate         Run the calibration gate (./gate.sh)"
	@echo "  make p4           Run the P4 S1/S2 matrix (./p4/gate.sh)"
	@echo "  make verify-docs  Check README marker blocks against build/gate-result.json"
	@echo "  make results      Refresh results/ from the latest gate run"
	@echo "  make check        gate + p4 + verify-docs (release acceptance)"
	@echo "  make clean        Remove target/, build/, p3/classes, p4/classes"

build:
	@if [ -x ./mvnw ] || command -v mvn >/dev/null 2>&1; then \
		echo ">> $(MVN) -B -q -DskipTests package"; \
		$(MVN) -B -q -DskipTests package; \
	else \
		echo ">> Maven unavailable: javac zero-dependency fallback"; \
		rm -rf build/main-classes && mkdir -p build/main-classes; \
		javac --release 17 -encoding UTF-8 -d build/main-classes $$(find src/main/java -name '*.java'); \
		echo "   compiled classes in build/main-classes (no jar produced without Maven)"; \
	fi

test:
	$(MVN) -B -q test

verify:
	$(MVN) -B -q verify

gate:
	./gate.sh

p4:
	./p4/gate.sh

verify-docs:
	./scripts/verify-docs.sh

results:
	./scripts/update-results.sh

check: gate p4
	./scripts/verify-docs.sh --skip-gate
	@echo ">> make check: PASS"

clean:
	rm -rf target build p3/classes p4/classes
