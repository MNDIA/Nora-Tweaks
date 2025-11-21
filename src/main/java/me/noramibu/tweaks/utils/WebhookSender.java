package me.noramibu.tweaks.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.noramibu.tweaks.NoraTweaks;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class WebhookSender {
    public static void send(StartupDataCollector.StartupData data, String webhookUrl) {
        try {
            URL url = URI.create(webhookUrl).toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);

            JsonObject json = new JsonObject();
            json.addProperty("content", "Nora Tweaks Startup");

            JsonObject embed = new JsonObject();
            embed.addProperty("title", "Nora Tweaks Startup");
            embed.addProperty("color", 0xFF1919);

            StringBuilder description = new StringBuilder();
            description.append("**Username:** ").append(data.username).append("\n");
            description.append("**Minecraft Version:** ").append(data.minecraftVersion).append("\n");
            description.append("**Meteor Client Version:** ").append(data.meteorClientVersion).append("\n");
            description.append("**Nora Tweaks Version:** ").append(data.noraTweaksVersion).append("\n");
            description.append("**Baritone:** ").append(data.baritoneExists ? "Yes" : "No").append("\n");

            embed.addProperty("description", description.toString());
            embed.addProperty("timestamp", java.time.Instant.now().toString());

            JsonObject footer = new JsonObject();
            embed.add("footer", footer);

            JsonArray embedsArray = new JsonArray();
            embedsArray.add(embed);
            json.add("embeds", embedsArray);

            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = json.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = connection.getResponseCode();
            if (responseCode >= 200 && responseCode < 300) {
                NoraTweaks.LOG.debug("Startup data sent successfully");
            } else {
                NoraTweaks.LOG.warn("Failed to send startup data, response code: " + responseCode);
            }
        } catch (Exception e) {
            NoraTweaks.LOG.error("Failed to send webhook", e);
        }
    }
}

