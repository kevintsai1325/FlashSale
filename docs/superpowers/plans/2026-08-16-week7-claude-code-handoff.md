# FlashSale Week 7 Claude Code 交接文件（2026-08-16）

> 本文件記錄從 Codex 轉交 Claude Code 時的實際狀態。請先閱讀本文件、Week 7 spec/plan 與 Task 2 review，再繼續修改。不要把 Task 2 誤判為完成，也不要直接開始 Task 3。

## 立即摘要

- Repository：`C:\SideProject\FlashSale`
- 目前工作目錄：`C:\SideProject\FlashSale\.worktrees\week7-portfolio`
- Branch：`codex/week7-portfolio`
- 最後已提交 commit：`e74f30c711b92f9bcf70d9bc48ae635f999edb41`
- `main`：`d04590d`
- 正常 Compose stack：8/8 services healthy（backend、frontend、mailpit、nginx、postgres、rabbitmq、redis、zipkin）
- Week 7 Task 1 已完成；Task 2 已有兩輪已提交修正，但最終複審仍有 2 個 Important。
- Task 2 第 3 輪目前是**未提交 WIP**。不要清除或覆寫；先 review diff 與測試狀態。
- Task 3–8 尚未開始。完整 backend/frontend regression 依使用者要求只在所有 implementation tasks 完成後跑一次。
- 不要 push、merge、刪 stash 或清除 worktree，除非使用者明確授權。

## 必讀文件

1. `docs/superpowers/specs/2026-08-16-flash-sale-week7-portfolio-design.md`
2. `docs/superpowers/plans/2026-08-16-flash-sale-week7-portfolio.md`
3. `.superpowers/sdd/2026-08-16-flash-sale-week7-portfolio/progress.md`
4. `.superpowers/sdd/2026-08-16-flash-sale-week7-portfolio/task-2-report.md`
5. `.superpowers/sdd/2026-08-16-flash-sale-week7-portfolio/task-2-review.md`
6. `.superpowers/sdd/2026-08-16-flash-sale-week7-portfolio/task-2-rereview.md`
7. `.superpowers/sdd/2026-08-16-flash-sale-week7-portfolio/task-2-round2-review.md`

`.superpowers/` 是 gitignored 的本機執行／review 證據，不要期待它出現在遠端 branch。

## Week 7 已完成內容

### Task 1：歷史文件同步（完成）

- `7701629 docs: synchronize Week 7 project history`
- `4934003 docs: clarify completed integration test isolation`
- 恢復 technical-debt roadmap，blob hash 為 `4260c7d58334511a9f7cc3e1927d47b70512ace0`。
- 舊 handoff 已同步為 backend 159 tests、frontend 70 tests、CI success、六批技術債與 order-item product snapshot 已完成。
- Task 1 review/re-review 已通過，無未解 Critical/Important。

### Task 2：安全 demo data tooling（尚未完成）

已提交：

- `da8dc14 feat: add safe portfolio demo data tooling`
- `0d89ec8 fix: harden demo data safety boundaries`
- `e74f30c fix: anchor demo cleanup to canonical stack`

已證實通過的能力：

- `seed`/`cleanup` 可重複執行，cleanup 不使用 `TRUNCATE`，只處理 exact demo identifiers。
- concurrent seed 經 advisory lock 後不重複建立 product/activity/inventory。
- Redis DEL/SET reply 有驗證；cleanup 在 DB delete 前清 Redis，失敗可重試。
- seed 會把 stale Redis stock 與 DB inventory 重建為一致的 1000。
- canonical repo root、compose file、env file 與 container compose labels 已有檢查。
- authenticated demo-user audit、兩個固定 W3C registration trace IDs 與 non-demo sentinel 已有真實 cycle 證據。
- Round 2 fresh checks：35 helper + 11 orchestration tests green；`bash -n`、`git diff --check` green。

使用者對 plan 中 activity prefix 的裁決：**不改 DB schema**。`flash_sales` 沒有文字 identifier；demo activity 以 exact demo product 關聯識別。`DEMO-PORTFOLIO-` prefix 保留給未來有文字 identifier 的 demo order/benchmark run。

## Task 2 最終複審未解的兩個 Important

`task-2-round2-review.md` verdict 是 Spec ❌ / Not approved，無 Critical，有 2 個 Important：

1. **Docker target 還未完全 fail-closed**：固定 compose/env path 尚不足以阻止 `DOCKER_HOST` 或 `DOCKER_CONTEXT` 導向遠端 engine；另外 localhost API 必須與已驗證的 canonical nginx container 身份綁定，不能出現 DB/Compose 指向一處而 API 指向另一處。
2. **非同步 audit cleanup 缺少真正 completion barrier**：固定次數的 250 ms quiet polling 不能證明 cleanup 成功返回後不會再有 `@Async` audit insert。匿名 exact trace 與 authenticated demo-user audit 都必須在 barrier 之後 exact cleanup，且不可 broad delete。

## 第 3 輪未提交 WIP

Codex 在收到「剩餘 token 很少，立即交接」後已停止 agent。當時 worktree 有以下未提交內容：

```text
 M backend/src/main/java/com/flashsale/common/web/ApiAuditWriter.java
 M backend/src/main/java/com/flashsale/identity/adapter/security/SecurityConfig.java
 M scripts/demo-data.sh
 M scripts/lib/demo-data-lib.sh
 M scripts/tests/demo-data-lib-test.sh
 M scripts/tests/demo-data-test.sh
?? backend/src/main/java/com/flashsale/common/web/ApiAuditPersistence.java
?? backend/src/main/java/com/flashsale/common/web/AsyncApiAuditPersistence.java
?? backend/src/main/java/com/flashsale/common/web/DemoDataAuditBarrierController.java
?? backend/src/test/java/com/flashsale/common/web/ApiAuditWriterTest.java
```

WIP 方向是：

- reject non-empty `DOCKER_HOST` / `DOCKER_CONTEXT`，並強化 API/container binding；
- 將 audit persistence 抽出，嘗試提供 demo cleanup 專用 completion barrier；
- 補 helper/orchestration/backend unit tests。

這批 WIP **尚未由主 agent 完成驗證或 review，也沒有 commit**。接手時先執行：

```powershell
git status --short
git diff --check
git diff -- backend/src/main/java/com/flashsale/common/web scripts
```

接著確認設計是否維持 production safety：barrier endpoint 必須只允許安全的本機 demo 使用情境、不得形成一般使用者可操控 async executor 的管理介面，也不能用固定 sleep 假裝 barrier。先跑 RED tests，再完成最小實作與 scoped verification；最後再跑 canonical real seed/cleanup cycle，並找 fresh reviewer 逐項驗證上面兩個 Important。

## 建議接續順序

1. 保留 WIP，先 review 第 3 輪 diff 與 `ApiAuditWriter` 現有 async architecture。
2. 完成 Task 2 fix round 3 的 RED→GREEN。
3. 至少跑 shell helper/orchestration tests、相關 backend unit tests、`bash -n`、`git diff --check`。
4. 對正常 local stack 跑 canonical real cycle：seed twice/concurrent seed、stale Redis、cleanup twice、delayed audit insert、authenticated demo audit、exact trace audit、non-demo sentinel。
5. commit Task 2 fix，fresh scoped review；只有 Critical/Important 全部 addressed 才把 Task 2 標為 complete。
6. 再依 implementation plan 執行 Task 3–8，不要跳過每 task 的 scoped tests/review。
7. Task 8 才跑一次完整 backend `clean test`、frontend serial unit tests/build/lint 與最終 Compose demo verification。

## 尚未開始的 Week 7 tasks

- Task 3：`docs/portfolio/` architecture、API examples、trade-offs、demo script 與 docs contract test。
- Task 4：隔離的 `flashsale-benchmark` k6 harness。
- Task 5：15 次 contention + 10 分鐘 soak 的真實 evidence 與 performance report。
- Task 6：六張 1440×900、去敏感資訊的 portfolio screenshots。
- Task 7：繁體中文 evidence-first README 與正式 Week 7 final handoff。
- Task 8：最後一次完整端到端 verification。

負載測試只描述**目前非同步系統**；不要做同步版比較，也不要宣稱 production capacity/SLA。

## 環境與安全注意事項

- 正常 Compose stack 在 repository root `C:\SideProject\FlashSale` 啟動；交接時 8/8 healthy。
- Week 7 worktree 自己沒有完整秘密 env；從該目錄直接執行普通 `docker compose ps` 會看到 JWT key 未設定警告。
- Windows PATH 沒有一般 Node/npm；bundled Node 位於 `C:\Users\kevin\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin`。
- Git Bash：`C:\Program Files\Git\bin\bash.exe`。
- `stash@{0}: On main: pre-merge untracked docs 2026-08-16` 必須保留；只有 technical-debt roadmap blob 已精確恢復，其他 divergent stash docs 不可擅自覆寫。
- 既有完整驗證基線是 backend 159/159、frontend 70/70、使用者回報 GitHub CI success；Week 7 目前未重跑完整 regression。
- 本機 self-signed TLS、Compose-only demo、沒有 production HA/alerts/deployment，對外文件必須如實揭露。

## Git handoff 狀態

交接文件建立時，HEAD 為 `e74f30c`，工作樹因 Task 2 round 3 WIP 而非 clean。只提交交接文件本身時，不要把 WIP 一併 stage。不要 reset/checkout 掉使用者或前一 agent 的未提交內容。
