/*
 * Copyright (C) 2016 The Android Open Source Project
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
package android.car.cluster.renderer;

import android.annotation.CallSuper;
import android.annotation.MainThread;
import android.annotation.SystemApi;
import android.app.Service;
import android.car.CarLibLog;
import android.car.navigation.CarNavigationInstrumentCluster;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.util.Pair;
import android.view.KeyEvent;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;

/**
 * A service that used for interaction between Car Service and Instrument Cluster. Car Service may
 * provide internal navigation binder interface to Navigation App and all notifications will be
 * eventually land in the {@link NavigationRenderer} returned by {@link #getNavigationRenderer()}.
 *
 * <p>To extend this class, you must declare the service in your manifest file with
 * the {@code android.car.permission.BIND_INSTRUMENT_CLUSTER_RENDERER_SERVICE} permission
 * <pre>
 * &lt;service android:name=".MyInstrumentClusterService"
 *          android:permission="android.car.permission.BIND_INSTRUMENT_CLUSTER_RENDERER_SERVICE">
 * &lt;/service></pre>
 * <p>Also, you will need to register this service in the following configuration file:
 * {@code packages/services/Car/service/res/values/config.xml}
 *
 * @hide
 */
@SystemApi
public abstract class InstrumentClusterRenderingService extends Service {

    private static final String TAG = CarLibLog.TAG_CLUSTER;

    private RendererBinder mRendererBinder;

    @Override
    @CallSuper
    public IBinder onBind(Intent intent) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onBind, intent: " + intent);
        }

        if (mRendererBinder == null) {
            mRendererBinder = new RendererBinder(getNavigationRenderer());
        }

        return mRendererBinder;
    }

    /** Returns {@link NavigationRenderer} or null if it's not supported. */
    @MainThread
    protected abstract NavigationRenderer getNavigationRenderer();

    /** Called when key event that was addressed to instrument cluster display has been received. */
    @MainThread
    protected void onKeyEvent(KeyEvent keyEvent) {
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.println("**" + getClass().getSimpleName() + "**");
        writer.println("renderer binder: " + mRendererBinder);
        if (mRendererBinder != null) {
            writer.println("navigation renderer: " + mRendererBinder.mNavigationRenderer);
            String owner = "none";
            if (mRendererBinder.mNavContextOwner != null) {
                owner = "[uid: " + mRendererBinder.mNavContextOwner.first
                        + ", pid: " + mRendererBinder.mNavContextOwner.second + "]";
            }
            writer.println("navigation focus owner: " + owner);
        }
    }

    private class RendererBinder extends IInstrumentCluster.Stub {

        private final NavigationRenderer mNavigationRenderer;
        private final UiHandler mUiHandler;

        private volatile NavigationBinder mNavigationBinder;
        private volatile Pair<Integer, Integer> mNavContextOwner;

        RendererBinder(NavigationRenderer navigationRenderer) {
            mNavigationRenderer = navigationRenderer;
            mUiHandler = new UiHandler(InstrumentClusterRenderingService.this);
        }

        @Override
        public IInstrumentClusterNavigation getNavigationService() throws RemoteException {
            if (mNavigationBinder == null) {
                mNavigationBinder = new NavigationBinder(mNavigationRenderer);
                if (mNavContextOwner != null) {
                    mNavigationBinder.setNavigationContextOwner(
                            mNavContextOwner.first, mNavContextOwner.second);
                }
            }
            return mNavigationBinder;
        }

        @Override
        public void setNavigationContextOwner(int uid, int pid) throws RemoteException {
            mNavContextOwner = new Pair<>(uid, pid);
            if (mNavigationBinder != null) {
                mNavigationBinder.setNavigationContextOwner(uid, pid);
            }
        }

        @Override
        public void onKeyEvent(KeyEvent keyEvent) throws RemoteException {
            mUiHandler.doKeyEvent(keyEvent);
        }
    }

    private class NavigationBinder extends IInstrumentClusterNavigation.Stub {

        private final NavigationRenderer mNavigationRenderer;  // Thread-safe navigation renderer.

        private volatile Pair<Integer, Integer> mNavContextOwner;

        NavigationBinder(NavigationRenderer navigationRenderer) {
            mNavigationRenderer = ThreadSafeNavigationRenderer.createFor(
                    Looper.getMainLooper(),
                    navigationRenderer);
        }

        void setNavigationContextOwner(int uid, int pid) {
            mNavContextOwner = new Pair<>(uid, pid);
        }

        @Override
        public void onStartNavigation() throws RemoteException {
            assertContextOwnership();
            mNavigationRenderer.onStartNavigation();
        }

        @Override
        public void onStopNavigation() throws RemoteException {
            assertContextOwnership();
            mNavigationRenderer.onStopNavigation();
        }

        @Override
        public void onNextManeuverChanged(int event, CharSequence eventName, int turnAngle,
                int turnNumber, Bitmap image, int turnSide) throws RemoteException {
            assertContextOwnership();
            mNavigationRenderer.onNextTurnChanged(event, eventName, turnAngle, turnNumber,
                    image, turnSide);
        }

        @Override
        public void onNextManeuverDistanceChanged(int distanceMeters, int timeSeconds,
                int displayDistanceMillis, int displayDistanceUnit) throws RemoteException {
            assertContextOwnership();
            mNavigationRenderer.onNextTurnDistanceChanged(distanceMeters, timeSeconds,
                    displayDistanceMillis, displayDistanceUnit);
        }

        @Override
        public CarNavigationInstrumentCluster getInstrumentClusterInfo() throws RemoteException {
            return mNavigationRenderer.getNavigationProperties();
        }

        private void assertContextOwnership() {
            int uid = getCallingUid();
            int pid = getCallingPid();

            Pair<Integer, Integer> owner = mNavContextOwner;
            if (owner == null || owner.first != uid || owner.second != pid) {
                throw new IllegalStateException("Client (uid:" + uid + ", pid: " + pid + ") is"
                        + "not an owner of APP_CONTEXT_NAVIGATION");
            }
        }
    }

    private static class UiHandler extends Handler {
        private static int KEY_EVENT = 0;
        private final WeakReference<InstrumentClusterRenderingService> mRefService;

        UiHandler(InstrumentClusterRenderingService service) {
            mRefService = new WeakReference<>(service);
        }

        @Override
        public void handleMessage(Message msg) {
            InstrumentClusterRenderingService service = mRefService.get();
            if (service == null) {
                return;
            }

            if (msg.what == KEY_EVENT) {
                service.onKeyEvent((KeyEvent) msg.obj);
            } else {
                throw new IllegalArgumentException("Unexpected message: " + msg);
            }
        }

        void doKeyEvent(KeyEvent event) {
            sendMessage(obtainMessage(KEY_EVENT, event));
        }
    }
}
