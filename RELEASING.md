# Releasing

A tag `vX.Y.Z` runs `.github/workflows/release.yml`, which:

1. sets the version to `X.Y.Z`,
2. signs `nl2sql-core` and the parent POM with GPG and uploads them, with their sources and Javadoc, to Maven Central
   as `io.github.alok26sinha:nl2sql-core:X.Y.Z`,
3. attaches the runnable REST server `nl2sql-X.Y.Z.jar` to a GitHub Release.

Maven Central releases are permanent: a version can never be changed or deleted, only superseded.

## One-time setup

### 1. Central Portal account and namespace

1. Go to <https://central.sonatype.com>, choose **Sign in** → **Continue with GitHub**, and sign in as `alok26sinha`.
2. Open **Namespaces**. `io.github.alok26sinha` should already be listed as verified (GitHub sign-in verifies it).
   If it is not, add it and follow the instructions (create a temporary public repo with the name shown).
3. Open **Account** → **Generate User Token**. Copy the username and password it shows (they are shown once).

### 2. GPG signing key

On any machine with GnuPG (Windows: install Gpg4win, then use a PowerShell window):

```bash
gpg --quick-generate-key "Alok Sinha <you@example.com>" rsa4096 sign 3y     # choose a passphrase when asked
gpg --list-secret-keys --keyid-format long                                   # note the key id after "rsa4096/"
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>                    # Central checks signatures against public keyservers
gpg --keyserver keys.openpgp.org --send-keys <KEY_ID>
gpg --armor --export-secret-keys <KEY_ID> > private-key.asc                  # for the GitHub secret below; delete the file afterwards
```

### 3. GitHub secrets

In <https://github.com/alok26sinha/nl2sql-java> → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**:

| Secret | Value |
|---|---|
| `CENTRAL_USERNAME` | token username from step 1 |
| `CENTRAL_PASSWORD` | token password from step 1 |
| `GPG_PRIVATE_KEY` | the whole content of `private-key.asc`, including the BEGIN/END lines |
| `GPG_PASSPHRASE` | the key's passphrase |

## Each release

```bash
git tag v0.1.0
git push origin v0.1.0
```

Then open <https://central.sonatype.com/publishing>. The deployment shows as **Validated**: check the files and press
**Publish**. It appears on Maven Central after roughly 10–30 minutes, and in search somewhat later.

Once a release or two has gone through cleanly, skip the manual step: under **Settings** → **Secrets and variables** →
**Actions** → **Variables**, add `CENTRAL_AUTO_PUBLISH` = `true`.

If validation fails, the Portal lists the reason (missing signature, unknown key, POM field). Fix it, delete the failed
deployment in the Portal, and release a new patch version (or delete and re-push the same tag if nothing was published).
