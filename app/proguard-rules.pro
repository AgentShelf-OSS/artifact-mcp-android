# The app uses no reflection-based serialization; kotlinx.serialization is used only through
# its JsonElement tree API. Media3 and AndroidX ship their own consumer rules.
-dontwarn org.slf4j.**
