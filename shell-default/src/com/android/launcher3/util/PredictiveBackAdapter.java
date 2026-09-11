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

import android.app.Activity;
import android.os.Build;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.util.function.Supplier;

/**
 * The one class in droidtop that names android.window's predictive-back
 * types. It is loaded only from {@link #register}, which every caller
 * reaches behind a Build.VERSION check, so no class-load of it can happen
 * below API 34 -- the property that keeps the launcher activity loadable
 * back to API 26 (see {@link BackGesture} for the crash this prevents).
 *
 * Semantics match AOSP's own launcher: a gesture picks its handler once, at
 * the start, and every later sample of that same gesture goes to that
 * handler, so a sheet that closes mid-swipe cannot hand the rest of the
 * gesture to a different target.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
public final class PredictiveBackAdapter implements OnBackAnimationCallback {

    private final Supplier<BackCallback> mHandlerSource;

    private BackCallback mActiveHandler;

    private PredictiveBackAdapter(Supplier<BackCallback> handlerSource) {
        mHandlerSource = handlerSource;
    }

    /**
     * Registers a predictive-back callback for the activity that asks
     * handlerSource for the handler of each new gesture.
     *
     * Callers must guard this with a check for API 34 or newer; it is that
     * guard, not this method, which keeps the framework types off the
     * calling class's own load path.
     */
    public static void register(Activity activity, Supplier<BackCallback> handlerSource) {
        activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                new PredictiveBackAdapter(handlerSource));
    }

    private static BackGesture toGesture(BackEvent event) {
        return new BackGesture(event.getTouchX(), event.getTouchY(), event.getProgress(),
                event.getSwipeEdge());
    }

    private BackCallback activeHandler() {
        if (mActiveHandler == null) {
            mActiveHandler = mHandlerSource.get();
        }
        return mActiveHandler;
    }

    @Override
    public void onBackStarted(@NonNull BackEvent backEvent) {
        if (mActiveHandler != null) {
            // A previous gesture was never finished; do not leave it mid-animation.
            mActiveHandler.onBackCancelled();
            mActiveHandler = null;
        }
        activeHandler().onBackStarted(toGesture(backEvent));
    }

    @Override
    public void onBackProgressed(@NonNull BackEvent backEvent) {
        activeHandler().onBackProgressed(toGesture(backEvent));
    }

    @Override
    public void onBackInvoked() {
        activeHandler().onBackInvoked();
        mActiveHandler = null;
    }

    @Override
    public void onBackCancelled() {
        if (mActiveHandler != null) {
            mActiveHandler.onBackCancelled();
            mActiveHandler = null;
        }
    }
}
