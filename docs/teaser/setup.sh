#!/usr/bin/env bash
# Copyright (c) 2026 IS4IT
#
# SPDX-License-Identifier: EUPL-1.2
#
# Off camera: a tracked tree deployed once (v1), then a commit (v2) for the recording to deploy.
set -euo pipefail
source "$(dirname "$0")/env.sh"

role() { # file oid name description subtype...
    local file=$1 oid=$2 name=$3 description=$4
    shift 4
    {
        printf '<role xmlns="%s" oid="%s">\n  <name>%s</name>\n  <description>%s</description>\n' \
            "$NS" "$oid" "$name" "$description"
        for subtype in "$@"; do
            printf '  <subtype>%s</subtype>\n' "$subtype"
        done
        printf '</role>\n'
    } > "$file"
}

if ! curl -sf -o /dev/null -u "$MIDPOINT_USER:$MIDPOINT_PASSWORD" "$MIDPOINT_URL/ws/rest/self"; then
    echo "setup.sh: no midPoint at $MIDPOINT_URL; start the docker/ rig first." >&2
    exit 1
fi
delete_demo_objects
rm -rf "$TREE"
mkdir -p "$TREE/roles" "$TREE/archetypes"
cd "$TREE"
git init -q .
git config user.email demo@example.invalid
git config user.name "midgie demo"

role roles/helpdesk.xml "$HELPDESK_OID" "Helpdesk" "First-level support" support it-staff
role roles/app-admin.xml "$APPADMIN_OID" "App Admin" "Administers the business applications" it-staff
role roles/legacy-reporting.xml "$LEGACY_OID" "Legacy Reporting" "Read access to the old reporting database" reporting
git add -A && git commit -qm v1
midgie init --create-state > /dev/null 2>&1 || true
if ! midgie deploy . --yes > "$WORK/setup.log" 2>&1; then
    cat "$WORK/setup.log" >&2
    exit 1
fi

# v2: Helpdesk is edited and drops a subtype, Legacy Reporting is retired, Contractor is new.
role roles/helpdesk.xml "$HELPDESK_OID" "Helpdesk" "First- and second-level support" support
git rm -q roles/legacy-reporting.xml
cat > archetypes/contractor.xml <<EOF
<archetype xmlns="$NS" oid="$CONTRACTOR_OID">
  <name>Contractor</name>
  <description>External staff on a fixed-term contract</description>
  <archetypePolicy>
    <display>
      <label>Contractor</label>
    </display>
  </archetypePolicy>
</archetype>
EOF
git add -A && git commit -qm v2
echo "tree ready: $TREE"
