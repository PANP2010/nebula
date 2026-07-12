---
name: Regression test
about: Report a regression or request a regression test for an existing bug
title: '[regression] '
labels: ['regression', 'ci']
assignees: []
---

## Summary

<!-- One-line summary of the regression. What broke, in what module, under
what input? -->

## Affected module(s)

<!-- Check all that apply. The CI path-filter uses this to decide whether the
PR can skip cross-module runs. -->

- [ ] `nebula-core`
- [ ] `nebula-guard-api`
- [ ] `nebula-agent`
- [ ] `nebula-redstone`
- [ ] `nebula-entity`
- [ ] `nebula-replay`
- [ ] `nebula-folia-bridge`
- [ ] `nebula-folia-adapter`
- [ ] `nebula-plugin`
- [ ] `nebula-bench`
- [ ] `nebula-integration`
- [ ] `nebula-maintenance`
- [ ] Build / Gradle (cross-cutting)
- [ ] Other (describe below)

## Environment

- **Minecraft server**: Folia 26.1.2 / Paper 26.1.2 / Other: _______
- **JDK**: 21 / 25 / Other: _______
- **Server jar sha256**: _______ (paste `sha256sum <jar>`)
- **nebula-plugin version**: _______ (`/nebula version`)
- **OS**: Linux / macOS / Windows
- **Kernel / distro**: _______

## Reproduction steps

1.
2.
3.

## Expected behaviour

<!-- What should have happened? -->

## Actual behaviour

<!-- What actually happened? Paste server log / stderr / surefire-report
excerpt below. -->

```text

```

## Reproducible test

<!-- If a JUnit 5 regression test already exists or is attached, link it here.
If not, describe the smallest fixture that would reproduce the failure so a
maintainer can write one. -->

- Test class: `org.nebula._______._______`
- Fixture / input: _______
- Expected assertion: _______

## CI run URL

<!-- Paste the failing GitHub Actions run URL, if applicable. -->

- Workflow: Nebula Regression CI / Nebula Annotation Regression CI / Other
- Run: _______

## Impact

<!-- Who is affected and how badly? -->

- [ ] Crashes the server
- [ ] Silently corrupts state (replay / redstone / entity)
- [ ] Performance regression only
- [ ] Build / CI breakage only
- [ ] Other: _______

## Checklist

- [ ] I searched existing issues and did not find a duplicate
- [ ] I attached a surefire-report excerpt or stack trace
- [ ] I tagged the affected module(s) above
- [ ] I am willing to send a PR with the regression test