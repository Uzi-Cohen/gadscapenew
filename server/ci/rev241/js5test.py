"""JS5 smoke test against the rev-241 rsprot server.
usage: python3 js5test.py <host> <port> <cache_dir>
Checks: handshake (15 + rev 241) accepted; 255/255 equals the master index computed from the cache files;
every 255/x the client would ask for has the CRC listed in 255/255; a few regular groups come back
byte-identical to the cache container (minus the version trailer) and decompress."""
import socket, struct, sys, zlib, bz2, gzip

host, port, cache = sys.argv[1], int(sys.argv[2]), sys.argv[3]

def read_raw(archive, group):
    try:
        idx = open(f'{cache}/main_file_cache.idx{archive}', 'rb').read()
    except FileNotFoundError:
        return None
    if group * 6 + 6 > len(idx): return None
    e = idx[group*6:group*6+6]
    size = int.from_bytes(e[0:3], 'big'); sector = int.from_bytes(e[3:6], 'big')
    if size <= 0 or sector <= 0: return None
    dat = open(f'{cache}/main_file_cache.dat2', 'rb')
    out = b''; part = 0; ext = group > 0xFFFF; hs = 10 if ext else 8
    while len(out) < size:
        dat.seek(sector * 520); hdr = dat.read(hs)
        n = min(520 - hs, size - len(out))
        out += dat.read(n); sector = int.from_bytes(hdr[(6 if ext else 4):(9 if ext else 7)], 'big'); part += 1
    return out

def container_len(b):
    comp = b[0]; ln = int.from_bytes(b[1:5], 'big')
    return 5 + ln + (0 if comp == 0 else 4)

def decompress(c):
    comp = c[0]; ln = int.from_bytes(c[1:5], 'big')
    if comp == 0: return c[5:5+ln]
    if comp == 1: return bz2.decompress(b'BZh1' + c[9:9+ln])
    return gzip.decompress(c[9:9+ln])

def expected_master():
    n = len(open(f'{cache}/main_file_cache.idx255', 'rb').read()) // 6
    payload = b''
    for a in range(n):
        raw = read_raw(255, a)
        if raw is None:
            payload += struct.pack('>ii', 0, 0); continue
        c = raw[:container_len(raw)]
        crc = zlib.crc32(c) & 0xffffffff
        d = decompress(c)
        ver = int.from_bytes(d[1:5], 'big') if d[0] >= 6 else 0
        payload += struct.pack('>Ii', crc, ver)
    return bytes([0]) + struct.pack('>I', len(payload)) + payload

s = socket.create_connection((host, port), timeout=20)
s.sendall(bytes([15]) + struct.pack('>i', 241) + bytes(16))
resp = s.recv(1)
assert resp == b'\x00', f'handshake rejected: {resp!r}'
print('handshake ok')

buf = b''
def recv_exact(n):
    global buf
    while len(buf) < n:
        chunk = s.recv(65536)
        if not chunk: raise SystemExit('connection closed')
        buf += chunk
    out, buf = buf[:n], buf[n:]
    return out

def request(archive, group):
    s.sendall(bytes([1, archive]) + struct.pack('>H', group))
    hdr = recv_exact(3)
    a, g = hdr[0], int.from_bytes(hdr[1:3], 'big')
    assert (a, g) == (archive, group), (a, g, archive, group)
    head = recv_exact(5)
    comp = head[0]; ln = int.from_bytes(head[1:5], 'big')
    total = 5 + ln + (0 if comp == 0 else 4)
    data = bytearray(head)
    offset = 3 + 5  # bytes of the current 512 block consumed
    while len(data) < total:
        if offset == 512:
            assert recv_exact(1) == b'\xff', 'missing block separator'
            offset = 1
        n = min(512 - offset, total - len(data))
        data += recv_exact(n); offset += n
    return bytes(data)

master = request(255, 255)
exp = expected_master()
assert master == exp, f'master index differs ({len(master)} vs {len(exp)})'
print(f'255/255 ok: {(len(master)-5)//8} archives')
crcs = [struct.unpack('>I', master[5+i*8:9+i*8])[0] for i in range((len(master)-5)//8)]
checked = 0
for a, crc in enumerate(crcs):
    if crc == 0: continue
    t = request(255, a)
    assert zlib.crc32(t) & 0xffffffff == crc, f'255/{a} crc mismatch'
    checked += 1
print(f'255/x ok: {checked} reference tables match their master-index CRC')
for a, g in [(2, 10), (10, 1), (7, 0), (12, 1), (8, 0)]:
    raw = read_raw(a, g)
    if raw is None: print(f'{a}/{g}: not in cache, skipped'); continue
    got = request(a, g)
    assert got == raw[:container_len(raw)], f'{a}/{g} differs'
    decompress(got)
    print(f'{a}/{g} ok ({len(got)} bytes, version trailer stripped)')
print('JS5 OK')
