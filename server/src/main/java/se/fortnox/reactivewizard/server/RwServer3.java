package se.fortnox.reactivewizard.server;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Module;
import com.google.inject.name.Names;
import com.google.inject.util.Modules;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.guice.module.BeanFactoryProvider;
import org.springframework.guice.module.SpringModule;
import se.fortnox.reactivewizard.binding.AutoBindModules;
import se.fortnox.reactivewizard.config.ConfigFactory;
import se.fortnox.reactivewizard.logging.LoggingFactory;

import java.nio.file.NoSuchFileException;

public class RwServer3 {

    public static void main(String[] args) {
        try {
            ConfigFactory  configFactory  = createConfigFactory(args);
            LoggingFactory loggingFactory = configFactory.get(LoggingFactory.class);
            loggingFactory.init();
            Module bootstrap = new AbstractModule() {
                @Override
                protected void configure() {
                    bind(String[].class).annotatedWith(Names.named("args")).toInstance(args);
                    bind(ConfigFactory.class).toInstance(configFactory);
                }
            };

            //bootstrap = Modules.override(bootstrap).with(new SpringModule(BeanFactoryProvider.from(RwServerConfigOnly.class)));

            Guice.createInjector(new AutoBindModules(bootstrap), new SpringModule(BeanFactoryProvider.from(RwServerConfig.class)));


        } catch (Exception e) {
            // Since logging is configured at runtime we cant have a static logger.
            LoggerFactory
                .getLogger(RwServer3.class)
                .error("Caught exception at startup.", e);
            System.exit(-1);
        }
    }

    /**
     * Prepares a ConfigFactory before setting up Guice.
     * <p>
     * As logging can be part of the configuration file and the configuration file
     * could be missing, we have a side effect of setting up and initializing a LoggingFactory
     * that doesn't depend on ConfigFactory, if the configuration file is missing.
     *
     * @param args commandline arguments
     * @return A ConfigFactory based on a configuration file.
     * @throws NoSuchFileException If the configuration file cannot be found.
     */
    private static ConfigFactory createConfigFactory(String[] args) throws NoSuchFileException {
        try {
            return new ConfigFactory(args);
        } catch (RuntimeException runtimeException) {
            Throwable cause = runtimeException.getCause();
            if (cause != null && cause.getClass().isAssignableFrom(NoSuchFileException.class)) {
                LoggingFactory loggingFactory = new LoggingFactory();
                loggingFactory.init();
                throw (NoSuchFileException)cause;
            }
            throw runtimeException;
        }
    }
}
