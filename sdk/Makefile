.PHONY: all go python node rust java clean

all: go python node rust java

go:
	cd go && go build ./... && go vet ./... && go test ./...

python:
	cd python && python3 -m venv .venv --clear >/dev/null && \
	.venv/bin/pip install -q -e ".[dev]" && \
	.venv/bin/pytest -q

node:
	cd node && pnpm install --silent && pnpm build && pnpm test

rust:
	cd rust && cargo build --all-features && cargo test --all-features

java:
	cd java && mvn -q -B test

clean:
	rm -rf python/.venv python/dist python/*.egg-info \
	       node/node_modules node/dist \
	       rust/target java/target
