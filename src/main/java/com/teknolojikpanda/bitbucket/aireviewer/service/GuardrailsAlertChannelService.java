package com.teknolojikpanda.bitbucket.aireviewer.service;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teknolojikpanda.bitbucket.aireviewer.ao.GuardrailsAlertChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Named
@Singleton
public class GuardrailsAlertChannelService {

    private static final Logger log = LoggerFactory.getLogger(GuardrailsAlertChannelService.class);
    private final ActiveObjects ao;
    private final ObjectMapper mapper = new ObjectMapper();

    @Inject
    public GuardrailsAlertChannelService(@ComponentImport ActiveObjects ao) {
        this.ao = Objects.requireNonNull(ao, "activeObjects");
    }

    public List<Channel> listChannels() {
        GuardrailsAlertChannel[] rows = ao.executeInTransaction(() ->
                ao.find(GuardrailsAlertChannel.class));
        if (rows.length == 0) {
            return Collections.emptyList();
        }
        List<Channel> channels = new ArrayList<>(rows.length);
        for (GuardrailsAlertChannel row : rows) {
            channels.add(toValue(row));
        }
        return channels;
    }

    public Channel createChannel(String url, String description, boolean enabled) {
        String sanitizedUrl = sanitize(url);
        return ao.executeInTransaction(() -> {
            GuardrailsAlertChannel row = ao.create(GuardrailsAlertChannel.class);
            row.setUrl(sanitizedUrl);
            row.setDescription(sanitize(description));
            row.setEnabled(enabled);
            row.setCreatedAt(System.currentTimeMillis());
            row.setUpdatedAt(row.getCreatedAt());
            row.save();
            log.info("Created guardrails alert channel {}", sanitizedUrl);
            return toValue(row);
        });
    }

    public Channel updateChannel(int id, String description, Boolean enabled) {
        return ao.executeInTransaction(() -> {
            GuardrailsAlertChannel row = ao.get(GuardrailsAlertChannel.class, id);
            if (row == null) {
                throw new IllegalArgumentException("Channel " + id + " not found");
            }
            if (description != null) {
                row.setDescription(sanitize(description));
            }
            if (enabled != null) {
                row.setEnabled(enabled);
            }
            row.setUpdatedAt(System.currentTimeMillis());
            row.save();
            return toValue(row);
        });
    }

    public void deleteChannel(int id) {
        ao.executeInTransaction(() -> {
            GuardrailsAlertChannel row = ao.get(GuardrailsAlertChannel.class, id);
            if (row != null) {
                log.info("Deleting guardrails alert channel {}", row.getUrl());
                ao.delete(row);
            }
            return null;
        });
    }

    public void notifyChannels(GuardrailsAlertingService.AlertSnapshot snapshot) {
        List<Channel> channels = listChannels();
        if (channels.isEmpty() || snapshot.getAlerts().isEmpty()) {
            return;
        }
        for (Channel channel : channels) {
            if (!channel.isEnabled()) {
                continue;
            }
            deliverSnapshot(channel, snapshot);
        }
    }

    private void deliverSnapshot(Channel channel, GuardrailsAlertingService.AlertSnapshot snapshot) {
        URL target = toUrl(channel.getUrl());
        if (target == null) {
            log.warn("Skipping alert delivery; invalid URL {}", channel.getUrl());
            return;
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) target.openConnection();
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("Content-Type", "application/json");
            byte[] payload = mapper.writeValueAsBytes(snapshot);
            try (OutputStream os = connection.getOutputStream()) {
                os.write(payload);
            }
            int status = connection.getResponseCode();
            if (status >= 400) {
                log.warn("Guardrails alert delivery to {} failed with HTTP {}", channel.getUrl(), status);
            }
        } catch (IOException ex) {
            log.warn("Failed to deliver guardrails alert to {}: {}", channel.getUrl(), ex.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private URL toUrl(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return new URL(value.trim());
        } catch (MalformedURLException e) {
            return null;
        }
    }

    private Channel toValue(GuardrailsAlertChannel row) {
        return new Channel(
                row.getID(),
                row.getUrl(),
                row.getDescription(),
                row.isEnabled(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }

    private String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static final class Channel {
        private final int id;
        private final String url;
        private final String description;
        private final boolean enabled;
        private final long createdAt;
        private final long updatedAt;

        public Channel(int id,
                       String url,
                       String description,
                       boolean enabled,
                       long createdAt,
                       long updatedAt) {
            this.id = id;
            this.url = url;
            this.description = description;
            this.enabled = enabled;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }

        public int getId() {
            return id;
        }

        public String getUrl() {
            return url;
        }

        public String getDescription() {
            return description;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public long getCreatedAt() {
            return createdAt;
        }

        public long getUpdatedAt() {
            return updatedAt;
        }
    }
}
