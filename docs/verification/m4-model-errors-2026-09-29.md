# AC-24：模型错误分类与单次尝试

在 Java 21 本地假 Anthropic Messages HTTP 服务上，`AnthropicModelGatewayIT` 验证：401/403 映射 `MODEL_AUTH_ERROR` 且不泄漏提供方错误详情，429 映射 `MODEL_RATE_LIMITED`，故意延迟的请求映射 `MODEL_TIMEOUT`，拒绝连接映射 `MODEL_NETWORK_ERROR`。可收到请求的每个错误场景均只有一次 HTTP 尝试；客户端构造时设置 `maxRetries(0)`。`AgentLoop` 只消费完整适配器响应，由后端显式分发工具。

回归发现 SDK 在请求超时时可能以 `AnthropicIoException → InterruptedIOException("timeout") → SocketException` 包装，而先前只识别 `SocketTimeoutException` 和 `HttpTimeoutException`，会误报网络错误。适配器现识别异常链中带明确 timeout 文本的 `InterruptedIOException`，同时保留普通连接失败的网络错误分类。测试不依赖真实模型密钥。

验证：设置 `MOJIE_TEST_LOCAL_HTTP=1` 后，`mvn -o -Dtest=AnthropicModelGatewayIT test -q` 四个测试通过；同时连接 PostgreSQL 18.6 运行 `mvn -o verify -q` 通过。真实提供方的错误文案与账号配额仍应在真实模型验收时记录。
