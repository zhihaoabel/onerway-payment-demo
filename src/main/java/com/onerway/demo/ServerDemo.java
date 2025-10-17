package com.onerway.demo;
import static spark.Spark.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import spark.Request;
import spark.Response;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
// ... other imports

public class ServerDemo {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SANDBOX_BASE_URL = "https://sandbox-acq.onerway.com/txn/payment";
    // TODO: Replace with your merchant no, app id, and merchant secret
    private static final String MERCHANT_NO = "REPLACE_WITH_MERCHANT_NO";
    private static final String DEFAULT_APP_ID = "REPLACE_WITH_APP_ID";
    private static final String MERCHANT_SECRET = "REPLACE_WITH_MERCHANT_SECRET";
    private static final String DEFAULT_RETURN_URL = "https://merchant.example.com/pay/return";
    private static final String DEFAULT_NOTIFY_URL = "https://merchant.example.com/pay/notify";

    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void main(String[] args) {
        port(8080);
        get("/create-checkout-session", ServerDemo::handlePaymentRequest);
    }

    private static Object handlePaymentRequest(Request req, Response res) {
        res.type("application/json");

        // Build payment request data
        Map<String, String> body = buildPaymentBody(req);

        // Generate signature
        String signBase = buildSignBaseString(body, MERCHANT_SECRET);
        String sign = sha256Hex(signBase);
        body.put("sign", sign);

        try {
            String requestJson = toJson(body);

            // Send request to Onerway API
            String responseJson = postJson(SANDBOX_BASE_URL, requestJson);

            // Extract redirect URL and redirect customer
            String redirectUrl = extractRedirectUrl(responseJson);
            if (redirectUrl != null) {
                res.redirect(redirectUrl, 303);
                return "";
            }

            return responseJson;
        } catch (Exception e) {
            return "{\"error\": \"Failed to create checkout session: " + e.getMessage() + "\"}";
        }
    }

    private static Map<String, String> buildPaymentBody(Request req) {
        String merchantTxnId = String.valueOf(System.currentTimeMillis());
        String merchantTxnTime = LocalDateTime.now().format(DATETIME_FMT);

        String appId = resolveAppId(req);
        String returnUrl = resolveReturnUrl(appId);
        String billingInformation = buildBillingInformation("US", "test@example.com");
        String txnOrderMsg = buildTxnOrderMsg(appId, returnUrl, DEFAULT_NOTIFY_URL, req.ip());

        Map<String, String> body = new TreeMap<>();
        body.put("billingInformation", billingInformation);
        body.put("merchantCustId", "DEMO-CUSTOMER-ID");
        body.put("merchantNo", MERCHANT_NO);
        body.put("merchantTxnId", merchantTxnId);
        body.put("merchantTxnTime", merchantTxnTime);
        body.put("orderAmount", "1");
        body.put("orderCurrency", "USD");
        body.put("productType", "CARD");
        body.put("shippingInformation", billingInformation);
        body.put("subProductType", "DIRECT");
        body.put("txnOrderMsg", txnOrderMsg);
        body.put("txnType", "SALE");
        return body;
    }

    private static String buildBillingInformation(String country, String email) {
        Map<String, Object> billing = new TreeMap<>();
        billing.put("country", country);
        billing.put("email", email);
        return toJson(billing);
    }

    private static String buildTxnOrderMsg(String appId, String returnUrl, String notifyUrl, String transactionIp) {
        List<Map<String, String>> products = new ArrayList<>();
        Map<String, String> product = new TreeMap<>();
        product.put("price", "110.00");
        product.put("num", "1");
        product.put("name", "iphone11");
        product.put("currency", "USD");
        products.add(product);

        String productsJson = toJson(products);

        Map<String, Object> txnOrder = new TreeMap<>();
        txnOrder.put("products", productsJson);
        txnOrder.put("appId", appId);
        txnOrder.put("returnUrl", returnUrl);
        txnOrder.put("notifyUrl", notifyUrl);
        txnOrder.put("transactionIp", transactionIp);
        return toJson(txnOrder);
    }

    // Signature generation following Onerway's specification
    private static String buildSignBaseString(Map<String, String> params, String secret) {
        boolean refundRequest = isRefundRequest(params);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : new TreeMap<>(params).entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (isNonEmpty(value) && !shouldFilterKey(key, refundRequest)) {
                sb.append(value);
            }
        }
        sb.append(secret);
        return sb.toString();
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest); // JDK 17+
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // HTTP communication
    private static String postJson(String url, String jsonBody) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return response.body();
    }

    // Response parsing
    private static String extractRedirectUrl(String responseJson) {
        try {
            JsonNode root = JSON.readTree(responseJson);
            JsonNode redirectNode = root.path("data").path("redirectUrl");
            if (redirectNode.isTextual()) {
                String value = redirectNode.asText().trim();
                if (isNonEmpty(value)) {
                    return value;
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to parse redirect url: " + e.getMessage());
        }
        return null;
    }

    // JSON utilities
    private static String toJson(Object obj) {
        try {
            return JSON.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // Helper methods
    private static boolean isRefundRequest(Map<String, String> params) {
        return params != null && params.containsKey("refundType");
    }

    private static boolean shouldFilterKey(String key, boolean refundRequest) {
        Set<String> EXCLUDED_KEYS_BASE = Set.of(
                "originMerchantTxnId", "customsDeclarationAmount", "customsDeclarationCurrency",
                "paymentMethod", "walletTypeName", "periodValue", "tokenExpireTime", "sign");
        return EXCLUDED_KEYS_BASE.contains(key) || (!refundRequest && "originTransactionId".equals(key));
    }

    private static String resolveAppId(Request req) {
        if (req == null)
            return DEFAULT_APP_ID;
        String[] candidates = {
                req.queryParams("appId"), req.queryParams("app_id"),
                req.headers("X-App-Id"), req.headers("appId")
        };
        for (String candidate : candidates) {
            if (candidate != null && !candidate.trim().isEmpty()) {
                return candidate.trim();
            }
        }
        return DEFAULT_APP_ID;
    }

    private static String resolveReturnUrl(String appId) {
        return DEFAULT_RETURN_URL;
    }

    private static boolean isNonEmpty(String value) {
        return value != null && (value.length() > 0 || "0".equals(value));
    }

}
