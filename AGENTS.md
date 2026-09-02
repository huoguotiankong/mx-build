# MX Build agent rules

This repository is the public build-infrastructure repository for the MX Tachiyomi / Mihon / Komikku project.

Before changes, read `README.md` and `docs/SECURITY.md`.

## Repository boundary

- Keep `mx-app` and `mx-dev` source code in their private repositories.
- Do not copy private source files into this repository.
- `mx-repo` remains the public extension distribution repository.
- This repository may contain only build workflows, helper scripts, and minimal build documentation.

## Security

- Never commit passwords, cookies, access tokens, signing bundles, keystores, or private API credentials.
- Privileged workflows that can read private repositories or signing secrets must use `workflow_dispatch` only.
- Do not add `pull_request_target`, privileged `issue_comment`, or other untrusted-code triggers to secret-bearing workflows.
- Treat all Actions logs in this public repository as public.
- Never use `set -x`, `printenv`, or commands that echo secret-bearing environment variables.
- Use Fine-grained PATs with repository-specific least privilege.
- Do not upload private MX app APKs or private-source diagnostics as public Actions artifacts.
- Clean temporary keystores in `if: always()` steps.

## Validation

A workflow may only claim a build is verified after the actual command completes successfully. Android device validation remains separate from CI validation.
