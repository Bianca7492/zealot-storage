#!/usr/bin/env python3
"""Board #50: the peer-proxy notification title must be the host app's own name.

`PeerProxyService.createNotification` used to hard-code `"Proxies.sx Peer"` as the
notification's content title. Every app Zealot injects the SDK into -- including each
tenant's and every white-label build -- then showed the SDK vendor's brand instead of the
app the user actually installed. The title must be resolved at runtime from the host app,
so a single injected dex shows the right name for every app it lands in.

This checks the real committed `proxies_sdk.dex` (not a copy or a guess):

  * FAIL if any `createNotification` `const-string` is a hard-coded brand title;
  * FAIL if the method does not call `PackageManager.getApplicationLabel` (the dynamic read);
  * PASS otherwise, naming the API it found.

Needs `androguard` (`pip install androguard`), the same tool the SDK's class names were read
with. Exits non-zero on FAIL so it can gate CI.

Usage: python3 check-peer-notification-title.py [path/to/proxies_sdk.dex]
"""
import logging
import sys
from pathlib import Path

logging.getLogger("androguard").setLevel(logging.ERROR)

try:
    from androguard.core.dex import DEX
except ImportError:  # pragma: no cover - only when androguard is absent
    print("SKIP: androguard not installed (pip install androguard)")
    sys.exit(0)

# The brand string that must never come back, and any other vendor title we would refuse.
FORBIDDEN_TITLES = {"Proxies.sx Peer", "Proxies.sx", "Peer Proxy Service"}
# The dynamic read that makes the title the host app's name.
REQUIRED_CALL = "getApplicationLabel"

SERVICE = "Lsx/proxies/peer/service/PeerProxyService;"
METHOD = "createNotification"


def main() -> int:
    dex_path = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[3] / "proxies_sdk.dex"
    if not dex_path.is_file():
        print(f"FAIL: dex not found: {dex_path}")
        return 1

    d = DEX(dex_path.read_bytes())

    const_strings: set[str] = set()
    calls: set[str] = set()
    found = False

    for cls in d.get_classes():
        if cls.get_name() != SERVICE:
            continue
        for m in cls.get_methods():
            if m.get_name() != METHOD:
                continue
            found = True
            code = m.get_code()
            if code is None:
                continue
            for ins in code.get_bc().get_instructions():
                name = ins.get_name()
                out = ins.get_output()
                if name.startswith("const-string"):
                    # output looks like: v6, "Proxies.sx Peer"
                    if '"' in out:
                        const_strings.add(out.split('"', 2)[1])
                elif name.startswith("invoke"):
                    calls.add(out)

    if not found:
        print(f"FAIL: {METHOD} not found in {SERVICE} ({dex_path})")
        return 1

    hardcoded = const_strings & FORBIDDEN_TITLES
    if hardcoded:
        print(f"FAIL: {METHOD} still hard-codes a vendor title: {sorted(hardcoded)}")
        return 1

    if REQUIRED_CALL not in " ".join(calls):
        print(f"FAIL: {METHOD} does not call {REQUIRED_CALL} -- the title is not the host app's name")
        return 1

    print(f"[PASS] {METHOD} resolves the title dynamically via {REQUIRED_CALL} (no hard-coded vendor name)")
    print(f"       const-strings in the method: {sorted(const_strings)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
