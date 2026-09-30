/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The forward proxy the live modes talk through: where it is, which hosts skip it, and the credentials it
 * asks for. No picocli and no REST — {@link ConnectionOptions} supplies the flags, {@link MidpointRestClient}
 * and {@link ServerCertificates} consume the result.
 *
 * <p><b>Nothing configured means nothing installed.</b> {@link #resolve} returns {@code null} when neither a
 * flag nor an environment variable names a proxy, and the client then leaves the JDK's default
 * {@link ProxySelector} alone — so a {@code -Dhttps.proxyHost} passed through {@code JAVA_OPTS} keeps working
 * exactly as it did before midgie had any proxy support of its own. midgie's own settings simply win when
 * they are there.
 *
 * <p><b>Why the environment matters here.</b> The JVM reads none of {@code HTTPS_PROXY} / {@code HTTP_PROXY} /
 * {@code NO_PROXY}, but the git that the container image forks does. A pod given only {@code HTTPS_PROXY}
 * would otherwise clone its tree happily and then fail to reach midPoint, which reads as a midgie bug rather
 * than as a JVM that ignores the variable everything else honours.
 */
public final class ProxyConfig {

    private static final Logger LOG = LoggerFactory.getLogger(ProxyConfig.class);

    /**
     * The bypass list when none is given. It is what the JDK's own default {@code http.nonProxyHosts} covers,
     * and keeping it means turning proxy support on does not send a local rig's traffic to a proxy that has
     * never heard of it. A list given explicitly <em>replaces</em> this rather than adding to it — the same
     * rule the JDK property follows — so {@code --no-proxy ''} is how you say "proxy everything, loopback
     * included".
     */
    private static final List<String> LOOPBACK_BYPASS = List.of("localhost", "127.", "::1", "[::1]", "0.0.0.0");

    private final URI proxyUri;
    private final InetSocketAddress address;
    private final List<String> bypass;
    private final boolean bypassEverything;
    private final String user;
    private final String password;
    private final String source;

    private ProxyConfig(URI proxyUri, List<String> bypass, String user, String password, String source) {
        this.proxyUri = proxyUri;
        this.address = InetSocketAddress.createUnresolved(proxyUri.getHost(), proxyUri.getPort());
        this.bypass = bypass;
        this.bypassEverything = bypass.contains("*");
        this.user = user;
        this.password = password;
        this.source = source;
    }

    /**
     * The proxy to use for {@code target}, or {@code null} for a direct connection.
     *
     * <p>Precedence is the one every other setting follows: the option (which the config file also feeds)
     * first, then the environment. The variable consulted depends on the scheme of the target, as it does for
     * curl and git — {@code https_proxy} for an https server, {@code http_proxy} for a plain one — and the
     * lowercase spelling is preferred, that being the older and more specific convention.
     *
     * @throws IllegalArgumentException if the value is not a usable HTTP proxy; the message is meant to be
     *         printed as-is
     */
    static ProxyConfig resolve(String proxyOption, String noProxyOption, URI target, UnaryOperator<String> env) {
        String value = proxyOption;
        String source = "--proxy";
        boolean fromEnvironment = false;
        if (isBlank(value)) {
            for (String variable : variablesFor(target)) {
                String candidate = env.apply(variable);
                if (!isBlank(candidate)) {
                    value = candidate;
                    source = variable;
                    fromEnvironment = true;
                    break;
                }
            }
        }
        if (isBlank(value)) {
            return null;
        }
        URI uri = parse(value.trim(), source);

        String user = null;
        String password = null;
        String userInfo = uri.getUserInfo();
        if (userInfo != null) {
            if (fromEnvironment) {
                // The credentials-in-the-URL form is how the whole ecosystem writes this, and an environment
                // variable is not on show in 'ps' the way a command line is, so it is taken as given.
                int colon = userInfo.indexOf(':');
                user = (colon < 0) ? userInfo : userInfo.substring(0, colon);
                password = (colon < 0) ? null : userInfo.substring(colon + 1);
            } else {
                // Same reasoning as the credentials embedded in --url: the option would put them in 'ps' and
                // in shell history, and every error message that names the proxy would print them back.
                System.err.println("midgie: warning: ignoring the credentials embedded in the proxy URL; "
                        + "pass them with --proxy-user and --proxy-password-file/--proxy-password-env instead.");
            }
        }

        List<String> bypass = bypassList(noProxyOption, env);
        ProxyConfig config = new ProxyConfig(withoutUserInfo(uri), bypass, user, password, source);
        LOG.debug("proxy: {} (bypass: {})", config.describe(), String.join(",", bypass));
        return config;
    }

    /**
     * The variables to consult for this target, lowercase first.
     *
     * <p>Both spellings, because both are in use and neither is authoritative; lowercase wins where they
     * disagree, that being the older convention and the one curl documents. There is deliberately <b>no</b>
     * fallback from {@code https_proxy} to {@code http_proxy}: curl, git, Go and Python all treat the two as
     * separate settings, and a site that sets only {@code http_proxy} means it. Falling back would put an
     * https server through a proxy that the git in the same image would not have used — the very asymmetry
     * reading these variables exists to remove.
     */
    private static List<String> variablesFor(URI target) {
        boolean https = target != null && "https".equalsIgnoreCase(target.getScheme());
        return https ? List.of("https_proxy", "HTTPS_PROXY") : List.of("http_proxy", "HTTP_PROXY");
    }

    /**
     * Parse a proxy value into a URI with a host and a port.
     *
     * <p>A bare {@code host:port} is accepted — that is how most tooling lets you write it — and anything
     * midgie cannot actually use is refused here with the reason, rather than left to fail later as a
     * connection error that says nothing about why.
     */
    private static URI parse(String value, String source) {
        String text = value.contains("://") ? value : "http://" + value;
        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(source + " is not a URL: " + shown(value));
        }
        String scheme = (uri.getScheme() == null) ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.startsWith("socks")) {
            throw new IllegalArgumentException(source + " names a SOCKS proxy (" + shown(value) + "); "
                    + "java.net.http speaks only to HTTP proxies. Use an HTTP proxy, or -DsocksProxyHost via "
                    + "JAVA_OPTS.");
        }
        if ("https".equals(scheme)) {
            throw new IllegalArgumentException(source + " names an https:// proxy (" + shown(value) + "); "
                    + "the JDK's HTTP client cannot wrap the connection to the proxy itself in TLS. Use http:// for "
                    + "the proxy — an https:// target is still tunnelled end-to-end through it with CONNECT.");
        }
        if (!"http".equals(scheme)) {
            throw new IllegalArgumentException(source + " must be an http:// proxy URL or host:port, not "
                    + shown(value));
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException(source + " has no host: " + shown(value));
        }
        // No port means the scheme's default, as it would for any other http:// URL. Guessing a proxy port
        // instead (3128, 8080) would be right for one site and quietly wrong for the next.
        int port = (uri.getPort() > 0) ? uri.getPort() : 80;
        try {
            return new URI("http", uri.getUserInfo(), uri.getHost(), port, null, null, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(source + " is not a usable proxy URL: " + shown(value));
        }
    }

    /**
     * {@code value} as a message may show it: with any {@code user:password@} before the host replaced. Done on
     * the text, since the messages that need it are the ones about a value that does not parse — and a proxy
     * variable is the one place a CI job is likely to carry a password, straight into its log.
     */
    static String shown(String value) {
        int scheme = value.indexOf("://");
        int start = (scheme < 0) ? 0 : scheme + 3;
        int slash = value.indexOf('/', start);
        int end = (slash < 0) ? value.length() : slash;
        int at = value.lastIndexOf('@', end - 1);
        return (at < start) ? value : value.substring(0, start) + "***@" + value.substring(at + 1);
    }

    private static URI withoutUserInfo(URI uri) {
        try {
            return new URI("http", null, uri.getHost(), uri.getPort(), null, null, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("cannot strip the credentials from the proxy URL");
        }
    }

    /**
     * The hosts that skip the proxy: the option, else {@code no_proxy}/{@code NO_PROXY}, else loopback.
     *
     * <p>An explicit list replaces the built-in one instead of extending it. That is what the JDK property it
     * stands in for does, and the alternative — a loopback exemption nothing can remove — would make a proxy
     * in front of a local rig untestable.
     */
    private static List<String> bypassList(String noProxyOption, UnaryOperator<String> env) {
        String value = noProxyOption;
        if (value == null) {
            for (String variable : List.of("no_proxy", "NO_PROXY")) {
                String candidate = env.apply(variable);
                if (candidate != null) {
                    value = candidate;
                    break;
                }
            }
        }
        if (value == null) {
            return LOOPBACK_BYPASS;
        }
        List<String> entries = new ArrayList<>();
        for (String entry : value.split("[,|]")) {
            String cleaned = entry.trim().toLowerCase(Locale.ROOT);
            // A port on an entry is accepted and ignored: the decision is per host, and refusing the form
            // would reject a NO_PROXY somebody's whole toolchain already shares.
            int colon = cleaned.lastIndexOf(':');
            if (colon > 0 && cleaned.indexOf(':') == colon && cleaned.substring(colon + 1).matches("\\d+")) {
                cleaned = cleaned.substring(0, colon);
            }
            if (cleaned.startsWith("*.")) {
                cleaned = cleaned.substring(1);
            }
            if (!cleaned.isEmpty()) {
                entries.add(cleaned);
            }
        }
        return List.copyOf(entries);
    }

    /**
     * Whether {@code host} goes direct.
     *
     * <p>Entry semantics are curl's, because that is what a {@code NO_PROXY} in an existing environment was
     * written for: {@code *} bypasses everything, a leading dot matches subdomains only
     * ({@code .corp.example} covers {@code mp.corp.example} but not {@code corp.example}), and a bare name
     * matches the host itself and anything under it. A trailing dot on either side is ignored, and so is case.
     */
    boolean bypasses(String host) {
        if (bypassEverything) {
            return true;
        }
        if (host == null) {
            return false;
        }
        String candidate = host.toLowerCase(Locale.ROOT);
        if (candidate.endsWith(".")) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        for (String entry : bypass) {
            if (entry.startsWith(".")) {
                if (candidate.endsWith(entry)) {
                    return true;
                }
            } else if (entry.endsWith(".")) {
                // A literal prefix, which is how the loopback default covers all of 127.0.0.0/8.
                if (candidate.startsWith(entry)) {
                    return true;
                }
            } else if (candidate.equals(entry) || candidate.endsWith("." + entry)) {
                return true;
            }
        }
        return false;
    }

    /** A copy carrying credentials given as options, which outrank anything the environment's URL held. */
    ProxyConfig withCredentials(String user, String password) {
        if (user == null) {
            return this;
        }
        return new ProxyConfig(proxyUri, bypass, user, password, source);
    }

    /** True when this proxy asks for credentials and has them. */
    boolean hasCredentials() {
        return user != null && password != null;
    }

    String user() {
        return user;
    }

    String password() {
        return password;
    }

    /** Unresolved on purpose: name resolution belongs to whoever connects, not to the configuration. */
    InetSocketAddress address() {
        return address;
    }

    /** A selector that sends everything through the proxy except the hosts on the bypass list. */
    ProxySelector selector() {
        Proxy proxy = new Proxy(Proxy.Type.HTTP, address);
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                boolean direct = uri == null || bypasses(uri.getHost());
                return List.of(direct ? Proxy.NO_PROXY : proxy);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress socketAddress, java.io.IOException failure) {
                // There is no second proxy to fall back to, so this is a note for --trace, not a decision.
                LOG.debug("proxy connection to {} failed for {}: {}", socketAddress, uri, failure.getMessage());
            }
        };
    }

    /**
     * The proxy credentials, and only ever those.
     *
     * <p>An {@link Authenticator} is asked for the origin server too, and midPoint's own password is already
     * sent as a preemptive Basic header. Answering a server challenge here would hand the proxy's password to
     * whatever returned a 401 — so the requestor type is checked and anything but {@code PROXY} gets nothing.
     */
    Authenticator authenticator() {
        if (!hasCredentials()) {
            return null;
        }
        return new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                if (getRequestorType() != RequestorType.PROXY) {
                    LOG.debug("not answering a {} authentication challenge from {} with the proxy credentials",
                            getRequestorType(), getRequestingHost());
                    return null;
                }
                return new PasswordAuthentication(user, password.toCharArray());
            }
        };
    }

    /** Where it is and who it authenticates as — never the password; this goes into the debug log. */
    String describe() {
        return proxyUri + (user != null ? " (user " + user + ")" : "") + " from " + source;
    }

    @Override
    public String toString() {
        return describe();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
