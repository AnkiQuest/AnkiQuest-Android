# SPDX-License-Identifier: AGPL-3.0-or-later
# The shared rules' UniFFI bindings (org.ankiquest.rules) call their library through JNA, which
# finds the native methods, structures and callbacks by reflection. JNA references java.awt, which
# Android doesn't have.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.ankiquest.rules.** { *; }
-dontwarn java.awt.**
