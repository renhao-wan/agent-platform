package io.github.renhaowan.platform.agent.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class AgentEventEmitterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void heartbeatSendsComment() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        AgentEventEmitter events = new AgentEventEmitter(emitter, mapper, 0);

        events.heartbeat();

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        events.close();
    }

    @Test
    void scheduledHeartbeatFiresWithoutBusinessEvents() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        AgentEventEmitter events = new AgentEventEmitter(emitter, mapper, 30);

        // 确认等待期内没有任何业务事件，只能靠定时 comment 避免代理 idle timeout 断链
        verify(emitter, timeout(1000).atLeastOnce()).send(any(SseEmitter.SseEventBuilder.class));
        events.close();
    }

    @Test
    void closedEmitterStopsHeartbeat() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        AgentEventEmitter events = new AgentEventEmitter(emitter, mapper, 0);

        events.close();
        events.heartbeat();

        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void failedHeartbeatMarksEmitterClosed() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IOException("broken pipe")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        AgentEventEmitter events = new AgentEventEmitter(emitter, mapper, 0);

        events.heartbeat();
        events.heartbeat();

        assertThat(events.isClosed()).isTrue();
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }
}
