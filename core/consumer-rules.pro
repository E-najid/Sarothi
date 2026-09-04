# Native bridges are resolved by name through JNI; keep the entry points.
-keep class com.ngi.sarothi.core.runtime.NativeBridge { *; }
-keep class com.ngi.sarothi.core.runtime.NativeBridge$TokenCallback { *; }

# Gson reflects over data classes used for manifests, plans and plugin results.
-keepattributes Signature, *Annotation*, EnclosingMethod
-keep class com.ngi.sarothi.core.storage.model.** { *; }
-keep class com.ngi.sarothi.core.agent.model.** { *; }
-keep class com.ngi.sarothi.core.plugin.model.** { *; }

# ML Kit ships its own consumer rules; nothing extra required.
