"""Builds small MOBI fixtures (uncompressed, PalmDOC, HUFF/CDIC) for the Kotlin tests.

The HUFF/CDIC file uses the simplest legal code assignment: a fixed 8-bit code per
byte value. With codelen=8 and dict1[v] = (255<<8)|0x80|8, the decoder's index
arithmetic ((maxcode - code) >> 24) yields 255-v, so phrase p holds the byte 255-p.
That exercises the whole path — the 64-bit window, the term bit, the CDIC offset
table and the phrase length word — with an outcome that can be stated by hand.
"""
import struct, os, sys

def palmdoc_compress(data):
    """PalmDOC LZ77 encoder: literals, or a back-reference when a match is long enough."""
    out = bytearray()
    i = 0
    while i < len(data):
        best_len, best_dist = 0, 0
        start = max(0, i - 2047)
        for j in range(start, i):
            n = 0
            while n < 10 and i + n < len(data) and data[j + n] == data[i + n]:
                n += 1
            if n > best_len:
                best_len, best_dist = n, i - j
        if best_len >= 3:
            code = 0x8000 + ((best_dist << 3) & 0x3FF8) + (best_len - 3)
            out += bytes([code >> 8, code & 0xFF])
            i += best_len
        else:
            c = data[i]
            if c == 0x20 and i + 1 < len(data) and 0x40 <= data[i + 1] < 0x80:
                out.append(data[i + 1] | 0x80); i += 2
            elif 1 <= c <= 8:
                # Would be read as a length prefix; emit as a single literal instead.
                out.append(0x01); out.append(c); i += 1
            else:
                out.append(c); i += 1
    return bytes(out)

def records_to_pdb(records, name=b'TESTBOOK'):
    header = bytearray(78)
    header[0:len(name)] = name
    header[60:64] = b'BOOK'; header[64:68] = b'MOBI'
    struct.pack_into('>H', header, 76, len(records))
    # Two zero bytes of alignment between the directory and the first record, which is
    # what real Palm databases have; the recorded offsets must include them.
    pad = b'\x00\x00'
    offset = 78 + len(records) * 8 + len(pad)
    entries = bytearray()
    body = bytearray()
    for i, r in enumerate(records):
        entries += struct.pack('>LBBBB', offset, 0, 0, 0, i & 0xFF)
        body += r
        offset += len(r)
    return bytes(header) + bytes(entries) + pad + bytes(body)

def build(records, path):
    open(path, 'wb').write(records_to_pdb(records))

def make_text(chapters):
    """MOBI6-style markup: chapters separated by mbp:pagebreak, with a heading each."""
    out = ['<html><head><guide><reference type="toc" title="Contents" filepos=0000000000/></guide></head><body>']
    for i, (title, body) in enumerate(chapters):
        if i:
            out.append('<mbp:pagebreak/>')
        out.append('<h1>%s</h1>' % title)
        for p in body:
            out.append('<p>%s</p>' % p)
    out.append('</body></html>')
    return ''.join(out).encode('utf-8')

def mobi_record0(text_len, record_count, compression, encoding=65001, version=6,
                 first_image=0xFFFFFFFF, huff_record=0, huff_count=0, extra_flags=0,
                 title=b'Fixture Book', author=b'Fixture Author', cover_offset=None,
                 mobi_header_len=0xE8, encryption=0):
    pd = struct.pack('>HHIHHHH', compression, 0, text_len, record_count, 4096, encryption, 0)

    exth_records = [(100, author), (503, title), (524, b'en')]
    if cover_offset is not None:
        exth_records.append((201, struct.pack('>L', cover_offset)))
    exth_body = b''.join(struct.pack('>LL', t, len(d) + 8) + d for t, d in exth_records)
    exth = b'EXTH' + struct.pack('>LL', len(exth_body) + 12, len(exth_records)) + exth_body
    exth += b'\x00' * ((4 - len(exth) % 4) % 4)

    name_at = len(pd) + mobi_header_len + len(exth)
    mh = bytearray(mobi_header_len)
    # Offsets below are relative to the MOBI header, i.e. record-0 offset minus 16.
    mh[0:4] = b'MOBI'
    struct.pack_into('>L', mh, 4, mobi_header_len)
    struct.pack_into('>L', mh, 8, 2)             # mobi type: book
    struct.pack_into('>L', mh, 12, encoding)
    struct.pack_into('>L', mh, 20, version)
    struct.pack_into('>L', mh, 0x44, name_at)    # record-0 offset 84
    struct.pack_into('>L', mh, 0x48, len(title))  # 88
    struct.pack_into('>L', mh, 0x5C, first_image)  # 108
    struct.pack_into('>L', mh, 0x60, huff_record)  # 112
    struct.pack_into('>L', mh, 0x64, huff_count)   # 116
    struct.pack_into('>L', mh, 0x70, 0x40)         # 128: EXTH present
    if mobi_header_len >= 0xE4:
        struct.pack_into('>H', mh, 0xE2, extra_flags)  # 242
    return pd + bytes(mh) + exth + title

def huff_cdic_records():
    """A HUFF record plus one CDIC record implementing a fixed 8-bit code."""
    dict1 = b''.join(struct.pack('>L', (255 << 8) | 0x80 | 8) for _ in range(256))
    # dict2: only consulted when the term bit is clear, which never happens here.
    dict2 = b''.join(struct.pack('>L', v) for v in ([0] * 32 + [255] * 32))
    off1 = 24
    off2 = off1 + len(dict1)
    huff = b'HUFF' + struct.pack('>LL', 24, off1) + struct.pack('>L', off2) + b'\x00' * 8 + dict1 + dict2

    # 256 phrases; phrase p is the single byte 255-p.
    phrases = [bytes([255 - p]) for p in range(256)]
    table = bytearray()
    data = bytearray()
    # Entry offsets are relative to offset 16 of the CDIC record, and each phrase's
    # length word lives at that offset, so the table comes first and the payloads follow.
    table_size = 256 * 2
    at = table_size
    offsets = []
    for phrase in phrases:
        offsets.append(at)
        data += struct.pack('>H', len(phrase) | 0x8000) + phrase
        at += 2 + len(phrase)
    for off in offsets:
        table += struct.pack('>H', off)
    cdic = b'CDIC' + struct.pack('>LL', 16, 256) + struct.pack('>L', 8) + bytes(table) + bytes(data)
    return huff, cdic

def main(out_dir):
    os.makedirs(out_dir, exist_ok=True)
    chapters = [
        ('The First Chapter', [
            'It was a bright cold day in April, and the clocks were striking thirteen.',
            'Winston Smith, his chin nuzzled into his breast in an effort to escape the vile wind,'
            ' slipped quickly through the glass doors of Victory Mansions.',
        ]),
        ('The Second Chapter', [
            'The flat was seven flights up, and Winston, who was thirty-nine and had a varicose'
            ' ulcer above his right ankle, went slowly, resting several times on the way.',
            'The hallway smelt of boiled cabbage and old rag mats.',
        ]),
    ]
    text = make_text(chapters)
    per_record = 4096
    pieces = [text[i:i + per_record] for i in range(0, len(text), per_record)]

    # 1. Uncompressed.
    build([mobi_record0(len(text), len(pieces), 1)] + pieces, os.path.join(out_dir, 'plain.mobi'))

    # 2. PalmDOC.
    compressed = [palmdoc_compress(p) for p in pieces]
    build([mobi_record0(len(text), len(compressed), 2)] + compressed,
          os.path.join(out_dir, 'palmdoc.mobi'))

    # 3. HUFF/CDIC. Codes are 8 bits, so the "compressed" stream is the text itself.
    huff, cdic = huff_cdic_records()
    # The HUFF and CDIC records sit after the text records, which is where the
    # header's huff-record field points in a real file.
    build([mobi_record0(len(text), len(pieces), 17480, version=6,
                        huff_record=1 + len(pieces), huff_count=2)]
          + pieces + [huff, cdic], os.path.join(out_dir, 'huffcdic.mobi'))

    # 4. PalmDOC with trailing entries (extra data flags), as real converters emit.
    # Bit 0 is the multibyte overlap padding, bits 1..N are trailing entries.
    trailers = 2
    flagged = []
    for p in compressed:
        entry = bytearray()
        for _ in range(trailers):
            payload = b'\x00' * 6
            # Variable-width, written so the high bit marks the first byte; a
            # trailing zero byte would be read as a continuation and inflate the size.
            size = len(payload)
            enc = bytes([(size & 0x7F) | 0x80])
            entry += payload + enc
        flagged.append(p + bytes(entry))
    build([mobi_record0(len(text), len(flagged), 2, extra_flags=(1 << trailers))]
          + flagged, os.path.join(out_dir, 'trailers.mobi'))

    # 5. A tiny DRM-flagged file, to prove the refusal.
    build([mobi_record0(len(text), len(pieces), 2, encryption=2)] + pieces,
          os.path.join(out_dir, 'encrypted.mobi'))

    # 6. A cover image, to prove EXTH 201 resolves through the first-image index.
    png = bytes.fromhex('89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c489')
    build([mobi_record0(len(text), len(pieces), 2, first_image=1 + len(pieces), cover_offset=0)]
          + pieces + [png], os.path.join(out_dir, 'cover.mobi'))

    # 7. A PalmDOC LZ77 unit fixture: text that forces back-references.
    body = ('the quick brown fox jumps over the lazy dog. ' * 12).encode('utf-8')
    build([mobi_record0(len(body), 1, 2)] + [palmdoc_compress(body)],
          os.path.join(out_dir, 'palmdoc-only.mobi'))
    open(os.path.join(out_dir, 'palmdoc-only.txt'), 'wb').write(body)
    open(os.path.join(out_dir, 'plain.txt'), 'wb').write(text)
    for f in sorted(os.listdir(out_dir)):
        print(f, os.path.getsize(os.path.join(out_dir, f)))

if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'fixtures')
