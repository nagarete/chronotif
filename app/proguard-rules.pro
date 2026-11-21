# Framework-only app; components are kept via the manifest.

# Usage data's crash reports name Chronotif's classes, methods and lines, so they're readable
# without a mapping file. Shrinking and optimising still apply.
-keepattributes SourceFile,LineNumberTable
-keepnames class app.chronotif.** { *; }
