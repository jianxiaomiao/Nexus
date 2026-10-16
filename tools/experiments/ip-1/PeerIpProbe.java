import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/** Isolated transport observation: no Spring, database, credentials or business calls. */
public class PeerIpProbe {
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 18081), 0);
        server.createContext("/peer", exchange -> {
            if (!exchange.getRequestMethod().equals("GET")
                    || !exchange.getRequestURI().getPath().equals("/peer")) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            var peer = exchange.getRemoteAddress();
            var local = exchange.getLocalAddress();
            var headers = exchange.getRequestHeaders();
            String observation = "peer=" + endpoint(peer) + "\n"
                    + "local=" + endpoint(local) + "\n"
                    + "xRealIpPresent=" + headers.containsKey("X-Real-IP") + "\n"
                    + "xForwardedForPresent=" + headers.containsKey("X-Forwarded-For") + "\n"
                    + "forwardedPresent=" + headers.containsKey("Forwarded") + "\n"
                    + "xRealIp=" + diagnosticValue(headers.getFirst("X-Real-IP")) + "\n"
                    + "xForwardedFor=" + diagnosticValue(headers.getFirst("X-Forwarded-For")) + "\n"
                    + "forwarded=" + diagnosticValue(headers.getFirst("Forwarded")) + "\n";
            System.out.println(observation);
            byte[] body = observation.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            } finally {
                exchange.close();
            }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
        server.start();
        System.out.println("Java peer IP probe listening on 127.0.0.1:18081");
    }

    private static String endpoint(InetSocketAddress address) {
        String ip = address.getAddress().getHostAddress();
        return (ip.contains(":") ? "[" + ip + "]" : ip) + ":" + address.getPort();
    }

    // Display bounded diagnostic input, without treating the header as a trusted identity.
    private static String diagnosticValue(String value) {
        if (value == null) {
            return "<absent>";
        }
        String bounded = value.substring(0, Math.min(value.length(), 200));
        return bounded.replaceAll("\\p{Cntrl}", "?");
    }
}
