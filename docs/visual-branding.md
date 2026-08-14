# Visual branding boundary

AIOS keeps upstream package names, Java/Kotlin namespaces, service identities,
source paths, and compatibility identifiers unchanged. The Pixel hardware lane
applies static, platform-signed resource overlays to replace user-visible
upstream branding in Setup Wizard, Settings, and the framework.

The overlays deliberately use neutral labels such as `Privacy proxy` for
services still backed by an upstream endpoint. This avoids presenting those
services as operated by AIOS. Internal resource identifiers containing an
upstream project name are not displayed and remain stable for easy rebasing.

The transparent Setup Wizard icon is derived from the original AIOS boot emblem.
English is the source locale and fallback for the currently supported English
and Spanish product configuration; there are no upstream Spanish variants for
the replaced custom strings in the pinned release.
