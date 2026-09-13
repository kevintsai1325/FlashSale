// PowerShell 腳本的編碼契約檢查。
//
// 為什麼需要這個檢查：這台開發機的系統 ANSI 代碼頁是 950（Big5）。Windows PowerShell 5.1
// 讀取「沒有 BOM」的 .ps1 時會用代碼頁 950 解碼，而不是 UTF-8。中文註解的位元組在 Big5 下
// 常常配對成不同的字元，其中一種後果是**註解行尾的換行被吃掉**，下一行程式碼因此被併進註解。
//
// 這個失敗模式很惡劣：檔案內容看起來完全正常，git diff 也正常，但腳本執行時會出現
// 「變數未定義」這種與真正原因毫無關聯的錯誤。本專案實際踩過兩次（Q7、Q19）。
//
// 規則：任何含非 ASCII 位元組的 .ps1 / .psm1 都必須以 UTF-8 BOM（EF BB BF）開頭。
// BOM 會讓 PowerShell 5.1 無條件以 UTF-8 解碼，跳過代碼頁推測。
// 純 ASCII 的檔案不需要 BOM —— 兩種解碼方式對 ASCII 的結果相同。
//
// 這個檢查刻意可以在 Linux 上跑（CI 用 ubuntu-latest），因此不依賴 PowerShell 本身。
import { readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

export const UTF8_BOM = Uint8Array.of(0xef, 0xbb, 0xbf);

const SCRIPT_EXTENSIONS = ['.ps1', '.psm1'];
const SKIPPED_DIRECTORIES = new Set(['.git', 'node_modules', 'build', 'dist', '.gradle', '.idea', 'target']);

function hasBom(bytes) {
  return bytes.length >= 3 && bytes[0] === UTF8_BOM[0] && bytes[1] === UTF8_BOM[1] && bytes[2] === UTF8_BOM[2];
}

function hasNonAscii(bytes) {
  for (const byte of bytes) {
    if (byte > 0x7f) return true;
  }
  return false;
}

function decodesAsUtf8(bytes) {
  try {
    new TextDecoder('utf-8', { fatal: true }).decode(bytes);
    return true;
  } catch {
    return false;
  }
}

/**
 * 檢查一組檔案。輸入刻意是 {path, bytes} 而不是路徑，讓單元測試能用位元組字面值當 fixture，
 * 不必在磁碟上放一個故意壞掉的檔案（那種檔案很容易被編輯器「好心」修好）。
 */
export function checkPowerShellEncoding(files) {
  const failures = [];
  for (const { path, bytes } of files) {
    if (!decodesAsUtf8(bytes)) {
      failures.push({
        path,
        kind: 'invalid-utf8',
        message: `${path}：不是合法的 UTF-8。這通常表示檔案被存成 Big5／ANSI，請改存為 UTF-8 with BOM。`,
      });
      continue;
    }
    if (hasNonAscii(bytes) && !hasBom(bytes)) {
      failures.push({
        path,
        kind: 'missing-bom',
        message: `${path}：含非 ASCII 字元但沒有 UTF-8 BOM。Windows PowerShell 5.1 會以代碼頁 950 解碼，可能吃掉換行並讓下一行程式碼失效。請存成 UTF-8 with BOM。`,
      });
    }
  }
  return failures;
}

export function collectPowerShellFiles(rootDirectory) {
  const files = [];
  const walk = (directory) => {
    for (const entry of readdirSync(directory)) {
      if (SKIPPED_DIRECTORIES.has(entry)) continue;
      const full = join(directory, entry);
      if (statSync(full).isDirectory()) {
        walk(full);
        continue;
      }
      if (SCRIPT_EXTENSIONS.some((extension) => entry.toLowerCase().endsWith(extension))) {
        files.push({ path: relative(rootDirectory, full).split(sep).join('/'), bytes: readFileSync(full) });
      }
    }
  };
  walk(rootDirectory);
  return files;
}

export function prependBom(bytes) {
  return Buffer.concat([Buffer.from(UTF8_BOM), Buffer.from(bytes)]);
}

const isDirectRun = process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1];
if (isDirectRun) {
  const shouldFix = process.argv.includes('--fix');
  const repoRoot = join(fileURLToPath(new URL('.', import.meta.url)), '..', '..');
  const files = collectPowerShellFiles(repoRoot);
  const failures = checkPowerShellEncoding(files);

  if (shouldFix) {
    // 只自動修 missing-bom。invalid-utf8 需要人判斷原本是哪一種編碼才能正確轉換，
    // 盲目補上 BOM 只會把一個壞掉的檔案偽裝成好的。
    const fixable = failures.filter((failure) => failure.kind === 'missing-bom');
    for (const failure of fixable) {
      const full = join(repoRoot, failure.path);
      writeFileSync(full, prependBom(readFileSync(full)));
      console.log(`已補上 BOM：${failure.path}`);
    }
    const remaining = failures.filter((failure) => failure.kind !== 'missing-bom');
    if (remaining.length > 0) {
      console.error('以下檔案無法自動修復，需要人工確認原始編碼：');
      for (const failure of remaining) console.error(`  - ${failure.message}`);
      process.exit(1);
    }
    console.log(`完成：修復 ${fixable.length} 個檔案。`);
    process.exit(0);
  }

  if (failures.length > 0) {
    console.error('FAIL: PowerShell 腳本編碼檢查');
    for (const failure of failures) console.error(`  - ${failure.message}`);
    console.error('\n修法：node scripts/tests/ps1-encoding.mjs --fix（只補 BOM，不猜測原始編碼）');
    process.exit(1);
  }
  console.log(`PASS: ${files.length} 個 PowerShell 腳本的編碼符合契約。`);
}
