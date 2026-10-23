---
title: "Examples"
layout: plan
permalink: /examples/
description: "Every runnable example in this repo, from the starting monolith through each extracted service."
---

The book modernizes one system in front of you. These directories under
[`examples/`](https://github.com/{{ site.github_username }}/{{ site.github_repo }}/tree/main/examples)
are the runnable state of that system at each stage: the starting Spring Boot
monolith, the strangler proxy that fronts it, and each service extracted across
the seam. Work them in order, or jump to the one a chapter is building.

| Example | What it is |
|---|---|
| [00-monolith](https://github.com/{{ site.github_username }}/{{ site.github_repo }}/tree/main/examples/00-monolith) | The reference Spring Boot monolith: order, inventory, payment, shipping, notification, and review in one deployable |
| [01-strangler-proxy](https://github.com/{{ site.github_username }}/{{ site.github_repo }}/tree/main/examples/01-strangler-proxy) | The strangler-fig proxy that routes traffic between the monolith and extracted services |
| [02-review-service](https://github.com/{{ site.github_username }}/{{ site.github_repo }}/tree/main/examples/02-review-service) | First extraction: the review bounded context as a Quarkus service |
| [03-notification-service](https://github.com/{{ site.github_username }}/{{ site.github_repo }}/tree/main/examples/03-notification-service) | Event-driven notification service extracted across the seam |
| [04-inventory-service](https://github.com/{{ site.github_username }}/{{ site.github_repo }}/tree/main/examples/04-inventory-service) | Inventory service, with owned data split from the monolith |
| [05-payment-service](https://github.com/{{ site.github_username }}/{{ site.github_repo }}/tree/main/examples/05-payment-service) | Payment service extraction and its transactional boundary |
| [06-shipping-service](https://github.com/{{ site.github_username }}/{{ site.github_repo }}/tree/main/examples/06-shipping-service) | Shipping service across the seam |
| [07-order-service](https://github.com/{{ site.github_username }}/{{ site.github_repo }}/tree/main/examples/07-order-service) | Order service: the last extraction that retires the monolith |
| [08-graphql-gateway](https://github.com/{{ site.github_username }}/{{ site.github_repo }}/tree/main/examples/08-graphql-gateway) | A GraphQL gateway composing the extracted services (CQRS read side) |
| [09-schema-registry-demo](https://github.com/{{ site.github_username }}/{{ site.github_repo }}/tree/main/examples/09-schema-registry-demo) | Contract and schema evolution with a schema registry |
| [10-supply-chain](https://github.com/{{ site.github_username }}/{{ site.github_repo }}/tree/main/examples/10-supply-chain) | Build and deployment supply-chain integrity for the modernized system |

## Running an example

Each directory is self-contained with its own README and run steps. The
services build with Maven and run against the shared infrastructure stack
(PostgreSQL, Kafka, the observability stack). See
[Prerequisites]({{ '/docs/01-prerequisites/' | relative_url }}) for the full
toolchain and
[The Project Ledger]({{ '/docs/02-project-ledger/' | relative_url }}) for how
the stages fit together.
