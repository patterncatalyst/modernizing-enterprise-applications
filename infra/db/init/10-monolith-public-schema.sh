#!/bin/sh
# infra/db/init/10-monolith-public-schema.sh
#
# Bootstraps the shared `public` schema from the frozen monolith's committed
# Flyway migrations (examples/00-monolith/src/main/resources/db/migration,
# mounted read-only at /monolith-migrations by compose.yaml). Runs once, on
# the first start of an EMPTY postgres-data volume, after 00-init.sql.
#
# Why (DRQ-077, 2026-10-09 live retest): the monolith is decommissioned and
# never started, yet the running topology still depends on the tables its V1
# migration created in `public`: review-service reads/writes `public.reviews`
# (schema-management strategy=none, no Flyway of its own), the Debezium
# connector captures `public.inventory_items`, and the demos' burn-in reads
# `public.orders`. Nothing else creates them, so on a fresh volume (mandatory
# for the Postgres 16 -> 18 move) review-service returned 500s and
# scripts/register-debezium.sh failed on a missing relation. The old podman
# volume had simply kept the tables from back when the monolith still ran.
#
# Same approach as .github/workflows/code-ci.yml ("Apply the monolith's
# schema + seed data"): apply the committed SQL with psql, no monolith JVM.
# All four versions are applied in order, as the monolith's own Flyway did.
set -eu

dir=/monolith-migrations
if [ ! -d "$dir" ]; then
  echo "10-monolith-public-schema: $dir not mounted, skipping" >&2
  exit 0
fi

for f in $(ls "$dir"/V*__*.sql | sort -V); do
  echo "10-monolith-public-schema: applying $(basename "$f")"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -q -f "$f"
done
