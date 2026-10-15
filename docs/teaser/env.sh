# Copyright (c) 2026 IS4IT
#
# SPDX-License-Identifier: EUPL-1.2
#
# Shared settings for the teaser scripts; sourced, not run. They talk to the docker/ rig only:
# setup.sh deletes and recreates objects there.
TEASER=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd "$TEASER/../.." && pwd)
# bin/midgie runs MIDGIE_JAR when set (a released jar, say), otherwise the dev build in target/.
export PATH="$REPO/bin:$PATH"
export MIDPOINT_URL=http://localhost:12080/midpoint
export MIDPOINT_USER=administrator
export MIDPOINT_PASSWORD=T3stPw890uio
# No rollback net: its line names a long scratch path the teaser has no use for.
export MIDGIE_CONF="$TEASER/midgie.properties"
# The tree and the recording live outside the checkout, so the tree's own git repo is not nested in it.
WORK="${TEASER_WORK:-${TMPDIR:-/tmp}/midgie-teaser}"
TREE="$WORK/iam-config"
NS=http://midpoint.evolveum.com/xml/ns/public/common/common-3

# Throwaway objects on the rig, deleted before every setup and after the recording.
HELPDESK_OID=5a3b1e00-0000-0000-0000-0000000000a1
APPADMIN_OID=5a3b1e00-0000-0000-0000-0000000000a2
LEGACY_OID=5a3b1e00-0000-0000-0000-0000000000a3
CONTRACTOR_OID=5a3b1e00-0000-0000-0000-0000000000a4

delete_demo_objects() {
    local auth="$MIDPOINT_USER:$MIDPOINT_PASSWORD" object
    for object in "roles/$HELPDESK_OID" "roles/$APPADMIN_OID" "roles/$LEGACY_OID" \
            "archetypes/$CONTRACTOR_OID"; do
        curl -s -o /dev/null -X DELETE -u "$auth" "$MIDPOINT_URL/ws/rest/$object" || true
    done
}
