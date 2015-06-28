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

package com.mycelium.wallet;

import java.util.LinkedList;
import java.util.List;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.os.Handler;

import com.mrd.bitlib.util.ByteReader;
import com.mrd.bitlib.util.ByteWriter;
import com.mrd.bitlib.util.HexUtils;
import com.mrd.mbwapi.api.ApiException;
import com.mrd.mbwapi.api.ApiObject;
import com.mrd.mbwapi.api.ExchangeRate;
import com.mrd.mbwapi.api.MyceliumWalletApi;
import com.mrd.mbwapi.api.QueryExchangeRatesRequest;
import com.mrd.mbwapi.api.QueryExchangeRatesResponse;

public class ExchangeRateManager {

   private static final int MAX_RATE_AGE_MS = 1000 * 60;
   private static final String EXCHANGE_DATA = "exchange_rate_data";

   public static abstract class EventSubscriber {

      private Handler _handler;

      public EventSubscriber(Handler handler) {
         _handler = handler;
      }

      public Handler getHandler() {
         return _handler;
      }

      public abstract void refreshingEcahngeRatesSuccedded();

      public abstract void refreshingExchangeRatesFailed();
   }

   private Context _applicationContext;
   private MyceliumWalletApi _api;
   private MbwManager _mbwManager;
   private QueryExchangeRatesResponse _latestRates;
   private String _currentRateName;
   private long _latestRatesTime;
   private volatile Fetcher _fetcher;
   private Object _requestLock;
   private List<EventSubscriber> _subscribers;

   public ExchangeRateManager(Context applicationContext, MyceliumWalletApi api, MbwManager manager) {
      _applicationContext = applicationContext;
      _api = api;
      _mbwManager = manager;
      SharedPreferences settings = _applicationContext.getSharedPreferences(EXCHANGE_DATA, Activity.MODE_PRIVATE);

      // Load the latest rates if we have them
      String latestRatesString = settings.getString("latestRates", null);
      if (latestRatesString != null) {
         try {
            byte[] bytes = HexUtils.toBytes(latestRatesString);
            _latestRates = ApiObject.deserialize(QueryExchangeRatesResponse.class, new ByteReader(bytes));
         } catch (RuntimeException e) {
            // ignore, let the last rates remain unknown
         } catch (ApiException e) {
            // ignore, let the last rates remain unknown
         }
      }

      // Load latest rates time
      _latestRatesTime = settings.getLong("latestRatesTime", 0);

      // Load the name of the current exchange rate
      _currentRateName = settings.getString("currentRateName", null);

      _requestLock = new Object();

      _subscribers = new LinkedList<EventSubscriber>();
   }

   public synchronized void subscribe(EventSubscriber subscriber) {
      _subscribers.add(subscriber);
   }

   public synchronized void unsubscribe(EventSubscriber subscriber) {
      _subscribers.remove(subscriber);
   }

   private class Fetcher implements Runnable {
      public void run() {
         try {
            QueryExchangeRatesResponse response = _api.queryExchangeRates(new QueryExchangeRatesRequest(_mbwManager
                  .getFiatCurrency()));
            synchronized (_requestLock) {
               setLatestRates(response);
               _fetcher = null;
               notifyRefreshingExchangeRatesSucceeded();
            }
         } catch (ApiException e) {
            // we failed to get the exchange rate
            synchronized (_requestLock) {
               _fetcher = null;
               notifyRefreshingExchangeRatesFailed();
            }
         }
      }
   }

   private synchronized void notifyRefreshingExchangeRatesSucceeded() {
      for (final EventSubscriber s : _subscribers) {
         s.getHandler().post(new Runnable() {

            @Override
            public void run() {
               s.refreshingEcahngeRatesSuccedded();
            }
         });
      }
   }

   private synchronized void notifyRefreshingExchangeRatesFailed() {
      for (final EventSubscriber s : _subscribers) {
         s.getHandler().post(new Runnable() {

            @Override
            public void run() {
               s.refreshingExchangeRatesFailed();
            }
         });
      }
   }

   public void requestRefresh() {
      synchronized (_requestLock) {
         if (_fetcher == null) {
            _fetcher = new Fetcher();
            Thread t = new Thread(_fetcher);
            t.setDaemon(true);
            t.start();
         } else {
            // request already in progress
            return;
         }
      }
   }

   private synchronized void setLatestRates(QueryExchangeRatesResponse latestRates) {
      _latestRates = latestRates;
      _latestRatesTime = System.currentTimeMillis();

      if (_currentRateName == null) {
         // This only happens the first time the wallet picks up exchange rates.
         // We will default to the first one in the list
         if (_latestRates.exchangeRates.length > 0) {
            _currentRateName = _latestRates.exchangeRates[0].name;
         }
      }
      // Turn latest rates into a string
      ByteWriter writer = new ByteWriter(2048);
      latestRates.serialize(writer);
      String latestRatesString = HexUtils.toHex(writer.toBytes());

      // Persist
      Editor editor = getEditor();
      editor.putString("latestRates", latestRatesString);
      editor.putLong("latestRatesTime", _latestRatesTime);
      editor.commit();
   }

   /**
    * Get the name of the current exchange rate. May be null the first time the
    * app is running
    */
   public String getCurrentRateName() {
      return _currentRateName;
   }

   /**
    * Get the names of the currently available exchange rates. May be empty the
    * first time the app is running
    */
   public List<String> getExchangeRateNames() {
      QueryExchangeRatesResponse latestRates = _latestRates;
      List<String> result = new LinkedList<String>();
      if (latestRates != null) {
         for (ExchangeRate r : latestRates.exchangeRates) {
            result.add(r.name);
         }
      }
      return result;
   }

   public synchronized void setCurrentRateName(String name) {
      _currentRateName = name;
      getEditor().putString("currentRateName", _currentRateName).commit();
   }

   /**
    * Get the exchange rate for the currently selected currency.
    * <p>
    * Returns null if the current rate is too old or for a different currency.
    * In that the case the caller could choose to call refreshRates() and listen
    * for callbacks. If a rate is returned the contained price may be null if
    * the currently chosen exchange source is not available.
    */
   public synchronized ExchangeRate getExchangeRate() {
      String currency = _mbwManager.getFiatCurrency();
      if (_latestRates == null || !currency.equals(_latestRates.currency)
            || _latestRatesTime + MAX_RATE_AGE_MS < System.currentTimeMillis()) {
         return null;
      }
      for (ExchangeRate r : _latestRates.exchangeRates) {
         if (r.name.equals(_currentRateName)) {
            return r;
         }
      }
      if (_currentRateName != null) {
         // We end up here if the exchange is no longer on the list
         return new ExchangeRate(_currentRateName, System.currentTimeMillis(), currency, null);
      }
      return null;
   }

   private SharedPreferences.Editor getEditor() {
      return _applicationContext.getSharedPreferences(EXCHANGE_DATA, Activity.MODE_PRIVATE).edit();
   }

}
