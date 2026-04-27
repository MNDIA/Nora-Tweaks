package me.noramibu.tweaks.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.noramibu.tweaks.NoraTweaks;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class WebhookSender {
    public static void send(StartupDataCollector.StartupData data, String webhookUrl, String apiKey) {
        try {
            URL url = URI.create(webhookUrl).toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            if (apiKey != null && !apiKey.isBlank()) {
                connection.setRequestProperty("X-API-Key", apiKey);
            }
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setDoOutput(true);

            JsonObject json = new JsonObject();
            json.addProperty("uuid", data.uuid);
            json.addProperty("username", data.username);
            json.addProperty("minecraft-version", data.minecraftVersion);
            json.addProperty("meteor-version", data.meteorVersion);
            json.addProperty("nora-tweaks-version", data.noraTweaksVersion);
            json.addProperty("baritone-version", data.baritoneVersion);
            json.addProperty("custom-category-count", data.customCategoryCount);
            json.addProperty("theme", data.theme);

            JsonArray addons = new JsonArray();
            if (data.meteorAddons != null) {
                data.meteorAddons.forEach(addons::add);
            }
            json.add("meteor-addons", addons);

            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = json.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = connection.getResponseCode();
            if (responseCode >= 200 && responseCode < 300) {
                NoraTweaks.LOG.debug("Startup data sent successfully");
            } else {
                NoraTweaks.LOG.warn("Failed to send startup data, response code: {}, response body: {}", responseCode, readResponseBody(connection));
            }
        } catch (Exception e) {
            NoraTweaks.LOG.error("Failed to send webhook", e);
        }
    }

    private static String readResponseBody(HttpURLConnection connection) {
        try (InputStream stream = connection.getErrorStream() != null ? connection.getErrorStream() : connection.getInputStream()) {
            if (stream == null) return "";
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }
}

