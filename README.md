# Spring Boot CPU + Memory Workload App (profiling üçün)

Bu layihə **profiling üçün məqsədli workload** yaradır:

- **CPU**: prime search, matrix multiplication, SHA-256 hashing, pointer-chasing
- **Memory**:
  - heap-də **retained** bloklar (uzunömürlü obyektlər)
  - yüksək sürətli **allocation churn** (GC üçün)

Layihə **heç bir profiler / agent əlavə etmir**. Siz istədiyiniz profileri ayrıca qoşa bilərsiniz.

## Tələblər

- JDK 17+ (JRE yox, tam JDK məsləhətdir)

## Build & Run (Spring Boot / Maven)

Workspace root-da:

```bash
mvn spring-boot:run
```

Default port: `8080`

## API (controller)

- `GET /api/health` → `"ok"`
- `GET /api/workload/status`
- `POST /api/workload/start` (JSON body optional)
- `POST /api/workload/stop`

## Nümunə `curl` call-lar

Status:

```bash
curl -s "http://localhost:8080/api/workload/status"
```

Start (60s run, 10s warmup, 8 threads, 512MB retained):

```bash
curl -s -X POST "http://localhost:8080/api/workload/start" \
  -H "Content-Type: application/json" \
  -d '{"warmupSec":10,"durationSec":60,"threads":8,"memRetainMB":512}'
```

Stop:

```bash
curl -s -X POST "http://localhost:8080/api/workload/stop"
```

## Parametrlər

`/api/workload/start` body-si `StartRequest`-dir (hamısı optional, default var):

- `durationSec`, `warmupSec`, `threads`, `reportEverySec`
- `memRetainMB`, `memBlockKB`
- `matrixSize`, `hashPayloadKB`, `churnMBps`, `pointerChaseMB`
- `printThreadCpu`, `seed`

## Faydalı JVM flag-ləri (profilersiz)

10 saniyə warmup + 60 saniyə run, 8 thread, 512MB retained:

```bash
java -cp out Main --warmupSec 10 --durationSec 60 --threads 8 --memRetainMB 512
```

Allocation churn-i artır (MB/s):

```bash
java -cp out Main --durationSec 60 --churnMBps 1024
```

Matrix workload-u “ağırlaşdır”:

```bash
java -cp out Main --durationSec 60 --matrixSize 256
```

## Faydalı JVM flag-ləri (profilersiz)

Profiling üçün tez-tez istifadə olunan (amma profiler olmayan) nümunələr:

```bash
mvn -q -DskipTests package
java -Xms1g -Xmx1g -jar target/workload-app-0.1.0.jar
```

GC davranışını daha “sərt” görmək üçün retained/churn parametrlərini artırın.

Qeyd: workload log-ları default olaraq stdout-a yazılır; `reportEverySec: 0` göndərsəniz periodik report söndürülür.

## Docker Desktop Kubernetes (dev) + OpenTelemetry Operator + profiling (Pyroscope) + async-profiler

Bu repo-da `k8s/dev/` altında Docker Desktop Kubernetes üçün dev manifestlər var:

- `k8s/dev/namespace.yaml`
- `k8s/dev/otel-instrumentation.yaml` (OpenTelemetry Operator üçün `Instrumentation` CR)
- `k8s/dev/deployment.yaml`
  - OpenTelemetry Operator injection: `instrumentation.opentelemetry.io/inject-java: "java-app-dev/java-instrumentation"`
  - Profiling toggle: `profiling.enabled: "true"` (Pyroscope javaagent)
- `k8s/dev/service.yaml`

### OpenTelemetry Operator install (cluster)

Qeyd: OpenTelemetry Operator adətən **cert-manager** tələb edir. Cluster-də cert-manager yoxdursa, əvvəlcə onu quraşdırın.

#### Variant A: Plain YAML (pinned)

Bu repo-da Helm-siz variant da var:

```bash
kubectl apply -k k8s/operator
```

Pində olan versiyalar:

- cert-manager `v1.19.3`
- opentelemetry-operator `v0.144.0`

#### Variant B: Helm

```bash
helm repo add open-telemetry https://open-telemetry.github.io/opentelemetry-helm-charts
helm repo update
helm upgrade --install opentelemetry-operator open-telemetry/opentelemetry-operator \
  --namespace opentelemetry-operator-system --create-namespace
```

### Build image (lokal)

```bash
docker build -t workload-app:dev .
```

### Deploy

```bash
kubectl apply -f k8s/observability/namespace.yaml
kubectl apply -f k8s/observability/otel-collector.yaml

kubectl apply -f k8s/dev/namespace.yaml
kubectl apply -f k8s/dev/otel-instrumentation.yaml
kubectl apply -f k8s/dev/deployment.yaml
kubectl apply -f k8s/dev/service.yaml
```

Pod-u gözlə:

```bash
kubectl -n java-app-dev rollout status deploy/workload-app
kubectl -n java-app-dev get pods -o wide
```

Port-forward:

```bash
kubectl -n java-app-dev port-forward svc/workload-app 8080:8080
```

### Workload start (CPU + memory)

```bash
curl -s -X POST "http://localhost:8080/api/workload/start" \
  -H "Content-Type: application/json" \
  -d '{"warmupSec":0,"durationSec":300,"threads":16,"reportEverySec":5,"memRetainMB":256,"churnMBps":512}'
```

### async-profiler (asprof) ilə CPU profile çıxart (HTML)

Manifest `async-profiler v4.3`-ü initContainer ilə pod-a yükləyir və app konteynerində bu path-da olur:

- `/opt/async-profiler/bin/asprof`

Pod adını tap:

```bash
POD="$(kubectl -n java-app-dev get pod -l app=workload-app -o jsonpath='{.items[0].metadata.name}')"
echo "$POD"
```

CPU profiling (PID adətən `1` olur):

```bash
kubectl -n java-app-dev exec "$POD" -c app -- sh -lc '
  PID="$(pgrep -f \"java -jar /app/app.jar\" | head -n 1)"
  /opt/async-profiler/bin/asprof -e itimer -d 30 -o flamegraph -f /tmp/profile.html "$PID"
'

kubectl -n java-app-dev cp -c app "$POD:/tmp/profile.html" ./profile.html
```

Sonra lokal aç:

```bash
open ./profile.html
```

### inject-java (OpenTelemetry) söndürmək

`k8s/dev/deployment.yaml` içində pod template annotasiyasını silin:

- `instrumentation.opentelemetry.io/inject-java`

Profiling-i söndürmək üçün isə:

- `profiling.enabled: "false"`

## Grafana + Alloy + Pyroscope (continuous profiling)

Bu setup-da **Java app** `pyroscope-java` agent (async-profiler istifadə edir) ilə profile data toplayır və **Alloy**-a push edir.
Alloy isə profilləri **Pyroscope** server-ə forward edir. Vizualizasiya üçün Grafana istifadə olunur.

### Deploy observability stack

```bash
kubectl apply -f k8s/observability/namespace.yaml
kubectl apply -f k8s/observability/otel-collector.yaml
kubectl apply -f k8s/observability/pyroscope.yaml
kubectl apply -f k8s/observability/alloy.yaml
kubectl apply -f k8s/observability/grafana.yaml
```

Gözlə:

```bash
kubectl -n observability rollout status deploy/pyroscope
kubectl -n observability rollout status deploy/alloy
kubectl -n observability rollout status deploy/grafana
```

### App-i pyroscope ilə deploy et

`k8s/dev/deployment.yaml` artıq `pyroscope.jar`-ı initContainer ilə endirir və bu env-lə profilləri Alloy-a göndərir:

- `PYROSCOPE_SERVER_ADDRESS=http://alloy.observability.svc.cluster.local:9999`

Apply:

```bash
kubectl apply -f k8s/dev/deployment.yaml
kubectl -n java-app-dev rollout status deploy/workload-app
```

### Grafana UI

```bash
kubectl -n observability port-forward svc/grafana 3000:3000
```

Login: `admin / admin`

Datasource avtomatik provision olunur: **Pyroscope** → `http://pyroscope.observability.svc.cluster.local:4040`

### Debug (Alloy receive endpoint)

Alloy port-forward:

```bash
kubectl -n observability port-forward svc/alloy 9999:9999
```

App profilləri Alloy-a push edəndə Alloy log-larında (pod `alloy`) request-ləri və forward davranışını görə bilərsən:

```bash
kubectl -n observability logs deploy/alloy --tail=200
```

Memory allocation ucun

PYROSCOPE_PROFILER_ALLOC=1m
PYROSCOPE_ALLOC_LIVE=true-i yalnız leak şübhəsi olanda, qısa interval üçün aç.