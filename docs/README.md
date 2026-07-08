# Nebula Documentation Index

Start here. Documents are grouped by purpose. When a document conflicts with
[PROJECT_STATUS.md](PROJECT_STATUS.md), **PROJECT_STATUS.md wins** — it is the
single source of truth for current status.

## Current status (read these first)

| Document | Purpose |
|----------|---------|
| [PROJECT_STATUS.md](PROJECT_STATUS.md) | **Source of truth.** What is verified, what isn't, blocker status |
| [../README.md](../README.md) | Project overview, build/deploy, current milestones |
| [../CHANGELOG.md](../CHANGELOG.md) | Release notes; `[Unreleased]` has the latest verified work |

## Getting started

| Document | Purpose |
|----------|---------|
| [QUICKSTART.md](QUICKSTART.md) | Fastest path to building and running |
| [Folia-Server-Setup-Guide.md](Folia-Server-Setup-Guide.md) | Setting up a Folia test server |
| [FOLIA-DEPLOYMENT.md](FOLIA-DEPLOYMENT.md) | Deploying the plugin + agent |
| [INTEGRATION_TESTING_GUIDE.md](INTEGRATION_TESTING_GUIDE.md) | Step-by-step live verification tests |

## Architecture & design

| Document | Purpose |
|----------|---------|
| [nebula-architecture.md](nebula-architecture.md) | System architecture overview |
| [dag-execution.md](dag-execution.md) | How the DAG executor works |
| [runtime-rw-guard.md](runtime-rw-guard.md) | RW-set integrity guard design |

## Planning

| Document | Purpose |
|----------|---------|
| [DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md) | Phased roadmap (Phase 1 + zero-diff done; performance next) |

## Acceptance gates

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
