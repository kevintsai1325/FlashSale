# API 稽核涵蓋 401／403 設計規格

## 背景與問題

目前 `ApiAuditFilter` 是由 Spring Boot 自動註冊的 Servlet filter，執行順序在 Spring Security
filter chain 之後。正常請求會通過 Security chain 並到達 `ApiAuditFilter`，但認證或授權失敗時，
Spring Security 會直接由 `AuthenticationEntryPoint` 或 `AccessDeniedHandler` 產生 401／403 回應，
不再呼叫後方 filter。因此 `api_audit_logs` 恰好漏掉事故排查時重要的未登入與越權嘗試。

本次只修正後端稽核缺口。前端對 401／403 的全域處理另列為下一項技術債，不納入本次修改。

## 目標

- 所有進入 Spring Security 的 HTTP 請求，不論結果為成功、業務錯誤、401 或 403，都寫入一筆稽核紀錄。
- 401 紀錄使用 `UNAUTHENTICATED`，且 `user_id` 為空。
- 403 紀錄使用 `ACCESS_DENIED`；請求帶有已驗證 JWT 時，保留該使用者的 `user_id`。
- 保持既有 method、path template、status、trace ID、duration、client IP 與 user agent 行為。
- 每個請求只能寫入一筆稽核紀錄，不得因 filter 同時註冊在兩條 chain 而重複寫入。

## 不在範圍內

- 不修改前端登入狀態、重新整理 token、導頁或錯誤提示邏輯。
- 不變更 JWT claim、角色或 API 授權規則。
- 不記錄 Authorization header、JWT 原文、密碼或 request／response body。
- 不處理 outbox trace context、剩餘整合測試 container 共用或其他技術債。

## 設計

### Filter 註冊位置

將 `ApiAuditFilter` 從獨立 Servlet filter 改為 Spring Security chain 內的 filter：

1. 保留 `ApiAuditFilter` 為 Spring bean，供 `SecurityConfig` 注入。
2. 關閉 Spring Boot 對該 bean 的 Servlet filter 自動註冊，避免 Security chain 內外各執行一次。
3. 在 `SecurityConfig` 中把它加入 `SecurityContextHolderFilter` 之後、Bearer token 認證之前。

這個位置讓 `ApiAuditFilter` 包住後續 JWT 認證、授權與 MVC dispatch：

- 無 token 或無效 token 的 401 會在它的 `finally` 區塊被記錄。
- 有效 JWT 通過認證後發生的 403，`finally` 執行時仍位於 Security context 的生命週期內，能取得 userId。
- 正常請求與 Controller 丟出的業務錯誤仍沿用原有稽核流程。

### Security error code

`ProblemDetailAuthenticationEntryPoint` 在產生回應前，於 request attribute
`apiAuditErrorCode` 寫入 `UNAUTHENTICATED`。`ProblemDetailAccessDeniedHandler` 以相同方式寫入
`ACCESS_DENIED`。`ApiAuditFilter` 延續既有做法，從同一 attribute 讀取 error code。

error code 是穩定的程式識別值，因此維持英文；使用者可見的 `detail` 繼續使用繁體中文。

### userId 語意

- 401 表示請求沒有可接受的已驗證身分，`user_id` 必須為空。
- 403 表示身分可能已驗證但權限不足；若 `SecurityContextHolder` 中是
  `JwtAuthenticationToken`，從已驗證 token 的 `userId` claim 取得數值。
- 不為了稽核自行解析未驗證 JWT，也不從 Authorization header 擷取資料。

### 失敗隔離

`ApiAuditWriter` 既有的非同步、獨立交易與錯誤吞吐策略維持不變。稽核資料庫寫入失敗不得改變原始
HTTP 回應，也不得讓登入、授權或業務請求失敗。

## 測試策略

擴充 `ApiAuditFilterIT`，至少驗證：

1. 未帶 token 存取受保護 API，回應 401，且產生一筆 `UNAUTHENTICATED`、空 userId 的紀錄。
2. 一般 USER 存取 admin API，回應 403，且產生一筆 `ACCESS_DENIED`、具有 userId 的紀錄。
3. 上述每個請求各自只產生一筆紀錄，防止重複註冊回歸。
4. 既有匿名 200、已登入 200、trace ID 與 client IP 測試維持通過。

實作採測試驅動：先加入會失敗的 401／403 整合測試，確認目前確實漏記，再調整 filter 註冊與 handler
attribute，最後執行相關測試及完整後端測試套件。

## 驗收標準

- 401／403 均可在 `api_audit_logs` 查到正確 status、error code 與 userId。
- 每個請求恰好一筆稽核紀錄。
- API 的既有 Problem Details status、code、中文 detail 與 content type 不變。
- 既有稽核欄位、安全限制及其他 API 行為不變。
- 相關測試與完整後端測試套件通過。
