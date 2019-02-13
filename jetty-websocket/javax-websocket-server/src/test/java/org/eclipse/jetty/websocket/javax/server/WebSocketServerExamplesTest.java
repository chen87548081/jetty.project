package org.eclipse.jetty.websocket.javax.server;

import java.net.URI;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.ContainerProvider;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import javax.websocket.server.ServerContainer;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.AuthenticationStore;
import org.eclipse.jetty.client.util.BasicAuthentication;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.security.UserStore;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.security.Credential;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.javax.client.JavaxWebSocketClientContainer;
import org.eclipse.jetty.websocket.javax.server.examples.GetHttpSessionSocket;
import org.eclipse.jetty.websocket.javax.server.examples.MyAuthedSocket;
import org.eclipse.jetty.websocket.javax.server.examples.StreamingEchoSocket;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class WebSocketServerExamplesTest
{
    @ClientEndpoint
    public static class ClientSocket
    {
        CountDownLatch closed = new CountDownLatch(1);
        ArrayBlockingQueue<String> messageQueue = new ArrayBlockingQueue<>(2);

        @OnOpen
        public void onOpen(Session sess)
        {
            System.err.println("ClientSocket Connected: " + sess);
        }

        @OnMessage
        public void onMessage(String message)
        {
            messageQueue.offer(message);
            System.err.println("Received TEXT message: " + message);
        }

        @OnClose
        public void onClose(CloseReason closeReason)
        {
            System.err.println("ClientSocket Closed: " + closeReason);
            closed.countDown();
        }

        @OnError
        public void onError(Throwable cause)
        {
            cause.printStackTrace(System.err);
        }
    }

    static Server _server;
    static ServletContextHandler _context;

    @BeforeAll
    public static void setup() throws Exception
    {
        _server = new Server();
        ServerConnector connector = new ServerConnector(_server);
        connector.setPort(8080);
        _server.addConnector(connector);

        _context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        _context.setContextPath("/");
        _context.setSecurityHandler(getSecurityHandler("user", "password", "testRealm"));
        _server.setHandler(_context);

        ServerContainer serverContainer = JavaxWebSocketServletContainerInitializer.configureContext(_context);
        serverContainer.addEndpoint(MyAuthedSocket.class);
        serverContainer.addEndpoint(StreamingEchoSocket.class);
        serverContainer.addEndpoint(GetHttpSessionSocket.class);

        _server.start();
    }

    @AfterAll
    public static void stop() throws Exception
    {
        _server.stop();
    }

    private static SecurityHandler getSecurityHandler(String username, String password, String realm) {

        HashLoginService loginService = new HashLoginService();
        UserStore userStore = new UserStore();
        userStore.addUser(username, Credential.getCredential(password), new String[] {"websocket"});
        loginService.setUserStore(userStore);
        loginService.setName(realm);

        Constraint constraint = new Constraint();
        constraint.setName("auth");
        constraint.setAuthenticate(true);
        constraint.setRoles(new String[]{"**"});

        ConstraintMapping mapping = new ConstraintMapping();
        mapping.setPathSpec("/secured/socket/*");
        mapping.setConstraint(constraint);

        ConstraintSecurityHandler security = new ConstraintSecurityHandler();
        security.addConstraintMapping(mapping);
        security.setAuthenticator(new BasicAuthenticator());
        security.setLoginService(loginService);

        return security;
    }

    @Test
    public void testMyAuthedSocket() throws Exception
    {
        URI uri = URI.create("ws://localhost:8080/secured/socket");
        HttpClient client = new HttpClient(new SslContextFactory()); // TODO: use HttpClientProvider from issue #3341
        AuthenticationStore authenticationStore = client.getAuthenticationStore();
        authenticationStore.addAuthentication(new BasicAuthentication(uri, "testRealm", "user", "password"));
        JavaxWebSocketClientContainer clientContainer = new JavaxWebSocketClientContainer(new WebSocketCoreClient(client));
        clientContainer.start(); // TODO: use container provider

        ClientSocket clientEndpoint = new ClientSocket();
        try(Session session = clientContainer.connectToServer(clientEndpoint, uri))
        {
            session.getBasicRemote().sendText("hello world");
        }
        clientEndpoint.closed.await(5, TimeUnit.SECONDS);

        String msg = clientEndpoint.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat(msg, is("hello world"));

        clientContainer.stop();
    }

    @Test
    public void testStreamingEchoSocket() throws Exception
    {
        URI uri = URI.create("ws://localhost:8080/echo");
        WebSocketContainer clientContainer = ContainerProvider.getWebSocketContainer();

        ClientSocket clientEndpoint = new ClientSocket();
        try(Session session = clientContainer.connectToServer(clientEndpoint, uri))
        {
            session.getBasicRemote().sendText("hello world");
        }
        clientEndpoint.closed.await(5, TimeUnit.SECONDS);

        String msg = clientEndpoint.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat(msg, is("hello world"));
    }

    @Test
    public void testGetHttpSessionSocket() throws Exception
    {
        URI uri = URI.create("ws://localhost:8080/example");
        WebSocketContainer clientContainer = ContainerProvider.getWebSocketContainer();

        ClientSocket clientEndpoint = new ClientSocket();
        try(Session session = clientContainer.connectToServer(clientEndpoint, uri))
        {
            session.getBasicRemote().sendText("hello world");
        }
        clientEndpoint.closed.await(5, TimeUnit.SECONDS);

        String msg = clientEndpoint.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat(msg, is("hello world"));
    }
}
