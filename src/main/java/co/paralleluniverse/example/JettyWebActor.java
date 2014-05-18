package co.paralleluniverse.example;

import co.paralleluniverse.actors.*;
import co.paralleluniverse.comsat.webactors.*;
import static co.paralleluniverse.comsat.webactors.HttpResponse.*;
import co.paralleluniverse.comsat.webactors.servlet.WebActorInitializer;
import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.strands.channels.Channels;
import co.paralleluniverse.strands.channels.SendPort;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;

@WebActor(httpUrlPatterns = {"/*"}, webSocketUrlPatterns = {"/ws"})
public class JettyWebActor extends BasicActor<Object, Void> {
    private static final String webSocketHtml = loadResourceAsByteArray("index.html");
    private static final String sseHtml = loadResourceAsByteArray("sseclient.html");
    private static final Set<ActorRef<Object>> actors = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private SendPort<WebDataMessage> peer;

    public static void main(String[] args) throws IOException, InterruptedException, Exception {
        Server server = new Server(8080);
        ServletContextHandler webActorsContext = new ServletContextHandler(ServletContextHandler.SESSIONS);
        webActorsContext.addEventListener(new WebActorInitializer(ClassLoader.getSystemClassLoader()));
        server.setHandler(webActorsContext);
        WebSocketServerContainerInitializer.configureContext(webActorsContext);
        server.start();
        System.out.println("open http://localhost:8080/ in your browser");
        server.join();
    }

    @Override
    protected Void doRun() throws InterruptedException, SuspendExecution {
        actors.add(self());
        try {
            for (;;) {
                Object message = receive();
                if (message instanceof HttpRequest) {
                    HttpRequest msg = (HttpRequest) message;
                    switch (msg.getRequestURI()) {
                        case "/":
                            msg.getFrom().send(ok(self(), msg, webSocketHtml)
                                    .setContentType("text/html").build());
                            break;
                        case "/sseclient":
                            msg.getFrom().send(ok(self(), msg, sseHtml)
                                    .setContentType("text/html").build());
                            break;
                        case "/ssepublish":
                            postMessage(new WebDataMessage(self(), msg.getStringBody()));
                            msg.getFrom().send(ok(self(), msg, "").build());
                            break;
                        case "/ssechannel":
                            msg.getFrom().send(SSE.startSSE(self(), msg).build());
                            break;

                    }
                } // -------- WebSocket/SSE opened -------- 
                else if (message instanceof WebStreamOpened) {
                    WebStreamOpened msg = (WebStreamOpened) message;
                    watch(msg.getFrom()); // will call handleLifecycleMessage with ExitMessage when the session ends

                    SendPort<WebDataMessage> p = msg.getFrom();
                    if (msg instanceof HttpStreamOpened)
                        p = Channels.mapSend(p, (WebDataMessage f) -> new WebDataMessage(f.getFrom(), SSE.event(f.getStringBody())));
                    this.peer = p;

                    p.send(new WebDataMessage(self(), "Welcome. " + actors.size() + " listeners"));
                } // -------- WebSocket message received -------- 
                else if (message instanceof WebDataMessage) {
                    postMessage((WebDataMessage) message);
                }
            }
        } finally {
            actors.remove(self());
        }
    }

    private void postMessage(final WebDataMessage webDataMessage) throws InterruptedException, SuspendExecution {
        if (peer != null)
            peer.send(webDataMessage);
        if (webDataMessage.getFrom().equals(peer))
            for (SendPort actor : actors)
                if (actor != self())
                    actor.send(webDataMessage);
    }

    @Override
    protected Object handleLifecycleMessage(LifecycleMessage m) {
        // while listeners might contain an SSE actor wrapped with Channels.map, the wrapped SendPort maintains the original actors hashCode and equals behavior
        if (m instanceof ExitMessage) {
            ActorRef actor = ((ExitMessage) m).getActor();
            System.out.println("actor "+actor);
            System.out.println("list ");
            actors.stream().forEach(System.out::println);
            boolean remove = actors.remove(actor);
            System.out.println("remove "+remove);
        }
        return super.handleLifecycleMessage(m);
    }

    private static String loadResourceAsByteArray(final String filename) {
        try {
            return new String(Files.readAllBytes(Paths.get(ClassLoader.getSystemClassLoader().getResource(filename).toURI())));
        } catch (IOException | URISyntaxException ex) {
            throw new RuntimeException(ex);
        }
    }
}
