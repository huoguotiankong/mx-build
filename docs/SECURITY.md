# Security model

`mx-build` is public. Every workflow file and every Actions log must be treated as public information.

Private source remains in `huoguotiankong/mx-app` and `huoguotiankong/mx-dev`. The public runner may temporarily check out private source during a trusted manual workflow run, but source files must never be committed to this repository or uploaded as artifacts.

## Required repository secrets for MX App builds

Create these in:

`mx-build → Settings → Secrets and variables → Actions → Repository secrets`

### `MX_PRIVATE_READ_TOKEN`

Fine-grained personal access token used only to clone private MX source repositories.

Recommended permissions:

- Repository access: only `huoguotiankong/mx-app` and `huoguotiankong/mx-dev`
- Repository permissions → Contents: **Read-only**
- No Issues, Pull requests, Administration, Actions, or other write permissions

### `MX_APP_PUBLISH_TOKEN`

Fine-grained personal access token used only to publish the finished signed APK to the private `mx-app/downloads` branch.

Recommended permissions:

- Repository access: only `huoguotiankong/mx-app`
- Repository permissions → Contents: **Read and write**
- No Administration permission

### `MX_APP_SIGNING_BUNDLE`

Copy the existing MX App signing bundle from the private build configuration. Never paste it into a file, issue, PR, discussion, or workflow input.

The workflow verifies the certificate SHA-256 before and after APK signing.

## Public-log rule

Do not add commands such as:

```bash
set -x
printenv
env
echo "$TOKEN"
echo "$PASSWORD"
```

Compiler output may reveal file names, class names, and line numbers. It must not deliberately print private source contents.

## Trigger rule

Secret-bearing workflows use `workflow_dispatch` only. Public pull requests and forks must never execute private-source/signing steps with secrets.

## Output rule

MX App APKs are not uploaded as artifacts from this public repository. Successful builds are pushed to the private `mx-app/downloads` branch.

Extension build/release workflows may later publish intended public extension APK/JAR/index outputs to `mx-repo`, but must never publish private source.
