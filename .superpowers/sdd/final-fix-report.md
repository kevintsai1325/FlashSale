# K3s single-node baseline final-fix report

Date: 2026-08-21 (Asia/Taipei)

## Status

All requested Important review findings were implemented and covered by deterministic offline tests. No live image build, server dry-run, deploy, Pod mutation, or cluster verification was run: the installed `kubectl` is v1.23 while the Rancher Desktop server is v1.36, and `nerdctl` is absent.

## Files changed

- `scripts/k8s/k8s-preflight.ps1` (new shared command/context/version/API guard)
- `scripts/k8s/build-local.ps1`
- `scripts/k8s/deploy.ps1`
- `scripts/k8s/verify.ps1`
- `scripts/tests/k8s-manifests-test.ps1`
- `scripts/tests/k8s-scripts-test.ps1`
- `k8s/base/namespace.yaml`
- `k8s/base/config.yaml`
- `k8s/base/data.yaml`
- `k8s/base/support.yaml`
- `k8s/base/application.yaml`
- `k8s/base/gateway.yaml`
- `k8s/secret.example.yaml`
- `docs/portfolio/k3s-baseline.md`
- `.superpowers/sdd/final-fix-report.md`

## Decisions

- The shared preflight requires active context `rancher-desktop`, explicitly uses `--context rancher-desktop` after the active-context query, parses JSON client/server versions, requires client 1.35–1.37, enforces minor skew <= 1, and performs a bounded `/readyz` probe before build, mutation, or workload inspection.
- A `bootstrap` label applies only the Namespace. Once it exists, a full server-side dry-run validates the rendered baseline. Config/Secrets, dependencies, and application resources remain behind that gate.
- Stage labels reuse one Kustomize base without duplicate resources: `bootstrap` (Namespace), `foundation` (ConfigMap), `dependency` (stateful/support resources), and `application` (Backend/Frontend/Nginx and Services).
- PostgreSQL, Redis, and RabbitMQ retain their client ClusterIP Services and gain distinct `*-headless` governing Services. Each StatefulSet points `serviceName` at its headless Service.
- Dependency rollouts finish before the application stage. The baseline still declares exactly one Backend replica.
- Mutable `:local` tags remain for the laptop workflow. Every successful application apply explicitly restarts Backend, Frontend, and Nginx; rollout completion and non-empty running image IDs are then checked. The Nginx restart activates changed TLS files mounted through `subPath`.
- Stateful passwords are create-once. Existing cluster values are reused when inputs are omitted, matching supplied values are accepted, attempted changes are rejected before apply/restart, and PVCs without the retained Secret stop deployment. JWT and TLS values can be updated deliberately and are activated by application/gateway rollouts.
- Runtime and TLS Secret JSON is constructed in memory, base64 encoded, and sent through stdin. Secret values never appear in kubectl arguments, deterministic shim logs, or script output.
- Mailpit is pinned to verified upstream release tag `v1.27.9`. PostgreSQL, Redis, RabbitMQ, Zipkin, and Backend have startup probes with explicit timeouts; real HTTPS verification uses a ten-second timeout.
- The rendered manifest test parses `kubectl kustomize` output with Python/PyYAML and asserts exact resources/replicas/probes/stages, three claims, three governors plus client Services, Secret refs, namespace, pinned/local images, and absence of rendered Secrets/values.

## TDD evidence (selected RED checks)

- `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/k8s-manifests-test.ps1`
  - Expected RED: `postgres must define a startup probe.`
- `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/k8s-scripts-test.ps1`
  - Expected RED: missing `scripts/k8s/k8s-preflight.ps1`.
  - Expected RED for existing empty namespace: `An existing empty namespace must still support safe first credential creation.`
  - Expected RED for unsafe validation ordering: `Full server-side dry-run must follow Namespace/Secret bootstrap and precede every workload mutation.`
- A deliberate `redis-headless` wrong-stage mutation produced expected RED: `dependency stage resources do not match the staged deployment contract.` It was restored before final verification.

## Final verification commands and exact PASS output

Command:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/k8s-manifests-test.ps1
```

Output:

```text
PASS: Kubernetes rendered-resource contract (8 workloads, probes, persistence, headless Services, Secret refs, namespace, and local images).
```

Command:

```powershell
pwsh -NoProfile -File scripts/tests/k8s-manifests-test.ps1
```

Output:

```text
PASS: Kubernetes rendered-resource contract (8 workloads, probes, persistence, headless Services, Secret refs, namespace, and local images).
```

Command:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/k8s-scripts-test.ps1
```

Output:

```text
PASS: FlashSale single-node baseline
PASS: k8s scripts enforce shared version-safe preflight, staged deployment, create-once credentials, stdin Secret safety, local rollouts, and deterministic verification.
```

Command:

```powershell
pwsh -NoProfile -File scripts/tests/k8s-scripts-test.ps1
```

Output:

```text
PASS: FlashSale single-node baseline
PASS: k8s scripts enforce shared version-safe preflight, staged deployment, create-once credentials, stdin Secret safety, local rollouts, and deterministic verification.
```

Command:

```powershell
python -c "import pathlib,re; p=pathlib.Path('docs/portfolio/k3s-baseline.md'); t=p.read_text(encoding='utf-8'); links=[x.split('#',1)[0] for x in re.findall(r'\]\(([^)]+)\)',t) if not x.startswith(('http:','https:','#'))]; missing=[x for x in links if not (p.parent/x).resolve().exists()]; assert not missing, missing; print('PASS: k3s baseline local documentation links resolve.')"
```

Output:

```text
PASS: k3s baseline local documentation links resolve.
```

Command:

```powershell
git diff --check
```

Result: exit 0. Git emitted only the repository's CRLF conversion warnings; it reported no whitespace errors.

## Additional check and concern

Command:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/portfolio-docs-test.ps1
```

Output (exit 1):

```text
FlashSale portfolio documentation contract test
repo root: C:\SideProject\FlashSale\.worktrees\k3s-single-node-baseline

FAIL (1 problem(s))
  - README.md: cites a 300-VU number without carrying the ~212 effective buyers caveat
```

This failure is outside the k3s change set: neither `README.md` nor `scripts/tests/portfolio-docs-test.ps1` is modified here, and the committed benchmark JSON does not contain a `212` value that would substantiate the test's requested caveat. The targeted link check for the changed k3s guide passes. No unsupported claim was added merely to satisfy this unrelated stale contract.

## Remaining live concerns

- Server-side dry-run and all cluster operations remain intentionally unexecuted until `kubectl` 1.35–1.37 (prefer 1.36) is selected and `nerdctl` is available.
- Image pulls for upstream pinned tags, StorageClass provisioning, LoadBalancer ports, actual image IDs, routes, restart counts, PVC persistence, and Zipkin traces still require the documented live acceptance run.
- Stateful password rotation remains deliberately outside this baseline; it requires coordinated database/broker changes plus Secret update.
