# Widget taps name their ActionCallback class; Glance creates it by name, so keep those classes.
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }
