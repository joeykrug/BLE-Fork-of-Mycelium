/*
 * Copyright 2013 Megion Research and Development GmbH
 *
 * Licensed under the Microsoft Reference Source License (MS-RSL)
 *
 * This license governs use of the accompanying software. If you use the software, you accept this license.
 * If you do not accept the license, do not use the software.
 *
 * 1. Definitions
 * The terms "reproduce," "reproduction," and "distribution" have the same meaning here as under U.S. copyright law.
 * "You" means the licensee of the software.
 * "Your company" means the company you worked for when you downloaded the software.
 * "Reference use" means use of the software within your company as a reference, in read only form, for the sole purposes
 * of debugging your products, maintaining your products, or enhancing the interoperability of your products with the
 * software, and specifically excludes the right to distribute the software outside of your company.
 * "Licensed patents" means any Licensor patent claims which read directly on the software as distributed by the Licensor
 * under this license.
 *
 * 2. Grant of Rights
 * (A) Copyright Grant- Subject to the terms of this license, the Licensor grants you a non-transferable, non-exclusive,
 * worldwide, royalty-free copyright license to reproduce the software for reference use.
 * (B) Patent Grant- Subject to the terms of this license, the Licensor grants you a non-transferable, non-exclusive,
 * worldwide, royalty-free patent license under licensed patents for reference use.
 *
 * 3. Limitations
 * (A) No Trademark License- This license does not grant you any rights to use the Licensor’s name, logo, or trademarks.
 * (B) If you begin patent litigation against the Licensor over patents that you think may apply to the software
 * (including a cross-claim or counterclaim in a lawsuit), your license to the software ends automatically.
 * (C) The software is licensed "as-is." You bear the risk of using it. The Licensor gives no express warranties,
 * guarantees or conditions. You may have additional consumer rights under your local laws which this license cannot
 * change. To the extent permitted under your local laws, the Licensor excludes the implied warranties of merchantability,
 * fitness for a particular purpose and non-infringement.
 */

package com.mycelium.wallet.activity.receive;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import com.mycelium.wallet.ble.*;

import com.google.common.base.Preconditions;
import com.mrd.bitlib.util.CoinUtil;
import com.mycelium.wallet.MbwManager;
import com.mycelium.wallet.R;
import com.mycelium.wallet.Record;
import com.mycelium.wallet.Utils;
import com.mycelium.wallet.activity.util.QrImageView;
import com.mycelium.wallet.ble.BluetoothUtility;

import android.bluetooth.le.*;

import java.util.UUID;

public class ReceiveCoinsActivity extends Activity {

   private static final int GET_AMOUNT_RESULT_CODE = 1;
   private static final int REQUEST_ENABLE_BT = 11;

   private MbwManager _mbwManager;
   private Record receivingAddress;
   private Long _amount;
   BluetoothUtility ble;
   private ReceiveCoinsActivity thisClass = this;

   private String serviceOneCharUuid;
   private String url = "";
   private static final String SERVICE_UUID_1 = "00001802-0000-1000-8000-00805f9b34fb";


    public static void callMe(Activity currentActivity, Record record) {
      Intent intent = new Intent(currentActivity, ReceiveCoinsActivity.class);
      intent.putExtra("record", record);
      currentActivity.startActivity(intent);
   }

   /**
    * Called when the activity is first created.
    */
   @Override
   public void onCreate(Bundle savedInstanceState) {
      this.requestWindowFeature(Window.FEATURE_NO_TITLE);
      this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
      super.onCreate(savedInstanceState);
      setContentView(R.layout.receive_coins_activity);

      _mbwManager = MbwManager.getInstance(getApplication());

      // Get intent parameters
      receivingAddress = Preconditions.checkNotNull((Record) getIntent().getSerializableExtra("record"));

      // Load saved state
      if (savedInstanceState != null) {
         _amount = savedInstanceState.getLong("amount", -1);
         if (_amount == -1) {
            _amount = null;
         }
      }

      // Enter Amount
      findViewById(R.id.btEnterAmount).setOnClickListener(amountClickListener);

      // Amount Hint
      ((TextView) findViewById(R.id.tvAmount)).setHint(getResources().getString(R.string.amount_hint_denomination,
            _mbwManager.getBitcoinDenomination().toString()));
   }

   @Override
   protected void onSaveInstanceState(Bundle outState) {
      if (_amount != null) {
         outState.putLong("amount", _amount);
      }
      super.onSaveInstanceState(outState);
   }

   @Override
   protected void onResume() {
      updateUi();
       BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
       if (mBluetoothAdapter == null) {
          // device doesn't support bluetooth
       } else {
           if (!mBluetoothAdapter.isEnabled()) {
               Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
               startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
           }
       }
       if(isBLESupported(this)) {
           Handler handler = new Handler();
           handler.postDelayed(new Runnable() {
               @Override
               public void run() {
                   ble = new BluetoothUtility(thisClass);

                   ble.setAdvertiseCallback(advertiseCallback);
                   ble.setGattServerCallback(gattServerCallback);

                   addServiceToGattServer();


                   ble.startAdvertise();
                   Log.d("BLE", "started advertising");

               }
           }, 2500);

       }
      super.onResume();
   }

    /** check if BLE Supported device */
    public static boolean isBLESupported(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
    }

    private AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onSuccess(AdvertiseSettings advertiseSettings) {
            String successMsg = "Advertisement command attempt successful";
            Log.d("BLE", successMsg);
        }

        @Override
        public void onFailure(int i) {
            String failMsg = "Advertisement command attempt failed: " + i;
            Log.e("BLE", failMsg);
        }
    };

    public BluetoothGattServerCallback gattServerCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            super.onConnectionStateChange(device, status, newState);
            Log.d("BLE", "onConnectionStateChange status=" + status + "->" + newState);
        }

        @Override
        public void onServiceAdded(int status, BluetoothGattService service) {
            super.onServiceAdded(status, service);
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic);
            Log.d("BLE", "onCharacteristicReadRequest requestId=" + requestId + " offset=" + offset);
            String partOne;
            String partTwo;
            String partThree;

            try {

                String url = thisClass.getPaymentUri() + "&";
                String bitcoinURI = url.substring(url.indexOf("b"), url.indexOf("&")+1);
                Log.d("BLE", bitcoinURI);

                if(bitcoinURI.contains("=")) {
                    // first 19 chars
                    partOne = bitcoinURI.substring(bitcoinURI.indexOf("b"), bitcoinURI.indexOf("b") + 19);
                    // next 19 chars
                    partTwo = bitcoinURI.substring(bitcoinURI.indexOf("b") + 19, bitcoinURI.indexOf("b") + 38);
                    // last set of chars (20)
                    partThree = bitcoinURI.substring(bitcoinURI.indexOf("b") + 38, bitcoinURI.indexOf("&") + 1);

                }
                // else case has no amount on it
                else {
                    // first 19 chars
                    partOne = bitcoinURI.substring(bitcoinURI.indexOf("b"), bitcoinURI.indexOf("b") + 19);
                    // next 19 chars
                    partTwo = bitcoinURI.substring(bitcoinURI.indexOf("b") + 19, bitcoinURI.indexOf("b") + 38);
                    // last set of chars (20)
                    partThree = bitcoinURI.substring(bitcoinURI.indexOf("b") + 38, bitcoinURI.length());
                }


                Log.d("WOAH", partOne+partTwo+partThree);

                if (characteristic.getUuid().equals(UUID.fromString("230f04b4-42ff-4ce9-94cb-ed0dc8231957"))) {
                    characteristic.setValue(partOne);
                    ble.getGattServer().sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                            characteristic.getValue());
                }
                if (characteristic.getUuid().equals(UUID.fromString("230f04b4-42ff-4ce9-94cb-ed0dc8231958"))) {
                    characteristic.setValue(partTwo);
                    ble.getGattServer().sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                            characteristic.getValue());
                }
                if (characteristic.getUuid().equals(UUID.fromString("230f04b4-42ff-4ce9-94cb-ed0dc8231959"))) {
                    characteristic.setValue(partThree);
                    ble.getGattServer().sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                            characteristic.getValue());
                }

            }
            catch (Exception e) {
                // uri not created yet
            }
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value);
            Log.d("BLE", "onCharacteristicWriteRequest requestId=" + requestId + " preparedWrite="
                    + Boolean.toString(preparedWrite) + " responseNeeded="
                    + Boolean.toString(responseNeeded) + " offset=" + offset);
        }
    };
   private void updateUi() {
      final String address = receivingAddress.address.toString();
      final String qrText = getPaymentUri();
      Log.d("QR", qrText);

      if (_amount == null) {
         ((TextView) findViewById(R.id.tvTitle)).setText(R.string.bitcoin_address_title);
         ((Button) findViewById(R.id.btShare)).setText(R.string.share_bitcoin_address);
         ((TextView) findViewById(R.id.tvAmountLabel)).setText(R.string.optional_amount);
         ((TextView) findViewById(R.id.tvAmount)).setText("");
      } else {
         ((TextView) findViewById(R.id.tvTitle)).setText(R.string.payment_request);
         ((Button) findViewById(R.id.btShare)).setText(R.string.share_payment_request);
         ((TextView) findViewById(R.id.tvAmountLabel)).setText(R.string.amount_title);
         ((TextView) findViewById(R.id.tvAmount)).setText(_mbwManager.getBtcValueString(_amount));
      }

      // QR code
      QrImageView iv = (QrImageView) findViewById(R.id.ivQrCode);
      iv.setQrCode(qrText);

      // Show warning if the record has no private key
      if (receivingAddress.hasPrivateKey()) {
         findViewById(R.id.tvWarning).setVisibility(View.GONE);
      } else {
         findViewById(R.id.tvWarning).setVisibility(View.VISIBLE);
      }

      String[] addressStrings = Utils.stringChopper(address, 12);
      ((TextView) findViewById(R.id.tvAddress1)).setText(addressStrings[0]);
      ((TextView) findViewById(R.id.tvAddress2)).setText(addressStrings[1]);
      ((TextView) findViewById(R.id.tvAddress3)).setText(addressStrings[2]);

      // Only show "Show to Sender" splash to non experts
      if (_mbwManager.getExpertMode()) {
         findViewById(R.id.tvSplash).setVisibility(View.INVISIBLE);
      } else {
         Utils.fadeViewInOut(findViewById(R.id.tvSplash));
      }
      updateAmount();
   }

   private void updateAmount() {
      if (_amount == null) {
         // No amount to show
         ((TextView) findViewById(R.id.tvAmount)).setText("");
      } else {
         // Set Amount
         ((TextView) findViewById(R.id.tvAmount)).setText(_mbwManager.getBtcValueString(_amount));
      }
   }

   private String getPaymentUri() {
      final StringBuilder uri = new StringBuilder("bitcoin:" + receivingAddress.address.toString());
      if (_amount != null) {
         uri.append("?amount=").append(CoinUtil.valueString(_amount, false));
      }
      // XXX: For now we are not encoding the label. It makes the QR-code harder
      // to scan and should also be URI encoded
      // final String label =
      // getAddressBookManager().getNameByAddress(receivingAddress.address.toString());
      // if (label != null && label.length() > 0) {
      // uri.append("&label=").append(label);
      // }
      return uri.toString();
   }

   private String getBitcoinAddress() {
      return receivingAddress.address.toString();
   }

   public void shareRequest(View view) {
      if (_amount == null) {
         Intent s = new Intent(android.content.Intent.ACTION_SEND);
         s.setType("text/plain");
         s.putExtra(Intent.EXTRA_SUBJECT, getResources().getString(R.string.bitcoin_address_title));
         s.putExtra(Intent.EXTRA_TEXT, getBitcoinAddress());
         startActivity(Intent.createChooser(s, getResources().getString(R.string.share_bitcoin_address)));
      } else {
         Intent s = new Intent(android.content.Intent.ACTION_SEND);
         s.setType("text/plain");
         s.putExtra(Intent.EXTRA_SUBJECT, getResources().getString(R.string.payment_request));
         s.putExtra(Intent.EXTRA_TEXT, getPaymentUri());
         startActivity(Intent.createChooser(s, getResources().getString(R.string.share_payment_request)));
      }
   }

   public void copyToClipboard(View view) {
      String text;
      if (_amount == null) {
         text = getBitcoinAddress();
      } else {
         text = getPaymentUri();
      }
      Utils.setClipboardString(text, this);
      Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
   }

   public void onActivityResult(final int requestCode, final int resultCode, final Intent intent) {
      if (requestCode == GET_AMOUNT_RESULT_CODE && resultCode == RESULT_OK) {
         // Get result from address chooser (may be null)
         _amount = (Long) intent.getSerializableExtra("amount");
      } else {
         // We didn't like what we got, bail
      }
   }

   private OnClickListener amountClickListener = new OnClickListener() {

      @Override
      public void onClick(View arg0) {
         GetReceivingAmountActivity.callMe(ReceiveCoinsActivity.this, _amount, GET_AMOUNT_RESULT_CODE);
      }
   };

    @Override
    public void onDestroy() {
        try {
            ble.cleanUp();
        }
        catch (Exception e) {
            // was already cleaned up in on stop
        }
        super.onDestroy();
    }

    @Override
    public void onPause() {
        try {
            ble.cleanUp();
        }
        catch (Exception e) {
            // was already cleaned up in on stop
        }
        super.onPause();
    }

    private void addServiceToGattServer() {
        serviceOneCharUuid = UUID.randomUUID().toString();
        BluetoothGattService firstService = new BluetoothGattService(
                UUID.fromString(SERVICE_UUID_1),
                BluetoothGattService.SERVICE_TYPE_PRIMARY);
        // alert level char.
        BluetoothGattCharacteristic firstServiceChar = new BluetoothGattCharacteristic(
                UUID.fromString(serviceOneCharUuid),
                BluetoothGattCharacteristic.PROPERTY_READ |
                        BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ |
                        BluetoothGattCharacteristic.PERMISSION_WRITE);
        firstService.addCharacteristic(firstServiceChar);
        ble.addService(firstService);
    }


}