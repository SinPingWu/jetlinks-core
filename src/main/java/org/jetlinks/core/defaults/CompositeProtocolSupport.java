package org.jetlinks.core.defaults;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.jetlinks.core.ProtocolSupport;
import org.jetlinks.core.device.*;
import org.jetlinks.core.message.codec.DeviceMessageCodec;
import org.jetlinks.core.message.codec.Transport;
import org.jetlinks.core.message.interceptor.DeviceMessageSenderInterceptor;
import org.jetlinks.core.metadata.ConfigMetadata;
import org.jetlinks.core.metadata.DeviceMetadata;
import org.jetlinks.core.metadata.DeviceMetadataCodec;
import org.jetlinks.core.metadata.DeviceMetadataType;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Getter
@Setter
public class CompositeProtocolSupport implements ProtocolSupport {

    private String id;

    private String name;

    private String description;

    private DeviceMetadataCodec metadataCodec;

    @Getter(AccessLevel.PRIVATE)
    private final Map<String, Supplier<Mono<ConfigMetadata>>> configMetadata = new ConcurrentHashMap<>();

    @Getter(AccessLevel.PRIVATE)
    private final Map<String, Supplier<Mono<DeviceMetadata>>> defaultDeviceMetadata = new ConcurrentHashMap<>();

    @Getter(AccessLevel.PRIVATE)
    private final Map<String, Supplier<Mono<DeviceMessageCodec>>> messageCodecSupports = new ConcurrentHashMap<>();

    @Getter(AccessLevel.PRIVATE)
    private Map<String, ExpandsConfigMetadataSupplier> expandsConfigSupplier = new ConcurrentHashMap<>();


    @Getter(AccessLevel.PRIVATE)
    @Setter(AccessLevel.PRIVATE)
    private DeviceMessageSenderInterceptor deviceMessageSenderInterceptor;

    @Getter(AccessLevel.PRIVATE)
    private Map<String, Authenticator> authenticators = new ConcurrentHashMap<>();

    private DeviceStateChecker deviceStateChecker;

    private volatile boolean disposed;

    private Disposable.Composite composite = Disposables.composite();

    private Mono<ConfigMetadata> initConfigMetadata = Mono.empty();

    private List<DeviceMetadataCodec> metadataCodecs = new ArrayList<>();

    private List<Consumer<Map<String, Object>>> doOnInit = new CopyOnWriteArrayList<>();

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        composite.dispose();
        configMetadata.clear();
        defaultDeviceMetadata.clear();
        messageCodecSupports.clear();
        expandsConfigSupplier.clear();
    }

    public void setInitConfigMetadata(ConfigMetadata metadata) {
        initConfigMetadata = Mono.just(metadata);
    }

    @Override
    public void init(Map<String, Object> configuration) {
        for (Consumer<Map<String, Object>> mapConsumer : doOnInit) {
            mapConsumer.accept(configuration);
        }
    }

    public CompositeProtocolSupport doOnDispose(Disposable disposable) {
        composite.add(disposable);
        return this;
    }

    public CompositeProtocolSupport doOnInit(Consumer<Map<String, Object>> runnable) {
        doOnInit.add(runnable);
        return this;
    }

    public void addMessageCodecSupport(Transport transport, Supplier<Mono<DeviceMessageCodec>> supplier) {
        messageCodecSupports.put(transport.getId(), supplier);
    }

    public void addMessageCodecSupport(Transport transport, DeviceMessageCodec codec) {
        messageCodecSupports.put(transport.getId(), () -> Mono.just(codec));
    }

    public void addMessageCodecSupport(DeviceMessageCodec codec) {
        addMessageCodecSupport(codec.getSupportTransport(), codec);
    }

    public void addAuthenticator(Transport transport, Authenticator authenticator) {
        authenticators.put(transport.getId(), authenticator);
    }

    public void addDefaultMetadata(Transport transport, Mono<DeviceMetadata> metadata) {
        defaultDeviceMetadata.put(transport.getId(), () -> metadata);
    }

    public void addDefaultMetadata(Transport transport, DeviceMetadata metadata) {
        defaultDeviceMetadata.put(transport.getId(), () -> Mono.just(metadata));
    }

    @Override
    public Mono<DeviceMessageSenderInterceptor> getSenderInterceptor() {
        return Mono.justOrEmpty(deviceMessageSenderInterceptor)
                   .defaultIfEmpty(DeviceMessageSenderInterceptor.DO_NOTING);
    }

    public synchronized void addMessageSenderInterceptor(DeviceMessageSenderInterceptor interceptor) {
        if (this.deviceMessageSenderInterceptor == null) {
            this.deviceMessageSenderInterceptor = interceptor;
        } else {
            CompositeDeviceMessageSenderInterceptor composite;
            if (!(this.deviceMessageSenderInterceptor instanceof CompositeDeviceMessageSenderInterceptor)) {
                composite = new CompositeDeviceMessageSenderInterceptor();
                composite.addInterceptor(this.deviceMessageSenderInterceptor);
            } else {
                composite = ((CompositeDeviceMessageSenderInterceptor) this.deviceMessageSenderInterceptor);
            }
            composite.addInterceptor(interceptor);
            this.deviceMessageSenderInterceptor = composite;
        }
    }

    public void addConfigMetadata(Transport transport, Supplier<Mono<ConfigMetadata>> metadata) {
        configMetadata.put(transport.getId(), metadata);
    }

    public void addConfigMetadata(Transport transport, ConfigMetadata metadata) {
        configMetadata.put(transport.getId(), () -> Mono.just(metadata));
    }


    public void setExpandsConfigMetadata(Transport transport,
                                         ExpandsConfigMetadataSupplier supplier) {
        expandsConfigSupplier.put(transport.getId(), supplier);
    }


    @Override
    public Flux<ConfigMetadata> getMetadataExpandsConfig(Transport transport,
                                                         DeviceMetadataType metadataType,
                                                         String metadataId,
                                                         String dataTypeId) {

        return Optional
                .ofNullable(expandsConfigSupplier.get(transport.getId()))
                .map(supplier -> supplier.getConfigMetadata(metadataType, metadataId, dataTypeId))
                .orElse(Flux.empty());
    }

    @Override
    public Mono<DeviceMetadata> getDefaultMetadata(Transport transport) {
        return Optional
                .ofNullable(defaultDeviceMetadata.get(transport.getId()))
                .map(Supplier::get)
                .orElse(Mono.empty());
    }

    @Override
    public Flux<Transport> getSupportedTransport() {
        return Flux.fromIterable(messageCodecSupports.values())
                   .flatMap(Supplier::get)
                   .map(DeviceMessageCodec::getSupportTransport)
                   .distinct(Transport::getId);
    }

    @Nonnull
    @Override
    public Mono<? extends DeviceMessageCodec> getMessageCodec(Transport transport) {
        return messageCodecSupports.getOrDefault(transport.getId(), Mono::empty).get();
    }

    @Nonnull
    @Override
    public DeviceMetadataCodec getMetadataCodec() {
        return metadataCodec;
    }

    public Flux<DeviceMetadataCodec> getMetadataCodecs() {
        return Flux.merge(Flux.just(metadataCodec), Flux.fromIterable(metadataCodecs));
    }

    public void addDeviceMetadataCodec(DeviceMetadataCodec codec) {
        metadataCodecs.add(codec);
    }

    @Nonnull
    @Override
    public Mono<AuthenticationResponse> authenticate(@Nonnull AuthenticationRequest request,
                                                     @Nonnull DeviceOperator deviceOperation) {
        return Mono.justOrEmpty(authenticators.get(request.getTransport().getId()))
                   .flatMap(at -> at
                           .authenticate(request, deviceOperation)
                           .defaultIfEmpty(AuthenticationResponse.error(400, "无法获取认证结果")))
                   .switchIfEmpty(Mono.error(() -> new UnsupportedOperationException("不支持的认证请求:" + request)));
    }

    @Nonnull
    @Override
    public Mono<AuthenticationResponse> authenticate(@Nonnull AuthenticationRequest request,
                                                     @Nonnull DeviceRegistry registry) {
        return Mono.justOrEmpty(authenticators.get(request.getTransport().getId()))
                   .flatMap(at -> at
                           .authenticate(request, registry)
                           .defaultIfEmpty(AuthenticationResponse.error(400, "无法获取认证结果")))
                   .switchIfEmpty(Mono.error(() -> new UnsupportedOperationException("不支持的认证请求:" + request)));
    }

    @Override
    public Mono<ConfigMetadata> getConfigMetadata(Transport transport) {
        return configMetadata.getOrDefault(transport.getId(), Mono::empty).get();
    }

    public Mono<ConfigMetadata> getInitConfigMetadata() {
        return initConfigMetadata;
    }

    @Nonnull
    @Override
    public Mono<DeviceStateChecker> getStateChecker() {
        return Mono.justOrEmpty(deviceStateChecker);
    }
}
