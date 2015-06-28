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

package com.mycelium.wallet.activity.main;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.TextView;

import com.google.common.base.Preconditions;
import com.mrd.mbwapi.api.ExchangeRate;
import com.mycelium.wallet.BalanceInfo;
import com.mycelium.wallet.Constants;
import com.mycelium.wallet.ExchangeRateManager;
import com.mycelium.wallet.MbwManager;
import com.mycelium.wallet.R;
import com.mycelium.wallet.RecordManager;
import com.mycelium.wallet.Utils;
import com.mycelium.wallet.Wallet;
import com.mycelium.wallet.activity.modern.Toaster;
import com.mycelium.wallet.activity.receive.ReceiveCoinsActivity;
import com.mycelium.wallet.activity.send.SendInitializationActivity;
import com.mycelium.wallet.event.BlockchainError;
import com.mycelium.wallet.event.BlockchainReady;
import com.mycelium.wallet.event.RecordSetChanged;
import com.mycelium.wallet.event.SelectedRecordChanged;
import com.squareup.otto.Subscribe;

public class BalanceFragment extends Fragment {

   private MbwManager _mbwManager;
   private RecordManager _recordManager;
   private View _root;
   // private BalanceInfo _balance;
   private ExchangeRate _exchangeRate;
   private Toaster _toaster;

   @Override
   public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
      _root = Preconditions.checkNotNull(inflater.inflate(R.layout.main_balance_view, container, false));
      final View balanceArea = Preconditions.checkNotNull(_root.findViewById(R.id.llBalance));
      balanceArea.setOnClickListener(new OnClickListener() {
         @Override
         public void onClick(View v) {
            _mbwManager.getSyncManager().triggerUpdate();
         }
      });
      return _root;
   }

   @Override
   public void onCreate(Bundle savedInstanceState) {
      setHasOptionsMenu(true);
      setRetainInstance(true);
      super.onCreate(savedInstanceState);
   }

   @Override
   public void onAttach(Activity activity) {
      _mbwManager = MbwManager.getInstance(getActivity());
      _recordManager = _mbwManager.getRecordManager();
      _toaster = new Toaster(activity);
      super.onAttach(activity);
   }

   @Override
   public void onDetach() {
      super.onDetach();
   }

   @Override
   public void onResume() {
      _mbwManager.getExchamgeRateManager().subscribe(excahngeSubscriber);
      _exchangeRate = _mbwManager.getExchamgeRateManager().getExchangeRate();
      if (_exchangeRate == null || _exchangeRate.price == null) {
         _mbwManager.getExchamgeRateManager().requestRefresh();
      }
      _mbwManager.getEventBus().register(this);
      _root.findViewById(R.id.btSend).setOnClickListener(sendClickListener);
      _root.findViewById(R.id.btReceive).setOnClickListener(receiveClickListener);
      // _root.findViewById(R.id.btTrade).setOnClickListener(tradeClickListener);
      updateUi();
      super.onResume();
   }

   OnClickListener sendClickListener = new OnClickListener() {

      @Override
      public void onClick(View arg0) {
         SendInitializationActivity.callMe(BalanceFragment.this.getActivity(), getWallet(), false,
               _exchangeRate == null ? null : (_exchangeRate.price == null ? null : _exchangeRate.price));
      }
   };

   OnClickListener receiveClickListener = new OnClickListener() {

      @Override
      public void onClick(View arg0) {
         ReceiveCoinsActivity.callMe(getActivity(), _recordManager.getSelectedRecord());
      }
   };

   private Wallet getWallet() {
      return _recordManager.getWallet(_mbwManager.getWalletMode());
   }

   @Override
   public void onPause() {
      _mbwManager.getExchamgeRateManager().unsubscribe(excahngeSubscriber);
      _mbwManager.getEventBus().unregister(this);
      super.onPause();
   }

   private void updateUi() {
      if (!isAdded()) {
         return;
      }
      Wallet wallet = getWallet();
      BalanceInfo balance = wallet.getLocalBalance(_mbwManager.getBlockChainAddressTracker());

      if (wallet.canSpend()) {
         // Show spend button
         _root.findViewById(R.id.btSend).setVisibility(View.VISIBLE);
      } else {
         // Hide spend button
         _root.findViewById(R.id.btSend).setVisibility(View.GONE);
      }

      if (balance == null) {
         return;
      }

      if (balance.isKnown()) {
         updateUiKnownBalance(wallet, balance);
      } else {
         updateUiUnknownBalance();
      }

      // Set BTC rate
      if (_exchangeRate == null) {
         // No rate, will probably get one soon
         _root.findViewById(R.id.tvBtcRate).setVisibility(View.INVISIBLE);
      } else if (_exchangeRate.price == null) {
         // We have no price, exchange not available
         TextView tvBtcRate = (TextView) _root.findViewById(R.id.tvBtcRate);
         tvBtcRate.setVisibility(View.VISIBLE);
         tvBtcRate.setText(getResources().getString(R.string.exchange_source_not_available, _exchangeRate.name));
      } else {
         TextView tvBtcRate = (TextView) _root.findViewById(R.id.tvBtcRate);
         tvBtcRate.setVisibility(View.VISIBLE);
         String currency = _mbwManager.getFiatCurrency();
         String converted = Utils.getFiatValueAsString(Constants.ONE_BTC_IN_SATOSHIS, _exchangeRate.price);
         tvBtcRate.setText(getResources().getString(R.string.btc_rate, currency, converted, _exchangeRate.name));
      }

      // // Hide/Show Local Trader trade button
      // LocalTraderManager ltManager = _mbwManager.getLocalTraderManager();
      // if (ltManager.isLocalTraderDisabled()) {
      // _root.findViewById(R.id.llLocalTrader).setVisibility(View.GONE);
      // } else {
      // _root.findViewById(R.id.llLocalTrader).setVisibility(View.VISIBLE);
      // // Local Trader update dot
      // _root.findViewById(R.id.ivDot).setVisibility(
      // ltManager.hasLocalTraderAccount() &&
      // ltManager.needsTraderSynchronization() ? View.VISIBLE : View.GONE);
      // }
   }

   private void updateUiKnownBalance(Wallet wallet, BalanceInfo balance) {

      // Set Balance
      ((TextView) _root.findViewById(R.id.tvBalance)).setText(_mbwManager.getBtcValueString(balance.unspent
            + balance.pendingChange));

      // Set balance fiat value
      setFiatValue(R.id.tvFiat, balance.unspent + balance.pendingChange, false);

      // Show/Hide Receiving
      // todo de-duplicate code
      if (balance.pendingReceiving > 0) {
         String receivingString = _mbwManager.getBtcValueString(balance.pendingReceiving);
         String receivingText = getResources().getString(R.string.receiving, receivingString);
         TextView tvReceiving = (TextView) _root.findViewById(R.id.tvReceiving);
         tvReceiving.setText(receivingText);
         tvReceiving.setVisibility(View.VISIBLE);
      } else {
         _root.findViewById(R.id.tvReceiving).setVisibility(View.GONE);
      }
      setFiatValue(R.id.tvReceivingFiat, balance.pendingReceiving, true);

      // Show/Hide Sending
      // todo de-duplicate code
      if (balance.pendingSending > 0) {
         String sendingString = _mbwManager.getBtcValueString(balance.pendingSending);
         String sendingText = getResources().getString(R.string.sending, sendingString);
         TextView tvSending = (TextView) _root.findViewById(R.id.tvSending);
         tvSending.setText(sendingText);
         tvSending.setVisibility(View.VISIBLE);
      } else {
         _root.findViewById(R.id.tvSending).setVisibility(View.GONE);
      }
      setFiatValue(R.id.tvSendingFiat, balance.pendingSending, true);

   }

   private void setFiatValue(int textViewResourceId, long satoshis, boolean hideOnZeroBalance) {
      TextView tv = (TextView) _root.findViewById(textViewResourceId);
      if (_exchangeRate == null || _exchangeRate.price == null || (hideOnZeroBalance && satoshis == 0)) {
         tv.setVisibility(View.GONE);
      } else {
         tv.setVisibility(View.VISIBLE);

         String converted = Utils.getFiatValueAsString(satoshis, _exchangeRate.price);
         String currency = _mbwManager.getFiatCurrency();
         tv.setText(getResources().getString(R.string.approximate_fiat_value, currency, converted));
      }

   }

   private void updateUiUnknownBalance() {

      // Show "Tap to Refresh" instead of balance
      ((TextView) _root.findViewById(R.id.tvBalance)).setText(R.string.tap_to_refresh);

      // Hide Receiving
      _root.findViewById(R.id.tvSending).setVisibility(View.GONE);

      // Hide Sending
      _root.findViewById(R.id.tvSending).setVisibility(View.GONE);

      // Set Fiat value
      _root.findViewById(R.id.tvFiat).setVisibility(View.INVISIBLE);
   }

   private ExchangeRateManager.EventSubscriber excahngeSubscriber = new ExchangeRateManager.EventSubscriber(
         new Handler()) {

      @Override
      public void refreshingExchangeRatesFailed() {
         _toaster.toastConnectionError();
         _exchangeRate = null;
      }

      @Override
      public void refreshingEcahngeRatesSuccedded() {
         _exchangeRate = _mbwManager.getExchamgeRateManager().getExchangeRate();
         updateUi();
      }
   };

   @Subscribe
   public void balanceChanged(BlockchainReady blockchainReady) {
      updateUi();
   }

   @Subscribe
   public void onBlockchainError(BlockchainError exception) {
      _toaster.toastConnectionError();
   }

   /**
    * Fires when record set changed
    */
   @Subscribe
   public void recordSetChanged(RecordSetChanged event) {
      updateUi();
   }

   /**
    * Fires when the selected record changes
    */
   @Subscribe
   public void selectedRecordChanged(SelectedRecordChanged event) {
      updateUi();
   }

}
