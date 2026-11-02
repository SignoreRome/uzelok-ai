package uzelok.config;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

public final class ProxySupport {

    private static final int DEFAULT_PORT = 8080;
    private static final String INVALID_PROXY_URL = "некорректный proxy-url";

    public record ProxyConfig(Proxy proxy, String user, char[] password) {

        public boolean hasCredentials() {
            return user != null && !user.isEmpty();
        }
    }

    private ProxySupport() {
    }

    public static Optional<ProxyConfig> parse(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException exception) {
            throw invalid();
        }
        if (!"http".equalsIgnoreCase(uri.getScheme())) {
            throw invalid();
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw invalid();
        }
        int port = uri.getPort() == -1 ? DEFAULT_PORT : uri.getPort();
        if (port < 1 || port > 65535) {
            throw invalid();
        }

        String user = null;
        char[] password = new char[0];
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null && !userInfo.isEmpty()) {
            int separator = userInfo.indexOf(':');
            if (separator < 0) {
                user = userInfo;
            } else {
                user = userInfo.substring(0, separator);
                password = userInfo.substring(separator + 1).toCharArray();
            }
            if (user.isEmpty()) {
                user = null;
            }
        }

        Proxy proxy = new Proxy(Proxy.Type.HTTP,
                InetSocketAddress.createUnresolved(host, port));
        return Optional.of(new ProxyConfig(proxy, user, password));
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(INVALID_PROXY_URL);
    }
}
