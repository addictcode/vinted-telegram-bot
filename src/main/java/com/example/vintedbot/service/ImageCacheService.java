package com.example.vintedbot.service;

import com.example.vintedbot.config.VintedParserProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.List;

/**
 * Downloads and caches listing images under the configured cache directory
 * (default {@code /tmp/vinted_images}). Best-effort: failures are logged, not thrown.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageCacheService {

    private final VintedParserProperties props;

    public void cacheAll(List<String> imageUrls) {
        if (imageUrls == null || imageUrls.isEmpty()) return;
        for (String url : imageUrls) {
            try {
                cache(url);
            } catch (Exception e) {
                log.debug("Could not cache image {}: {}", url, e.getMessage());
            }
        }
    }

    public Path cache(String imageUrl) throws Exception {
        Path dir = Path.of(props.getImageCacheDir());
        Files.createDirectories(dir);

        String fileName = hash(imageUrl) + extension(imageUrl);
        Path target = dir.resolve(fileName);
        if (Files.exists(target)) {
            return target; // already cached
        }

        HttpURLConnection conn = (HttpURLConnection) URI.create(imageUrl).toURL().openConnection();
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        conn.setRequestProperty("Referer", props.getReferer());
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(15_000);

        try (InputStream in = conn.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            conn.disconnect();
        }
        log.debug("Cached image {} -> {}", imageUrl, target);
        return target;
    }

    private String hash(String value) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(value.getBytes());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8 && i < digest.length; i++) {
            sb.append(String.format("%02x", digest[i]));
        }
        return sb.toString();
    }

    private String extension(String url) {
        String lower = url.toLowerCase();
        if (lower.contains(".png")) return ".png";
        if (lower.contains(".webp")) return ".webp";
        return ".jpg";
    }
}
