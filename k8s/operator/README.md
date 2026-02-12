# OpenTelemetry Operator (plain YAML, pinned versions)

Bu qovluq Helm istifadə etmədən, `kubectl apply`/Kustomize ilə operator-u quraşdırmaq üçündür.

## Versiyalar (pinned)

- **cert-manager**: `v1.19.3`
- **opentelemetry-operator**: `v0.144.0`

## Install

```bash
kubectl apply -k k8s/operator
```

Yoxlama:

```bash
kubectl get ns cert-manager opentelemetry-operator-system
kubectl -n cert-manager rollout status deploy/cert-manager --timeout=180s
kubectl -n cert-manager rollout status deploy/cert-manager-webhook --timeout=180s
kubectl -n cert-manager rollout status deploy/cert-manager-cainjector --timeout=180s

kubectl -n opentelemetry-operator-system get pods
```

## Qeyd

Bu repo-da `k8s/dev/otel-instrumentation.yaml` (Instrumentation CR) və `k8s/observability/otel-collector.yaml` ilə birlikdə işləyir.

