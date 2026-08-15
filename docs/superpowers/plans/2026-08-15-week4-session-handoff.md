# Week 4 完成與交接文件 (2026-08-15)

> **用途**：這次 session 從「remote 已經開發到 week 4（僅有 spec + plan，尚未實作）」一路做到
> Week 4 全部完成並合併回本地 `main`。使用者要求在合併後暫停、換 session，本文件是交接內容，
> 讓下一個 session 不需要重新爬 git log 或猜測狀態。

## 這個 session 做了什麼

1. 發現 remote 上 `worktree-week4-plan` 分支已有 Week 4（後台與驗證）的設計規格與 12-task
   實作計畫（`docs/superpowers/specs/2026-08-14-flash-sale-week4-admin-notifications-design.md`、
   `docs/superpowers/plans/2026-08-14-flash-sale-week4-admin-notifications.md`），但完全沒有實作
   程式碼。
2. 依照計畫文件指定的 `superpowers:subagent-driven-development` 流程，在
   `.claude/worktrees/week4-plan`（branch `worktree-week4-plan`）逐一執行全部 12 個 task，每個
   task 都有獨立的 implementer + reviewer 兩階段，有問題的進 fix loop（Task 1、Task 2、Task 11
   各修了一輪，其餘一次過關）。
3. 全部 12 個 task 完成後，跑了一次 whole-branch 的 final review（opus model），拿到「with
   fixes」的結論：0 個 Critical、8 個 Important、若干 Minor。挑出可以在**一輪** fix wave 內安全
   處理的 10 項 + 2 個零風險 cleanup 一起修掉，另外 2 項（見下方「刻意沒修」）記錄下來延後處理。
4. Fix wave 修完後跑了一次 scoped re-review，結論：全部 9 個被驗證的 finding 都已解決，沒有新的
   Critical/Important 問題。
5. 在合併前，直接在本機重新完整跑過一次 backend 全套測試（`./gradlew test --rerun`，非
   incremental cache）與 frontend 全套測試，確認要合併的那個 commit 本身是綠的，不是相信舊的
   測試紀錄。
6. Fast-forward merge `worktree-week4-plan` 進本地 `main`（commit `6b8142d`），清掉
   worktree 與已合併的 branch。**沒有 push 到 remote**——只到本地 `main` 為止，push 需要另外
   詢問。

## 現在的狀態

- `main` 目前在 commit `6b8142d`，內容涵蓋 Week 1-4 全部功能（使用者註冊/登入、搶購核心、
  訂單/付款流程、後台管理 API + 前端、k6 壓測腳本）。
- Backend 測試：95/95 綠燈（42 個測試類別）。Frontend 測試：46/46 綠燈（17 個檔案），
  `tsc --noEmit` 乾淨。
- k6 壓測腳本 (`load-tests/purchase-flow.js`) 已經對著真實跑起來的 stack 實際執行過一次，
  30 VU / 10 庫存，結果完全符合預期（10 筆訂單、20 筆 SOLD_OUT、零超賣、零 5xx）。
- 想在本機重現 admin 後台或 k6 腳本的話，`README.md` 新增的「管理後台 (Admin)」章節與
  `load-tests/README.md` 都已補齊操作步驟（如何把一個使用者升級成 ADMIN、如何重跑 k6）。

## 刻意沒修、留給之後處理的項目

Final review 找到的問題中，以下 2 項 Important finding **刻意沒有在這次 fix wave 處理**（理由記
在 SDD ledger 裡，ledger 本身已隨 workspace 清除，這裡摘要保留）：

1. **`ApiAuditFilter` 沒有稽核到 401/403**：這個 filter 註冊在 Spring Security filter chain
   之後，所以認證/授權失敗的請求（`AuthenticationEntryPoint`/`AccessDeniedHandler` 處理掉的）
   永遠不會被稽核 filter 看到——只有 chain 內部產生的 400/404/409 會被記錄。這不是這次新增的
   regression（本來就沒做過），但確實是設計規格 §3.1「擷取每個 API 請求」的缺口。要修好需要
   重新設計 filter 註冊順序（可能要放到 Security chain 之前，但這樣就拿不到
   `SecurityContextHolder` 的 userId，需要另外想辦法），是真正的架構決策，不是機械式修改，
   所以延後。**這是 reviewer 認為兩項延後項目中比較重要的一項**——稽核記錄漏掉的剛好是事故
   排查時最想看的「失敗的登入/授權嘗試」。
2. **`ApiAuditQueryService`/`AdminOrderQueryService` 直接注入 JPA repository**，而不是像其他
   跨模組讀取一樣透過 `application` 層的 port 介面——跟 notification 模組自己的做法不一致，
   而且 `ArchitectureTest` 目前的規則沒有涵蓋到 `common` package，所以這個不一致完全不會被
   自動抓到。純架構潔癖問題，沒有任何功能性 bug，reviewer 也同意可以延後。

另外，final review 的 re-review 階段**額外發現一個不在這次 fix wave 範圍內、但值得盡快處理的
小 bug**：`frontend/src/features/auth/RequireAuth.tsx`（Week 1-3 的舊程式碼，這次完全沒碰）有
跟 `RequireAdmin` 完全一樣的「頁面重新整理時被踢出」問題——`role`/`isAuthenticated` 在
`App.tsx` 的 `refresh()` resolve 之前都是初始值，`RequireAuth` 在那個瞬間就同步判斷
`!isAuthenticated` 並導去 `/login`。這次已經幫 `AuthContext` 加了 `isRestoring` 這個 flag
（`RequireAdmin` 已經在用），`RequireAuth` 只要比照套用同樣兩行邏輯就好，是很便宜的修法，
但屬於 Week 1-3 範圍、不在 Week 4 計畫內，所以這次沒有動它，留給下一個 session 當作快速的
待辦事項。

其餘都是 Minor、不影響功能的項目（CSS 在三個 admin table 頁面重複、nav bell 30 秒 poll 會
持續往 audit log 灌噪音資料但有 30 天保留期界定範圍、`client_ip` 在 swagger/api-docs 這兩個
路由沒有經過 nginx 的 `X-Real-IP` header 轉發等等）——都記錄在這次 final review 的過程中，
不影響合併決定。

## 下一步：Week 5

使用者在這次 session 中段（session 額度用盡、切換到隔夜自動執行模式時）給過標準指示：Week 4
做完、合併回 main 之後，接著開始 Week 5，流程跟前幾週一樣——**先寫 spec，再寫 plan，plan
產出後直接開始開發**（不像之前幾週那樣先暫停等使用者過目 plan）。過程中遇到的待確認問題，用
最佳判斷處理，但要寫進文件裡記錄下來。

**這次 session 因為使用者要換 session、要求先暫停，所以 Week 5 完全還沒開始**——沒有新的
worktree、沒有新的 spec/plan 文件。下一個 session 如果要繼續，應該：

1. 讀主規格 `docs/superpowers/specs/2026-08-08-flash-sale-design.md`，確認 Week 5 的範圍
   （原始規劃應該是 §15 提到的 Micrometer Tracing、結構化 JSON 日誌、GitHub Actions CI 之類的
   項目——Week 4 的 spec §0 有提到這些是特意留給 Week 5 的）。
2. 用 `superpowers:brainstorming` 確認 Week 5 的範圍/取捨（就像 Week 2-4 開始前那樣），寫
   design spec，再寫 implementation plan。
3. Plan 寫完後直接開始執行（`superpowers:subagent-driven-development`），不用停下來等確認——
   這是使用者當時給的明確指示。
4. 可以考慮把上面「RequireAuth 的 isRestoring 修法」跟兩個延後的 Important finding
   一起排進 Week 5 的待辦，或當成獨立的小修快速處理掉。

## 環境備忘（這次 session 踩過的坑，下次可以省掉重新發現的時間）

- 這台機器 `node`/`npm` 不在 PATH 上，前端相關指令一律要用
  `MSYS_NO_PATHCONV=1 docker run --rm -v "<絕對路徑>:/app" -w /app node:20-alpine sh -c "<npm/npx 指令>"`。
- Docker Desktop 需要手動啟動，且要 `export DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine`
  backend 的 Testcontainers 測試才連得到。
- k6 也不在 PATH 上，用 `grafana/k6` 的 Docker image 代替。
- Subagent 如果自己把 `./gradlew test` 丟到背景執行、然後想「等待」它完成，會卡住——subagent
  不會像 controller 一樣收到背景任務完成的自動通知。dispatch 時要明確要求前景執行、擋著等結果。
- 本專案的 commit 慣例：implementer subagent 一律不 commit，只在報告裡列出改了哪些檔案跟建議
  commit message，由 controller session（有使用者當場授權）代為 commit——這個模式在 Week 2 就
  確立過，這次 session 又被重新發現/驗證了一次。
