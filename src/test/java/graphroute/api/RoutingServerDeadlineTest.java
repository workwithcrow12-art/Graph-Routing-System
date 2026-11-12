package graphroute.api;

import graphroute.io.GraphFileManager;
import graphroute.routing.RoutingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class RoutingServerDeadlineTest {
    @Test void incompleteHeadersAndBodiesExpireAndReleaseTheOnlyWorker() throws Exception {
        try (var server = new RoutingServer(new RoutingService(new GraphFileManager().loadThanhXuanGraph()),
                0, Set.of(), 1, 2, Duration.ofSeconds(1));
             var client = HttpClient.newHttpClient()) {
            server.start();
            for (String tail : new String[]{"X-Unfinished: ",
                    "Content-Type: application/json\r\nContent-Length: 100\r\n\r\n{"}) {
                try (var socket = new Socket("127.0.0.1", server.port())) {
                    socket.setSoTimeout(3000);
                    String head = "POST /api/routes HTTP/1.1\r\nHost: 127.0.0.1:" + server.port() + "\r\n";
                    socket.getOutputStream().write((head + tail).getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    try {
                        assertEquals(-1, socket.getInputStream().read(), "Expired exchange must close");
                    } catch (SocketException reset) {
                        // A reset instead of EOF is also a transport close; SocketTimeoutException is not accepted.
                    }
                }
                var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                                + server.port() + "/api/health")).timeout(Duration.ofSeconds(3)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), response.body());
            }
        }
    }
}
