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
 * droidtop's own value type for one sample of a predictive-back gesture: a
 * field-for-field mirror of {@link android.window.BackEvent}, which only
 * exists from API 33 (and whose animation callback interface only from 34).
 *
 * Why this type exists rather than passing BackEvent around: droidtop's
 * minSdk is 26, and a framework type used in a class's own shape --
 * superclass, implemented interface, field type, method parameter or return
 * type -- is resolved when that class is LOADED, not when the code using it
 * runs. No Build.VERSION check can guard that. The launcher activity carried
 * android.window.OnBackAnimationCallback in exactly that position and so
 * could not be loaded at all below API 34: a real crash, build 497 on
 * Android 9, java.lang.NoClassDefFoundError at
 * AppComponentFactory.instantiateActivity with droidtop set as HOME.
 *
 * Everything in the launcher therefore speaks {@link BackCallback} and this
 * type; the only class that names the framework interfaces is
 * {@link PredictiveBackAdapter}, which is instantiated behind a version
 * check and so is never loaded below API 34.
 */
public final class BackGesture {

    /** Mirrors {@link android.window.BackEvent#EDGE_LEFT}. */
    public static final int EDGE_LEFT = 0;
    /** Mirrors {@link android.window.BackEvent#EDGE_RIGHT}. */
    public static final int EDGE_RIGHT = 1;

    private final float mTouchX;
    private final float mTouchY;
    private final float mProgress;
    private final int mSwipeEdge;

    public BackGesture(float touchX, float touchY, float progress, int swipeEdge) {
        mTouchX = touchX;
        mTouchY = touchY;
        mProgress = progress;
        mSwipeEdge = swipeEdge;
    }

    /** Absolute X location of the touch point of this event. */
    public float getTouchX() {
        return mTouchX;
    }

    /** Absolute Y location of the touch point of this event. */
    public float getTouchY() {
        return mTouchY;
    }

    /** Progress of the back gesture, 0 to 1. */
    public float getProgress() {
        return mProgress;
    }

    /** Either {@link #EDGE_LEFT} or {@link #EDGE_RIGHT}. */
    public int getSwipeEdge() {
        return mSwipeEdge;
    }
}
