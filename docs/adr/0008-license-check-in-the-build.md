# 8. License check in the build

Date: 2026-11-08 · Status: accepted

## Context
Rule 4 says no GPL or AGPL libraries in the app. Until now this was checked by hand when a dependency was added,
and a transitive dependency could slip in unseen. Many Java libraries are dual-licensed ("EPL-2.0 OR GPL-2.0 with
Classpath Exception", "EPL-2.0 OR LGPL-2.1"), and POMs spell the same license many different ways.

## Decision
- `org.codehaus.mojo:license-maven-plugin` (Apache-2.0, build plugin only) runs `add-third-party` in every
  `./mvnw verify`, including CI and the release build. It writes `target/generated-sources/license/THIRD-PARTY.txt`
  (also packed into the jar as `THIRD-PARTY.txt`); CI prints it and keeps it as a build artifact.
- **Allow-list, not a block-list:** a dependency passes when at least one of its licenses is on the list:
  Apache-2.0, MIT, BSD-2-Clause, BSD-3-Clause (incl. EDL-1.0), EPL-1.0, EPL-2.0, CC0-1.0, LGPL-2.1, LGPL-3.0, and
  GPL-2.0 with the Classpath Exception. Everything else fails the build: GPL and AGPL, but also any license nobody
  has looked at yet, and a dependency with no license at all.
- **Dual licensing:** we may pick the option we use, so "EPL-2.0 OR GPL-2.0" passes; "GPL-2.0 OR GPL-3.0" fails.
- **LGPL and GPL with Classpath Exception are allowed.** We use these libraries unmodified as separate jars, which
  both licenses permit without putting our code under them. Plain GPL and AGPL stay forbidden.
- **Only shipped scopes** (compile and runtime) are checked. Test libraries never leave the build machine, so they
  are outside rule 4, like ShellCheck in CI. Today they are all permissive anyway.
- License spellings are mapped to SPDX ids with `licenseMerges` in `pom.xml`.

## Consequences
- A new dependency with an unusual license spelling fails the build until its spelling is added to
  `licenseMerges` (same license) or the license is added to the allow-list (a decision; update this ADR).
- Adds about a second to the build and no runtime memory.
- The check trusts the licenses declared in POMs. A wrong POM is not caught; review new dependencies as before.
