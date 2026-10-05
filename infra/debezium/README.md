# Debezium CDC — Kafka Connect + Postgres connector

r05/ch.19 step S3 (`_plans/iterations/inventory-plan.md`), DRQ-040. This is
the CDC infrastructure the inventory extraction (ch.19) uses to
initial-snapshot backfill, and then keep current, the new inventory
service's owned database from the monolith's `public.inventory_items` table
during the transition window.

**Tool decision (DRQ-040):** a standalone **Debezium Postgres connector
running on Kafka Connect** — not Debezium Embedded. A visible, inspectable
connector container is the clearer teaching artifact for log-tailing CDC,
and the stack already runs Kafka.

## Topology

```
mea-postgres (wal_level=logical)
      │  pgoutput, replication slot "mea_inventory_slot"
      ▼
mea-connect (Kafka Connect, debezium/connect image)
      │  connector "mea-inventory-connector"
      ▼
mea-kafka topic "mea.public.inventory_items"
```

## Postgres: logical replication

`compose.yaml`'s `postgres` service now starts with:

```
command: ["postgres", "-c", "wal_level=logical", "-c", "max_replication_slots=4", "-c", "max_wal_senders=4"]
```

This requires a Postgres **restart** to take effect (handled automatically
by `podman compose up -d`); the `postgres-data` named volume persists, so no
monolith data is lost. The bootstrap superuser (`POSTGRES_USER` from `.env`)
already has `REPLICATION` privilege, so no separate grant/init script is
needed for a single-connector dev stack.

**Anything else talking to this Postgres** (the monolith's Flyway
migrations, future services) is unaffected by `wal_level=logical` — it only
changes what's recorded in the WAL, not SQL semantics. The one shared-infra
effect: WAL volume grows slightly because logical decoding keeps more
information than the default `replica` level.

## Kafka Connect

The `connect` service (`compose.yaml`) runs `debezium/connect` (tag pinned
in `.env`/`.env.example` as `CONNECT_IMAGE_TAG`) in distributed mode, using
Kafka Connect's own internal topics (`mea-connect-configs`,
`mea-connect-offsets`, `mea-connect-status`, replication factor 1 — matches
this single-broker stack).

**REST API port:** the container always binds `:8083` internally, but the
**host-side** port mapping is `${CONNECT_HOST_PORT:-8086}` (default host
port **8086**, not 8083) — `examples/03-notification-service`'s Quarkus
HTTP listener binds `localhost:8083` when it's running, and the two would
otherwise clash. Inside the compose network, other containers still reach
Connect at `http://connect:8083`.

Health check: `GET http://localhost:8083/connectors` inside the container
(curl is present in the `debezium/connect` image).

## The connector config: `inventory-connector.json`

`infra/debezium/inventory-connector.json` is a **template** — `${POSTGRES_USER}`,
`${POSTGRES_PASSWORD}`, `${POSTGRES_DB}` are placeholders substituted from
`.env` at registration time (`scripts/register-debezium.sh`), so no
credential is hardcoded in a tracked file (secrets hygiene, OWASP/CIS;
`.env` itself is gitignored).

Highlights:

| Field | Value | Why |
|---|---|---|
| `table.include.list` | `public.inventory_items` | only the table this extraction needs |
| `topic.prefix` | `mea` | resulting topic: `mea.public.inventory_items` |
| `plugin.name` | `pgoutput` | built into Postgres 10+, no extra decoder plugin to install |
| `slot.name` | `mea_inventory_slot` | the replication slot Debezium owns |
| `publication.name` | `mea_inventory_publication` | auto-created (`publication.autocreate.mode=filtered`), scoped to the include list |
| `snapshot.mode` | `initial` | snapshot existing rows once, then stream |

**`REPLICA IDENTITY FULL`:** Postgres's default `REPLICA IDENTITY` (`DEFAULT`)
only includes primary-key columns in the WAL's "old row" image, so Debezium's
`before` field on UPDATE/DELETE events would otherwise be `null` except for
`id`. `scripts/register-debezium.sh` runs
`ALTER TABLE public.inventory_items REPLICA IDENTITY FULL;` against
`mea-postgres` before registering the connector, so every UPDATE/DELETE
event carries a complete `before` row alongside `after`. This is a
runtime/session-level Postgres setting (not a schema/DDL change tracked by
the monolith's Flyway migrations) and is idempotent to re-run.

## Registering the connector

```bash
scripts/register-debezium.sh            # idempotent create-or-update, waits for RUNNING
scripts/register-debezium.sh --status   # just print GET /connectors/<name>/status
```

The script `PUT`s `infra/debezium/inventory-connector.json`'s `config`
object to `/connectors/mea-inventory-connector/config` — Kafka Connect's
`PUT .../config` endpoint creates the connector if it doesn't exist yet, or
updates it in place otherwise, so re-running is always safe.

## Verifying CDC end-to-end

```bash
scripts/verify-cdc.sh [sku]
```

Updates a row in `public.inventory_items` via `mea-postgres`, consumes the
resulting event off `mea.public.inventory_items` via
`kafka-console-consumer`, and asserts the sku appears in the captured
output.

## Replication-slot lifecycle (H2 — slot-leak mitigation)

A replication slot that nobody is draining holds WAL segments on disk
indefinitely — if the connector is deleted or dies without the slot being
dropped, WAL will grow unbounded.

- **Normal stop/start** (`podman compose stop`/`start`, `scripts/stack-down.sh`
  without `-v`): the slot persists in the `postgres-data` volume and Debezium
  resumes from its last confirmed LSN. This is expected and fine.
- **Deleting the connector** (`DELETE /connectors/mea-inventory-connector`):
  does **not** automatically drop the replication slot or publication. Drop
  them explicitly afterward:
  ```sql
  SELECT pg_drop_replication_slot('mea_inventory_slot');
  DROP PUBLICATION mea_inventory_publication;
  ```
- **`podman compose down -v`** (wipes `postgres-data`): removes the slot
  along with all Postgres state. Do not run this against a shared/working
  stack — see the standing "no destructive `down -v`" constraint for this task.
- At decommission (ch.19 S11), drop the slot/publication once the inventory
  service is the sole writer and CDC is no longer needed for backfill.

## Known trade-off vs ch.17's polling outbox (DRQ-034)

CDC reads the WAL directly — no application poll, no table scan, lower
latency than ch.17's notification outbox poller — at the cost of
`wal_level=logical` on the monolith's Postgres, a replication slot +
publication to manage, and a Kafka Connect/Debezium process to operate.
Debezium Embedded (library-in-service) would avoid the extra container but
is a less visible teaching artifact for log-tailing CDC — deliberately not
used here (DRQ-040).
