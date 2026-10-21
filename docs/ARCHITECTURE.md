# WAEX architecture in the current public source

This page describes the implementation checked against public `main` at
`f6ec7d4e6f47a246ebb413848d66358217fa9c08` on 18 October 2026. Source links below
identify the owning components; update this document when those contracts change.
Earlier revisions of these architecture pages described a proposed plugin and
capability system. Those proposals are preserved in Git history and are not the
implemented API described here.

## Modules and source layout

[settings.gradle.kts](../settings.gradle.kts) includes `:app` and `:licensing`.
The public manager, UI, configuration, bridge and hook implementations are packages
inside `:app`. `:licensing` is a required private project: its repository URL is
declared in [.gitmodules](../.gitmodules), and
[app/build.gradle.kts](../app/build.gradle.kts) depends on it. This public revision
contains no gitlink and pins no licensing commit. A full APK build therefore
requires a separate, authorized checkout of a compatible licensing revision at
`licensing/`, plus the configured signing key. A public checkout can run the
isolated QA suites without that module. See
[the build instructions](../CONTRIBUTING.md#development-setup).

| Area | Implemented responsibility | Source |
|---|---|---|
| Manager application | Application startup and Compose activity | [app](../app/src/main/java/com/waenhancer/app) |
| User interface | Navigation, screens, reusable components and theme | [ui](../app/src/main/java/com/waenhancer/ui) |
| Feature catalog | Data used to expose settings in the UI | [FeatureCatalog.kt](../app/src/main/java/com/waenhancer/config/FeatureCatalog.kt) |
| Configuration | Preference types, sensitivity and store selection | [config](../app/src/main/java/com/waenhancer/config) |
| Manager preferences and backups | Storage access and settings import/export | [core/preferences](../app/src/main/java/com/waenhancer/core/preferences) |
| Process entry and hook registry | Target selection, initialization and feature registration | [MainHook.java](../app/src/main/java/com/waenhancer/xposed/MainHook.java), [FeatureRegistry.java](../app/src/main/java/com/waenhancer/xposed/core/FeatureRegistry.java) |
| Host features | Java/Kotlin Xposed callbacks grouped by feature area | [xposed/features](../app/src/main/java/com/waenhancer/xposed/features) |
| Cross-process bridge | Provider calls, typed snapshots and settings changes | [xposed/bridge](../app/src/main/java/com/waenhancer/xposed/bridge) |
| Host method discovery | DexKit searches and cached lookup results | [xposed/core/devkit](../app/src/main/java/com/waenhancer/xposed/core/devkit) |
| Updates | Release selection, downloads and installation state | [update](../app/src/main/java/com/waenhancer/update) |

The public tree does not implement separate Gradle modules named `core-ui`,
`feature-*`, `plugin-api` or `plugin-host`. Its features extend
[BaseFeature](../app/src/main/java/com/waenhancer/xposed/core/BaseFeature.kt) and
register Xposed callbacks directly. The registry imports those feature classes;
there is no enforced `api.contracts` boundary or general sandboxed external-plugin
runtime in these public packages.

## Runtime initialization

The legacy Xposed entry is declared by
[assets/xposed_init](../app/src/main/assets/xposed_init). `MainHook` has separate
handling for the manager's own package and declared WhatsApp targets. Target
handling runs in the package's main process and guards initialization with an
atomic flag across Application and Instrumentation entry points.

After a target Context is available, initialization establishes activity tracking,
provider communication, preference hydration and runtime reporting. It calls
[VersionGuard](../app/src/main/java/com/waenhancer/xposed/core/VersionGuard.kt)
before constructing the feature registry. A rejected or unavailable compatibility
decision skips feature-registry initialization; some lifecycle and diagnostic
hooks have already been installed at that point.

For an allowed decision, `FeatureRegistry` creates the registered feature objects
and invokes their hook setup. It records per-feature failures so one failed setup
can be reported alongside other initialized features. Several features from the
private licensing module also participate. An `ACTIVE` registration status means
setup returned successfully; it does not establish that a method selector matched
the intended behavior or that every callback works on a device.

Package targeting and configured version rules are documented in
[client compatibility](client-compatibility.md). Settings transport and its
permission boundary are documented in
[preference architecture](preference-architecture.md).

## How to extend the existing implementation

For a setting, trace the UI/catalog entry through its actual preference key,
`PreferenceSchema`, manager writer, provider/bridge representation and consuming
hook. Preserve existing persisted keys and supported backup forms. UI labels are
presentation, not storage identifiers.

For a hook, verify the host method's arguments, return type and behavior before
changing parameters or returning early. A string anchor or successful DexKit
lookup identifies a candidate; it does not establish the candidate's meaning.
Keep unsupported paths isolated and keep private message contents, contact
identifiers and media paths out of diagnostics.

Separate tests of pure policy, compiled production code with doubles, Android
framework fixtures, packaged APKs and real host behavior. The public
[source QA workflow](../.github/workflows/source-qa.yml) intentionally supports
checks without the private licensing module or a proprietary host APK. Its
success cannot replace a signed app build or Android/LSPosed acceptance.

## Documentation scope

This snapshot is an implementation map, not a security certification or a complete
feature-acceptance matrix. Future modularization, encrypted storage or a plugin
API should be proposed explicitly, with code and migration work, before being
described as shipped behavior. Build prerequisites and supported test entry
points belong in [CONTRIBUTING.md](../CONTRIBUTING.md).
