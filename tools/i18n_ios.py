"""iOS 介面多語言工具（照 Android 的 i18n_wrap.py / i18n_keys.py）。

用法（在 repo 根目錄）：
  python3 tools/i18n_ios.py --wrap FILE...   把顯示用的中文字串包成 "…".tr / "…".trf(x)（\\(x) 換成 {0}）
  python3 tools/i18n_ios.py --missing        列出 iOS 程式裡 .tr/.trf 的鍵，對照表沒有（或缺 en/ko）的
  python3 tools/i18n_ios.py --sync           把共用對照表 shared/.../strings.json 複製到 ios/Dylan/i18n/

不包的：註解、NSLog / print、比對用的字（== != case hasPrefix contains…）、存檔用的識別字（步驟名稱、設定鍵）、
多行字串（提示詞）、已經有 .tr 的。包完要編譯、看一遍 diff。
"""
import json
import os
import re
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHARED = os.path.join(ROOT, 'shared/src/main/assets/i18n/strings.json')
IOS = os.path.join(ROOT, 'ios/Dylan/i18n/strings.json')
HAN = re.compile(r'[一-鿿]')

# 字串前面是這些 → 比對、鍵、識別字，不包
SKIP_BEFORE = re.compile(
    r'(==|!=|\bcase|hasPrefix\(|hasSuffix\(|contains\(|range\(of:|replacingOccurrences\(of:|with:|forKey:|'
    r'forHTTPHeaderField:|NSLog\(|print\(|setAiStep\([^,]*,|step:|aiStep\s*=|pattern:|Regex|\.tag\(|'
    r'firstIndex|lastIndex|components\(separatedBy:|split\(separator:|id:|systemImage:|named:|'
    r'forResource:|withExtension:|identifier:|dateFormat\s*=|Fmt\.stamp\([^,]*,|\bin\s*\[|\[)\s*$')
# 字串後面是這些 → 字典的鍵 / switch，不包
SKIP_AFTER = re.compile(r'^(:|\.tr\b|\.trf\b)')


def split_literal(s, i):
    """s[i] == '"'：回傳字串結尾（含引號）的位置與內容（處理 \\( … ) 與跳脫）。"""
    j = i + 1
    out = []
    while j < len(s):
        c = s[j]
        if c == '\\' and j + 1 < len(s) and s[j + 1] == '(':
            depth, k = 1, j + 2
            while k < len(s) and depth:
                if s[k] == '(':
                    depth += 1
                elif s[k] == ')':
                    depth -= 1
                elif s[k] == '"':   # 插值裡的字串：略過
                    k += 1
                    while k < len(s) and s[k] != '"':
                        k += 2 if s[k] == '\\' else 1
                k += 1
            out.append(('expr', s[j + 2:k - 1]))
            j = k
            continue
        if c == '\\':
            out.append(('text', s[j:j + 2]))
            j += 2
            continue
        if c == '"':
            return j, out
        out.append(('text', c))
        j += 1
    return None, None


def wrap_line(line):
    code = line
    stripped = code.lstrip()
    if stripped.startswith('//') or '"""' in code:
        return line
    res = []
    i = 0
    while i < len(code):
        c = code[i]
        if code.startswith('//', i):
            res.append(code[i:])
            break
        if c != '"':
            res.append(c)
            i += 1
            continue
        end, parts = split_literal(code, i)
        if end is None:
            res.append(code[i:])
            break
        if i > 0 and code[i - 1] == '#':   # 原始字串 #"…"#（正規表示式）：不包
            j = code.find('"#', i + 1)
            j = len(code) if j < 0 else j + 2
            res.append(code[i:j])
            i = j
            continue
        lit = code[i:end + 1]
        text = ''.join(p[1] for p in parts if p[0] == 'text')
        before = ''.join(res)
        after = code[end + 1:]
        if not HAN.search(text) or SKIP_BEFORE.search(before) or SKIP_AFTER.match(after):
            res.append(lit)
            i = end + 1
            continue
        exprs = [p[1] for p in parts if p[0] == 'expr']
        if exprs:
            n = 0
            body = ''
            for kind, v in parts:
                if kind == 'expr':
                    body += '{%d}' % n
                    n += 1
                else:
                    body += v
            res.append('"%s".trf(%s)' % (body, ', '.join(exprs)))
        else:
            res.append(lit + '.tr')
        i = end + 1
    return ''.join(res)


def wrap(files):
    for f in files:
        src = open(f, encoding='utf-8').read().split('\n')
        out = []
        in_multi = False
        for line in src:
            if line.count('"""') % 2 == 1:
                in_multi = not in_multi
                out.append(line)
                continue
            out.append(line if in_multi else wrap_line(line))
        open(f, 'w', encoding='utf-8').write('\n'.join(out))


PAT = re.compile(r'"((?:[^"\\\n]|\\.)*)"\.trf?\b')
ESC = {'n': '\n', 't': '\t', '"': '"', "'": "'", '\\': '\\'}


def keys():
    out = []
    for d in ['ios/Dylan', 'ios/Shared', 'ios/DylanWatch']:
        for dp, _, fs in os.walk(os.path.join(ROOT, d)):
            for f in sorted(fs):
                if f.endswith('.swift'):
                    for m in PAT.finditer(open(os.path.join(dp, f), encoding='utf-8').read()):
                        k = re.sub(r'\\(.)', lambda x: ESC.get(x.group(1), '\\' + x.group(1)), m.group(1))
                        if k not in out:
                            out.append(k)
    return out


def main():
    if '--wrap' in sys.argv:
        wrap(sys.argv[sys.argv.index('--wrap') + 1:])
    elif '--missing' in sys.argv:
        t = json.load(open(SHARED, encoding='utf-8'))
        miss = [k for k in keys() if k not in t or not t[k].get('en') or not t[k].get('ko')]
        print(json.dumps(miss, ensure_ascii=False, indent=0))
        print(len(miss), file=sys.stderr)
    elif '--sync' in sys.argv:
        shutil.copyfile(SHARED, IOS)
        print('copied', IOS)
    else:
        print(__doc__)


if __name__ == '__main__':
    main()
