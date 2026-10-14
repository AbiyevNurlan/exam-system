package com.examportal.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The client address a request is counted under by the rate limiter.
 *
 * X-Forwarded-For is trusted only when the request comes from a configured proxy
 * (gateway.client-ip.trusted-proxies, env GATEWAY_TRUSTED_PROXIES: IPs or CIDRs, comma separated).
 * Then the header is read from the right, and the first address that is not itself a trusted proxy
 * is the client: a client can put anything at the left of the header, but not past the proxy that
 * appended the address it really came from. Otherwise, and when nothing is configured (the
 * default), the remote address of the connection is used and the header is ignored.
 */
@Slf4j
@Component
public class ClientIpResolver {

    static final String UNKNOWN = "unknown";

    private final List<Cidr> trustedProxies;

    public ClientIpResolver(@Value("${gateway.client-ip.trusted-proxies:}") String trustedProxies) {
        this.trustedProxies = parse(trustedProxies);
        log.info("Rate limit client address: {}", this.trustedProxies.isEmpty()
                ? "remote address only (no trusted proxy configured)"
                : "X-Forwarded-For through " + this.trustedProxies.size() + " trusted proxy range(s)");
    }

    public String resolve(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        String remoteIp = remote != null && remote.getAddress() != null ? remote.getAddress().getHostAddress() : null;
        if (remoteIp == null) {
            return UNKNOWN;
        }
        if (trustedProxies.isEmpty() || !isTrusted(remoteIp)) {
            return remoteIp;
        }
        List<String> forwarded = request.getHeaders().getOrEmpty("X-Forwarded-For").stream()
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
        for (int i = forwarded.size() - 1; i >= 0; i--) {
            String candidate = forwarded.get(i);
            if (!isIpLiteral(candidate)) {
                // Garbage in the chain: stop trusting it and count the proxy itself
                return remoteIp;
            }
            if (!isTrusted(candidate)) {
                return candidate;
            }
        }
        // Every hop is a trusted proxy (or there is no header): the leftmost known address
        return forwarded.isEmpty() ? remoteIp : forwarded.get(0);
    }

    boolean isTrusted(String ip) {
        byte[] address = toBytes(ip);
        return address != null && trustedProxies.stream().anyMatch(cidr -> cidr.contains(address));
    }

    private static List<Cidr> parse(String value) {
        List<Cidr> result = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return result;
        }
        for (String part : value.split(",")) {
            String entry = part.trim();
            if (entry.isEmpty()) {
                continue;
            }
            String[] pieces = entry.split("/", 2);
            byte[] network = toBytes(pieces[0].trim());
            if (network == null) {
                throw new IllegalArgumentException("gateway.client-ip.trusted-proxies: not an IP address or CIDR: " + entry);
            }
            int bits = pieces.length == 2 ? Integer.parseInt(pieces[1].trim()) : network.length * 8;
            if (bits < 0 || bits > network.length * 8) {
                throw new IllegalArgumentException("gateway.client-ip.trusted-proxies: bad prefix length: " + entry);
            }
            result.add(new Cidr(network, bits));
        }
        return List.copyOf(result);
    }

    // Only literal addresses: a host name in a header must never cause a DNS lookup
    private static boolean isIpLiteral(String value) {
        return value.matches("[0-9.]+") || (value.contains(":") && value.matches("[0-9a-fA-F:.]+"));
    }

    private static byte[] toBytes(String ip) {
        if (ip == null || !isIpLiteral(ip)) {
            return null;
        }
        try {
            return InetAddress.getByName(ip).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private record Cidr(byte[] network, int bits) {
        boolean contains(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            int full = bits / 8;
            for (int i = 0; i < full; i++) {
                if (address[i] != network[i]) {
                    return false;
                }
            }
            int rest = bits % 8;
            if (rest == 0) {
                return true;
            }
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (address[full] & mask) == (network[full] & mask);
        }
    }
}
