# Offline voice typing (mytop-project/tracker#343): the downloaded sherpa-onnx JNI library looks up these
# classes, their constructors and their fields by name, so they keep their names and members.
-keep class com.k2fsa.sherpa.onnx.** { *; }
