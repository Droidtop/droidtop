/*
 * Copyright (C) 2025 The Android Open Source Project
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

package android.os

import androidx.annotation.RequiresApi
import com.android.launcher3.Utilities
import com.android.launcher3.util.Executors.MAIN_EXECUTOR
import com.android.launcher3.util.WeakCleanupSet
import com.android.launcher3.util.WeakCleanupSet.OnOwnerDestroyedCallback

/** Utility methods related to Binder */
object BinderUtils {

    /** Creates a binder wrapper which is tied to the [lifecycle] */
    @JvmStatic
    fun <T : Binder> T.wrapLifecycle(cleanupSet: WeakCleanupSet): Binder =
        if (Utilities.ATLEAST_Q) LifecycleBinderWrapper(this, cleanupSet, interfaceDescriptor)
        else LifecycleBinderWrapper(this, cleanupSet)

    private class LifecycleBinderWrapper<T : Binder> : Binder, OnOwnerDestroyedCallback {

        private var realBinder: T?

        /**
         * Pre-29 construction. Binder's descriptor constructor is API 29,
         * and which super constructor a class calls is decided when it is
         * compiled, so the descriptor is simply not carried there. It is a
         * diagnostic label: queryLocalInterface below still answers from
         * the wrapped binder either way.
         */
        constructor(realBinder: T?, cleanupSet: WeakCleanupSet) : super() {
            this.realBinder = realBinder
            register(cleanupSet)
        }

        @RequiresApi(Build.VERSION_CODES.Q)
        constructor(
            realBinder: T?,
            cleanupSet: WeakCleanupSet,
            descriptor: String?,
        ) : super(descriptor) {
            this.realBinder = realBinder
            register(cleanupSet)
        }

        private fun register(cleanupSet: WeakCleanupSet) {
            MAIN_EXECUTOR.execute { cleanupSet.addOnOwnerDestroyedCallback(this) }
        }

        override fun queryLocalInterface(descriptor: String): IInterface? =
            realBinder?.queryLocalInterface(descriptor)

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
            realBinder?.transact(code, data, reply, flags)
                ?: throw RemoteException("Original binder cleaned up")

        override fun onOwnerDestroyed() {
            realBinder = null
        }
    }
}
