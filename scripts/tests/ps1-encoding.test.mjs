// ps1-encoding.mjs 的單元測試。
//
// fixture 是位元組字面值而不是磁碟上的檔案：一個「故意存成 Big5」的測試檔很容易被編輯器、
// git 的 autocrlf 或下一個看到它的人「好心修好」，那樣這個守門就會在不知不覺中失效。
import test from 'node:test';
import assert from 'node:assert/strict';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { checkPowerShellEncoding, collectPowerShellFiles, UTF8_BOM } from './ps1-encoding.mjs';

const utf8 = (text) => new TextEncoder().encode(text);
const withBom = (text) => Uint8Array.from([...UTF8_BOM, ...utf8(text)]);

test('純 ASCII 的腳本不需要 BOM', () => {
  const failures = checkPowerShellEncoding([{ path: 'a.ps1', bytes: utf8('Write-Output "ok"\n') }]);
  assert.deepEqual(failures, []);
});

test('含中文且有 BOM 的腳本通過', () => {
  const failures = checkPowerShellEncoding([{ path: 'b.ps1', bytes: withBom('# 中文註解\nWrite-Output "ok"\n') }]);
  assert.deepEqual(failures, []);
});

test('含中文但沒有 BOM 的腳本被擋下', () => {
  const failures = checkPowerShellEncoding([{ path: 'c.ps1', bytes: utf8('# 中文註解\n$x = 1\n') }]);
  assert.equal(failures.length, 1);
  assert.equal(failures[0].kind, 'missing-bom');
  assert.match(failures[0].message, /c\.ps1/);
});

test('被存成 Big5 的腳本被擋下，且理由與缺 BOM 區分開', () => {
  // 「中文」的 Big5 位元組：0xA4 0xA4 0xA4 0xE5。這個序列不是合法的 UTF-8。
  const big5 = Uint8Array.from([0x23, 0x20, 0xa4, 0xa4, 0xa4, 0xe5, 0x0a]);
  const failures = checkPowerShellEncoding([{ path: 'd.ps1', bytes: big5 }]);
  assert.equal(failures.length, 1);
  assert.equal(failures[0].kind, 'invalid-utf8');
});

test('一次回報全部問題，而不是遇到第一個就停', () => {
  const failures = checkPowerShellEncoding([
    { path: 'ok.ps1', bytes: withBom('# 好的\n') },
    { path: 'bad1.ps1', bytes: utf8('# 壞的\n') },
    { path: 'bad2.ps1', bytes: utf8('# 也是壞的\n') },
  ]);
  assert.equal(failures.length, 2);
  assert.deepEqual(failures.map((failure) => failure.path), ['bad1.ps1', 'bad2.ps1']);
});

test('repository 內現有的 PowerShell 腳本全部符合契約', () => {
  const repoRoot = join(fileURLToPath(new URL('.', import.meta.url)), '..', '..');
  const files = collectPowerShellFiles(repoRoot);
  assert.ok(files.length >= 5, `預期至少找到 5 個 .ps1，實際 ${files.length} 個 —— 檔案搜尋可能壞了`);
  assert.deepEqual(checkPowerShellEncoding(files), []);
});
