# Nebula Documentation Index

![Nebula Logo](images/nebula-logo-flat.png)

Start here. Documents are grouped by purpose. When a document conflicts with
[PROJECT_STATUS.md](PROJECT_STATUS.md), **PROJECT_STATUS.md wins** — it is the
single source of truth for current status.

## Current status (read these first)

| Document | Purpose |
|----------|---------|
| [PROJECT_STATUS.md](PROJECT_STATUS.md) | **Source of truth.** What is verified, what isn't, blocker status |
| [../README.md](../README.md) | Project overview, the two runtime paths, build/deploy, status matrix |
| [../CHANGELOG.md](../CHANGELOG.md) | Release notes; `[Unreleased]` tracks post-0.2.0 native/maintenance/player work |

## Getting started

| Document | Purpose |
|----------|---------|
| [QUICKSTART.md](QUICKSTART.md) | Fastest path to building the plugin and running either path |
| [Folia-Server-Setup-Guide.md](Folia-Server-Setup-Guide.md) | ⚠️ Dated setup notes — see QUICKSTART.md first |
| [FOLIA-DEPLOYMENT.md](FOLIA-DEPLOYMENT.md) | ⚠️ Dated deployment notes — see QUICKSTART.md first |
| [INTEGRATION_TESTING_GUIDE.md](INTEGRATION_TESTING_GUIDE.md) | ⚠️ Dated B1/B2/B3 bring-up record — see PROJECT_STATUS.md first |

## Architecture & design

| Document | Purpose |
|----------|---------|
| [nebula-architecture.md](nebula-architecture.md) | System architecture whitepaper (design intent, not current status) |
| [dag-execution.md](dag-execution.md) | How the DAG executor works |
| [runtime-rw-guard.md](runtime-rw-guard.md) | RW-set integrity guard design |
| [authority-transition.md](authority-transition.md) | Authority-transfer research (shadow → authoritative) |

## Planning

| Document | Purpose |
|----------|---------|
| [DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md) | Dated phased roadmap (historical planning record) |

## Acceptance gates (dated evidence — not current status)

These record the state of each gate at the time it was written. For current
status, see [PROJECT_STATUS.md](PROJECT_STATUS.md).

| Document | Purpose |
|----------|---------|
| [DG1-ACCEPTANCE-REPORT.md](DG1-ACCEPTANCE-REPORT.md) | DG1 (redstone subsystem) acceptance |
| [DG2-ACCEPTANCE-REPORT.md](DG2-ACCEPTANCE-REPORT.md) | DG2 (entity subsystem) acceptance |
| [DG3-ACCEPTANCE-REPORT.md](DG3-ACCEPTANCE-REPORT.md) | DG3 (full system) acceptance |

## Profiling & performance

| Document | Purpose |
|----------|---------|
| [phase1-profiling-guide.md](phase1-profiling-guide.md) | How to profile |
| [phase1-standard-test-world.md](phase1-standard-test-world.md) | Standard test world layout |
| [profiling/](profiling/) | Flame graphs, flat profiles, baseline reports |

## Historical records (superseded — kept for context)

These describe earlier points in the project and are marked with a SUPERSEDED
banner. Do not treat their status claims as current.

| Document | Was |
|----------|-----|
| [NEBULA_BLOCKERS.md](NEBULA_BLOCKERS.md) | 2026-06-24 blocker analysis (B1/B2/B3/B6 since resolved) |
| [DOCUMENTATION_UPDATE_SUMMARY.md](DOCUMENTATION_UPDATE_SUMMARY.md) | Early-session doc-drift cleanup notes |
| [phase1-progress.md](phase1-progress.md), [phase1-engineering-summary.md](phase1-engineering-summary.md) | Phase 1 progress snapshots |
| [nebula-patch-002.md](nebula-patch-002.md), [nebula-server-patch-001.md](nebula-server-patch-001.md), [patch-002-perf-and-competitor.md](patch-002-perf-and-competitor.md) | Point-in-time patch notes |

## Templates

| Document | Purpose |
|----------|---------|
| [templates/](templates/) | Report templates (profiling, DG0 decision, test-world setup) |
