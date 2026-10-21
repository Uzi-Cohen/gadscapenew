"""Rev-241 login smoke test (a minimal protocol client, like the RuneLite 241 client's login).
usage: python3 logintest.py <host> <port> <public-modulus-hex-file> <cache_dir> [username] [password]
Does: INIT_GAME_CONNECTION (14) -> session id; GAMELOGIN (16) with an RSA block encrypted with the TEST
public key (e=65537) and an XTEA-encrypted remainder incl. the 23 live CRCs from the cache's master index;
solves the SHA-256 proof of work; prints the server's login response code.
Expected while the world is not loaded: 8 (LOGINSERVER_OFFLINE) - which proves PoW, RSA, XTEA, the
session id and NET's connection handler all ran."""
import socket, struct, sys, os, hashlib, zlib, bz2, gzip

host, port, modfile, cache = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4]
user = sys.argv[5] if len(sys.argv) > 5 else 'nettest'
pwd = sys.argv[6] if len(sys.argv) > 6 else 'password1'
mod = int(open(modfile).read().strip(), 16)

def read_raw(archive, group):
    idx = open(f'{cache}/main_file_cache.idx{archive}', 'rb').read()
    if group * 6 + 6 > len(idx): return None
    e = idx[group*6:group*6+6]
    size = int.from_bytes(e[0:3], 'big'); sector = int.from_bytes(e[3:6], 'big')
    if size <= 0 or sector <= 0: return None
    dat = open(f'{cache}/main_file_cache.dat2', 'rb'); out = b''
    while len(out) < size:
        dat.seek(sector * 520); hdr = dat.read(8)
        out += dat.read(min(512, size - len(out))); sector = int.from_bytes(hdr[4:7], 'big')
    return out

def crcs():
    res = []
    for a in range(23):
        raw = read_raw(255, a)
        if raw is None: res.append(0); continue
        ln = 5 + int.from_bytes(raw[1:5], 'big') + (0 if raw[0] == 0 else 4)
        res.append(zlib.crc32(raw[:ln]) & 0xffffffff)
    return res

def i32(v): return struct.pack('>I', v & 0xffffffff)
def alt1(v): return struct.pack('<I', v & 0xffffffff)
def alt2(v): v &= 0xffffffff; return bytes([(v >> 8) & 255, v & 255, (v >> 24) & 255, (v >> 16) & 255])
def alt3(v): v &= 0xffffffff; return bytes([(v >> 16) & 255, (v >> 24) & 255, v & 255, (v >> 8) & 255])
def jstr(s): return s.encode('cp1252') + b'\0'
def jstr2(s): return b'\0' + jstr(s)

def xtea_encrypt(data, key):
    out = bytearray(data); delta = 0x9E3779B9
    for off in range(0, len(data) - len(data) % 8, 8):
        v0, v1 = struct.unpack('>II', data[off:off+8]); s = 0
        for _ in range(32):
            v0 = (v0 + ((((v1 << 4) ^ (v1 >> 5)) + v1) ^ (s + key[s & 3]))) & 0xffffffff
            s = (s + delta) & 0xffffffff
            v1 = (v1 + ((((v0 << 4) ^ (v0 >> 5)) + v0) ^ (s + key[(s >> 11) & 3]))) & 0xffffffff
        out[off:off+8] = struct.pack('>II', v0, v1)
    return bytes(out)

s = socket.create_connection((host, port), timeout=60)
buf = b''
def recv_exact(n):
    global buf
    while len(buf) < n:
        c = s.recv(4096)
        if not c: raise SystemExit(f'connection closed (had {buf!r})')
        buf += c
    out, buf = buf[:n], buf[n:]
    return out

s.sendall(bytes([14]))
code = recv_exact(1)[0]
assert code == 0, f'init game connection answered {code}'
session_id = struct.unpack('>q', recv_exact(8))[0]
print('session id received')

seed = [int.from_bytes(os.urandom(4), 'big') for _ in range(4)]
rsa_plain = bytes([1]) + b''.join(i32(x) for x in seed) + struct.pack('>q', session_id)
rsa_plain += bytes([2]) + bytes(4)          # OTP_NONE + 4 skipped bytes
rsa_plain += bytes([0]) + jstr(pwd)          # PASSWORD_AUTHENTICATION
rsa_int = pow(int.from_bytes(rsa_plain, 'big'), 65537, mod)
rsa_bytes = rsa_int.to_bytes((rsa_int.bit_length() + 8) // 8, 'big')  # java BigInteger.toByteArray style

c = crcs()
order = [(5,alt3),(4,alt3),(1,i32),(17,i32),(21,alt2),(9,alt3),(8,alt1),(22,alt2),(15,alt3),(19,alt1),(7,alt3),
         (0,alt3),(18,alt3),(12,alt3),(11,alt1),(14,alt2),(3,i32),(13,alt3),(2,i32),(10,alt2),(6,i32),(16,i32),(20,alt1)]
x = jstr(user) + bytes([2]) + struct.pack('>HH', 765, 503) + bytes(24) + jstr('') + i32(0) + bytes([0])
x += bytes([9, 10, 1]) + struct.pack('>H', 6) + bytes([5, 17, 0, 12, 1]) + struct.pack('>H', 1024) + bytes([4])
x += (8 * 1024).to_bytes(3, 'big') + struct.pack('>H', 3000)
x += jstr2('') + jstr2('NET test GL') + jstr2('') + jstr2('4.5') + bytes([1]) + struct.pack('>H', 2026)
x += jstr2('GenuineIntel') + jstr2('NET test CPU') + bytes([4, 4]) + i32(0) * 3 + i32(0) + jstr2('nettest') + jstr2('')
x += bytes([1]) + i32(0)
x += b''.join(fn(c[i]) for i, fn in order)
xtea = xtea_encrypt(x, seed)

payload = i32(241) + i32(1) + i32(241) + bytes([1, 0, 0]) + struct.pack('>H', len(rsa_bytes)) + rsa_bytes + xtea
s.sendall(bytes([16]) + struct.pack('>H', len(payload)) + payload)

op = recv_exact(1)[0]
if op == 69:
    ln = struct.unpack('>H', recv_exact(2))[0]
    body = recv_exact(ln)
    ctype, version, difficulty = body[0], body[1], body[2]
    salt = body[3:].split(b'\0')[0].decode('utf-8')
    base = format(version, 'x') + format(difficulty, 'x') + salt
    print(f'proof of work: type {ctype}, difficulty {difficulty}')
    n = 0
    while True:
        h = hashlib.sha256((base + format(n, 'x')).encode()).digest()
        bits = 0
        for b in h:
            if b == 0: bits += 8; continue
            bits += 8 - b.bit_length(); break
        if bits >= difficulty: break
        n += 1
    print(f'proof of work solved after {n} hashes')
    s.sendall(bytes([19]) + struct.pack('>H', 8) + struct.pack('>q', n))
    op = recv_exact(1)[0]
names = {2: 'OK', 3: 'INVALID_USERNAME_OR_PASSWORD', 6: 'CLIENT_OUT_OF_DATE', 8: 'LOGINSERVER_OFFLINE',
         22: 'INVALID_LOGIN_PACKET', 24: 'LOGINSERVER_LOAD_ERROR', 65: 'LOGIN_FAIL_1'}
print(f'login response: {op} {names.get(op, "")}')
if op == 2:
    ln = recv_exact(1)[0]
    print(f'OK block: {recv_exact(ln).hex()}')
