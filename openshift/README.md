# Deploying the modernized system to OpenShift

This directory is the **OpenShift counterpart** to [`deploy/k8s/`](../deploy/k8s/).
The `deploy/k8s/` tree runs the eight-workload topology on vanilla Kubernetes
(minikube, Chapter 30); this tree runs the same topology on OpenShift, packaged as
a **Helm chart** (`helm/mea/`). The appendix *"Deploying to OpenShift"* walks
through it end to end; this README is the quick reference.

## What's here

```
openshift/
├── lib.sh                    # crc-admin context guard, service list
├── mirror-infra-images.sh    # skopeo: docker.io postgres/kafka -> project ImageStreams
├── build-images.sh           # mvn package + in-cluster Docker-strategy binary builds
├── deploy.sh                 # helm upgrade --install + rollout status
├── teardown.sh               # release, project, registry route, then crc stop
├── evidence/verification.txt # the 2026-10-06 live capture
├── gitops/application.yaml   # illustrative Argo CD Application
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
   it (the UBI OpenJDK images run as any UID). Postgres and Kafka *do* need
   specific UIDs (70 / 1000), so they run under a dedicated ServiceAccount
   (`mea-infra`) bound to the `nonroot-v2` SCC.
2. **Routes, not port-forward.** External access is two `Route` objects (the
   strangler proxy and the GraphQL gateway) served by the cluster's router — no
   `kubectl port-forward`, no NodePort.
3. **The integrated registry, filled from inside the cluster.** The eight
   service images are built *in* OpenShift (Docker-strategy binary builds fed
   with each service's `target/quarkus-app` and `Dockerfile.jvm`), so the host
   needs no container engine; Postgres and Kafka are copied in with `skopeo`.
   Everything is referenced as
   `image-registry.openshift-image-registry.svc:5000/mea/...`.

Everything else — the shared env ConfigMap, the per-service schema wiring, the
Kafka/Postgres `ch.30 live-apply` fixes, the graceful-shutdown windows, the health
probes — is carried over unchanged.

## Prerequisites

- A Fedora or RHEL host with **OpenShift Local (CRC)**, `oc` (`eval "$(crc
  oc-env)"`), `helm`, `skopeo`, JDK 25 and Maven — see the appendix for account
  + download + `crc setup`/`crc start`. No container engine is needed on this
  path. Stop any running minikube profile first: one local cluster at a time.
- The scripts use the `crc-admin` kubeconfig context `crc start` writes
  (override with `OC_CONTEXT`) and refuse any API server other than
  `api.crc.testing`.

## Install

```sh
oc --context crc-admin new-project mea
openshift/mirror-infra-images.sh        # postgres:18.6-alpine, kafka:4.3.1 -> ImageStreams
openshift/build-images.sh               # 8 in-cluster builds -> <svc>:1.0
openshift/deploy.sh                     # helm upgrade --install + rollout status
```

Reach the system through its Routes, verifying TLS with the cluster's ingress CA:

```sh
oc --context crc-admin get configmap default-ingress-cert -n openshift-config-managed \
  -o jsonpath='{.data.ca-bundle\.crt}' > /tmp/ingress-ca.crt
curl -s --cacert /tmp/ingress-ca.crt https://strangler-proxy-mea.apps-crc.testing/api/inventory
curl -s --cacert /tmp/ingress-ca.crt https://graphql-gateway-mea.apps-crc.testing/q/health/ready
```

## Uninstall

```sh
openshift/teardown.sh                   # release, project (PVCs, builds), registry route, crc stop
```
