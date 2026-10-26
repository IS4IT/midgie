# Copyright (c) 2026 IS4IT
#
# SPDX-License-Identifier: EUPL-1.2

"""Fail when a library in the CycloneDX SBOM carries a licence the allowlist does not name.

Usage: python3 check-licences.py <bom.json> <allowlist>

Every licence on every component must be listed, so a dual-licensed library (logback: EPL-2.0 or
LGPL-2.1) needs both. A component without any licence must match one of the allowlist's group:artifact
patterns, which record a licence established by hand. Exit codes: 0 all known, 1 something to review,
2 the inputs could not be read.
"""

import fnmatch
import json
import sys


def read_allowlist(path):
    licences, reviewed = set(), []
    with open(path, encoding="utf-8") as f:
        for number, raw in enumerate(f, 1):
            line = raw.split("#", 1)[0].strip()
            if not line:
                continue
            fields = line.split()
            if ":" in fields[0]:
                if len(fields) != 2:
                    raise ValueError(f"{path}:{number}: expected 'group:artifact licence', got '{line}'")
                reviewed.append((fields[0], fields[1], number))
            elif len(fields) == 1:
                licences.add(fields[0])
            else:
                raise ValueError(f"{path}:{number}: expected one licence per line, got '{line}'")
    for pattern, licence, number in reviewed:
        if licence not in licences:
            raise ValueError(f"{path}:{number}: '{pattern}' is recorded as {licence}, which is not listed")
    return licences, reviewed


def licences_of(component):
    """The licence identifiers a component declares: SPDX ids, else names, else whole expressions."""
    found = []
    for entry in component.get("licenses", []):
        if "expression" in entry:
            found.append(entry["expression"])
        else:
            licence = entry.get("license", {})
            found.append(licence.get("id") or licence.get("name") or "?")
    return found


def main(bom_path, allowlist_path):
    try:
        licences, reviewed = read_allowlist(allowlist_path)
        with open(bom_path, encoding="utf-8") as f:
            components = json.load(f).get("components", [])
    except (OSError, ValueError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 2

    problems = []
    for component in components:
        coordinates = f"{component.get('group', '')}:{component['name']}"
        name = f"{coordinates}:{component.get('version', '?')}"
        declared = licences_of(component)
        if not declared:
            if not any(fnmatch.fnmatchcase(coordinates, pattern) for pattern, _, _ in reviewed):
                problems.append(f"{name}: declares no licence and no group:artifact entry covers it")
            continue
        unknown = [licence for licence in declared if licence not in licences]
        if unknown:
            problems.append(f"{name}: {', '.join(unknown)}")

    if problems:
        print(f"Licences to review before {bom_path} can ship ({allowlist_path} says how):")
        for problem in problems:
            print(f"  {problem}")
        return 1
    print(f"All {len(components)} bundled libraries carry a listed licence.")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        sys.exit(2)
    sys.exit(main(sys.argv[1], sys.argv[2]))
