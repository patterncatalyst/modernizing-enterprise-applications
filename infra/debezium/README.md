# Debezium CDC — Kafka Connect + Postgres connector

> **RETIRED post-cutover (r05/ch.19 step S11, `_plans/iterations/inventory-plan.md`).**
> This connector was deliberately **transition-only**. Once the monolith's
> inventory write/reserve path was decommissioned (S11 — `RemoteInventoryClient`'s
> gRPC `Reserve`/`Release` against `examples/04-inventory-service`'s OWN
> database is the only writer left; the monolith no longer touches
> `inventory_items` at all), the connector had nothing left to replicate.
> `scripts/retire-debezium.sh` deletes `mea-inventory-connector` and drops
> its replication slot (`mea_inventory_slot`) and publication
> (`mea_inventory_publication`) so WAL isn't retained indefinitely on
> `mea-postgres` — see "Retiring the connector" below. The rest of this
> document is kept as the historical record of what the connector did
> during the transition window (S3–S10) and remains accurate for anyone
> re-registering it (e.g. to replay the teaching demo from a fresh stack).

r05/ch.19 step S3 (`_plans/iterations/inventory-plan.md`), DRQ-040. This was
the CDC infrastructure the inventory extraction (ch.19) used to
initial-snapshot backfill, and then keep current, the new inventory
service's owned database from the monolith's `public.inventory_items` table
during the transition window (S3 through S10's cutover).

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

## Retiring the connector (post-cutover, r05/ch.19 S11)

```bash
scripts/retire-debezium.sh            # idempotent: delete connector, drop slot + publication
scripts/retire-debezium.sh --status   # report connector/slot/publication state, no changes
```

`scripts/retire-debezium.sh`:

1. `DELETE /connectors/mea-inventory-connector` against Kafka Connect
   (`:8086`). A `404` (already deleted) is treated as success — the script
   is idempotent.
2. Drops the replication slot `mea_inventory_slot` via
   `pg_drop_replication_slot(...)` against `mea-postgres`, guarded by an
   existence check (`pg_replication_slots`) so a re-run is a no-op.
3. Drops the publication `mea_inventory_publication` via
   `DROP PUBLICATION IF EXISTS`.

This does **not** touch the podman stack itself (`mea-postgres`,
`mea-connect`, `mea-kafka` all keep running) and does **not** revert
`wal_level=logical` on `mea-postgres` (harmless to leave set — see "Postgres:
logical replication" above; it only affects what's recorded in the WAL, not
SQL semantics, and other future connectors could reuse it).

**The inventory service's own CDC consumer becomes idle, not broken.**
`examples/04-inventory-service` historically consumed `mea.public.inventory_items`
during the S3–S10 transition window to seed/keep its owned database current
while the monolith was still the writer of record. Post-S11, the monolith
never writes `inventory_items` again, so that topic receives no new events
and the consumer (if still wired up) simply has nothing to consume — it is
not a correctness problem (the inventory service is now the sole writer of
its own data via gRPC `Reserve`/`Release`, not a CDC reader of someone
else's writes). Removing that now-idle consumer code from
`examples/04-inventory-service` is a cleanup, not a correctness fix, and is
explicitly **out of scope for S11** (scope discipline — a separate, later
step if ever done).

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
- **At decommission (ch.19 S11): DONE.** `scripts/retire-debezium.sh`
  deletes the connector and drops the slot/publication now that the
  inventory service is the sole writer and CDC is no longer needed for
  backfill — see "Retiring the connector" above.

## Known trade-off vs ch.17's polling outbox (DRQ-034)

CDC reads the WAL directly — no application poll, no table scan, lower
latency than ch.17's notification outbox poller — at the cost of
`wal_level=logical` on the monolith's Postgres, a replication slot +
publication to manage, and a Kafka Connect/Debezium process to operate.
Debezium Embedded (library-in-service) would avoid the extra container but
is a less visible teaching artifact for log-tailing CDC — deliberately not
used here (DRQ-040).
