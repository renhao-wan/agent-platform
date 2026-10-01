package io.github.renhaowan.platform.gateway.session;

import io.github.renhaowan.platform.gateway.session.entity.GatewaySession;
import io.github.renhaowan.platform.gateway.session.mapper.GatewaySessionMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import io.github.renhaowan.platform.gateway.session.impl.GatewaySessionServiceImpl;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import io.github.renhaowan.platform.gateway.security.GatewayProperties;

@ExtendWith(MockitoExtension.class)
class GatewaySessionServiceTest {

    @Mock
    private GatewaySessionMapper sessionMapper;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RBucket<String> bucket;

    private GatewaySessionService service() {
        GatewayProperties properties = new GatewayProperties();
        return new GatewaySessionServiceImpl(sessionMapper, redissonClient, properties);
    }

    @Test
    void createPersistsAuditRowAndRedisState() {
        doReturn(bucket).when(redissonClient).getBucket(anyString());

        GatewaySessionService.SessionState state = service().create("gw", "SSE", 7L);

        assertThat(state.sessionKey()).hasSize(36);
        assertThat(state.gatewayId()).isEqualTo("gw");
        assertThat(state.tenantId()).isEqualTo(7L);
        verify(sessionMapper).insert(any(GatewaySession.class));
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(bucket).set(json.capture(), eq(Duration.ofMinutes(30)));
        assertThat(json.getValue()).contains("\"gatewayId\":\"gw\"");
    }

    @Test
    void validateReturnsStateAndTouchesTtl() {
        doReturn(bucket).when(redissonClient).getBucket(anyString());
        when(bucket.get()).thenReturn(
                "{\"sessionKey\":\"s1\",\"tenantId\":7,\"gatewayId\":\"gw\","
                        + "\"transport\":\"SSE\",\"instanceId\":\"local-1\",\"createdAtEpochMs\":1}");

        Optional<GatewaySessionService.SessionState> state = service().validate("s1");

        assertThat(state).isPresent();
        assertThat(state.get().tenantId()).isEqualTo(7L);
        verify(bucket).expire(Duration.ofMinutes(30));
    }

    @Test
    void validateReturnsEmptyWhenSessionMissing() {
        doReturn(bucket).when(redissonClient).getBucket(anyString());
        when(bucket.get()).thenReturn(null);

        assertThat(service().validate("unknown")).isEmpty();
    }

    @Test
    void validateReturnsEmptyWhenRedisDown() {
        when(redissonClient.getBucket(anyString())).thenThrow(new IllegalStateException("down"));

        assertThat(service().validate("s1")).isEmpty();
    }

    @Test
    void closeDeletesRedisAndStampsExpiry() {
        doReturn(bucket).when(redissonClient).getBucket(anyString());

        service().close("s1");

        verify(bucket).delete();
        verify(sessionMapper).update(any(GatewaySession.class), any());
    }
}
