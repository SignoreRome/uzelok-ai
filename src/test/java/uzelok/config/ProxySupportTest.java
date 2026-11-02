package uzelok.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.net.Proxy;
import org.junit.jupiter.api.Test;

class ProxySupportTest {

    @Test
    void parsesProxyWithCredentials() {
        ProxySupport.ProxyConfig config = ProxySupport.parse(
                "http://user:secret@proxy.example:3128").orElseThrow();

        InetSocketAddress address = (InetSocketAddress) config.proxy().address();
        assertThat(config.proxy().type()).isEqualTo(Proxy.Type.HTTP);
        assertThat(address.getHostString()).isEqualTo("proxy.example");
        assertThat(address.getPort()).isEqualTo(3128);
        assertThat(address.isUnresolved()).isTrue();
        assertThat(config.user()).isEqualTo("user");
        assertThat(new String(config.password())).isEqualTo("secret");
        assertThat(config.hasCredentials()).isTrue();
    }

    @Test
    void parsesProxyWithoutCredentials() {
        ProxySupport.ProxyConfig config = ProxySupport.parse(
                "http://proxy.example:3128").orElseThrow();

        InetSocketAddress address = (InetSocketAddress) config.proxy().address();
        assertThat(address.getHostString()).isEqualTo("proxy.example");
        assertThat(address.getPort()).isEqualTo(3128);
        assertThat(config.user()).isNull();
        assertThat(config.password()).isEmpty();
        assertThat(config.hasCredentials()).isFalse();
    }

    @Test
    void usesDefaultPortWhenAbsent() {
        ProxySupport.ProxyConfig config = ProxySupport.parse("http://proxy.example")
                .orElseThrow();

        InetSocketAddress address = (InetSocketAddress) config.proxy().address();
        assertThat(address.getPort()).isEqualTo(8080);
    }

    @Test
    void blankUrlMeansNoProxy() {
        assertThat(ProxySupport.parse(null)).isEmpty();
        assertThat(ProxySupport.parse("")).isEmpty();
        assertThat(ProxySupport.parse("   ")).isEmpty();
    }

    @Test
    void nonNumericPortIsRejectedWithoutLeakingValue() {
        String url = "http://user:s3cret@proxy.example:notaport";

        assertThatThrownBy(() -> ProxySupport.parse(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("некорректный proxy-url")
                .hasNoCause()
                .satisfies(exception -> assertThat(exception.getMessage())
                        .doesNotContain("s3cret", "proxy.example"));
    }

    @Test
    void missingSchemeIsRejectedWithoutLeakingValue() {
        String url = "//user:s3cret@proxy.example:8080";

        assertThatThrownBy(() -> ProxySupport.parse(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("некорректный proxy-url")
                .hasNoCause()
                .satisfies(exception -> assertThat(exception.getMessage())
                        .doesNotContain("s3cret", "proxy.example"));
    }
}
