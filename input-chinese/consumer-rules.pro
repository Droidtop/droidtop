# RIME (Droidtop/tracker#903 H8b). libdroidtop_rime.so registers its natives by this class's name and theirs
# (rime_jni.cc, JNI_OnLoad). Both stay.
-keep class dev.droidtop.input.chinese.RimeJni {
    native <methods>;
}
