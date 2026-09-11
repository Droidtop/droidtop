/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.launcher3.util;

/**
 * What a back gesture is handed to inside the launcher: the same four
 * callbacks as {@link android.window.OnBackAnimationCallback}, expressed in
 * types that exist on droidtop's minSdk (26).
 *
 * Views, state handlers and the activity itself implement THIS. The
 * framework interface is named in exactly one place,
 * {@link PredictiveBackAdapter}, and only reached above API 34 -- see
 * {@link BackGesture} for why that separation is load-bearing rather than
 * stylistic.
 *
 * Below API 34 the animation half simply never fires: the platform delivers
 * a plain back invocation (or a KEYCODE_BACK press), which lands on
 * {@link #onBackInvoked()} the same way it always did.
 */
public interface BackCallback {

    /** Called when a back gesture starts. Animation-capable platforms only. */
    default void onBackStarted(BackGesture gesture) { }

    /** Called on each progress sample of a back gesture. Animation-capable platforms only. */
    default void onBackProgressed(BackGesture gesture) { }

    /** Called when back is committed. Fires on every API level. */
    void onBackInvoked();

    /** Called when a started back gesture is abandoned. Animation-capable platforms only. */
    default void onBackCancelled() { }
}
