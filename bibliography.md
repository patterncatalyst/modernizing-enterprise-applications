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

<table class="bib bib--books">
  <colgroup><col style="width:62%"><col style="width:24%"><col style="width:14%"></colgroup>
  <thead><tr><th>Title</th><th>Author(s)</th><th>Year</th></tr></thead>
  <tbody>
    <tr><td><a href="https://samnewman.io/books/monolith-to-microservices/" rel="noopener"><em>Monolith to Microservices</em></a><br><small>O'Reilly. The incremental, strangler-first migration playbook this whole course follows</small></td><td>Sam Newman</td><td>2019</td></tr>
    <tr><td><a href="https://samnewman.io/books/building_microservices_2nd_edition/" rel="noopener"><em>Building Microservices</em> (2nd ed.)</a><br><small>O'Reilly. Service boundaries, integration styles, and independent deployment</small></td><td>Sam Newman</td><td>2021</td></tr>
    <tr><td><a href="https://www.domainlanguage.com/ddd/" rel="noopener"><em>Domain-Driven Design</em></a><br><small>Addison-Wesley. Bounded contexts, subdomains, and the anti-corruption layer</small></td><td>Eric Evans</td><td>2003</td></tr>
    <tr><td><a href="https://dataintensive.net/" rel="noopener"><em>Designing Data-Intensive Applications</em></a><br><small>O'Reilly. Derived data, materialized views, and replication behind CQRS and the read model</small></td><td>Martin Kleppmann</td><td>2017</td></tr>
    <tr><td><a href="https://pragprog.com/titles/mnee2/release-it-second-edition/" rel="noopener"><em>Release It!</em> (2nd ed.)</a><br><small>Pragmatic Bookshelf. Stability patterns: circuit breaker, timeouts, and cascading-failure avoidance</small></td><td>Michael Nygard</td><td>2018</td></tr>
    <tr><td><a href="https://www.enterpriseintegrationpatterns.com/" rel="noopener"><em>Enterprise Integration Patterns</em></a><br><small>Addison-Wesley. The messaging patterns the Camel strangler proxy and the sagas are built from</small></td><td>Gregor Hohpe, Bobby Woolf</td><td>2003</td></tr>
    <tr><td><a href="https://www.manning.com/books/microservices-patterns" rel="noopener"><em>Microservices Patterns</em></a><br><small>Manning. Saga, CQRS, API composition, and the transactional outbox</small></td><td>Chris Richardson</td><td>2018</td></tr>
  </tbody>
</table>

The **Strangler Fig** pattern at the center of this course is Martin Fowler's;
the canonical write-up is his [*StranglerFigApplication*](https://martinfowler.com/bliki/StranglerFigApplication.html)
article rather than a book.

These titles inform this course's coverage and rigor only: nothing in these
chapters or the runnable examples copies text or code from them.
