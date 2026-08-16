# 前端 401／403 全域處理設計規格

## 問題與目標

目前 `apiFetch` 把所有非 2xx 回應轉成只含 `detail` 的一般 `Error`，遺失 HTTP status 與 Problem
Details `code`。access token 在使用途中失效時，前端仍認為使用者已登入，也不會 refresh 或導頁。

本批建立一致的錯誤型別與認證恢復流程：401 只 refresh 一次並重送原請求；refresh 失敗才清除登入
狀態並返回登入頁。403 不登出，顯示無權限訊息。

## 設計

- 新增 `ApiError`，保留 `status`、`code`、`detail` 與 `instance`；非 JSON 回應使用安全的預設訊息。
- 將底層「送出一次 request」與「可自動恢復的 apiFetch」分開，login、register、refresh、logout
  可明確停用自動 refresh，避免 refresh endpoint 自我遞迴。
- module-level single-flight promise 保證並行 401 共用一次 refresh。成功後以新 access token 各自重送
  原 request 一次；重送仍為 401 時不得再次 refresh。
- refresh 成功時同步更新 access token 與 AuthContext；失敗時清除 token、認證狀態及 React Query
  中受保護資料，發出單一 session-expired 事件。
- Router 層接收 session-expired 事件，導向 `/login`，以 location state 保存 pathname、search 與
  hash。登入成功後回原位置。
- 403 保留 AuthContext，`ApiError` 顯示後端中文 detail；後台 route guard 對已知非 ADMIN 使用者仍
  導回首頁。
- 登出即使 API 失敗也清除本機 token 與認證狀態，避免 UI 假留在登入狀態。

## 測試與驗收

- `apiFetch` 正確解析 Problem Details；一般錯誤不遺失中文 detail。
- 單一 401 refresh 成功後只重送一次；refresh 失敗不遞迴。
- 多個並行 401 只呼叫一次 refresh，且各原請求最多重送一次。
- refresh 失敗會清除狀態並導向登入頁，登入後返回完整原 URL。
- 403 不 refresh、不登出，並顯示「沒有權限」類訊息。
- 初始 App auth restore、login、logout 與既有 route guard 測試維持通過。

## 非目標

不改後端 JWT／cookie 規則，不把 access token 存入 localStorage，不新增無限 retry，也不為一般 5xx
自動重送 mutation。
