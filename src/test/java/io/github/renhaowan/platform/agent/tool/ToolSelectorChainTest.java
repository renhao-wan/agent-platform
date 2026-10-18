package io.github.renhaowan.platform.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.renhaowan.platform.agent.tool.impl.ToolSelectorChain;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 工具路由责任链契约测试：规模闸门休眠、逐环弃权传递、异常降级、链尾兜底、先接管先短路。
 */
class ToolSelectorChainTest {

    private final List<ToolCallback> tools =
            List.of(stub("a"), stub("b"), stub("c"), stub("d"));

    @Test
    void belowThresholdChainSleepsAndPassesThrough() {
        AtomicInteger consulted = new AtomicInteger();
        ToolSelectorChain chain = new ToolSelectorChain(
                List.of(recording(consulted, taking(1))), 5);

        var picked = chain.select("订会议室", tools);

        assertThat(picked).containsSame(tools); // 休眠：全量直通
        assertThat(consulted.get()).isZero();   // 节点未被咨询
    }

    @Test
    void thresholdReachedActivatesChain() {
        AtomicInteger consulted = new AtomicInteger();
        ToolSelectorChain chain = new ToolSelectorChain(
                List.of(recording(consulted, abstaining())), 4);

        chain.select("订会议室", tools);

        assertThat(consulted.get()).isEqualTo(1); // 达到阈值，节点开始被咨询
    }

    @Test
    void firstTakingHandlerShortCircuits() {
        AtomicInteger later = new AtomicInteger();
        ToolSelectorChain chain = new ToolSelectorChain(
                List.of(taking(2), recording(later, taking(3))), 1);

        var picked = chain.select("订会议室", tools).orElseThrow();

        assertThat(picked).hasSize(2);            // 前环接管即返回
        assertThat(later.get()).isZero();         // 后环不再被咨询
    }

    @Test
    void abstainPassesToNextHandler() {
        ToolSelectorChain chain = new ToolSelectorChain(
                List.of(abstaining(), taking(1)), 1);

        var picked = chain.select("订会议室", tools).orElseThrow();

        assertThat(picked).hasSize(1);
    }

    @Test
    void allAbstainFallsBackToFullToolset() {
        ToolSelectorChain chain = new ToolSelectorChain(
                List.of(abstaining(), abstaining()), 1);

        var picked = chain.select("订会议室", tools);

        assertThat(picked).containsSame(tools); // 链尾兜底：都不用 → 全量直通
    }

    @Test
    void handlerExceptionDegradesToNextHandler() {
        ToolSelectorChain chain = new ToolSelectorChain(
                List.of(throwing(), taking(1)), 1);

        var picked = chain.select("订会议室", tools).orElseThrow();

        assertThat(picked).hasSize(1); // 节点抛异常 → 降级下一环，不带崩主流程
    }

    @Test
    void noHandlersEqualsNoRouting() {
        ToolSelectorChain chain = new ToolSelectorChain(List.of(), 1);

        var picked = chain.select("订会议室", tools);

        assertThat(picked).containsSame(tools);
    }

    // —— 测试用节点 ——

    private ToolSelector taking(int size) {
        return (userText, allTools) -> Optional.of(allTools.subList(0, size));
    }

    private ToolSelector abstaining() {
        return (userText, allTools) -> Optional.empty();
    }

    private ToolSelector throwing() {
        return (userText, allTools) -> {
            throw new IllegalStateException("decision endpoint down");
        };
    }

    private ToolSelector recording(AtomicInteger calls, ToolSelector delegate) {
        return (userText, allTools) -> {
            calls.incrementAndGet();
            return delegate.select(userText, allTools);
        };
    }

    private static ToolCallback stub(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description("desc " + name)
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
            }

            @Override
            public String call(String toolInput) {
                return "ok";
            }
        };
    }
}
