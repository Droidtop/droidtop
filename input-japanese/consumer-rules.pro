# Mozc (Droidtop/tracker#903 H7c). libmozc.so binds its natives to this exact class: initialize by its JNI symbol
# name, evalCommand, onPostLoad and getDataVersion by RegisterNatives with their names (mozcjni.cc). Both stay.
-keep class com.google.android.apps.inputmethod.libs.mozc.session.MozcJni {
    native <methods>;
}
# Mozc's protocol, generated as protobuf lite: the lite runtime reads each message's fields by name.
-keepclassmembers class org.mozc.android.inputmethod.japanese.protobuf.** extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
