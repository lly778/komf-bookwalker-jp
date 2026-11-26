"""Build the Komf adapter's private index from BookWalker Japan's official CSV."""
import argparse
import csv
import hashlib
import re
import sqlite3
import unicodedata
from contextlib import closing
from pathlib import Path

KINDS = {'マンガ': 'MANGA', 'ライトノベル': 'NOVEL', '新文芸': 'NOVEL', '文芸・小説': 'NOVEL'}
BOOK_URL = re.compile(r'https://bookwalker\.jp/(de[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})/?\Z')
PROMO = re.compile(r'期間限定|試し読み|無料お試し|無料版|無料配信|分冊版|【単話】|【単話版】|【タテヨミ】')
EDITION = re.compile(r'【(?:電子)?(?:フルカラー)?単行本版?】|【コミック版】')


def normalized(text):
    return ''.join(c for c in unicodedata.normalize('NFKC', text).lower() if c.isalnum())


def remove_label(text, label):
    for brackets in ('（）', '()'):
        tail = f'{brackets[0]}{label}{brackets[1]}'
        if label and text.endswith(tail):
            return text[:-len(tail)].strip()
    return text.strip()


def number_of(title, series):
    title = unicodedata.normalize('NFKC', EDITION.sub('', title))
    series = unicodedata.normalize('NFKC', EDITION.sub('', series))
    if title.startswith(series):
        tail = title[len(series):].strip()
    else:
        return None
    named = re.match(r'^\s*[（(]?([上下中])[)）]', tail)
    if named:
        return {'上': 1, '中': 2, '下': 3}[named[1]]
    number = re.match(r'^\s*(?:[(（:]\s*)?(\d{1,4})(?=\D|$)', tail)
    if not number:
        return None
    return int(number[1])


def build(source, target):
    temporary = target.with_suffix('.building')
    if temporary.exists():
        raise FileExistsError(temporary)
    with closing(sqlite3.connect(temporary)) as db:
        db.executescript("""
            PRAGMA journal_mode=OFF;
            PRAGMA synchronous=OFF;
            CREATE TABLE series (
              id TEXT PRIMARY KEY, title TEXT NOT NULL, search TEXT NOT NULL,
              alias TEXT NOT NULL, publisher TEXT NOT NULL, category TEXT NOT NULL
            );
            CREATE TABLE books (
              id TEXT PRIMARY KEY, series_id TEXT NOT NULL, title TEXT NOT NULL,
              url TEXT NOT NULL, release TEXT NOT NULL, volume INTEGER
            );
        """)
        records = csv.reader(source.open(encoding='cp932', errors='replace', newline=''))
        if next(records)[:7] != ['タイトル', 'URL', '配信日', '発行元', 'レーベル', 'シリーズ', 'カテゴリ']:
            raise ValueError('Unexpected official catalog format')
        group_ids = set()
        books = []
        for row in records:
            if len(row) < 7:
                continue
            title, url, release, publisher, label, series, kind = row[:7]
            category = KINDS.get(kind)
            match = BOOK_URL.fullmatch(url)
            if not category or not match or not title or '�' in title or PROMO.search(title):
                continue
            name = remove_label(series or title, label)
            if not name or '�' in name or PROMO.search(name):
                continue
            query = normalized(name)
            if not query:
                continue
            key = hashlib.sha256('\x1f'.join((category, name, publisher, label)).encode()).hexdigest()[:32]
            if key not in group_ids:
                alias = normalized(EDITION.sub('', name))
                db.execute('INSERT INTO series VALUES(?,?,?,?,?,?)', (key, name, query, alias, publisher, category))
                group_ids.add(key)
            books.append((match[1], key, title, url, release, number_of(title, name)))
            if len(books) >= 3000:
                db.executemany('INSERT OR IGNORE INTO books VALUES(?,?,?,?,?,?)', books)
                books.clear()
        db.executemany('INSERT OR IGNORE INTO books VALUES(?,?,?,?,?,?)', books)
        db.executescript("""
            CREATE INDEX ix_series_search ON series(category, search);
            CREATE INDEX ix_series_alias ON series(category, alias);
            CREATE INDEX ix_book_series ON books(series_id, volume);
            PRAGMA user_version=1;
            ANALYZE;
        """)
        # Use sequential numbers for explicitly labeled upper/middle/lower editions only.
        db.execute("""UPDATE books SET volume=2 WHERE volume=3 AND (title LIKE '%（下）%' OR title LIKE '%(下)%')
                      AND NOT EXISTS (SELECT 1 FROM books AS other WHERE other.series_id=books.series_id
                        AND (other.title LIKE '%（中）%' OR other.title LIKE '%(中)%'))""")
        counts = [db.execute('SELECT count(*) FROM '+table).fetchone()[0] for table in ('series','books')]
        if min(counts) < 10000 or db.execute('PRAGMA quick_check').fetchone()[0] != 'ok':
            raise ValueError('Catalog index incomplete or corrupt')
        db.commit()
    temporary.replace(target)
    return counts


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('csv', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    groups, books = build(args.csv, args.output)
    print(f'Indexed {groups:,} catalog series and {books:,} Japanese edition records.')
