"""
介面多语言：把 Kotlin 程式里显示用的中文字串包成 `"…".tr` / `"…{0}…".trf(x)`，并收集对照表的键。

用法：
  python -X utf8 tools/i18n_wrap.py --apply  FILE...   改档案（已经包过的不会重复包）
  python -X utf8 tools/i18n_wrap.py --keys   FILE...   只列出会用到的键（JSON 阵列，给翻译用）

规则（不包的）：
- 三引号字串（提示词）、注解、Log、Regex、const val、注解参数。
- 比对用的：前面是 == / != / startsWith( / contains( / in setOf( …；后面是 ` to `（map 的键）或 `->`（when 分支）。
- [DENY] 里列的内容（提示词片段、存档用的步骤名称等）。
"""
import json
import re
import sys

CJK = re.compile(r'[一-鿿　-〿＀-￯]')

# 内容含这些字的不包（提示词片段、资料、步骤名称）
DENY = [
    "錄音時間", "拍照時間", "錄音地點", "拍攝地點", "通話錄音，通常", "這段錄音", "逐字稿請", "國語照說的寫", "請依",
    "以下第", "說話者代號重新編排", "附上", "沒有錄音，請依", "一律使用繁體中文", "以下是語音辨識", "逐字稿太長", "這是第", "## 第",
]
# 完全等于这些的不包（存进 meta 的步骤名称：显示时才翻译）——只在 ProcessMeetingWorker
STEP_NAMES_ALL = {"接收錄音", "存入錄音機", "上傳音檔", "AI 聆聽整理", "分段分析", "整合報告", "語音辨識", "報告整理", "估算熱量",
                  "上傳到大D的主機", "大D的主機整理", "主機刪除錄音", "傳回報告"}
STEP_NAMES = set()

PRE_SKIP = re.compile(r'(==|!=|startsWith\(|endsWith\(|contains\(|indexOf\(|lastIndexOf\(|replace\(|split\(|equals\(|removePrefix\(|removeSuffix\(|substringBefore\(|substringAfter\(|\bin\s|Regex\(|Log\.[a-z]\(\s*TAG\s*,|Log\.[a-z]\([^,]*,|const val \w+\s*=|@\w+\(|getString\(|putString\(|stepTo\(\s*\w+\s*,|plan\(|STEP_\w+\s*=|setOf\(|stepTo\(id,\s*)\s*$')


def parse_string(src, i):
    """src[i] == '"'（非三引号）。回传 (end, parts)；parts = [('t', 原始文字) | ('e', 运算式原始码)]。"""
    assert src[i] == '"'
    j = i + 1
    parts = []
    buf = ''
    while j < len(src):
        c = src[j]
        if c == '\\':
            buf += src[j:j + 2]; j += 2; continue
        if c == '"':
            if buf: parts.append(('t', buf))
            return j + 1, parts
        if c == '$' and j + 1 < len(src):
            n = src[j + 1]
            if n == '{':
                if buf: parts.append(('t', buf)); buf = ''
                k = j + 2; depth = 1; start = k
                while k < len(src) and depth:
                    ch = src[k]
                    if ch == '"':
                        if src.startswith('"""', k):
                            k = src.index('"""', k + 3) + 3; continue
                        k, _ = parse_string(src, k); continue
                    if ch == "'":
                        k = skip_char(src, k); continue
                    if ch == '{': depth += 1
                    elif ch == '}': depth -= 1
                    k += 1
                parts.append(('e', src[start:k - 1]))
                j = k; continue
            if n.isalpha() or n == '_':
                if buf: parts.append(('t', buf)); buf = ''
                m = re.match(r'[A-Za-z_][A-Za-z0-9_]*', src[j + 1:])
                parts.append(('e', m.group(0)))
                j += 1 + len(m.group(0)); continue
        buf += c; j += 1
    raise ValueError('unterminated string')


def skip_char(src, k):
    """'x' / '\\n' / '\\u1234'"""
    if src[k + 1] == '\\':
        e = src.index("'", k + 2)
        return e + 1
    return k + 3


def unescape(t):
    return (t.replace('\\n', '\n').replace('\\t', '\t').replace('\\"', '"').replace("\\'", "'")
            .replace('\\$', '$').replace('\\\\', '\\'))


def transform(src, keys, apply):
    out = []
    i = 0
    n = len(src)
    while i < n:
        c = src[i]
        if src.startswith('//', i):
            e = src.find('\n', i); e = n if e < 0 else e
            out.append(src[i:e]); i = e; continue
        if src.startswith('/*', i):
            e = src.index('*/', i) + 2
            out.append(src[i:e]); i = e; continue
        if src.startswith('"""', i):
            e = src.index('"""', i + 3) + 3
            while e < n and src[e] == '"': e += 1
            out.append(src[i:e]); i = e; continue
        if c == "'":
            e = skip_char(src, i); out.append(src[i:e]); i = e; continue
        if c == '"':
            end, parts = parse_string(src, i)
            original = src[i:end]
            text = ''.join(p[1] for p in parts if p[0] == 't')
            before = ''.join(out)[-80:]
            after = src[end:end + 12]
            # 已经包过
            already = src.startswith('.tr', end) and not src[end + 3:end + 4].isalnum() or src.startswith('.trf(', end)
            skip = (not CJK.search(text) or already
                    or PRE_SKIP.search(before)
                    or re.match(r'\s+to\s', after) or re.match(r'\s*->', after)
                    or any(d in text for d in DENY)
                    or unescape(text) in STEP_NAMES)
            if skip:
                # 运算式里面可能还有要包的字串
                if not already and any(p[0] == 'e' for p in parts):
                    out.append(rebuild(parts, keys, apply, wrap=False))
                else:
                    out.append(original)
                i = end; continue
            exprs = [p[1] for p in parts if p[0] == 'e']
            key_src = ''
            idx = 0
            for p in parts:
                if p[0] == 't': key_src += p[1]
                else: key_src += '{%d}' % idx; idx += 1
            keys.append(unescape(key_src))
            if not exprs:
                out.append('"%s".tr' % key_src)
            else:
                args = []
                for e in exprs:
                    sub = []
                    args.append(transform(e, sub, apply) if '"' in e else e)
                    keys.extend(sub)
                out.append('"%s".trf(%s)' % (key_src, ', '.join(a.strip() for a in args)))
            i = end; continue
        out.append(c); i += 1
    return ''.join(out)


def rebuild(parts, keys, apply, wrap):
    s = '"'
    for p in parts:
        if p[0] == 't': s += p[1]
        else:
            e = p[1]
            if '"' in e:
                sub = []
                e = transform(e, sub, apply); keys.extend(sub)
            s += '${' + e + '}'
    return s + '"'


def main():
    mode = sys.argv[1]
    files = sys.argv[2:]
    allkeys = []
    for f in files:
        src = open(f, encoding='utf-8').read()
        STEP_NAMES.clear()
        if f.endswith('ProcessMeetingWorker.kt'): STEP_NAMES.update(STEP_NAMES_ALL)
        keys = []
        new = transform(src, keys, mode == '--apply')
        allkeys += keys
        if mode == '--apply' and new != src:
            if 'import com.example.voiceai.shared.tr' not in new:
                new = re.sub(r'(?m)^(package [^\n]+\n)', lambda m: m.group(1) + '\nimport com.example.voiceai.shared.tr\nimport com.example.voiceai.shared.trf\n', new, count=1)
            open(f, 'w', encoding='utf-8').write(new)
            print(f'{f}: {len(keys)}', file=sys.stderr)
    if mode == '--keys':
        seen = []
        for k in allkeys:
            if k not in seen: seen.append(k)
        print(json.dumps(seen, ensure_ascii=False, indent=0))


if __name__ == '__main__':
    main()
