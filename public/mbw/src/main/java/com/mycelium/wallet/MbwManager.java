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

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.widget.Toast;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;
import com.mrd.bitlib.crypto.MrdExport;
import com.mrd.bitlib.model.NetworkParameters;
import com.mrd.bitlib.util.CoinUtil;
import com.mrd.bitlib.util.CoinUtil.Denomination;
import com.mycelium.wallet.activity.modern.ExploreHelper;
import com.mycelium.wallet.api.AndroidApiCache;
import com.mycelium.wallet.api.AndroidAsyncApi;
import com.mycelium.wallet.api.ApiCache;
import com.mycelium.wallet.event.SelectedRecordChanged;
import com.mycelium.wallet.lt.LocalTraderManager;
import com.mycelium.wallet.persistence.TradeSessionDb;
import com.mycelium.wallet.persistence.TxOutDb;
import com.squareup.otto.Bus;

import java.util.Locale;

public class MbwManager {

   public static final String PROXY_HOST = "socksProxyHost";
   public static final String PROXY_PORT = "socksProxyPort";
   private static volatile MbwManager _instance = null;

   public static synchronized MbwManager getInstance(Context context) {
      if (_instance == null) {
         _instance = new MbwManager(context);
      }
      return _instance;
   }

   private final Bus _eventBus;
   private final SyncManager _syncManager;
   private NetworkConnectionWatcher _connectionWatcher;
   private Context _applicationContext;
   private int _displayWidth;
   private int _displayHeight;
   private ApiCache _cache;
   private AndroidAsyncApi _asyncApi;
   private RecordManager _recordManager;
   private AddressBookManager _addressBookManager;
   private LocalTraderManager _localTraderManager;
   private BlockChainAddressTracker _blockChainAddressTracker;
   private final String _btcValueFormatString;
   private String _pin;
   private Denomination _bitcoinDenomination;
   private WalletMode _walletMode;
   private String _fiatCurrency;
   private boolean _expertMode;
   private boolean _enableContinuousFocus;
   private boolean _keyManagementLocked;
   private int _mainViewFragmentIndex;
   private MrdExport.V1.EncryptionParameters _cachedEncryptionParameters;
   private final MrdExport.V1.ScryptParameters _deviceScryptParameters;
   private MbwEnvironment _environment;
   private final ExploreHelper exploreHelper;
   private HttpErrorCollector _httpErrorCollector;
   private String _language;
   private final VersionManager _versionManager;
   private final ExchangeRateManager _exchangeRateManager;

   private MbwManager(Context evilContext) {
      _applicationContext = Preconditions.checkNotNull(evilContext.getApplicationContext());
      _environment = MbwEnvironment.determineEnvironment(_applicationContext);
      String version = VersionManager.determineVersion(_applicationContext);

      _httpErrorCollector = HttpErrorCollector.registerInVM(_applicationContext, version, _environment.getMwsApi());

      if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.GINGERBREAD) {
         // Disable HTTP keep-alive on systems predating Gingerbread
         System.setProperty("http.keepAlive", "false");
      }

      _eventBus = new Bus();

      // Preferences
      SharedPreferences preferences = getPreferences();
      setProxy(preferences.getString(Constants.PROXY_SETTING, ""));
      // Initialize proxy early, to enable error reporting during startup..

      _connectionWatcher = new NetworkConnectionWatcher(_applicationContext);
      _cache = new AndroidApiCache(_applicationContext);
      TxOutDb txOutDb = new TxOutDb(_applicationContext);
      _asyncApi = new AndroidAsyncApi(_environment.getMwsApi(), _cache, _eventBus);
      _recordManager = new RecordManager(_applicationContext, _eventBus, _environment.getNetwork());

      // Local Trader
      TradeSessionDb tradeSessionDb = new TradeSessionDb(_applicationContext);
      _localTraderManager = new LocalTraderManager(_applicationContext, _recordManager, tradeSessionDb,
            _environment.getLocalTraderApi(), this);

      _btcValueFormatString = _applicationContext.getResources().getString(R.string.btc_value_string);

      _pin = preferences.getString(Constants.PIN_SETTING, "");
      _walletMode = WalletMode.fromInteger(preferences.getInt(Constants.WALLET_MODE_SETTING,
            Constants.DEFAULT_WALLET_MODE.asInteger()));
      _fiatCurrency = preferences.getString(Constants.FIAT_CURRENCY_SETTING, Constants.DEFAULT_CURRENCY);
      _bitcoinDenomination = Denomination.fromString(preferences.getString(Constants.BITCOIN_DENOMINATION_SETTING,
            Denomination.mBTC.toString()));
      _expertMode = preferences.getBoolean(Constants.EXPERT_MODE_SETTING, getExpertModeDefault());
      _enableContinuousFocus = preferences.getBoolean(Constants.ENABLE_CONTINUOUS_FOCUS_SETTING,
            getContinuousFocusDefault());
      _keyManagementLocked = preferences.getBoolean(Constants.KEY_MANAGEMENT_LOCKED_SETTING, false);
      _mainViewFragmentIndex = preferences.getInt(Constants.MAIN_VIEW_FRAGMENT_INDEX_SETTING, 0);

      // Get the display metrics of this device
      DisplayMetrics dm = new DisplayMetrics();
      WindowManager windowManager = (WindowManager) _applicationContext.getSystemService(Context.WINDOW_SERVICE);
      windowManager.getDefaultDisplay().getMetrics(dm);
      _displayWidth = dm.widthPixels;
      _displayHeight = dm.heightPixels;

      _blockChainAddressTracker = new BlockChainAddressTracker(_asyncApi, txOutDb, _applicationContext, _eventBus,
            _environment.getNetwork());
      _addressBookManager = new AddressBookManager(_applicationContext, _eventBus);
      exploreHelper = new ExploreHelper();
      _language = preferences.getString(Constants.LANGUAGE_SETTING, Locale.getDefault().getLanguage());
      _versionManager = new VersionManager(_applicationContext, _language, _asyncApi, version);
      _syncManager = new SyncManager(_eventBus, this, _recordManager, _blockChainAddressTracker, _versionManager);
      _exchangeRateManager = new ExchangeRateManager(_applicationContext, _environment.getMwsApi(), this);


      // Check the device MemoryClass and set the scrypt-parameters for the PDF backup
      ActivityManager am = (ActivityManager) _applicationContext.getSystemService(Context.ACTIVITY_SERVICE);
      int memoryClass = am.getMemoryClass();

      _deviceScryptParameters = memoryClass > 20
              ? MrdExport.V1.ScryptParameters.DEFAULT_PARAMS
              : MrdExport.V1.ScryptParameters.LOW_MEM_PARAMS;
   }

   /**
    * This helper method allows us to set sane default for users who installed
    * the wallet before we added expert mode
    */
   private boolean getExpertModeDefault() {
      // There is more than one record, so we default to expert mode
      // Do not enable expert mode by default
      return _recordManager.numRecords() > 1;
   }

   private boolean getContinuousFocusDefault() {
      if (Build.MODEL.equals("Nexus 4")) {
         // Disabled for Nexus 4
         return false;
      }
      // We may need to disable it for other models as well. TBD.
      return true;
   }

   public ApiCache getCache() {
      return _cache;
   }

   public int getDisplayWidth() {
      return _displayWidth;
   }

   public NetworkConnectionWatcher getNetworkConnectionWatcher() {
      return _connectionWatcher;
   }

   public int getDisplayHeight() {
      return _displayHeight;
   }

   public String getFiatCurrency() {
      return _fiatCurrency;
   }

   public void setFiatCurrency(String currency) {
      _fiatCurrency = currency;
      SharedPreferences.Editor editor = getEditor();
      editor.putString(Constants.FIAT_CURRENCY_SETTING, _fiatCurrency);
      editor.commit();
   }

   public WalletMode getWalletMode() {
      return _walletMode;
   }

   private SharedPreferences getPreferences() {
      return _applicationContext.getSharedPreferences(Constants.SETTINGS_NAME, Activity.MODE_PRIVATE);
   }

   private SharedPreferences.Editor getEditor() {
      return getPreferences().edit();
   }

   public void setWalletMode(WalletMode mode) {
      _walletMode = mode;
      SharedPreferences.Editor editor = getEditor();
      editor.putInt(Constants.WALLET_MODE_SETTING, _walletMode.asInteger());
      editor.commit();
      _eventBus.post(new SelectedRecordChanged(_recordManager.getSelectedRecord()));
   }

   public AndroidAsyncApi getAsyncApi() {
      return _asyncApi;
   }

   public LocalTraderManager getLocalTraderManager() {
      return _localTraderManager;
   }

   public RecordManager getRecordManager() {
      return _recordManager;
   }

   public ExchangeRateManager getExchamgeRateManager() {
      return _exchangeRateManager;
   }

   public AddressBookManager getAddressBookManager() {
      return _addressBookManager;
   }

   public BlockChainAddressTracker getBlockChainAddressTracker() {
      return _blockChainAddressTracker;
   }

   public boolean isPinProtected() {
      return getPin().length() > 0;
   }

   private String getPin() {
      return _pin;
   }

   public void setPin(String pin) {
      _pin = pin;
      getEditor().putString(Constants.PIN_SETTING, _pin).commit();
   }

   public void runPinProtectedFunction(final Context context, final Runnable fun) {
      if (isPinProtected()) {
         Dialog d = new PinDialog(context, true, new PinDialog.OnPinEntered() {

            @Override
            public void pinEntered(PinDialog dialog, String pin) {
               if (pin.equals(getPin())) {
                  dialog.dismiss();
                  fun.run();
               } else {
                  Toast.makeText(context, R.string.pin_invalid_pin, Toast.LENGTH_LONG).show();
                  vibrate(500);
                  dialog.dismiss();
               }
            }
         });
         d.setTitle(R.string.pin_enter_pin);
         d.show();
      } else {
         fun.run();
      }
   }

   public void vibrate() {
      vibrate(500);
   }

   public void vibrate(int milliseconds) {
      Vibrator v = (Vibrator) _applicationContext.getSystemService(Context.VIBRATOR_SERVICE);
      if (v != null) {
         v.vibrate(milliseconds);
      }
   }

   public CoinUtil.Denomination getBitcoinDenomination() {
      return _bitcoinDenomination;
   }

   public void setBitcoinDenomination(CoinUtil.Denomination denomination) {
      _bitcoinDenomination = denomination;
      getEditor().putString(Constants.BITCOIN_DENOMINATION_SETTING, _bitcoinDenomination.toString()).commit();
   }

   public String getBtcValueString(long satoshis) {
      return getBtcValueString(satoshis, _btcValueFormatString);
   }

   private String getBtcValueString(long satoshis, String formatString) {
      Denomination d = getBitcoinDenomination();
      String valueString = CoinUtil.valueString(satoshis, d, true);
      return String.format(formatString, valueString, d.getUnicodeName());
   }

   public boolean getExpertMode() {
      return _expertMode;
   }

   public void setExpertMode(boolean expertMode) {
      _expertMode = expertMode;
      getEditor().putBoolean(Constants.EXPERT_MODE_SETTING, _expertMode).commit();
   }

   public boolean isKeyManagementLocked() {
      return _keyManagementLocked;
   }

   public void setKeyManagementLocked(boolean locked) {
      _keyManagementLocked = locked;
      getEditor().putBoolean(Constants.KEY_MANAGEMENT_LOCKED_SETTING, _keyManagementLocked).commit();
   }

   public boolean getContinuousFocus() {
      return _enableContinuousFocus;
   }

   public void setContinousFocus(boolean disableContinousFocus) {
      _enableContinuousFocus = disableContinousFocus;
      getEditor().putBoolean(Constants.ENABLE_CONTINUOUS_FOCUS_SETTING, _enableContinuousFocus).commit();
   }

   public void setProxy(String proxy) {
      getEditor().putString(Constants.PROXY_SETTING, proxy).commit();
      ImmutableList<String> vals = ImmutableList.copyOf(Splitter.on(":").split(proxy));
      if (vals.size() != 2) {
         noProxy();
         return;
      }
      Integer portNumber = Ints.tryParse(vals.get(1));
      if (portNumber == null || portNumber < 1 || portNumber > 65535) {
         noProxy();
         return;
      }
      String hostname = vals.get(0);
      System.setProperty(PROXY_HOST, hostname);
      System.setProperty(PROXY_PORT, portNumber.toString());
   }

   private void noProxy() {
      System.clearProperty(PROXY_HOST);
      System.clearProperty(PROXY_PORT);
   }

   public int getMainViewFragmentIndex() {
      return _mainViewFragmentIndex;
   }

   public void setMainViewFragmentIndex(int index) {
      _mainViewFragmentIndex = index;
      getEditor().putInt(Constants.MAIN_VIEW_FRAGMENT_INDEX_SETTING, _mainViewFragmentIndex).commit();
   }

   public MrdExport.V1.EncryptionParameters getCachedEncryptionParameters() {
      return _cachedEncryptionParameters;
   }

   public void setCachedEncryptionParameters(MrdExport.V1.EncryptionParameters cachedEncryptionParameters) {
      _cachedEncryptionParameters = cachedEncryptionParameters;
   }

   public void clearCachedEncryptionParameters() {
      _cachedEncryptionParameters = null;
   }

   public Bus getEventBus() {
      return _eventBus;
   }

   public SyncManager getSyncManager() {
      return _syncManager;
   }

   /**
    * Get the Bitcoin network parameters that the wallet operates on
    */
   public NetworkParameters getNetwork() {
      return _environment.getNetwork();
   }

   /**
    * Get the brand of the wallet. This allows us to behave differently
    * depending on the brand of the wallet.
    */
   public String getBrand() {
      return _environment.getBrand();
   }

   public ExploreHelper getExploreHelper() {
      return exploreHelper;
   }

   public void reportIgnoredException(Throwable e) {
      if (_httpErrorCollector != null) {
         RuntimeException msg = new RuntimeException("We caught an exception that we chose to ignore.\n", e);
         _httpErrorCollector.reportErrorToServer(msg);
      }
   }

   public String getLanguage() {
      return _language;
   }

   public void setLanguage(String _language) {
      this._language = _language;
      SharedPreferences.Editor editor = getEditor();
      editor.putString(Constants.LANGUAGE_SETTING, _language);
      editor.commit();
   }

   public VersionManager getVersionManager() {
      return _versionManager;
   }

   public MrdExport.V1.ScryptParameters getDeviceScryptParameters() {
      return _deviceScryptParameters;
   }
}
