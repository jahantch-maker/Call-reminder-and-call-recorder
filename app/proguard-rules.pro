# Widget taps name their ActionCallback class; Glance creates it by name, so keep those classes.
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }

# Google sign-in (Credential Manager) finds its Play services part by name.
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }
