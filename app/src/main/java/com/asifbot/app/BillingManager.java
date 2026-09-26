package com.asifbot.app;

import android.app.Activity;
import android.content.Context;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.ProductDetailsResponseListener;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryProductDetailsResult;
import com.android.billingclient.api.QueryPurchasesParams;
import com.android.billingclient.api.UnfetchedProduct;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class BillingManager implements PurchasesUpdatedListener {
    interface Listener {
        void onBillingMessage(String message);
        void onPurchaseReady(Purchase purchase);
    }

    private final BillingClient billingClient;
    private final String productId;
    private final Listener listener;
    private ProductDetails subscriptionDetails;
    private String selectedOfferToken;

    BillingManager(Context context, String productId, Listener listener) {
        this.productId = productId;
        this.listener = listener;
        billingClient = BillingClient.newBuilder(context)
                .setListener(this)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .enableAutoServiceReconnection()
                .build();
    }

    void start() {
        if (billingClient.isReady()) {
            queryProduct();
            queryActivePurchases();
            return;
        }
        billingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult billingResult) {
                if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    queryProduct();
                    queryActivePurchases();
                } else {
                    listener.onBillingMessage("Billing unavailable: " + billingResult.getDebugMessage());
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                listener.onBillingMessage("Billing service disconnected.");
            }
        });
    }

    void endConnection() {
        if (billingClient.isReady()) {
            billingClient.endConnection();
        }
    }

    void launchSubscription(Activity activity, String obfuscatedAccountId) {
        if (!billingClient.isReady()) {
            listener.onBillingMessage("Billing is connecting. Try again in a moment.");
            start();
            return;
        }
        if (subscriptionDetails == null) {
            listener.onBillingMessage("Subscription product not loaded yet.");
            queryProduct();
            return;
        }
        if (selectedOfferToken == null || selectedOfferToken.isEmpty()) {
            listener.onBillingMessage("No subscription offer token found in Play Console.");
            return;
        }

        BillingFlowParams.ProductDetailsParams detailsParams =
                BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(subscriptionDetails)
                        .setOfferToken(selectedOfferToken)
                        .build();

        BillingFlowParams.Builder flowBuilder = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(Collections.singletonList(detailsParams));

        if (obfuscatedAccountId != null && !obfuscatedAccountId.isEmpty()) {
            flowBuilder.setObfuscatedAccountId(obfuscatedAccountId);
        }

        BillingResult result = billingClient.launchBillingFlow(activity, flowBuilder.build());
        if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
            listener.onBillingMessage("Purchase did not start: " + result.getDebugMessage());
        }
    }

    void acknowledge(Purchase purchase) {
        if (purchase == null || purchase.isAcknowledged()) {
            return;
        }
        AcknowledgePurchaseParams params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.getPurchaseToken())
                .build();
        billingClient.acknowledgePurchase(params, result -> {
            if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                listener.onBillingMessage("Subscription confirmed.");
            } else {
                listener.onBillingMessage("Subscription acknowledgment failed: " + result.getDebugMessage());
            }
        });
    }

    @Override
    public void onPurchasesUpdated(BillingResult billingResult, List<Purchase> purchases) {
        if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (Purchase purchase : purchases) {
                processPurchase(purchase);
            }
        } else if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.USER_CANCELED) {
            listener.onBillingMessage("Purchase cancelled.");
        } else {
            listener.onBillingMessage("Purchase failed: " + billingResult.getDebugMessage());
        }
    }

    private void queryProduct() {
        List<QueryProductDetailsParams.Product> products = new ArrayList<>();
        products.add(QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(BillingClient.ProductType.SUBS)
                .build());

        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(products)
                .build();

        billingClient.queryProductDetailsAsync(params, new ProductDetailsResponseListener() {
            @Override
            public void onProductDetailsResponse(BillingResult billingResult, QueryProductDetailsResult result) {
                if (billingResult.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                    listener.onBillingMessage("Product query failed: " + billingResult.getDebugMessage());
                    return;
                }
                if (!result.getUnfetchedProductList().isEmpty()) {
                    for (UnfetchedProduct unfetched : result.getUnfetchedProductList()) {
                        listener.onBillingMessage("Unfetched product: " + unfetched.getProductId());
                    }
                }
                if (result.getProductDetailsList().isEmpty()) {
                    listener.onBillingMessage("Create product id " + productId + " in Play Console first.");
                    return;
                }
                subscriptionDetails = result.getProductDetailsList().get(0);
                selectedOfferToken = chooseOfferToken(subscriptionDetails);
            }
        });
    }

    private void queryActivePurchases() {
        QueryPurchasesParams params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build();
        billingClient.queryPurchasesAsync(params, (billingResult, purchases) -> {
            if (billingResult.getResponseCode() != BillingClient.BillingResponseCode.OK || purchases == null) {
                return;
            }
            for (Purchase purchase : purchases) {
                processPurchase(purchase);
            }
        });
    }

    private void processPurchase(Purchase purchase) {
        if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
            listener.onPurchaseReady(purchase);
        } else if (purchase.getPurchaseState() == Purchase.PurchaseState.PENDING) {
            listener.onBillingMessage("Subscription payment is pending.");
        }
    }

    private static String chooseOfferToken(ProductDetails details) {
        List<ProductDetails.SubscriptionOfferDetails> offers = details.getSubscriptionOfferDetails();
        if (offers == null || offers.isEmpty()) {
            return null;
        }

        for (ProductDetails.SubscriptionOfferDetails offer : offers) {
            List<ProductDetails.PricingPhase> phases = offer.getPricingPhases().getPricingPhaseList();
            for (ProductDetails.PricingPhase phase : phases) {
                if (phase.getPriceAmountMicros() == 0L) {
                    return offer.getOfferToken();
                }
            }
        }
        return offers.get(0).getOfferToken();
    }
}
