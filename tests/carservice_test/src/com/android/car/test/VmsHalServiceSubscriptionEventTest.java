/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.car.test;

import static org.junit.Assume.assumeTrue;

import android.car.VehicleAreaType;
import android.car.annotation.FutureFeature;
import android.car.vms.VmsLayer;
import android.hardware.automotive.vehicle.V2_0.VehiclePropValue;
import android.hardware.automotive.vehicle.V2_0.VehiclePropertyAccess;
import android.hardware.automotive.vehicle.V2_0.VehiclePropertyChangeMode;
import android.hardware.automotive.vehicle.V2_1.VehicleProperty;
import android.hardware.automotive.vehicle.V2_1.VmsSimpleMessageIntegerValuesIndex;
import android.hardware.automotive.vehicle.V2_1.VmsMessageType;
import android.hardware.automotive.vehicle.V2_1.VmsSubscriptionResponseFormat;
import android.test.suitebuilder.annotation.MediumTest;

import com.android.car.vehiclehal.VehiclePropValueBuilder;
import com.android.car.vehiclehal.test.MockedVehicleHal;
import com.android.car.vehiclehal.test.MockedVehicleHal.VehicleHalPropertyHandler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@FutureFeature
@MediumTest
public class VmsHalServiceSubscriptionEventTest extends MockedCarTestBase {
    private static final String TAG = "VmsHalServiceTest";

    private HalHandler mHalHandler;
    private MockedVehicleHal mHal;
    // Used to block until the HAL property is updated in HalHandler.onPropertySet.
    private Semaphore mHalHandlerSemaphore;

    @Override
    protected synchronized void configureMockedHal() {
        mHalHandler = new HalHandler();
        addProperty(VehicleProperty.VEHICLE_MAP_SERVICE, mHalHandler)
                .setChangeMode(VehiclePropertyChangeMode.ON_CHANGE)
                .setAccess(VehiclePropertyAccess.READ_WRITE)
                .setSupportedAreas(VehicleAreaType.VEHICLE_AREA_TYPE_NONE);
    }

    @Override
    protected void setUp() throws Exception {
        if (!VmsTestUtils.canRunTest(TAG)) return;
        super.setUp();
        mHal = getMockedVehicleHal();
        mHalHandlerSemaphore = new Semaphore(0);
    }

    @Override
    protected synchronized void tearDown() throws Exception {
        if (!VmsTestUtils.canRunTest(TAG)) return;
        super.tearDown();
    }

    public void testEmptySubscriptions() throws Exception {
        if (!VmsTestUtils.canRunTest(TAG)) return;
        List<VmsLayer> layers = new ArrayList<>();
        subscriptionTestLogic(layers);
    }

    public void testOneSubscription() throws Exception {
        if (!VmsTestUtils.canRunTest(TAG)) return;
        List<VmsLayer> layers = Arrays.asList(new VmsLayer(8, 3));
        subscriptionTestLogic(layers);
    }

    public void testManySubscriptions() throws Exception {
        if (!VmsTestUtils.canRunTest(TAG)) return;
        List<VmsLayer> layers = Arrays.asList(
                new VmsLayer(8, 3),
                new VmsLayer(5, 1),
                new VmsLayer(3, 9),
                new VmsLayer(2, 7),
                new VmsLayer(9, 3));
        subscriptionTestLogic(layers);
    }

    /**
     * First, it subscribes to the given layers. Then it validates that a subscription request
     * responds with the same layers.
     */
    private void subscriptionTestLogic(List<VmsLayer> layers) throws Exception {
        for (VmsLayer layer : layers) {
            subscribeViaHal(layer);
        }
        // Send subscription request.
        mHal.injectEvent(createHalSubscriptionRequest());
        // Wait for response.
        assertTrue(mHalHandlerSemaphore.tryAcquire(2L, TimeUnit.SECONDS));
        // Validate response.
        ArrayList<Integer> v = mHalHandler.getValues();
        int messageType = v.get(VmsSubscriptionResponseFormat.VMS_MESSAGE_TYPE);
        int sequenceNumber = v.get(VmsSubscriptionResponseFormat.SEQUENCE_NUMBER);
        int numberLayers = v.get(VmsSubscriptionResponseFormat.NUMBER_OF_LAYERS);
        assertEquals(VmsMessageType.SUBSCRIPTION_RESPONSE, messageType);
        assertEquals(layers.size(), sequenceNumber);
        assertEquals(layers.size(), numberLayers);
        List<VmsLayer> receivedLayers = new ArrayList<>();
        int start = VmsSubscriptionResponseFormat.FIRST_LAYER;
        int end = VmsSubscriptionResponseFormat.FIRST_LAYER + 2 * numberLayers;
        while (start < end) {
            int id = v.get(start++);
            int version = v.get(start++);
            receivedLayers.add(new VmsLayer(id, version));
        }
        assertEquals(new HashSet<>(layers), new HashSet<>(receivedLayers));
    }

    /**
     * Subscribes to a layer, waits for the event to propagate back to the HAL layer and validates
     * the propagated message.
     */
    private void subscribeViaHal(VmsLayer layer) throws Exception {
        // Send subscribe request.
        mHal.injectEvent(createHalSubscribeRequest(layer));
        // Wait for response.
        assertTrue(mHalHandlerSemaphore.tryAcquire(2L, TimeUnit.SECONDS));
        // Validate response.
        ArrayList<Integer> v = mHalHandler.getValues();
        int messsageType = v.get(VmsSimpleMessageIntegerValuesIndex.VMS_MESSAGE_TYPE);
        int layerId = v.get(VmsSimpleMessageIntegerValuesIndex.VMS_LAYER_ID);
        int layerVersion = v.get(VmsSimpleMessageIntegerValuesIndex.VMS_LAYER_VERSION);
        assertEquals(VmsMessageType.SUBSCRIBE, messsageType);
        assertEquals(layer.getId(), layerId);
        assertEquals(layer.getVersion(), layerVersion);
    }

    private VehiclePropValue createHalSubscribeRequest(VmsLayer layer) {
        return VehiclePropValueBuilder.newBuilder(VehicleProperty.VEHICLE_MAP_SERVICE)
                .addIntValue(VmsMessageType.SUBSCRIBE)
                .addIntValue(layer.getId())
                .addIntValue(layer.getVersion())
                .build();
    }

    private VehiclePropValue createHalSubscriptionRequest() {
        return VehiclePropValueBuilder.newBuilder(VehicleProperty.VEHICLE_MAP_SERVICE)
                .addIntValue(VmsMessageType.SUBSCRIPTION_REQUEST)
                .build();
    }

    private class HalHandler implements VehicleHalPropertyHandler {
        private ArrayList<Integer> mValues;

        @Override
        public synchronized void onPropertySet(VehiclePropValue value) {
            mValues = value.value.int32Values;
            mHalHandlerSemaphore.release();
        }

        public ArrayList<Integer> getValues() {
            return mValues;
        }
    }
}
