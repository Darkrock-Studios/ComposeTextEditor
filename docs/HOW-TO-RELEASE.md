# How to release

- Pushed all changes
- Create a tag in the format `vX.Y.Z`
- Push the tag
- The `deploy.yml` workflow will run and publish the artifacts to Maven Central

## How to update the GitHub Pages site (demo + API docs)

GitHub Pages serves the `docs/` directory from the `demo` branch. The Wasm sample lives
at the site root and the Dokka API docs live under `docs/api/`.

### Automatic (on release)

Pushing a `vX.Y.Z` tag triggers the `deploy-docs.yml` workflow, which builds the demo
and API docs and commits the result to the `demo` branch — no manual step needed. It can
also be run on demand from the Actions tab (**Deploy Docs → Run workflow**).

The steps below are the manual equivalent, for local rebuilds or out-of-band updates.

1. Change to the `demo` branch and bring in the latest from `main`:
   ```bash
   git checkout demo
   git merge main
   ```

2. Refresh the whole site in one step:
   ```bash
   ./gradlew updateDocs
   ```

   This runs `:sampleApp:updateDemo` and `:dokkaGenerate` together:
    - Builds the Wasm sample and copies it into `docs/` (removing stale Wasm files)
    - Generates the aggregated API docs into `docs/api/`

   To rebuild just one, run `./gradlew :sampleApp:updateDemo` or `./gradlew :dokkaGenerate`.

3. Commit and push the changes:
   ```bash
   git add docs/
   git commit -m "Update demo and API docs"
   git push origin demo
   ```

4. The site will be available at the GitHub Pages URL:
   - Demo: `https://darkrock-studios.github.io/ComposeTextEditorLibrary/`
   - API docs: `https://darkrock-studios.github.io/ComposeTextEditorLibrary/api/`

> Note: `docs/api/` is generated output. Commit it on the `demo` branch only — don't
> commit it on `main`.
