/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.mycelium.wallet.ble;

import android.annotation.TargetApi;
import android.app.Activity;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ExpandableListView;
import android.widget.SimpleExpandableListAdapter;
import android.widget.TextView;

import com.google.common.base.Preconditions;
import com.mrd.bitlib.model.Address;
import com.mycelium.wallet.R;
import com.mycelium.wallet.Wallet;
import com.mycelium.wallet.activity.send.SendMainActivity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * For a given BLE device, this Activity provides the user interface to connect, display data,
 * and display GATT services and characteristics supported by the device.  The Activity
 * communicates with {@code BluetoothLeService}, which in turn interacts with the
 * Bluetooth LE API.
 */
public class DeviceControlActivity extends Activity {
    private final static String TAG = DeviceControlActivity.class.getSimpleName();

    public static final String EXTRAS_DEVICE_NAME = "DEVICE_NAME";
    public static final String EXTRAS_DEVICE_ADDRESS = "DEVICE_ADDRESS";

    private TextView mConnectionState;
    private TextView mDataField;
    private String mDeviceName;
    private String mDeviceAddress;
    private ExpandableListView mGattServicesList;
    private BluetoothLeService mBluetoothLeService;
    private ArrayList<ArrayList<BluetoothGattCharacteristic>> mGattCharacteristics =
            new ArrayList<ArrayList<BluetoothGattCharacteristic>>();
    private boolean mConnected = false;
    private BluetoothGattCharacteristic mNotifyCharacteristic;

    private final String LIST_NAME = "NAME";
    private final String LIST_UUID = "UUID";

    private Wallet _wallet;
    private Wallet.SpendableOutputs _spendable;
    private Double _oneBtcInFiat; // May be null
    //todo Andreas refactor this to hold bitocoin Uri
    private Long _amountToSend;
    private Address _receivingAddress;
    private boolean _isColdStorage;
    private volatile int displayDataCount = 1;

    private String bitcoinURI = "";



    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.gatt_services_characteristics);

        final Intent intent = getIntent();
        mDeviceName = intent.getStringExtra(EXTRAS_DEVICE_NAME);
        mDeviceAddress = intent.getStringExtra(EXTRAS_DEVICE_ADDRESS);

        // Sets up UI references.
        ((TextView) findViewById(R.id.device_address)).setText(mDeviceAddress);
        mGattServicesList = (ExpandableListView) findViewById(R.id.gatt_services_list);
        mGattServicesList.setOnChildClickListener(servicesListClickListner);
        mConnectionState = (TextView) findViewById(R.id.connection_state);
        mDataField = (TextView) findViewById(R.id.data_value);

        getActionBar().setTitle(mDeviceName);
        getActionBar().setDisplayHomeAsUpEnabled(true);
        Intent gattServiceIntent = new Intent(this, BluetoothLeService.class);
        bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);

        // Get intent parameters
        _wallet = Preconditions.checkNotNull((Wallet) getIntent().getSerializableExtra("wallet"));
        _spendable = Preconditions.checkNotNull((Wallet.SpendableOutputs) getIntent().getSerializableExtra("spendable"));
        // May be null
        _oneBtcInFiat = (Double) getIntent().getSerializableExtra("oneBtcInFiat");
        // May be null
        _amountToSend = (Long) getIntent().getSerializableExtra("amountToSend");
        // May be null
        _receivingAddress = (Address) getIntent().getSerializableExtra("receivingAddress");
        _isColdStorage = getIntent().getBooleanExtra("isColdStorage", false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
        if (mBluetoothLeService != null) {
            final boolean result = mBluetoothLeService.connect(mDeviceAddress);
            Log.d(TAG, "Connect request result=" + result);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mGattUpdateReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindService(mServiceConnection);
        mBluetoothLeService = null;
    }

    // Code to manage Service lifecycle.
    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
            if (!mBluetoothLeService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth");
                finish();
            }
            // Automatically connects to the device upon successful start-up initialization.
            mBluetoothLeService.connect(mDeviceAddress);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mBluetoothLeService = null;
        }
    };

    // Handles various events fired by the Service.
    // ACTION_GATT_CONNECTED: connected to a GATT server.
    // ACTION_GATT_DISCONNECTED: disconnected from a GATT server.
    // ACTION_GATT_SERVICES_DISCOVERED: discovered GATT services.
    // ACTION_DATA_AVAILABLE: received data from the device.  This can be a result of read
    //                        or notification operations.
    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                mConnected = true;
                updateConnectionState(R.string.connected);
                invalidateOptionsMenu();
            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                mConnected = false;
                updateConnectionState(R.string.disconnected);
                invalidateOptionsMenu();
                clearUI();
            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                // Show all the supported services and characteristics on the user interface.
                displayGattServices(mBluetoothLeService.getSupportedGattServices());
            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
                displayData(intent.getStringExtra(BluetoothLeService.EXTRA_DATA));
            }
        }
    };

    // If a given GATT characteristic is selected, check for supported features.  This sample
    // demonstrates 'Read' and 'Notify' features.  See
    // http://d.android.com/reference/android/bluetooth/BluetoothGatt.html for the complete
    // list of supported characteristic features.
    private final ExpandableListView.OnChildClickListener servicesListClickListner =
            new ExpandableListView.OnChildClickListener() {
                @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
                @Override
                public boolean onChildClick(ExpandableListView parent, View v, int groupPosition,
                                            int childPosition, long id) {
                    if (mGattCharacteristics != null) {
                        final BluetoothGattCharacteristic characteristic =
                                mGattCharacteristics.get(groupPosition).get(childPosition);
                        final int charaProp = characteristic.getProperties();
                        if ((charaProp | BluetoothGattCharacteristic.PROPERTY_READ) > 0) {
                            // If there is an active notification on a characteristic, clear
                            // it first so it doesn't update the data field on the user interface.
                            if (mNotifyCharacteristic != null) {
                                mBluetoothLeService.setCharacteristicNotification(
                                        mNotifyCharacteristic, false);
                                mNotifyCharacteristic = null;
                            }
                            mBluetoothLeService.readCharacteristic(characteristic);
                        }
                        if ((charaProp | BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0) {
                            mNotifyCharacteristic = characteristic;
                            mBluetoothLeService.setCharacteristicNotification(
                                    characteristic, true);
                        }
                        return true;
                    }
                    return false;
                }
    };

    private void clearUI() {
        mGattServicesList.setAdapter((SimpleExpandableListAdapter) null);
        mDataField.setText(R.string.no_data);
    }



    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.gatt_services, menu);
        if (mConnected) {
            menu.findItem(R.id.menu_connect).setVisible(false);
            menu.findItem(R.id.menu_disconnect).setVisible(true);
        } else {
            menu.findItem(R.id.menu_connect).setVisible(true);
            menu.findItem(R.id.menu_disconnect).setVisible(false);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()) {
            case R.id.menu_connect:
                mBluetoothLeService.connect(mDeviceAddress);
                return true;
            case R.id.menu_disconnect:
                mBluetoothLeService.disconnect();
                return true;
            case android.R.id.home:
                onBackPressed();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void updateConnectionState(final int resourceId) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mConnectionState.setText(resourceId);
            }
        });
    }

    private void displayData(String data) {
        if (data != null) {
            // send this data to sendmainactivity somehow
            if(displayDataCount<3) {
                displayDataCount++;
                mDataField.setText(data);
                bitcoinURI = bitcoinURI + data;
                Log.d("DATA<3", data);

            }
            else if(displayDataCount==3) {
                mDataField.setText(data);
                bitcoinURI = bitcoinURI + data;
                Log.d("DATA3", data);
                Log.d("DATA", bitcoinURI);
                displayDataCount = 1;
                if(bitcoinURI.contains("bitcoin")) {
                    Intent sendCoinsRequest = new Intent(this, SendMainActivity.class);
                    sendCoinsRequest.putExtra("BLE_INFO", bitcoinURI);
                    sendCoinsRequest.putExtra("wallet", _wallet);
                    sendCoinsRequest.putExtra("spendable", _spendable);
                    sendCoinsRequest.putExtra("oneBtcInFiat", _oneBtcInFiat);
                    try {
                        double dbCost = Double.parseDouble(bitcoinURI.substring(bitcoinURI.indexOf("=") + 1, bitcoinURI.indexOf("&")));
                        Log.d("COST", bitcoinURI.substring(bitcoinURI.indexOf("=") + 1, bitcoinURI.indexOf("&")));
                        long price = Math.round((dbCost * 100000000));
                        sendCoinsRequest.putExtra("amountToSend", price);
                        int bIndex = bitcoinURI.indexOf("b");
                        int qIndex = bitcoinURI.indexOf("?");
                        Address btcAddress = Address.fromString(bitcoinURI.substring(bitcoinURI.indexOf("b") + 8, bitcoinURI.indexOf("?")));
                        sendCoinsRequest.putExtra("receivingAddress", btcAddress);
                    }
                    // no amount given
                    catch (Exception e) {
                      //  long price = Long.parseLong(null);
                      //  sendCoinsRequest.putExtra("amountToSend", price);
                        int bIndex = bitcoinURI.indexOf("b");
                        int qIndex = bitcoinURI.length();
                        // length - 1 because don't want the & in this
                        Address btcAddress = Address.fromString(bitcoinURI.substring(bitcoinURI.indexOf("b") + 8, qIndex-1));
                        sendCoinsRequest.putExtra("receivingAddress", btcAddress);
                        Log.d("W", "WwW");
                    }

                    sendCoinsRequest.putExtra("isColdStorage", _isColdStorage);
                    startActivity(sendCoinsRequest);
                    Log.d("DATA", "starting new activity");
                    bitcoinURI = "";
                    finish();
                }
                else {
                    // do nothing
                    bitcoinURI = "";
                }
            }
        }
    }

    // Demonstrates how to iterate through the supported GATT Services/Characteristics.
    // In this sample, we populate the data structure that is bound to the ExpandableListView
    // on the UI.
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    private void displayGattServices(List<BluetoothGattService> gattServices) {
        if (gattServices == null) return;
        String uuid = null;
        String unknownServiceString = getResources().getString(R.string.unknown_service);
        String unknownCharaString = getResources().getString(R.string.unknown_characteristic);

        ArrayList<HashMap<String, String>> gattServiceData = new ArrayList<HashMap<String, String>>();

        // some data about the gatt characteristics
        ArrayList<ArrayList<HashMap<String, String>>> gattCharacteristicData
                = new ArrayList<ArrayList<HashMap<String, String>>>();

        // actual gatt characteristics
        mGattCharacteristics = new ArrayList<ArrayList<BluetoothGattCharacteristic>>();

        // Loops through available GATT Services.
        for (BluetoothGattService gattService : gattServices) {

            // put service data uuid in display
            HashMap<String, String> currentServiceData = new HashMap<String, String>();
            uuid = gattService.getUuid().toString();
            currentServiceData.put(
                    LIST_NAME, SampleGattAttributes.lookup(uuid, unknownServiceString));
            currentServiceData.put(LIST_UUID, uuid);
            gattServiceData.add(currentServiceData);

            // gets characteristics & their data
            ArrayList<HashMap<String, String>> gattCharacteristicGroupData =
                    new ArrayList<HashMap<String, String>>();
            List<BluetoothGattCharacteristic> gattCharacteristics =
                    gattService.getCharacteristics();
            ArrayList<BluetoothGattCharacteristic> charas =
                    new ArrayList<BluetoothGattCharacteristic>();

            // if we find the bitcoin service and its characteristic, read it w/ bluetoothLeService
            if (uuid.equals("230f04b4-42ff-4ce9-94cb-ed0dc8238447")) {
                Log.d("DATA", "1st step done");
                String bitcoinUri = "";

                // get the bitcoin payment req. uuid
                final BluetoothGattCharacteristic char1 = gattService.getCharacteristic(UUID.fromString("230f04b4-42ff-4ce9-94cb-ed0dc8231957"));
                final BluetoothGattCharacteristic char2 = gattService.getCharacteristic(UUID.fromString("230f04b4-42ff-4ce9-94cb-ed0dc8231958"));
                final BluetoothGattCharacteristic char3 = gattService.getCharacteristic(UUID.fromString("230f04b4-42ff-4ce9-94cb-ed0dc8231959"));





                  Thread thread = new Thread() {
                      @Override
                      public void run() {
                          BluetoothGattCharacteristic[] gatts = new BluetoothGattCharacteristic[3];
                          gatts[0] = char1;
                          gatts[1] = char2;
                          gatts[2] = char3;
                          try {
                              for (int x=0; x<3; x++) {
                                  int charaProp = gatts[x].getProperties();
                                  Log.d("HMM", "round ");

                                  if ((charaProp | BluetoothGattCharacteristic.PROPERTY_READ) > 0) {
                                      // If there is an active notification on a characteristic, clear
                                      // it first so it doesn't update the data field on the user interface.
                                      if (mNotifyCharacteristic != null) {
                                          mBluetoothLeService.setCharacteristicNotification(
                                                  mNotifyCharacteristic, false);
                                          mNotifyCharacteristic = null;
                                      }
                                      mBluetoothLeService.readCharacteristic(gatts[x]);
                                  }
                                  if ((charaProp | BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0) {
                                      mNotifyCharacteristic = gatts[x];
                                      mBluetoothLeService.setCharacteristicNotification(
                                              gatts[x], true);
                                  }
                                  this.sleep(500);
                              }
                          }
                          catch (Exception e) {
                              e.printStackTrace();
                          }
                      }
                  };
                  thread.start();


            }

            // Loops through available Characteristics.
            // adds characteristic uuid to the gattchar group data
            for (BluetoothGattCharacteristic gattCharacteristic : gattCharacteristics) {
                charas.add(gattCharacteristic);
                HashMap<String, String> currentCharaData = new HashMap<String, String>();
                uuid = gattCharacteristic.getUuid().toString();
                currentCharaData.put(
                        LIST_NAME, SampleGattAttributes.lookup(uuid, unknownCharaString));
                currentCharaData.put(LIST_UUID, uuid);
                gattCharacteristicGroupData.add(currentCharaData);
            }




            // add the characteristic data information
            mGattCharacteristics.add(charas);
            gattCharacteristicData.add(gattCharacteristicGroupData);
        }

        // display all of this stuff on the screen (service and characteristic data)
        SimpleExpandableListAdapter gattServiceAdapter = new SimpleExpandableListAdapter(
                this,
                gattServiceData,
                android.R.layout.simple_expandable_list_item_2,
                new String[] {LIST_NAME, LIST_UUID},
                new int[] { android.R.id.text1, android.R.id.text2 },
                gattCharacteristicData,
                android.R.layout.simple_expandable_list_item_2,
                new String[] {LIST_NAME, LIST_UUID},
                new int[] { android.R.id.text1, android.R.id.text2 }
        );
        mGattServicesList.setAdapter(gattServiceAdapter);
    }

    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        return intentFilter;
    }
}


