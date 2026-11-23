package fr.liseuse;

import android.app.Activity;
import android.content.SharedPreferences;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.AcknowledgePurchaseResponseListener;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.ProductDetailsResponseListener;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesResponseListener;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryProductDetailsResult;
import com.android.billingclient.api.QueryPurchasesParams;

import java.util.Collections;
import java.util.List;

/** Achat unique « Liseuse Pro » avec Google Play Billing. Compilé uniquement par Gradle (édition Play Store). */
public final class AchatsPlay extends Achats implements PurchasesUpdatedListener {

    private final Activity activite;
    private final Ecouteur ecouteur;
    private final SharedPreferences prefs;
    private final BillingClient client;
    private ProductDetails produit;
    private boolean pro;
    private String prix = "";

    public AchatsPlay(Activity a, Ecouteur e) {
        activite = a;
        ecouteur = e;
        prefs = a.getSharedPreferences("achats", Activity.MODE_PRIVATE);
        pro = prefs.getBoolean("pro", false); // dernier état connu, en attendant Google Play
        client = BillingClient.newBuilder(a)
                .setListener(this)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .build();
        connecter(null);
    }

    private void connecter(final Runnable ensuite) {
        if (client.isReady()) {
            if (ensuite != null) ensuite.run();
            return;
        }
        client.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult r) {
                if (r.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    verifierAchats(false);
                    chargerProduit();
                    if (ensuite != null) ensuite.run();
                } else if (ensuite != null) {
                    informer("Google Play n'est pas disponible pour le moment (" + r.getDebugMessage() + ").");
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                // nouvelle tentative au prochain besoin
            }
        });
    }

    private void chargerProduit() {
        QueryProductDetailsParams p = QueryProductDetailsParams.newBuilder()
                .setProductList(Collections.singletonList(QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUIT_PRO)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()))
                .build();
        client.queryProductDetailsAsync(p, new ProductDetailsResponseListener() {
            @Override
            public void onProductDetailsResponse(BillingResult r, QueryProductDetailsResult res) {
                List<ProductDetails> l = res.getProductDetailsList();
                if (r.getResponseCode() == BillingClient.BillingResponseCode.OK && l != null && !l.isEmpty()) {
                    produit = l.get(0);
                    ProductDetails.OneTimePurchaseOfferDetails o = produit.getOneTimePurchaseOfferDetails();
                    if (o != null) prix = o.getFormattedPrice();
                    informer(null);
                }
            }
        });
    }

    private void verifierAchats(final boolean signaler) {
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
                new PurchasesResponseListener() {
                    @Override
                    public void onQueryPurchasesResponse(BillingResult r, List<Purchase> achats) {
                        if (r.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                            if (signaler) informer("Impossible de vérifier vos achats pour le moment.");
                            return;
                        }
                        boolean trouve = false;
                        for (Purchase p : achats) {
                            if (p.getProducts().contains(PRODUIT_PRO) && p.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                                trouve = true;
                                confirmer(p);
                            }
                        }
                        definirPro(trouve, signaler ? (trouve ? "Achat retrouvé : Liseuse Pro est activée."
                                : "Aucun achat de Liseuse Pro trouvé sur ce compte Google.") : null);
                    }
                });
    }

    /** Google exige de confirmer (« acknowledge ») un achat dans les 3 jours, sinon il est remboursé. */
    private void confirmer(Purchase p) {
        if (p.isAcknowledged()) return;
        client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.getPurchaseToken()).build(),
                new AcknowledgePurchaseResponseListener() {
                    @Override
                    public void onAcknowledgePurchaseResponse(BillingResult r) {
                    }
                });
    }

    private void definirPro(boolean v, String message) {
        pro = v;
        prefs.edit().putBoolean("pro", v).apply();
        informer(message);
    }

    private void informer(final String message) {
        activite.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ecouteur.changement(pro, prix, message);
            }
        });
    }

    @Override
    public void onPurchasesUpdated(BillingResult r, List<Purchase> achats) {
        int code = r.getResponseCode();
        if (code == BillingClient.BillingResponseCode.OK && achats != null) {
            for (Purchase p : achats) {
                if (!p.getProducts().contains(PRODUIT_PRO)) continue;
                if (p.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                    confirmer(p);
                    definirPro(true, "Merci ! Liseuse Pro est activée.");
                } else if (p.getPurchaseState() == Purchase.PurchaseState.PENDING) {
                    informer("Paiement en attente : Liseuse Pro sera activée dès sa confirmation.");
                }
            }
        } else if (code == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
            definirPro(true, "Vous possédez déjà Liseuse Pro : elle est activée.");
        } else if (code != BillingClient.BillingResponseCode.USER_CANCELED) {
            informer("L'achat n'a pas abouti (" + r.getDebugMessage() + ").");
        }
    }

    @Override
    public boolean estPro() {
        return pro;
    }

    @Override
    public String prix() {
        return prix;
    }

    @Override
    public String edition() {
        return "play";
    }

    @Override
    public void acheter() {
        connecter(new Runnable() {
            @Override
            public void run() {
                if (produit == null) {
                    chargerProduit();
                    informer("La boutique se charge… réessayez dans un instant.");
                    return;
                }
                BillingFlowParams p = BillingFlowParams.newBuilder()
                        .setProductDetailsParamsList(Collections.singletonList(
                                BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(produit).build()))
                        .build();
                client.launchBillingFlow(activite, p);
            }
        });
    }

    @Override
    public void restaurer() {
        connecter(new Runnable() {
            @Override
            public void run() {
                verifierAchats(true);
            }
        });
    }

    @Override
    public void fermer() {
        try {
            client.endConnection();
        } catch (Throwable ignore) {
        }
    }
}
