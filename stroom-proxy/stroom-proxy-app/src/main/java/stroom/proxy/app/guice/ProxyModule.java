package stroom.proxy.app.guice;

import stroom.collection.mock.MockCollectionModule;
import stroom.dictionary.impl.DictionaryModule;
import stroom.dictionary.impl.DictionaryStore;
import stroom.docstore.api.DocumentResourceHelper;
import stroom.docstore.api.Serialiser2Factory;
import stroom.docstore.api.StoreFactory;
import stroom.docstore.impl.DocumentResourceHelperImpl;
import stroom.docstore.impl.Persistence;
import stroom.docstore.impl.Serialiser2FactoryImpl;
import stroom.docstore.impl.StoreFactoryImpl;
import stroom.docstore.impl.fs.FSPersistence;
import stroom.dropwizard.common.LogLevelInspector;
import stroom.dropwizard.common.PermissionExceptionMapper;
import stroom.dropwizard.common.TokenExceptionMapper;
import stroom.importexport.api.ImportExportActionHandler;
import stroom.legacy.impex_6_1.LegacyImpexModule;
import stroom.proxy.app.BufferFactoryImpl;
import stroom.proxy.app.Config;
import stroom.proxy.app.ContentSyncService;
import stroom.proxy.app.ProxyConfigHealthCheck;
import stroom.proxy.app.ProxyConfigHolder;
import stroom.proxy.app.ProxyPathConfig;
import stroom.proxy.app.RestClientConfig;
import stroom.proxy.app.handler.ForwardStreamHandlerFactory;
import stroom.proxy.app.handler.ProxyRequestHandler;
import stroom.proxy.app.handler.RemoteFeedStatusService;
import stroom.proxy.app.servlet.ProxySecurityFilter;
import stroom.proxy.app.servlet.ProxyStatusServlet;
import stroom.proxy.app.servlet.ProxyWelcomeServlet;
import stroom.proxy.repo.ProxyLifecycle;
import stroom.proxy.repo.ProxyRepositoryManager;
import stroom.proxy.repo.ProxyRepositoryReader;
import stroom.proxy.repo.StreamHandlerFactory;
import stroom.receive.common.DataReceiptPolicyAttributeMapFilterFactory;
import stroom.receive.common.DebugServlet;
import stroom.receive.common.FeedStatusResourceImpl;
import stroom.receive.common.FeedStatusService;
import stroom.receive.common.ReceiveDataServlet;
import stroom.receive.common.RemoteFeedModule;
import stroom.receive.common.RequestHandler;
import stroom.receive.rules.impl.DataReceiptPolicyAttributeMapFilterFactoryImpl;
import stroom.receive.rules.impl.ReceiveDataRuleSetResourceImpl;
import stroom.receive.rules.impl.ReceiveDataRuleSetService;
import stroom.receive.rules.impl.ReceiveDataRuleSetServiceImpl;
import stroom.security.api.SecurityContext;
import stroom.security.mock.MockSecurityContext;
import stroom.task.impl.TaskContextModule;
import stroom.util.BuildInfoProvider;
import stroom.util.config.ConfigLocation;
import stroom.util.entityevent.EntityEventBus;
import stroom.util.guice.FilterBinder;
import stroom.util.guice.FilterInfo;
import stroom.util.guice.GuiceUtil;
import stroom.util.guice.HasHealthCheckBinder;
import stroom.util.guice.RestResourcesBinder;
import stroom.util.guice.ServletBinder;
import stroom.util.io.BufferFactory;
import stroom.util.io.HomeDirProvider;
import stroom.util.io.HomeDirProviderImpl;
import stroom.util.io.PathConfig;
import stroom.util.io.PathCreator;
import stroom.util.io.TempDirProvider;
import stroom.util.io.TempDirProviderImpl;
import stroom.util.shared.BuildInfo;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import io.dropwizard.client.JerseyClientBuilder;
import io.dropwizard.client.JerseyClientConfiguration;
import io.dropwizard.lifecycle.Managed;
import io.dropwizard.setup.Environment;
import org.glassfish.jersey.logging.LoggingFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Optional;
import javax.inject.Provider;
import javax.ws.rs.client.Client;
import javax.ws.rs.ext.ExceptionMapper;

public class ProxyModule extends AbstractModule {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProxyModule.class);

    // This name is used by dropwizard metrics
    private static final String PROXY_JERSEY_CLIENT_NAME = "stroom-proxy_jersey_client";
    private static final String PROXY_JERSEY_CLIENT_USER_AGENT_PREFIX = "stroom-proxy/";

    private final Config configuration;
    private final Environment environment;
    private final ProxyConfigHolder proxyConfigHolder;

    public ProxyModule(final Config configuration,
                       final Environment environment,
                       final Path configFile) {
        this.configuration = configuration;
        this.environment = environment;

        proxyConfigHolder = new ProxyConfigHolder(
                configuration.getProxyConfig(),
                configFile);
    }

    @Override
    protected void configure() {
        bind(Config.class).toInstance(configuration);
        bind(Environment.class).toInstance(environment);

        install(new ProxyConfigModule(proxyConfigHolder));
        install(new MockCollectionModule());

        install(new DictionaryModule());
        // Allow discovery of feed status from other proxies.
        install(new RemoteFeedModule());

        install(new TaskContextModule());
        install(new LegacyImpexModule());

        bind(BuildInfo.class).toProvider(BuildInfoProvider.class);
        bind(BufferFactory.class).to(BufferFactoryImpl.class);
        bind(DataReceiptPolicyAttributeMapFilterFactory.class).to(DataReceiptPolicyAttributeMapFilterFactoryImpl.class);
        bind(DocumentResourceHelper.class).to(DocumentResourceHelperImpl.class);
        bind(FeedStatusService.class).to(RemoteFeedStatusService.class);
        bind(ProxyRepositoryManager.class).asEagerSingleton();
        bind(ProxyRepositoryReader.class).asEagerSingleton();
        bind(ReceiveDataRuleSetService.class).to(ReceiveDataRuleSetServiceImpl.class);
        bind(RequestHandler.class).to(ProxyRequestHandler.class);
        bind(SecurityContext.class).to(MockSecurityContext.class);
        bind(Serialiser2Factory.class).to(Serialiser2FactoryImpl.class);
        bind(StoreFactory.class).to(StoreFactoryImpl.class);
        bind(StreamHandlerFactory.class).to(ForwardStreamHandlerFactory.class);

        bind(HomeDirProvider.class).to(HomeDirProviderImpl.class);
        bind(TempDirProvider.class).to(TempDirProviderImpl.class);

        HasHealthCheckBinder.create(binder())
                .bind(ContentSyncService.class)
                .bind(FeedStatusResourceImpl.class)
                .bind(ForwardStreamHandlerFactory.class)
                .bind(LogLevelInspector.class)
                .bind(ProxyConfigHealthCheck.class)
                .bind(ProxyRepositoryManager.class)
                .bind(RemoteFeedStatusService.class);

        FilterBinder.create(binder())
                .bind(new FilterInfo(ProxySecurityFilter.class.getSimpleName(), "/*"),
                        ProxySecurityFilter.class);

        ServletBinder.create(binder())
                .bind(DebugServlet.class)
                .bind(ProxyStatusServlet.class)
                .bind(ProxyWelcomeServlet.class)
                .bind(ReceiveDataServlet.class);

        RestResourcesBinder.create(binder())
                .bind(ReceiveDataRuleSetResourceImpl.class)
                .bind(FeedStatusResourceImpl.class);

        GuiceUtil.buildMultiBinder(binder(), Managed.class)
                .addBinding(ContentSyncService.class)
                .addBinding(ProxyLifecycle.class);

        GuiceUtil.buildMultiBinder(binder(), ExceptionMapper.class)
                .addBinding(PermissionExceptionMapper.class)
                .addBinding(TokenExceptionMapper.class);

        GuiceUtil.buildMultiBinder(binder(), ImportExportActionHandler.class)
                .addBinding(ReceiveDataRuleSetService.class)
                .addBinding(DictionaryStore.class);
    }

    @Provides
    @Singleton
    Persistence providePersistence(final PathCreator pathCreator) {
        return new FSPersistence(
                configuration.getProxyConfig().getProxyContentDir(),
                pathCreator);
    }

    @Provides
    @Singleton
    Client provideJerseyClient(final RestClientConfig restClientConfig,
                               final Environment environment,
                               final Provider<BuildInfo> buildInfoProvider) {

        // RestClientConfig is really just JerseyClientConfiguration
        final JerseyClientConfiguration jerseyClientConfiguration = restClientConfig;

        // If the userAgent has not been explicitly set in the config then set it based
        // on the build version
        if (jerseyClientConfiguration.getUserAgent().isEmpty()) {
            final String userAgent = PROXY_JERSEY_CLIENT_USER_AGENT_PREFIX
                    + buildInfoProvider.get().getBuildVersion();
            LOGGER.info("Setting jersey client user agent string to [{}]", userAgent);
            jerseyClientConfiguration.setUserAgent(Optional.of(userAgent));
        }

        LOGGER.info("Creating jersey client {}", PROXY_JERSEY_CLIENT_NAME);
        return new JerseyClientBuilder(environment)
                .using(jerseyClientConfiguration)
                .build(PROXY_JERSEY_CLIENT_NAME)
                .register(LoggingFeature.class);
    }

    @Provides
    EntityEventBus entityEventBus() {
        return event -> {
        };
    }
}
