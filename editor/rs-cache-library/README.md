# rs-cache-library (GadScape fork of 6.4)

Displee's [rs-cache-library](https://github.com/Displee/rs-cache-library) at tag `6.4` (MIT, see LICENSE), the
version the editor used from Maven Central, with the changes the editor needs for OSRS build 241 caches. The package
and API are unchanged (`com.displee.cache.CacheLibrary` etc.), so the editor code didn't change. Every change is
marked `GadScape fork` in the source.

| Where | 6.4 | Now |
| --- | --- | --- |
| `ReferenceTable.read` / `write` | flags 0x4 (sizes) and 0x8 (uncompressed crc) only read when the cache has more than 39 archives (RS3); OSRS 229+ tables (build 241) failed to read (20 of 23 archives) or read wrong versions | read and written whenever the table's flags say so, in the client's order: crcs, 0x8, whirlpool (0x2), 0x4 |
| `Index.update` | kept the 0x4/0x8 values read from the cache, so every rewritten group had stale sizes | recomputed for every group written: container length without the version trailer, raw length, CRC-32 of the raw data (as the server's jagcached does) |
| `ReferenceTable.write` | protocol 5/6 tables wrote ids above 65535 in 2 bytes | a table with an id or count above 65535 is written as protocol 7 (never downgraded) |
| `ReferenceTable.read` (protocol 7) | disio's `readBigSmart` turns 32767 into -1 | unsigned big smart, as the client reads it |
| `ReferenceTable.archive` | took the last 2 bytes of the data as the group version when a group had no version trailer; replaced the table's full version with the 16-bit trailer | trailer found after the right header length (9 bytes when compressed); the table's version kept when its low 16 bits match |
| `Index.readArchiveSector` | checked the idx entry against the data file's length, so in a small cache groups with high ids (model 100000) couldn't be read back | checked against the idx file |
| `decompress` | lengths masked to 24 bits | full 4-byte lengths |
| `CacheLibrary.createIndex` | flags 0x4/0x8 only for RS3 | always |
| `Archive(archive)` copy | dropped the 0x4/0x8 values | copies them |
| `CacheLibrary(path, readOnly = true)` | - | opens every file read-only (used for reference and other servers' caches) |

Checked (editor/src/test/kotlin/cache, and `java -cp <editor jar> cache.CacheCheckKt [--write <scratch>] <cache>...`):
stock build 241 caches (OpenRS2 2740 and 2746), GadScape's merged 241 cache and the 211 caches read with every
reference table re-encoding byte for byte and every group matching its crc and sizes; a 241 copy written by the
editor (model 65534, frame set 30000, keyframe set 20000, skeleton 30000, sprite 20000, animation 40000, object
65534; an earlier run with model 100000 and object 70000 too) passes the server's strict `CacheVerifier` with 0
errors. (The library itself handles group ids above 65535, but the editor never makes such models: the 241 client
can't fetch them.)
