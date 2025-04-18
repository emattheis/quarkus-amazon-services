package io.quarkiverse.amazon.common.deployment;

import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import jakarta.enterprise.inject.spi.DeploymentException;

import org.jboss.jandex.DotName;
import org.jboss.jandex.Type;

import io.quarkiverse.amazon.common.AmazonClient;
import io.quarkiverse.amazon.common.AmazonClientBuilder;
import io.quarkiverse.amazon.common.runtime.AsyncHttpClientBuildTimeConfig.AsyncClientType;
import io.quarkiverse.amazon.common.runtime.AwsSdkTelemetryProducer;
import io.quarkiverse.amazon.common.runtime.SdkBuildTimeConfig;
import io.quarkiverse.amazon.common.runtime.SyncHttpClientBuildTimeConfig.SyncClientType;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.AdditionalApplicationArchiveMarkerBuildItem;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageProxyDefinitionBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.RuntimeInitializedClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ServiceProviderBuildItem;
import io.quarkus.runtime.configuration.ConfigurationException;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.SdkHttpService;
import software.amazon.awssdk.http.async.SdkAsyncHttpService;

public class AmazonServicesClientsProcessor {
    public static final String AWS_SDK_APPLICATION_ARCHIVE_MARKERS = "software/amazon/awssdk";
    public static final String AWS_SDK_XRAY_ARCHIVE_MARKER = "com/amazonaws/xray";

    private static final DotName EXECUTION_INTERCEPTOR_NAME = DotName.createSimple(ExecutionInterceptor.class.getName());

    @BuildStep
    AdditionalBeanBuildItem additionalBeans() {
        return new AdditionalBeanBuildItem(AmazonClient.class, AmazonClientBuilder.class);
    }

    @BuildStep
    void globalInterceptors(BuildProducer<AmazonClientInterceptorsPathBuildItem> producer) {
        producer.produce(
                new AmazonClientInterceptorsPathBuildItem("software/amazon/awssdk/global/handlers/execution.interceptors"));
    }

    @BuildStep
    void awsAppArchiveMarkers(BuildProducer<AdditionalApplicationArchiveMarkerBuildItem> archiveMarker) {
        archiveMarker.produce(new AdditionalApplicationArchiveMarkerBuildItem(AWS_SDK_APPLICATION_ARCHIVE_MARKERS));
        archiveMarker.produce(new AdditionalApplicationArchiveMarkerBuildItem(AWS_SDK_XRAY_ARCHIVE_MARKER));
    }

    @BuildStep
    void setupInterceptors(List<AmazonClientInterceptorsPathBuildItem> interceptors,
            BuildProducer<NativeImageResourceBuildItem> resource,
            CombinedIndexBuildItem combinedIndexBuildItem,
            List<RequireAmazonClientTransportBuilderBuildItem> amazonClients,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClasses,
            BuildProducer<UnremovableBeanBuildItem> unremovables) {

        interceptors.stream().map(AmazonClientInterceptorsPathBuildItem::getInterceptorsPath)
                .forEach(path -> resource.produce(new NativeImageResourceBuildItem(path)));

        //Discover all interceptor implementations
        List<String> knownInterceptorImpls = combinedIndexBuildItem.getIndex()
                .getAllKnownImplementors(EXECUTION_INTERCEPTOR_NAME)
                .stream()
                .map(c -> c.name().toString()).collect(Collectors.toList());

        //Validate configurations
        for (RequireAmazonClientTransportBuilderBuildItem client : amazonClients) {
            SdkBuildTimeConfig clientSdkConfig = client.getBuildTimeSdkConfig();
            if (clientSdkConfig != null) {
                clientSdkConfig.interceptors().orElse(Collections.emptyList()).forEach(interceptorClassName -> {
                    interceptorClassName = interceptorClassName.trim();
                    if (!knownInterceptorImpls.contains(interceptorClassName)) {
                        throw new ConfigurationException(
                                String.format(
                                        "quarkus.%s.interceptors (%s) - must list only existing implementations of software.amazon.awssdk.core.interceptor.ExecutionInterceptor",
                                        client.getAwsClientName(),
                                        clientSdkConfig.interceptors().toString()));
                    }
                });
            }
        }

        reflectiveClasses.produce(ReflectiveClassBuildItem
                .builder(knownInterceptorImpls.toArray(new String[knownInterceptorImpls.size()])).build());

        List<DotName> interceptorDotNames = knownInterceptorImpls.stream().map(DotName::createSimple)
                .collect(Collectors.toList());
        unremovables.produce(new UnremovableBeanBuildItem(beanInfo -> {
            return beanInfo.getTypes().stream()
                    .map(Type::name)
                    .anyMatch(interceptorDotNames::contains);
        }));
    }

    @BuildStep
    void runtimeInitialize(BuildProducer<RuntimeInitializedClassBuildItem> producer) {
        // FullJitterBackoffStrategy uses j.u.Ramdom, so needs to be runtime-initialized
        producer.produce(
                new RuntimeInitializedClassBuildItem("software.amazon.awssdk.core.retry.backoff.FullJitterBackoffStrategy"));
        // CachedSupplier uses j.u.Ramdom, so needs to be runtime-initialized
        producer.produce(
                new RuntimeInitializedClassBuildItem("software.amazon.awssdk.utils.cache.CachedSupplier"));
    }

    @BuildStep
    void setupTelemetry(List<RequireAmazonTelemetryBuildItem> items,
            Capabilities capabilities,
            BuildProducer<AdditionalBeanBuildItem> additionalBuildItem) {
        if (items.isEmpty())
            return;

        if (!capabilities.isPresent(Capability.OPENTELEMETRY_TRACER)) {
            throw new DeploymentException(
                    "Telemetry enabled for " + String.join(", ", items.stream().map(item -> item.getConfigName()).toList())
                            + " but 'io.quarkus:quarkus-opentelemetry' dependency is missing on the classpath");
        }

        additionalBuildItem.produce(AdditionalBeanBuildItem.unremovableOf(AwsSdkTelemetryProducer.class));
    }

    @BuildStep
    void setup(
            List<RequireAmazonClientTransportBuilderBuildItem> amazonClients,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClasses,
            BuildProducer<NativeImageProxyDefinitionBuildItem> proxyDefinition,
            BuildProducer<ServiceProviderBuildItem> serviceProvider) {

        reflectiveClasses
                .produce(ReflectiveClassBuildItem.builder("com.sun.xml.internal.stream.XMLInputFactoryImpl",
                        "com.sun.xml.internal.stream.XMLOutputFactoryImpl").methods().build());

        boolean syncTransportNeeded = amazonClients.stream().anyMatch(item -> item.getSyncClassName().isPresent());
        boolean asyncTransportNeeded = amazonClients.stream().anyMatch(item -> item.getAsyncClassName().isPresent());
        final Predicate<RequireAmazonClientTransportBuilderBuildItem> isSyncApache = client -> client
                .getBuildTimeSyncConfig().type() == SyncClientType.APACHE;
        final Predicate<RequireAmazonClientTransportBuilderBuildItem> isSyncCrt = client -> client
                .getBuildTimeSyncConfig().type() == SyncClientType.AWS_CRT;
        final Predicate<RequireAmazonClientTransportBuilderBuildItem> isAsyncNetty = client -> client
                .getBuildTimeAsyncConfig().type() == AsyncClientType.NETTY;

        // Register what's needed depending on the clients in the classpath and the configuration.
        // We use the configuration to guide us but if we don't have any clients configured,
        // we still register what's needed depending on what is in the classpath.
        boolean isSyncApacheInClasspath = new AmazonHttpClients.IsAmazonApacheHttpServicePresent().getAsBoolean();
        boolean isSyncUrlConnectionInClasspath = new AmazonHttpClients.IsAmazonUrlConnectionHttpServicePresent().getAsBoolean();
        boolean isAsyncNettyInClasspath = new AmazonHttpClients.IsAmazonNettyHttpServicePresent().getAsBoolean();
        boolean isAwsCrtInClasspath = new AmazonHttpClients.IsAmazonAwsCrtHttpServicePresent().getAsBoolean();

        // Check that the clients required by the configuration are available
        if (syncTransportNeeded) {
            if (amazonClients.stream().filter(isSyncApache).findAny().isPresent()) {
                if (isSyncApacheInClasspath) {
                    registerSyncApacheClient(proxyDefinition, serviceProvider);
                } else {
                    throw missingDependencyException("apache-client");
                }
            } else if (amazonClients.stream().filter(isSyncCrt).findAny().isPresent()) {
                if (isAwsCrtInClasspath) {
                    registerSyncAwsCrtClient(serviceProvider);
                } else {
                    throw missingDependencyException("aws-crt-client");
                }
            } else {
                if (isSyncUrlConnectionInClasspath) {
                    registerSyncUrlConnectionClient(serviceProvider);
                } else {
                    throw missingDependencyException("url-connection-client");
                }
            }
        } else {
            // even if we don't register any clients via configuration, we still register the clients
            // but this time only based on the classpath.
            if (isSyncApacheInClasspath) {
                registerSyncApacheClient(proxyDefinition, serviceProvider);
            } else if (isSyncUrlConnectionInClasspath) {
                registerSyncUrlConnectionClient(serviceProvider);
            }
        }

        if (asyncTransportNeeded) {
            if (amazonClients.stream().filter(isAsyncNetty).findAny().isPresent()) {
                if (isAsyncNettyInClasspath) {
                    registerAsyncNettyClient(serviceProvider);
                } else {
                    throw missingDependencyException("netty-nio-client");
                }
            } else {
                if (isAwsCrtInClasspath) {
                    registerAsyncAwsCrtClient(serviceProvider);
                } else {
                    throw missingDependencyException("aws-crt-client");
                }
            }
        } else {
            // even if we don't register any clients via configuration, we still register the clients
            // but this time only based on the classpath.
            if (isAsyncNettyInClasspath) {
                registerAsyncNettyClient(serviceProvider);
            } else if (isAwsCrtInClasspath) {
                registerAsyncAwsCrtClient(serviceProvider);
            }
        }
    }

    private static void registerSyncApacheClient(BuildProducer<NativeImageProxyDefinitionBuildItem> proxyDefinition,
            BuildProducer<ServiceProviderBuildItem> serviceProvider) {
        proxyDefinition
                .produce(new NativeImageProxyDefinitionBuildItem("org.apache.http.conn.HttpClientConnectionManager",
                        "org.apache.http.pool.ConnPoolControl",
                        "software.amazon.awssdk.http.apache.internal.conn.Wrapped"));

        serviceProvider.produce(
                new ServiceProviderBuildItem(SdkHttpService.class.getName(), AmazonHttpClients.APACHE_HTTP_SERVICE));
    }

    private static void registerSyncAwsCrtClient(BuildProducer<ServiceProviderBuildItem> serviceProvider) {
        serviceProvider.produce(
                new ServiceProviderBuildItem(SdkHttpService.class.getName(),
                        AmazonHttpClients.AWS_CRT_HTTP_SERVICE));
    }

    private static void registerSyncUrlConnectionClient(BuildProducer<ServiceProviderBuildItem> serviceProvider) {
        serviceProvider.produce(
                new ServiceProviderBuildItem(SdkHttpService.class.getName(), AmazonHttpClients.URL_CONNECTION_HTTP_SERVICE));
    }

    private static void registerAsyncNettyClient(BuildProducer<ServiceProviderBuildItem> serviceProvider) {
        serviceProvider.produce(
                new ServiceProviderBuildItem(SdkAsyncHttpService.class.getName(),
                        AmazonHttpClients.NETTY_HTTP_SERVICE));
    }

    private static void registerAsyncAwsCrtClient(BuildProducer<ServiceProviderBuildItem> serviceProvider) {
        serviceProvider.produce(
                new ServiceProviderBuildItem(SdkAsyncHttpService.class.getName(),
                        AmazonHttpClients.AWS_CRT_HTTP_SERVICE));
    }

    private DeploymentException missingDependencyException(String dependencyName) {
        return new DeploymentException("Missing 'software.amazon.awssdk:" + dependencyName + "' dependency on the classpath");
    }
}
