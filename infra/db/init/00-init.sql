-- infra/db/init/00-init.sql
--
-- One-time Postgres bootstrap for the r02 walking-skeleton stack. Runs via
-- the official postgres image's /docker-entrypoint-initdb.d mechanism on
-- first container start only (i.e. when the postgres-data volume is empty).
--
-- Scope discipline: this file intentionally does NOT create application
-- tables or seed data. Schema ownership (Flyway migrations, the six
-- bounded-context tables, deterministic seed data) belongs to S4
-- (examples/00-monolith/) per _plans/iterations/r02-plan.md. This script
-- only ensures the extensions the monolith will want are present so Flyway's
-- first migration doesn't need superuser privileges to add them later.
--
-- DRQ-077: with the monolith decommissioned, 10-monolith-public-schema.sh
-- (next in this directory) applies its committed migrations to `public`.

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
