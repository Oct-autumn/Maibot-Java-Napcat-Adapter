package org.maibot.mods.ncada;

import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.maibot.sdk.net.HttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.ExecutionException;

public class Utils {
    private static final Logger log = LoggerFactory.getLogger(Utils.class);

    public static byte[] getImgData(String url) {
        try {
            var request = buildGetFullHttpRequest(url);
            var response = HttpClient.request(url, request).get();
            if (response.status().code() != 200) {
                throw new RuntimeException("Failed to fetch image, status code: " + response.status().code());
            } else {
                var content = response.content();
                byte[] data = new byte[content.readableBytes()];
                content.readBytes(data);
                return data;
            }
        } catch (InterruptedException e) {
            log.warn("中断获取图片，URL: {}", url);
            Thread.currentThread().interrupt();
        } catch (RuntimeException | URISyntaxException | ExecutionException e) {
            log.error("获取图片失败，URL: {}", url, e);
        }
        return null;
    }

    private static DefaultFullHttpRequest buildGetFullHttpRequest(String url)
    throws URISyntaxException {
        var parsedUri = new URI(url);
        var requestUri = "/";
        if (parsedUri.getPath() != null && !parsedUri.getPath().isEmpty()) {
            requestUri = parsedUri.getPath();
        }
        if (parsedUri.getQuery() != null && !parsedUri.getQuery().isEmpty()) {
            requestUri += "?" + parsedUri.getQuery();
        }
        return new DefaultFullHttpRequest(
          HttpVersion.HTTP_1_1,
          HttpMethod.GET,
          requestUri
        );
    }
}
