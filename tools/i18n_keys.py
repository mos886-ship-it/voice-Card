"""列出程式里所有 "…".tr / "…".trf( 的键（执行时的字串），跟对照表比对缺哪些。
用法：python -X utf8 tools/i18n_keys.py [--missing]
"""
import json
import os
import re
import sys

PAT = re.compile(r'"((?:[^"\\\n]|\\.)*)"\.trf?\b')
ESC = {'n': '\n', 't': '\t', '"': '"', "'": "'", '$': '$', '\\': '\\'}


def unescape(t):
    return re.sub(r'\\(.)', lambda m: ESC.get(m.group(1), '\\' + m.group(1)), t)


def main():
    keys = []
    for root in ['app/src/main/java', 'wear/src/main/java']:
        for dp, _, fs in os.walk(root):
            for f in sorted(fs):
                if not f.endswith('.kt'):
                    continue
                for m in PAT.finditer(open(os.path.join(dp, f), encoding='utf-8').read()):
                    raw = m.group(1)
                    if '${' in raw or re.search(r'(?<!\\)\$[A-Za-z_]', raw):
                        print('TEMPLATE LEFT:', f, raw, file=sys.stderr)
                    k = unescape(raw)
                    if k not in keys:
                        keys.append(k)
    path = 'shared/src/main/assets/i18n/strings.json'
    table = json.load(open(path, encoding='utf-8')) if os.path.exists(path) else {}
    if '--missing' in sys.argv:
        keys = [k for k in keys if k not in table or not table[k].get('en') or not table[k].get('ko')]
    print(json.dumps(keys, ensure_ascii=False, indent=0))
    print(len(keys), file=sys.stderr)


if __name__ == '__main__':
    main()
