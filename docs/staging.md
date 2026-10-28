# Staging across environments

A tree usually goes through dev, test and prod, and a few of its values differ per stage: a resource's host name and port, its base context, the account it binds with. midgie has no variables of its own, and it does not need them for this: midPoint already has stage constants, and the object XML stays pure midPoint XML, the same file midPoint itself would import.

## One tree, every stage

Keep one tree on one branch and promote by deploying **the same commit or tag** to the next server. What decides the stage is the server midgie talks to: `--url`, `MIDPOINT_URL` or your own [config file](configuration.md#config-file--midgieproperties). The tree-local [`.midgie.properties`](configuration.md#a-midgieproperties-in-the-tree) cannot name a server, on purpose, so a checkout cannot send your credentials anywhere you did not choose.

## Values that differ: midPoint constants

midPoint's [constants](https://docs.evolveum.com/midpoint/reference/expressions/constants/) are its answer to exactly this. The object refers to a constant by name, and each server holds the value:

```xml
<connectorConfiguration>
    <icfc:configurationProperties
            xmlns:icfc="http://midpoint.evolveum.com/xml/ns/public/connector/icf-1/connector-schema-3"
            xmlns:ldap="http://midpoint.evolveum.com/xml/ns/public/connector/icf-1/bundle/com.evolveum.polygon.connector-ldap/com.evolveum.polygon.connector.ldap.LdapConnector">
        <ldap:host><expression><const>ldapHost</const></expression></ldap:host>
        <ldap:port><expression><const>ldapPort</const></expression></ldap:port>
        <ldap:baseContext><expression><const>ldapBaseContext</const></expression></ldap:baseContext>
    </icfc:configurationProperties>
</connectorConfiguration>
```

A server gets its values in one of three ways:

- in `config.xml` in its midPoint home, as `<constants><ldapHost>ldap.prod.example</ldapHost>…</constants>` inside `<midpoint>`;
- as a system property at startup, `-Dmidpoint.constants.ldapHost=ldap.prod.example`;
- in the container image, as an environment variable that the start script turns into that property, `MP_SET_midpoint_constants_ldapHost=ldap.prod.example`. The underscores become dots, so a constant's name must not contain one: write `ldapHost`, not `ldap_host`. Append `_FILE` (`MP_SET_midpoint_constants_ldapPassword_FILE=/run/secrets/ldap`) and midPoint reads the value from that file, which is how a mounted secret gets in.

Constants work wherever midPoint evaluates an expression: connector configuration properties, mappings and object templates, and scripts through `basic.const('ldapHost')`. They do not reach plain fields, names, OIDs or references.

The gain on midgie's side is that the tree is now the same for every stage, and so is what the server stores: the expression, not its value. A live `diff` of the tree against test and against prod therefore shows real drift and nothing else.

One thing midgie cannot check for you: a constant the server does not define evaluates to nothing, without an error. The deploy succeeds, and the resource's connection test is the first thing to fail. midgie cannot read a server's constants over REST, so it cannot warn. Define a new constant on every stage before the tree that uses it reaches that stage.

## Secrets

A password does not belong in the tree, whatever the stage. There are two ways to keep it out:

- **A constant read from a file**, as above. The tree names the constant, and the secret stays in the platform's secret store.
- **Leave the item out of the tree.** The default `--scope present` never touches an item the files do not mention, so a bind password set once on each server stays as it is across every deploy. For an item the tree should set when it creates the object and then leave alone, use [`apply=on-create`](policy.md#applyon-create) or [`apply=if-absent`](policy.md#applyif-absent). Those carry one value for every stage, though, so they suit a starting value, not a per-stage secret.

## What constants cannot reach

Some differences are not values in an expression: a resource only prod has, an inducement only test needs, a plain field. For those, render each stage's tree with whatever tool you already use (envsubst, gomplate, XSLT), and **commit the rendered tree to the repository**, `rendered/prod/` next to the templates, then deploy that folder.

Commit it rather than rendering into a temporary directory. midgie learns from git what each file last deployed, so a tree outside the repository is untracked. On an untracked tree, `--multivalue auto` replaces every list the files declare instead of merging into it (see [Choosing a strategy](strategy.md#choosing-a-strategy)), and nothing [detects what you removed](removal-detection.md). Committed, the rendered tree is tracked like any other, and as a bonus its git diff shows exactly what a promotion will change on that stage.

The rule is the same as for any generated file: edit the template, never the rendered copy.

## Why not a branch per stage

A branch per stage looks natural, but git cannot tell which lines belong to a stage. A merge from dev into test either carries dev's host names along or conflicts on the same lines every time, and branches kept apart with cherry-picks drift until nobody knows what prod runs. One branch, with the stage differences in constants or in committed per-stage folders, keeps each stage's state a single commit you can name.
