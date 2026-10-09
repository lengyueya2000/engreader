"""Reference MOBI/PalmDOC/HUFF-CDIC decompressor, used to produce expectations
for the Kotlin port in app/src/main/java/com/engreader/app/book/."""
import struct, sys, re, hashlib

def pdb_records(d):
    n = struct.unpack_from('>H', d, 76)[0]
    offs = [struct.unpack_from('>L', d, 78 + i * 8)[0] for i in range(n)]
    offs.append(len(d))
    return [d[offs[i]:offs[i + 1]] for i in range(n)]

def palmdoc(data):
    out = bytearray()
    p = 0
    while p < len(data):
        c = data[p]; p += 1
        if 1 <= c <= 8:
            out += data[p:p + c]; p += c
        elif c < 0x80:
            out.append(c)
        elif c < 0xC0:
            c = (c << 8) | data[p]; p += 1
            dist = (c >> 3) & 0x07FF
            ln = (c & 7) + 3
            for _ in range(ln):
                out.append(out[len(out) - dist])
        else:
            out.append(0x20); out.append(c ^ 0x80)
    return bytes(out)

class Huff:
    def __init__(self, recs, huffoff, huffcnt):
        huff = recs[huffoff]
        assert huff[0:8] == b'HUFF\x00\x00\x00\x18', huff[0:8]
        off1, off2 = struct.unpack_from('>LL', huff, 8)
        d1 = struct.unpack_from('>256L', huff, off1)
        self.dict1 = []
        for v in d1:
            cl, term, mx = v & 0x1F, v & 0x80, v >> 8
            mx = ((mx + 1) << (32 - cl)) - 1
            self.dict1.append((cl, term, mx))
        d2 = struct.unpack_from('>64L', huff, off2)
        self.mincode = [0]; self.maxcode = [0xFFFFFFFF]
        for i in range(1, 33):
            self.mincode.append(d2[2 * (i - 1)] << (32 - i))
            self.maxcode.append(((d2[2 * (i - 1) + 1] + 1) << (32 - i)) - 1)
        self.dictionary = []
        for i in range(1, huffcnt):
            self.load_cdic(recs[huffoff + i])

    def load_cdic(self, cdic):
        assert cdic[0:8] == b'CDIC\x00\x00\x00\x10', cdic[0:8]
        phrases, bits = struct.unpack_from('>LL', cdic, 8)
        n = min(1 << bits, phrases - len(self.dictionary))
        for k in range(n):
            off = struct.unpack_from('>H', cdic, 16 + 2 * k)[0]
            blen = struct.unpack_from('>H', cdic, 16 + off)[0]
            self.dictionary.append((cdic[18 + off:18 + off + (blen & 0x7FFF)], blen & 0x8000))

    def unpack(self, data, depth=0):
        if depth > 20: raise RuntimeError('huff depth')
        bitsleft = len(data) * 8
        data = data + b'\x00' * 8
        pos = 0
        x = struct.unpack_from('>Q', data, pos)[0]
        n = 32
        s = bytearray()
        while True:
            if n <= 0:
                pos += 4
                x = struct.unpack_from('>Q', data, pos)[0]
                n += 32
            code = (x >> n) & 0xFFFFFFFF
            cl, term, mx = self.dict1[code >> 24]
            if not term:
                while code < self.mincode[cl]:
                    cl += 1
                mx = self.maxcode[cl]
            n -= cl
            bitsleft -= cl
            if bitsleft < 0: break
            r = (mx - code) >> (32 - cl)
            sl, flag = self.dictionary[r]
            if not flag:
                self.dictionary[r] = (None, 1)
                sl = self.unpack(sl, depth + 1)
                self.dictionary[r] = (sl, 1)
            s += sl
        return bytes(s)

def trailing_size(data):
    num = 0
    for v in data[-4:]:
        if v & 0x80: num = 0
        num = (num << 7) | (v & 0x7F)
    return num

def trim(data, flags):
    for i in range(1, 16):
        if flags & (1 << i):
            data = data[:-trailing_size(data)]
    if flags & 1:
        num = (data[-1] & 3) + 1
        data = data[:-num]
    return data

def extract(path):
    d = open(path, 'rb').read()
    recs = pdb_records(d)
    r0 = recs[0]
    comp, _, tlen, rcount, rsize, enc, _ = struct.unpack_from('>HHIHHHH', r0, 0)
    hlen = struct.unpack_from('>L', r0, 20)[0]
    ver = struct.unpack_from('>L', r0, 36)[0]
    extra = struct.unpack_from('>H', r0, 0xF2)[0] if hlen >= 0xE4 else 0
    huffoff, huffcnt = struct.unpack_from('>LL', r0, 112)
    print(f'{path}: version={ver} compression={comp} records={rcount} textLen={tlen} enc={enc} extra={extra} huff={huffoff}/{huffcnt}')

    text = bytearray()
    if comp == 17480:
        h = Huff(recs, huffoff, huffcnt)
        for i in range(1, rcount + 1):
            text += h.unpack(trim(recs[i], extra))
    else:
        for i in range(1, rcount + 1):
            seg = trim(recs[i], extra)
            text += palmdoc(seg) if comp == 2 else seg
    raw = bytes(text[:tlen])
    s = raw.decode('utf-8', 'replace') if enc == 65001 else raw.decode('cp1252', 'replace')
    s = s.replace('\ufeff', '')
    return s

if __name__ == '__main__':
    for p in sys.argv[1:]:
        t = extract(p)
        print('  chars', len(t), 'sha', hashlib.sha256(t.encode()).hexdigest()[:16])
        print('  head', repr(t[:160]))
        print('  pagebreaks', t.lower().count('<mbp:pagebreak'))
        print('  h1', len(re.findall(r'(?i)<h1', t)), 'h2', len(re.findall(r'(?i)<h2', t)))
        open(p + '.txt', 'w', encoding='utf-8').write(t)
