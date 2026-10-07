# Deploying the modernized system to OpenShift

This directory is the **OpenShift counterpart** to [`deploy/k8s/`](../deploy/k8s/).
The `deploy/k8s/` tree runs the eight-workload topology on vanilla Kubernetes
(minikube, Chapter 30); this tree runs the same topology on OpenShift, packaged as
a **Helm chart** (`helm/mea/`). The appendix *"Deploying to OpenShift"* walks
through it end to end; this README is the quick reference.

## What's here

```
openshift/
└── helm/mea/                 # one Helm chart, the whole system
    ├── Chart.yaml
    ├── values.yaml           # the 8 services described as data; image + infra knobs
    └── templates/
        ├── _helpers.tpl          # labels + namespace-portable cluster DNS
        ├── configmap-app.yaml    # shared env contract (port of deploy/k8s/base/app-config.yaml)
        ├── secret-app.yaml       # dev-only Postgres credential
        ├── serviceaccount-infra.yaml  # SA + SCC binding for postgres/kafka
        ├── app-deployment.yaml   # ONE template, ranged over values.services → 8 Deployments
        ├── app-service.yaml      # → 8 Services
        ├── route.yaml            # Routes for the services flagged route: true
        ├── postgres.yaml         # Postgres StatefulSet (+ initdb ConfigMap, Service)
        ├── kafka.yaml            # single-broker KRaft Kafka StatefulSet (+ Service)
        └── NOTES.txt
```

## What changes from `deploy/k8s/` → OpenShift

Three things, and they are the reason this is its own tree rather than a one-line
`oc apply`:

1. **Security Context Constraints (SCC).** The `deploy/k8s/base` Deployments pin
   `runAsUser: 185`. OpenShift's default `restricted-v2` SCC assigns each pod a
   UID from the namespace's allocated range and rejects a hardcoded one — so the
   app Deployments here **drop `runAsUser` entirely** and let the platform assign
   it (the UBI9 OpenJDK images run as any UID). Postgres and Kafka *do* need
   specific UIDs (70 / 1000), so they run under a dedicated ServiceAccount
   (`mea-infra`) bound to the `nonroot-v2` SCC.
2. **Routes, not port-forward.** External access is two `Route` objects (the
   strangler proxy and the GraphQL gateway) served by the cluster's router — no
   `kubectl port-forward`, no NodePort.
3. **The integrated registry.** Images are pushed to OpenShift's internal registry
   (`image-registry.openshift-image-registry.svc:5000/mea/...`) and referenced
   from there.

Everything else — the shared env ConfigMap, the per-service schema wiring, the
Kafka/Postgres `ch.30 live-apply` fixes, the graceful-shutdown windows, the health
probes — is carried over unchanged.

## Prerequisites

- An OpenShift cluster and `oc`/`helm`. For a laptop, **OpenShift Local (CRC)** —
  see the appendix for account + download + `crc setup`/`crc start`.
- The eight service images built and pushed to the cluster's registry (the
  appendix shows the `podman build`/`podman push` loop from the prebuilt
  `examples/*/target/quarkus-app` jars).

## Install

```sh
oc new-project mea                      # or: oc project mea
helm upgrade --install mea openshift/helm/mea --namespace mea
oc get pods -n mea -w                   # watch infra, then the 8 apps, go Ready
```

Reach the system through its Routes:

```sh
oc get route strangler-proxy -n mea -o jsonpath='{.spec.host}'   # REST edge
oc get route graphql-gateway  -n mea -o jsonpath='{.spec.host}'   # GraphQL API
```

## Uninstall

```sh
helm uninstall mea -n mea
# PVCs (postgres-data, kafka-data) are retained by design; delete them to wipe state:
oc delete pvc -l app.kubernetes.io/part-of=mea -n mea
```
