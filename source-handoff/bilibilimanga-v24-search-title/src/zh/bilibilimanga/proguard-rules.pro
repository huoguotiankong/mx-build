# MX host/extension ABI classes are intentionally bundled as compatibility stubs.
# Their binary names and method signatures must survive R8 so MX can resolve the
# same namespace parent-first from the host class loader.
-keep class eu.kanade.tachiyomi.source.mx.** { *; }
-keep interface eu.kanade.tachiyomi.source.mx.** { *; }
