package io.github.renhaowan.platform.gateway.registry;

/** 拉取 OpenAPI 契约正文；抽成接口是为了让对账逻辑不依赖 HTTP，可直接用固定 JSON 测试。 */
public interface ContractFetcher {

    String fetch(String url);
}
