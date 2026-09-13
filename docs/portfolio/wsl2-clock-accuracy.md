# 量測環境的時鐘準確度：WSL2 的 VM 時鐘走快約 3.5%

這份文件記錄一個會影響**所有在容器內取得的時間數字**的環境問題，包含壓測延遲、
未來 Kafka 的事件時間戳與 Flink 的 watermark。它不是應用程式的缺陷，但不知道它的存在
就會把錯誤的數字當成系統行為。

**一句話結論：這台機器的 WSL2 VM 時鐘比實際時間快約 3.5%（修正前 4.5%），
因此任何在容器內量到的時間長度都被系統性高估約 3.5%。**

## 症狀與發現經過

最初的症狀是壓測結果出現**負的延遲**（`completedLatencyMs.min = -1342 ms`）與
**負的吞吐量**（`requestsPerSecond = -1437`）。當時的推論是「牆鐘會往回跳，改用單調時鐘即可」。

那個推論只對了一半。改用單調時鐘之後負值消失了，但**系統性偏差仍然存在** ——
因為單調時鐘也一樣快。

## 量測證據

### 1. 牆鐘的跳動是完全週期性的

以單調時鐘為基準，在容器內連續取樣 120 秒：

| 發生時間 | 偏移 |
|---|---|
| 4.8 s | −1411 ms |
| 37.2 s | −1466 ms |
| 69.7 s | −1464 ms |
| 102.1 s | −1358 ms |

四次全部向後、**零次向前**，間隔精確地落在 32.4–32.5 秒。

### 2. 跳動是「果」，走快才是「因」

VM 與 Windows 的時間差在 3 秒內從 571 ms 增加到 697 ms —— 約 **+40 ms/秒，即 4% 走快**。

4.3% × 32.5 秒 ≈ 1.4 秒，**正好等於每次回跳的幅度**。機制因此清楚了：
VM 時鐘持續走快，Hyper-V 的時間同步每約 32.5 秒把牆鐘拉回主機的正確時間，形成鋸齒波。

### 3. 走快的不只牆鐘，單調時鐘也一樣

這是最關鍵的一點。量法是讓容器以自己的單調時鐘等待固定時間，由 Windows 計時，
並用兩種不同長度相減以消去容器啟動的常數項：

| | VM 自認經過 | Windows 實測 | 走速 |
|---|---|---|---|
| 差值（10s 組與 40s 組） | 30,000 ms | 28,662 ms | **104.67%** |

### 4. 問題在 VM 不在主機

- `w32tm /stripchart` 對 time.windows.com：偏移穩定在 −0.73 秒，**15 秒內沒有增長**。
  那是一個固定偏移而非走速錯誤 —— **Windows 主機的時鐘速率正確**。
- `rancher-desktop` 與 `Ubuntu` 兩個 distro 量到相同的走速（103.84% / 103.85%），
  確認所有 WSL2 distro 共用同一個 utility VM 與時鐘。

## 嘗試過的修法

| 嘗試 | 結果 |
|---|---|
| 執行期 `echo hyperv_clocksource_tsc_page > .../current_clocksource` | **無效**（走速仍 104.3%）。核心的時鐘校準常數是開機時計算的，執行期切換來源不會重新校準。 |
| `.wslconfig` 的 `kernelCommandLine = clocksource=hyperv_clocksource_tsc_page`（開機時指定） | **部分有效**：104.67% → **約 103.5%**。改善約 1 個百分點，但沒有解決。 |

套用的設定（`C:\Users\<使用者>\.wslconfig`，在 repository 之外，此處留存一份以便重建）：

```ini
[wsl2]
# WSL2 的 VM 時鐘走得比實際時間快，Hyper-V 每約 32.5 秒把牆鐘往回校正約 1.4 秒。
# 執行期切換 clocksource 無效（核心的校準常數是開機時計算的），必須用開機參數。
kernelCommandLine = clocksource=hyperv_clocksource_tsc_page
```

改動後需要 `wsl --shutdown` 並重啟 Rancher Desktop 才會生效。

這個設定保留著。它有幫助、沒有副作用，且
`hyperv_clocksource_tsc_page` 本來就是 Hyper-V guest 建議使用的 clocksource。

**殘留的 3.5% 沒有繼續追下去。** 合理的懷疑是 hypervisor 提供的 TSC 頻率換算本身就不準，
那是 VM 內部無法修正的。

## 對既有數字的影響

| 數字 | 是否受影響 |
|---|---|
| **Compose benchmark 的延遲**（k6 在容器內） | **受影響，高估約 3.5%** |
| **P1 的 K8s 壓測數字** | **受影響**，k6 Job 跑在叢集內 |
| P1 的 Pod 就緒時間、擴容耗時 | 不受影響，由 Windows 端的 `kubectl` 計時 |
| P2 的鎖故障模式時間 | 不受影響，刻意全部由 Windows 端輪詢計時 |
| 所有正確性不變量（訂單數、庫存、重複執行次數） | 不受影響，純計數與時間無關 |

**3.5% 的偏差不會改變任何結論**：三副本比單副本慢 41%、租約到期誤差 0.4%、
不超賣的驗證 —— 這些都是比例或計數，不因為時間基準整體縮放而改變。
但**絕對數字（例如「p95 為 388 ms」）應理解為約 3.5% 的高估**。

## 建議的做法

### 能從 Windows 端量的，就從 Windows 端量

P2 的故障模式量測刻意這樣做（從 Windows 輪詢 Redis 的鎖鍵狀態），因此不受影響。
Windows 主機的時鐘已驗證為準確。

### K8s 壓測可以把施壓端移回 Windows

P1 發現「壓力來源必須在叢集內」是因為 Compose 發布到 Windows 的 host port 會在 300 條
瞬間新連線下拒絕約 25–30%。但那是 **Docker Compose 的 port 發布層**的問題。

實測過的另一條路徑不同：**從 Windows 經 K8s NodePort 打，2000 條同時連線 0 失敗**。
因此 P3 重做壓測工具時，K8s 這條路可以把 k6 放回 Windows —— 同時得到準確的時鐘與
不失真的連線。Compose benchmark 則仍必須在容器內執行，該路徑的 3.5% 偏差要如實標註。

### P4／P6 之前必須重新評估

Kafka 的事件時間戳與 Flink 的 watermark 都建立在時鐘假設上。3.5% 的走速偏差加上每 32.5 秒
一次的 1.4 秒回跳，對事件時間視窗聚合是實質風險 —— 而且症狀會是「聚合結果偶爾不對」，
比壓測數字偏高難查得多。進 P4 之前應該先決定：要繼續追這個 VM 的時鐘問題，
還是把事件時間改用其他來源（例如由應用程式在 Windows 端戳記，或改用處理時間語意）。

## 重現方式

```bash
# 走速（差值法消去容器啟動常數項）
for s in 10 40; do
  W0=$(date +%s%3N)
  VM=$(docker run --rm node:20-alpine node -e "
    const t0=process.hrtime.bigint(); const tgt=t0+${s}000000000n;
    while(process.hrtime.bigint()<tgt){}
    process.stdout.write(String(Number(process.hrtime.bigint()-t0)/1e6));")
  echo "$s 秒組: VM=$VM Windows=$(( $(date +%s%3N) - W0 ))"
done
# 走速 = ΔVM / ΔWindows

# 牆鐘跳動的方向與幅度
docker run --rm node:20-alpine node -e '
const t0m=process.hrtime.bigint(), t0w=Date.now();
let pm=t0m, pw=t0w;
while(process.hrtime.bigint() < t0m + 120000000000n){
  const m=process.hrtime.bigint(), w=Date.now();
  const skew=(w-pw)-Number(m-pm)/1e6;
  if(Math.abs(skew)>200) console.log("skew", Math.round(skew), "ms");
  pm=m; pw=w;
}'

# 確認 Windows 主機時鐘正確
w32tm /stripchart /computer:time.windows.com /samples:8 /dataonly
```
