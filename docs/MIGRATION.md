# Build migration plan

The goal is to keep project source private while moving repeatable CI compute to the public `mx-build` infrastructure repository.

## Stage 1 — MX App — verified

Workflow: `.github/workflows/mx-app-preview.yml`

- checks out a trusted ref from private `mx-app`
- runs `spotlessCheck`
- builds Preview APK
- runs `testReleaseUnitTest`
- signs with the existing stable MX certificate
- verifies package name, app label, and certificate
- publishes phone APKs back to the private `mx-app/downloads` branch
- uploads no private APK artifact to public `mx-build`

Verified on 2026-09-02 with merged `mx-app/main` commit `f319e7452e6a9816caffea1293e38a2b9233df5b`. Public builder run `33624156657` completed successfully, including stable signing verification and private downloads publication.

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

MX App Stage 1 is now verified through the public builder. Android device validation remains separate from CI/build verification.

Extension signed test/formal publishing is not yet migrated and must not be described as verified until its own public-builder workflow succeeds.
