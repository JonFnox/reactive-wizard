package se.fortnox.reactivewizard.server;

import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.message.Message;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import se.fortnox.reactivewizard.test.LoggingMockUtil;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.*;
import static reactor.core.publisher.Mono.empty;
import static se.fortnox.reactivewizard.test.TestUtil.matches;

@RunWith(MockitoJUnitRunner.class)
public class RwServerTest {

    @Mock
    ConnectionCounter connectionCounter;

    @Mock
    CompositeRequestHandler compositeRequestHandler;

    ArgumentCaptor<LogEvent> logCaptor;

    Appender mockAppender;

    @Before
    public void before() {
        RwServer.registerShutdownDependency(null);
        mockAppender = LoggingMockUtil.createMockedLogAppender(RwServer.class);
        logCaptor = ArgumentCaptor.forClass(LogEvent.class);
    }

    @After
    public void after() {
        LoggingMockUtil.destroyMockedAppender(RwServer.class);
    }

    @Test
    public void shouldSetServerToNullIfConfigSaysDisabled() throws InterruptedException {
        ServerConfig serverConfig = new ServerConfig();
        serverConfig.setEnabled(false);
        serverConfig.setPort(0);

        RwServer rwServer = new RwServer(serverConfig, compositeRequestHandler, connectionCounter);
        rwServer.join();

        assertNull(rwServer.getServer());
    }

    @Test
    public void shouldStartTheServerIfConfigSaysEnabled() throws InterruptedException {
        ServerConfig serverConfig = new ServerConfig();
        serverConfig.setPort(0);
        AtomicInteger startInvokedNumberOfTimes = new AtomicInteger(0);

        RwServer rwServer = null;
        try {
            rwServer = new RwServer(serverConfig, compositeRequestHandler, connectionCounter) {
                @Override
                public void start() {
                    startInvokedNumberOfTimes.incrementAndGet();
                }
            };
            rwServer.join();

            assertThat(rwServer.getServer()).isNotNull().isInstanceOf(DisposableServer.class);
            assertThat(startInvokedNumberOfTimes.get()).isEqualTo(1);
        } finally {
            if (rwServer != null) {
                rwServer.getServer().disposeNow();
            }
        }
    }

    @Test
    public void shouldAwaitShutDown() throws InterruptedException {
        ServerConfig serverConfig = new ServerConfig();

        DisposableServer disposableServer = Mockito.mock(DisposableServer.class);
        when(disposableServer.onDispose()).thenReturn(empty());
        RwServer rwServer = new RwServer(serverConfig, connectionCounter, HttpServer.create(), compositeRequestHandler, disposableServer);
        rwServer.join();

        verify(disposableServer).onDispose();
    }

    @Test
    public void shouldLogThatShutDownIsRegistered() {
        RwServer rwServer = null;
        try {
            final ServerConfig config = new ServerConfig();
            config.setPort(0);
            config.setShutdownDelaySeconds(3);
            rwServer = new RwServer(config, compositeRequestHandler, connectionCounter);
            RwServer.shutdownHook(config, rwServer.getServer(), connectionCounter);

            verify(mockAppender, atLeastOnce()).append(logCaptor.capture());

            assertThat(logCaptor.getAllValues())
                .extracting(LogEvent::getMessage)
                .extracting(Message::getFormattedMessage)
                .contains("Shutdown requested. Waiting 3 seconds before commencing.")
                .contains("Shutdown commencing. Will wait up to 20 seconds for ongoing requests to complete.")
                .contains("Shutdown complete");
        } finally {
            if (rwServer != null) {
                rwServer.getServer().disposeNow();
            }
        }
    }

    @Test
    public void shouldCallServerShutDownWhenShutdownHookIsInvoked() {
        DisposableServer disposableServer = mock(DisposableServer.class);

        RwServer.shutdownHook(new ServerConfig(), disposableServer, connectionCounter);

        verify(disposableServer).disposeNow(any());
    }

    @Test
    public void shouldLogErrorIfShutdownIsPerformedWhileConnectionCountIsNotZero() {
        when(connectionCounter.awaitZero(anyInt(), any(TimeUnit.class))).thenReturn(false);
        when(connectionCounter.getCount()).thenReturn(4L);
        RwServer rwServer = null;
        try {
            final ServerConfig config = new ServerConfig();
            config.setPort(0);
            rwServer = new RwServer(config, compositeRequestHandler, connectionCounter);
            RwServer.shutdownHook(config, rwServer.getServer(), connectionCounter);

            verify(mockAppender, atLeastOnce()).append(logCaptor.capture());

            assertThat(logCaptor.getAllValues())
                .extracting(LogEvent::getMessage)
                .extracting(Message::getFormattedMessage)
                .contains("Shutdown requested. Waiting 5 seconds before commencing.")
                .contains("Shutdown commencing. Will wait up to 20 seconds for ongoing requests to complete.")
                .contains("Shutdown proceeded while connection count was not zero: 4");

        } finally {
            rwServer.getServer().disposeNow();
        }
    }

    @Test
    public void shouldNotOverrideShutdownDependency() {
        RwServer.registerShutdownDependency(() -> {
        });
        try {
            RwServer.registerShutdownDependency(() -> {
            });
            fail();
        } catch (IllegalStateException e) {
            assertThat(e.getMessage()).isEqualTo("Shutdown dependency is already registered");
        }
    }

    @Test
    public void shouldSkipAwaitingShutdownDependencyIfNotSet() {
        RwServer.awaitShutdownDependency(new ServerConfig().getShutdownTimeoutSeconds());
        verify(mockAppender, never()).append(any());
    }

    @Test
    public void shouldAwaitShutdownDependency() {
        Supplier supplier = mock(Supplier.class);

        RwServer.registerShutdownDependency(supplier::get);
        verify(supplier, never()).get();

        RwServer.awaitShutdownDependency(new ServerConfig().getShutdownTimeoutSeconds());

        verify(mockAppender).append(matches(log ->
            assertThat(log.getMessage().getFormattedMessage()).matches("Wait for completion of shutdown dependency")
        ));
        verify(supplier).get();
        verify(mockAppender).append(matches(log ->
            assertThat(log.getMessage().getFormattedMessage()).matches("Shutdown dependency completed, continue...")
        ));
    }

    @Test
    public void shouldUseServerConfigurers() {
        RwServer rwServer = null;

        try {
            final ServerConfig config = new ServerConfig();
            config.setPort(0);
            final ReactorServerConfigurer mock = mock(ReactorServerConfigurer.class);
            when(mock.configure(any(HttpServer.class))).thenAnswer(invocationOnMock -> {
                return invocationOnMock.getArgument(0, HttpServer.class);
            });
            rwServer = new RwServer(config, compositeRequestHandler, connectionCounter, Set.of(mock));
            verify(mock).configure(any(HttpServer.class));
        } finally {
            rwServer.getServer().disposeNow();
        }
    }
}
