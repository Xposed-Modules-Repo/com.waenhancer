# WAEX client targeting and version rules

This document describes the public implementation checked at
`f6ec7d4e6f47a246ebb413848d66358217fa9c08` on 18 October 2026. A declared target,
a matching version rule, successful hook registration and functional device
acceptance are separate observations.

## Package scope

[DeclaredWhatsAppTargets.java](../app/src/main/java/com/waenhancer/utils/DeclaredWhatsAppTargets.java)
contains an exact, case-sensitive package allowlist used by
[WhatsAppPackageDetector.kt](../app/src/main/java/com/waenhancer/utils/WhatsAppPackageDetector.kt).
It includes `com.whatsapp`, `com.whatsapp.w4b` and explicitly named alternative
packages. Arbitrary names containing `whatsapp` are not accepted by this rule.

The list mirrors the target entries in
[arrays.xml](../app/src/main/res/values/arrays.xml). That resource also includes
`com.waenhancer` for the manager's own Xposed handling. Enable the manager and the
installed target in the framework's module scope. `android` (System Framework)
is not a declared target in this tree.

This is package-based targeting, not verification of the host signing
certificate, installer origin or authenticity. Inclusion of an alternative
package does not certify its publisher or prove that its obfuscated internals
match another client. The configured minimum APK SDK also does not constitute an
Android-version acceptance matrix.

## Version evaluation

[VersionGuard.kt](../app/src/main/java/com/waenhancer/xposed/core/VersionGuard.kt)
reads the target's `PackageInfo.versionName` and calls
[UniversalVersionValidator.kt](../app/src/main/java/com/waenhancer/utils/UniversalVersionValidator.kt).
It does not compare `longVersionCode` against a registry loaded from a Markdown
file. The earlier proposed `WaexVersionGate` and `WaexVersionRegistry` classes are
not the current implementation.

The validator reads `universal_supported_versions` from the WAEX package's
resources, including when called inside a target process. At the documented
commit, the built-in rules are `2.26.36.xx` through `2.26.41.xx`.
[arrays.xml](../app/src/main/res/values/arrays.xml) is authoritative when those
rules change. Rules may be exact strings or branch patterns ending in `.xx` or
`.*`; they are string/prefix matches, not a numeric minimum/maximum interval.

| Decision | Feature initialization allowed? | Meaning |
|---|---|---|
| `BUILTIN_RULE` | Yes | The version matches a rule packaged with WAEX. |
| `CUSTOM_RULE` | Yes | Custom version rules are enabled and a user-supplied rule matches. |
| `EXPLICIT_BYPASS` | Yes | The stored `bypass_version_check` setting is enabled. |
| `UNSUPPORTED` | No | Available rules do not match the version. |
| `RULES_UNAVAILABLE` | No | Built-in rules could not be loaded/validated and no allowed override matched. |
| `VERSION_UNAVAILABLE` | No | The target version is absent or blank. |
| `PREFERENCES_UNAVAILABLE` | No | Reading compatibility preferences raised a handled runtime/linkage error. |

The evaluator first requires a nonblank version, then checks an explicit bypass,
built-in rules and enabled custom rules in that order. Custom rules come from
`custom_supported_versions` or its legacy string representation. Overrides are
implemented behavior; they must be reported as such and do not prove
compatibility. An unavailable rule or preference read does not automatically
activate a bypass.

## Effect on initialization and diagnostics

[MainHook.java](../app/src/main/java/com/waenhancer/xposed/MainHook.java) invokes
`verifyAndGuard` after preference hydration and before constructing
`FeatureRegistry`. If the guard returns false, feature initialization is paused
and a compatibility notice can be shown on a target activity. Lifecycle hooks,
preference setup and diagnostic reporting may already exist. The manager remains
available to inspect the problem and review settings.

Runtime reporting distinguishes `UNSUPPORTED_VERSION` from
`COMPATIBILITY_UNAVAILABLE`. The manager also records bridge source and injection
observations; see
[InjectionEvidence.kt](../app/src/main/java/com/waenhancer/utils/InjectionEvidence.kt)
and [the runtime diagnostics checks](../qa/test_runtime_diagnostics.py).
A missing toast, installed framework-manager package or old injection record
alone does not establish that the current target process is working.

## Evidence required for a compatibility change

Record the WAEX commit/build type, host package and exact version, APK identity
when available, Android version and loaded hook framework. Check the affected
method's signature and behavior, then exercise the feature on an authorized test
account. Repeat relevant cases on Debug and optimized Release builds. Resource
shrinking and code optimization can change runtime lookup behavior even when
source-level tests pass.

Use [test_declared_targets.py](../qa/test_declared_targets.py),
[test_runtime_diagnostics.py](../qa/test_runtime_diagnostics.py) and
[verify_runtime_framework.py](../qa/verify_runtime_framework.py) for the
corresponding public contracts. These tests cover their declared source/framework
scenarios; changing the version array or obtaining green CI does not validate all
features on a newly admitted host.
