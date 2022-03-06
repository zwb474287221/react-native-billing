package com.xwzk.zoon.support.billing;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.AcknowledgePurchaseResponseListener;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.ConsumeResponseListener;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesResponseListener;
import com.android.billingclient.api.PurchasesUpdatedListener;

import com.android.billingclient.api.SkuDetails;
import com.android.billingclient.api.SkuDetailsParams;
import com.android.billingclient.api.SkuDetailsResponseListener;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class InAppBillingBridge extends ReactContextBaseJavaModule implements PurchasesUpdatedListener {
    ReactApplicationContext _reactContext;

    private BillingClient bp;

    Map<String, SkuDetails> skuDetailsMap = new HashMap<>();
    Map<String, Purchase> PurchaseMap = new HashMap<>();

    Boolean mShortCircuit = false;
//    static final String LOG_TAG = "rnbilling";

    public InAppBillingBridge(ReactApplicationContext reactContext) {
        super(reactContext);
        _reactContext = reactContext;
    }

    @Override
    public String getName() {
        return "InAppBillingBridge";
    }

    @Override
    public Map<String, Object> getConstants() {
        final Map<String, Object> constants = new HashMap<>();
        return constants;
    }

    @ReactMethod
    public void open(final Promise promise) {
        if (bp == null) {
            clearPromises();
            if (putPromise(PromiseConstants.OPEN, promise)) {
                try {
                    bp = BillingClient.newBuilder(_reactContext).setListener(this).enablePendingPurchases().build();
                    bp.startConnection(new BillingClientStateListener() {
                        @Override
                        public void onBillingSetupFinished(BillingResult billingResult) {
                            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                                // The BillingClient is ready. You can query purchases here.
                                resolvePromise(PromiseConstants.OPEN, true);
                            } else {
                                rejectPromise(PromiseConstants.OPEN,billingResult.getDebugMessage());
                                bp.endConnection();
                                bp = null;
                            }
//                            Log.w("onBillingSetupFinished","getResponseCode"+billingResult.getResponseCode()+" getDebugMessage="+billingResult.getDebugMessage());
                        }

                        @Override
                        public void onBillingServiceDisconnected() {
                            bp = null;
                            // Logic from ServiceConnection.onServiceDisconnected should be moved here.
                        }
                    });
                } catch (Exception ex) {
                    rejectPromise(PromiseConstants.OPEN, "Failure on open: " + ex.getMessage());
                }
            } else {
                promise.reject("EUNSPECIFIED", "Previous open operation is not resolved.");
            }
        } else {
            promise.resolve(true);
        }
    }

    @ReactMethod
    public void acknowledgePurchase(String token,Promise promise){
        if (bp != null) {
            AcknowledgePurchaseParams params= AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build();
            bp.acknowledgePurchase(params, new AcknowledgePurchaseResponseListener() {
                @Override
                public void onAcknowledgePurchaseResponse(@NonNull BillingResult billingResult) {
                    if(billingResult.getResponseCode()==BillingClient.BillingResponseCode.OK){
                        promise.resolve(1);
                    }else{
                        promise.reject("Error",billingResult.getDebugMessage());
                    }
                }
            });
            bp = null;
        }
    }

    @ReactMethod
    public void close(final Promise promise) {
        if (bp != null) {
            bp.endConnection();
            bp = null;
        }
        clearPromises();
        promise.resolve(true);
    }

    @ReactMethod
    public void getProductWithProductId(final String productId, Promise promise) {
        SkuDetails details = skuDetailsMap.get(productId);
        if (details != null) {
            promise.resolve(makeProductDetail(details));
        } else {
            WritableArray list = Arguments.createArray();
            list.pushString(productId);
            getProductDetails(list, promise);
        }
    }

    // 购买
    @ReactMethod
    public void purchase(final String productId, final String developerPayload, final Promise promise) {
        if (bp != null) {
            if (putPromise(PromiseConstants.PURCHASE_OR_SUBSCRIBE, promise)) {
                SkuDetails details = skuDetailsMap.get(productId);
                if (details != null) {
                    BillingFlowParams billingFlowParams = BillingFlowParams.newBuilder()
                            .setSkuDetails(details)
                            .build();
                    int purchaseProcessStarted = bp.launchBillingFlow(Objects.requireNonNull(getCurrentActivity()), billingFlowParams).getResponseCode();
                    if (purchaseProcessStarted != BillingClient.BillingResponseCode.OK)
                        rejectPromise(PromiseConstants.PURCHASE_OR_SUBSCRIBE, "Could not start purchase process.");
                } else {
                    promise.reject("EUNSPECIFIED", "Not find");
                }
            } else {
                promise.reject("EUNSPECIFIED", "Previous purchase or subscribe operation is not resolved.");
            }
        } else {
            promise.reject("EUNSPECIFIED", "Channel is not opened. Call open() on InAppBilling.");
        }
    }

    // 用户升级、降级或更改订阅
    @ReactMethod
    public void subscriptionUpdate(String OldToken, String newProductId,int mode,Promise promise){
        if(bp!=null){
            if (putPromise(PromiseConstants.PURCHASE_OR_SUBSCRIBE, promise)) {
                SkuDetails details = skuDetailsMap.get(newProductId);
                if (details != null) {
                    // Retrieve a value for "skuDetails" by calling querySkuDetailsAsync()
                    BillingFlowParams billingFlowParams = BillingFlowParams.newBuilder()
                            .setSubscriptionUpdateParams(BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                                    .setOldSkuPurchaseToken(OldToken)
                                    .setReplaceSkusProrationMode(mode).build())
                            .setSkuDetails(Objects.requireNonNull(skuDetailsMap.get(newProductId)))
                            .build();
                    int purchaseProcessStarted = bp.launchBillingFlow(Objects.requireNonNull(getCurrentActivity()), billingFlowParams).getResponseCode();
                    if (purchaseProcessStarted != BillingClient.BillingResponseCode.OK)
                        rejectPromise(PromiseConstants.PURCHASE_OR_SUBSCRIBE, "Could not start purchase process.");
                } else {
                    promise.reject("EUNSPECIFIED", "Not find");
                }

            }
        }
    }

    @ReactMethod
    public void consumePurchase(final String productId, final Promise promise) {
        if (bp != null) {
            try {
                queryPurchase2(productId, new CallbackListener() {
                    @Override
                    public void callback(@Nullable boolean isBuy) {
                        if (isBuy) {
                            Purchase purchase = PurchaseMap.get(productId);
                            ConsumeParams consumeParams =
                                    ConsumeParams.newBuilder()
                                            .setPurchaseToken(purchase.getPurchaseToken())
                                            .build();
                            bp.consumeAsync(consumeParams, (ConsumeResponseListener) (billingResult, purchaseToken) -> {
                                if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                                    // Handle the success of the consume operation.
                                    PurchaseMap.remove(productId);
                                    promise.resolve(true);
                                } else {
                                    promise.reject("EUNSPECIFIED", "Could not consume purchase");
                                }
                            });
                        } else {
                            promise.reject("EUNSPECIFIED", "Could not consume purchase");
                        }
                    }
                });
            } catch (Exception ex) {
                promise.reject("EUNSPECIFIED", "Failure on consume: " + ex.getMessage());
            }
        } else {
            promise.reject("EUNSPECIFIED", "Channel is not opened. Call open() on InAppBilling.");
        }
    }

    @ReactMethod
    public void queryPurchase(String type, Promise promise) {
        if (bp != null) {
            bp.queryPurchasesAsync(type, new PurchasesResponseListener() {
                @Override
                public void onQueryPurchasesResponse(@NonNull BillingResult billingResult, @NonNull List<Purchase> Purchase) {
                    WritableArray purchaseArray = Arguments.createArray();
                    for (Purchase purchase : Purchase) {
                        for (String sku : purchase.getSkus()) {
                            PurchaseMap.put(sku, purchase);
                            purchaseArray.pushMap(mapTransactionDetails(purchase));
                        }
                    }
                    promise.resolve(purchaseArray);
                }
            });
        } else {
            promise.reject("EUNSPECIFIED", "Channel is not opened. Call open() on InAppBilling.");
        }

    }

    public void queryPurchase2(final String productId, CallbackListener callback) {
        bp.queryPurchasesAsync("inapp", new PurchasesResponseListener() {
            @Override
            public void onQueryPurchasesResponse(@NonNull BillingResult billingResult, @NonNull List<Purchase> list) {
                boolean isFind = false;
                for (Purchase purchase : list) {
//                    Log.w("onQueryPurchasesResponse",purchase.getOriginalJson());
                    for (String sku : purchase.getSkus()) {
                        PurchaseMap.put(sku, purchase);
                        if (sku.equals(productId)) {
                            isFind = true;
                        }
                    }
                }
                callback.callback(isFind);
            }
        });
    }

    @ReactMethod
    public void isPurchased(final String productId, final Promise promise) {
        if (bp != null) {
            queryPurchase2(productId, new CallbackListener() {
                @Override
                public void callback(@Nullable boolean var2) {
                    promise.resolve(var2);
                }
            });
        } else {
            promise.reject("EUNSPECIFIED", "Channel is not opened. Call open() on InAppBilling.");
        }
    }

    public WritableMap makeProductDetail(SkuDetails detail) {
//        Log.w("SkuDetails",detail.toString());
        skuDetailsMap.put(detail.getSku(), detail);
        WritableMap map = Arguments.createMap();
        map.putString("productId", detail.getSku());
        map.putString("title", detail.getTitle());
        map.putString("description", detail.getDescription());
        map.putString("currency", detail.getPriceCurrencyCode());
        map.putDouble("priceValue", detail.getPriceAmountMicros());
        map.putString("subscriptionPeriod", detail.getSubscriptionPeriod());
        map.putString("priceText", detail.getPrice());
        map.putString("productInfo", detail.getOriginalJson());
        return map;
    }

    public void getProductDetailsNative(final ReadableArray productIds, String type, final Promise promise) {
        if (bp != null) {
            try {
                ArrayList<String> productIdList = new ArrayList<>();
                for (int i = 0; i < productIds.size(); i++) {
                    productIdList.add(productIds.getString(i));
                }
                SkuDetailsParams.Builder params = SkuDetailsParams.newBuilder();
                params.setSkusList(productIdList).setType(type);
                bp.querySkuDetailsAsync(params.build(), new SkuDetailsResponseListener() {
                    @Override
                    public void onSkuDetailsResponse(@NonNull BillingResult billingResult, @Nullable List<SkuDetails> details) {

//                        Log.w("onSkuDetailsResponse","getResponseCode:"+billingResult.getResponseCode());
                        if (details != null) {
                            WritableArray arr = Arguments.createArray();
                            for (SkuDetails detail : details) {
                                arr.pushMap(makeProductDetail(detail));
                            }
                            promise.resolve(arr);
                        } else {
                            promise.reject("EUNSPECIFIED", "Details was not found.");
                        }
                    }
                });
            } catch (Exception ex) {
                promise.reject("EUNSPECIFIED", "Failure on getting product details: " + ex.getMessage());
            }
        } else {
            promise.reject("EUNSPECIFIED", "Channel is not opened. Call open() on InAppBilling.");
        }
    }

    //获取商品信息
    @ReactMethod
    public void getProductDetails(final ReadableArray productIds, final Promise promise) {
        getProductDetailsNative(productIds, BillingClient.SkuType.INAPP, promise);
    }

    //获取订阅商品信息
    @ReactMethod
    public void getSubscriptionDetails(final ReadableArray productIds, final Promise promise) {
        getProductDetailsNative(productIds, BillingClient.SkuType.SUBS, promise);
    }

    @ReactMethod
    public void getPurchaseTransactionDetails(final String productId, final Promise promise) {
        if (bp != null) {
            queryPurchase2(productId, new CallbackListener() {
                @Override
                public void callback(@Nullable boolean var2) {
                    if (var2) {
                        Purchase purchase = PurchaseMap.get(productId);
                        WritableMap map = mapTransactionDetails(purchase);
                        promise.resolve(map);
                    } else {
                        promise.reject("EUNSPECIFIED", "Could not find transaction details for productId.");

                    }
                }
            });
        } else {
            promise.reject("EUNSPECIFIED", "Channel is not opened. Call open() on InAppBilling.");
        }
    }

    private WritableMap mapTransactionDetails(Purchase purchase) {
//        Log.w("purchase", purchase.toString());
        PurchaseMap.put(purchase.getSkus().get(0), purchase);
        WritableMap map = Arguments.createMap();
        map.putString("orderId", purchase.getOrderId());
        map.putString("productId", purchase.getSkus().get(0));
        map.putString("purchaseToken", purchase.getPurchaseToken());
        map.putString("receiptData", purchase.getOriginalJson());
        map.putDouble("purchaseTime", purchase.getPurchaseTime());
        map.putString("DeveloperPayload",purchase.getDeveloperPayload());
        map.putString("packageName", purchase.getPackageName());
        map.putBoolean("acknowledged",purchase.isAcknowledged());
        map.putInt("purchaseState", purchase.getPurchaseState());
//        map.putString("receiptData", details.purchaseInfo.responseData.toString());
//
//        if (details.purchaseInfo.signature != null)
//            map.putString("receiptSignature", details.purchaseInfo.signature.toString());
//
//        PurchaseData purchaseData = details.purchaseInfo.purchaseData;
//
//        map.putString("productId", purchaseData.productId);
//        map.putString("orderId", purchaseData.orderId);
//        map.putString("purchaseToken", purchaseData.purchaseToken);
//        map.putString("purchaseTime", purchaseData.purchaseTime == null
//          ? "" : purchaseData.purchaseTime.toString());
//        map.putString("purchaseState", purchaseData.purchaseState == null
//          ? "" : purchaseData.purchaseState.toString());
//        map.putBoolean("autoRenewing", purchaseData.autoRenewing);
//
//        if (purchaseData.developerPayload != null)
//            map.putString("developerPayload", purchaseData.developerPayload);

        return map;
    }

    @ReactMethod
    public void shortCircuitPurchaseFlow(final Boolean enable) {
        mShortCircuit = enable;
    }

    int PURCHASE_FLOW_REQUEST_CODE = 32459;
    int BILLING_RESPONSE_RESULT_OK = 0;
    String RESPONSE_CODE = "RESPONSE_CODE";

    private void shortCircuitActivityResult(final Activity activity, final int requestCode, final int resultCode, final Intent intent) {
        if (requestCode != PURCHASE_FLOW_REQUEST_CODE) {
            return;
        }

        int responseCode = intent.getIntExtra(RESPONSE_CODE, BILLING_RESPONSE_RESULT_OK);
        if (resultCode == Activity.RESULT_OK && responseCode == BILLING_RESPONSE_RESULT_OK) {
            resolvePromise(PromiseConstants.PURCHASE_OR_SUBSCRIBE, true);
        } else {
            rejectPromise(PromiseConstants.PURCHASE_OR_SUBSCRIBE, "An error has occured. Code " + requestCode);
        }
    }


    HashMap<String, Promise> mPromiseCache = new HashMap<>();

    synchronized void resolvePromise(String key, Object value) {
        if (mPromiseCache.containsKey(key)) {
            Promise promise = mPromiseCache.get(key);
            promise.resolve(value);
            mPromiseCache.remove(key);
        } else {
//            Log.w(LOG_TAG, String.format("Tried to resolve promise: %s - but does not exist in cache", key));
        }
    }

    synchronized void rejectPromise(String key, String reason) {
        if (mPromiseCache.containsKey(key)) {
            Promise promise = mPromiseCache.get(key);
            promise.reject("EUNSPECIFIED", reason);
            mPromiseCache.remove(key);
        } else {
//            Log.w(LOG_TAG, String.format("Tried to reject promise: %s - but does not exist in cache", key));
        }
    }

    synchronized Boolean putPromise(String key, Promise promise) {
        if (!mPromiseCache.containsKey(key)) {
            mPromiseCache.put(key, promise);
            return true;
        } else {
//            Log.w(LOG_TAG, String.format("Tried to put promise: %s - already exists in cache", key));
        }
        return false;
    }

    synchronized Boolean hasPromise(String key) {
        return mPromiseCache.containsKey(key);
    }

    synchronized void clearPromises() {
        mPromiseCache.clear();
    }

    void handlePurchase(Purchase purchase) {
        try {
            WritableMap map = mapTransactionDetails(purchase);
            resolvePromise(PromiseConstants.PURCHASE_OR_SUBSCRIBE, map);
        } catch (Exception ex) {
            rejectPromise(PromiseConstants.PURCHASE_OR_SUBSCRIBE, "Failure on purchase or subscribe callback: " + ex.getMessage());
        }
    }

    @Override
    public void onPurchasesUpdated(@NonNull BillingResult billingResult, @Nullable List<Purchase> purchases) {
        if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
            if(purchases!=null){
                for (Purchase purchase : purchases) {
                    Log.w("onPurchasesUpdated",purchase.getOriginalJson());
                    handlePurchase(purchase);
                }
            } else {
                resolvePromise(PromiseConstants.PURCHASE_OR_SUBSCRIBE,1);
            }
        } else {
            rejectPromise(PromiseConstants.PURCHASE_OR_SUBSCRIBE, "Failure on purchase or subscribe callback: " + billingResult.getResponseCode());
        }
    }
}
