# Consumer ProGuard rules for lib module
-keep class com.asyncanimator.** { *; }
# Keep rules do not resolve duplicate classes from an OEM DynamicAnimation fork.
# Select one compatible implementation in the consuming dependency graph; see docs/build-environment.md.
