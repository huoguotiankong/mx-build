# Build migration plan

The goal is to keep project source private while moving repeatable CI compute to the public `mx-build` infrastructure repository.

## Stage 1 — MX App

Workflow: `.github/workflows/mx-app-preview.yml`

- manually checks out a trusted ref from private `mx-app`
- runs `spotlessCheck`
- builds Preview APK
- runs `testReleaseUnitTest`
- signs with the existing stable MX certificate
- verifies package name, app label, and certificate
- publishes phone APKs back to the private `mx-app/downloads` branch
- uploads no private APK artifact to public `mx-build`

## Stage 2 — Extension validation

Workflow: `.github/workflows/mx-extension-check.yml`

- manually checks out a trusted ref from private `mx-dev`
- validates the private repository layout
- overlays one selected `src/zh/<source>/` module onto the current public Keiyoushi build framework
- builds the Debug APK
- uploads no private-source artifact

## Stage 3 — Extension signed test/formal publishing

After Stage 1 is proven on a real run, signed extension test-store and formal publishing can be moved from private `mx-dev` Actions into this repository. Those workflows will use:

- `MANGA_SIGNING_BUNDLE`
- a least-privilege write token for `mx-repo`

The existing `mx-dev` release semantics, version rules, stable signing identity, test-store separation, and `mx-repo/repo` index generation must be preserved.

## Important

Until a public-builder workflow has actually completed successfully, the matching private workflow remains the known-good implementation and must not be described as migrated or verified.
