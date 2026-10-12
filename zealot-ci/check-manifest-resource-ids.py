#!/usr/bin/env python3
"""Task 46c-prov, widened in 46d-diag: fail if an android: attribute on an injected element is unreadable.

Reads the binary AndroidManifest.xml of an APK directly (string pool, resource map, start-element
chunks), because aapt2 and androguard print attributes by name and would not show a missing id.

Which elements are checked is decided by the element and its android:name, never by what an attribute's value
contains (the first version looked for "zealot" in a value, so it skipped android:exported="false" and the
API-key value, and its PASS proved less than it said). Checked, EVERY android: attribute of:
  - <provider> whose android:name is com.zealot.proxy.ZealotProxyProvider or com.zealot.updater.ZealotUpdaterProvider (Task 47b),
  - <meta-data> whose android:name starts with com.zealot.proxy. or com.zealot.updater.,
  - <service> whose android:name is sx.proxies.peer.service.PeerProxyService (the SDK's own foreground
    service, which ProxiesPeerSDK.start() starts) or starts with com.zealot.updater. (Task 47b),
  - <receiver> whose android:name starts with com.zealot.updater. (Task 47b),
  - every <uses-permission> (the patcher adds some; the bundle's own are compiled by aapt2 and pass).
Two failures: a resource id of 0 (Android reads manifest attributes by id), and android:exported stored as a
string where aapt2 writes a boolean (binary type 0x12), which the framework only tolerates.
usage: python3 -I check-manifest-resource-ids.py <apk>
"""
import struct
import sys
import zipfile


def strings_of(d, o):
    sc, = struct.unpack_from('<I', d, o + 8)
    flags, sstart = struct.unpack_from('<II', d, o + 16)
    hs, = struct.unpack_from('<H', d, o + 2)
    utf8 = bool(flags & 0x100)
    out = []
    for so in struct.unpack_from('<%dI' % sc, d, o + hs):
        p = o + sstart + so
        if utf8:
            p += 1 if d[p] < 0x80 else 2
            n = d[p]
            p += 1
            if n & 0x80:
                n = ((n & 0x7f) << 8) | d[p]
                p += 1
            out.append(d[p:p + n].decode('utf-8', 'replace'))
        else:
            n, = struct.unpack_from('<H', d, p)
            out.append(d[p + 2:p + 2 + 2 * n].decode('utf-16le', 'replace'))
    return out


def main(apk):
    d = zipfile.ZipFile(apk).read('AndroidManifest.xml')
    strings, resmap, off = [], (), 8
    bad, seen = [], 0
    while off < len(d):
        t, _, size = struct.unpack_from('<HHI', d, off)
        if t == 0x1:
            strings = strings_of(d, off)
        elif t == 0x180:
            resmap = struct.unpack_from('<%dI' % ((size - 8) // 4), d, off + 8)
        elif t == 0x102:
            name, = struct.unpack_from('<i', d, off + 20)
            aoff, = struct.unpack_from('<H', d, off + 24)
            acount, = struct.unpack_from('<H', d, off + 28)
            tag = strings[name]
            attrs = []
            for i in range(acount):
                a = off + 16 + aoff + i * 20
                ns, an, raw = struct.unpack_from('<iii', d, a)
                dtype = d[a + 15]
                if ns < 0 or an < 0 or an >= len(strings):
                    continue
                val = strings[raw] if 0 <= raw < len(strings) else ''
                attrs.append((strings[an], an, val, dtype))
            ident = next((v for n, _, v, _ in attrs if n == 'name'), '')
            injected = (
                (tag == 'provider' and ident in ('com.zealot.proxy.ZealotProxyProvider', 'com.zealot.updater.ZealotUpdaterProvider'))
                or (tag == 'meta-data' and (ident.startswith('com.zealot.proxy.') or ident.startswith('com.zealot.updater.')))
                or (tag in ('service', 'receiver') and (ident.startswith('com.zealot.updater.') or ident == 'sx.proxies.peer.service.PeerProxyService'))
                or tag == 'uses-permission'
            )
            if injected:
                for aname, an, val, dtype in attrs:
                    seen += 1
                    rid = resmap[an] if an < len(resmap) else 0
                    if rid == 0:
                        bad.append('<%s> android:%s = %r has resource id 0' % (tag, aname, val))
                    if aname == 'exported' and dtype != 0x12:
                        bad.append('<%s> android:exported = %r is stored as type 0x%02x, not a boolean (0x12)' % (tag, val, dtype))
        off += size
    if not seen:
        print('FAIL: no injected element found to check')
        return 1
    if bad:
        print('FAIL: Android would not read these attributes:\n  ' + '\n  '.join(bad))
        return 1
    print('PASS: %d attributes on the injected elements all carry a resource id, and exported is a boolean' % seen)
    return 0


sys.exit(main(sys.argv[1]))
