---
title: "Bibliography"
layout: plan
permalink: /bibliography/
description: "The reference books this modernization course's patterns, rigor, and vocabulary draw on: nothing here is ported from them."
---

This course's pattern coverage, its migration playbook, and the vocabulary it
uses for seams, sagas, and data ownership draw on a small set of reference
works. They are reference works, not sources: no text or code from any of them
is reproduced in these chapters or the runnable examples. Where a chapter
leans on one of them, it says so inline; this page collects them in one place
so you know where to go deeper.

| Title | Author(s) | Publisher | Year | Why it's here |
|---|---|---|---|---|
| [*Monolith to Microservices*](https://samnewman.io/books/monolith-to-microservices/) | Sam Newman | O'Reilly | 2019 | The incremental, strangler-first migration playbook this whole course follows |
| [*Building Microservices* (2nd ed.)](https://samnewman.io/books/building_microservices_2nd_edition/) | Sam Newman | O'Reilly | 2021 | Service boundaries, integration styles, and independent deployment |
| [*Domain-Driven Design*](https://www.domainlanguage.com/ddd/) | Eric Evans | Addison-Wesley | 2003 | Bounded contexts, subdomains, and the anti-corruption layer |
| [*Designing Data-Intensive Applications*](https://dataintensive.net/) | Martin Kleppmann | O'Reilly | 2017 | Derived data, materialized views, and replication behind CQRS and the read model |
| [*Release It!* (2nd ed.)](https://pragprog.com/titles/mnee2/release-it-second-edition/) | Michael Nygard | Pragmatic Bookshelf | 2018 | Stability patterns: circuit breaker, timeouts, and cascading-failure avoidance |
| [*Enterprise Integration Patterns*](https://www.enterpriseintegrationpatterns.com/) | Gregor Hohpe, Bobby Woolf | Addison-Wesley | 2003 | The messaging patterns the Camel strangler proxy and the sagas are built from |
| [*Microservices Patterns*](https://www.manning.com/books/microservices-patterns) | Chris Richardson | Manning | 2018 | Saga, CQRS, API composition, and the transactional outbox |

The **Strangler Fig** pattern at the center of this course is Martin Fowler's;
the canonical write-up is his [*StranglerFigApplication*](https://martinfowler.com/bliki/StranglerFigApplication.html)
article rather than a book.

These titles inform this course's coverage and rigor only: nothing in these
chapters or the runnable examples copies text or code from them.
