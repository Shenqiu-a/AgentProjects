# 文件位置清单

项目根目录：`A:/A_workSpace/AgentProjects/after-sales-agent`。完整步骤见 [粘贴指南](./粘贴指南.md)。

| 操作 | 目标相对路径 | 可复制文件 |
|---|---|---|
| 替换/合并 | `backend/agent-service/pom.xml` | [打开](./files/backend/agent-service/pom.xml) |
| 新增 | `backend/agent-service/src/main/java/com/example/aftersales/agent/ActiveTicketRepository.java` | [打开](./files/backend/agent-service/src/main/java/com/example/aftersales/agent/ActiveTicketRepository.java) |
| 新增 | `backend/agent-service/src/main/java/com/example/aftersales/agent/InternalTicketController.java` | [打开](./files/backend/agent-service/src/main/java/com/example/aftersales/agent/InternalTicketController.java) |
| 新增 | `backend/agent-service/src/main/java/com/example/aftersales/agent/InternalTicketSecurityConfiguration.java` | [打开](./files/backend/agent-service/src/main/java/com/example/aftersales/agent/InternalTicketSecurityConfiguration.java) |
| 新增 | `backend/agent-service/src/test/java/com/example/aftersales/agent/InternalTicketIntegrationTest.java` | [打开](./files/backend/agent-service/src/test/java/com/example/aftersales/agent/InternalTicketIntegrationTest.java) |
| 新增 | `backend/agent-service/src/test/resources/s1-active-ticket.sql` | [打开](./files/backend/agent-service/src/test/resources/s1-active-ticket.sql) |
| 替换/合并 | `backend/order-service/pom.xml` | [打开](./files/backend/order-service/pom.xml) |
| 新增 | `backend/order-service/src/main/java/com/example/aftersales/order/ActiveTicketClient.java` | [打开](./files/backend/order-service/src/main/java/com/example/aftersales/order/ActiveTicketClient.java) |
| 新增 | `backend/order-service/src/main/java/com/example/aftersales/order/EligibilityService.java` | [打开](./files/backend/order-service/src/main/java/com/example/aftersales/order/EligibilityService.java) |
| 新增 | `backend/order-service/src/main/java/com/example/aftersales/order/OrderClockConfiguration.java` | [打开](./files/backend/order-service/src/main/java/com/example/aftersales/order/OrderClockConfiguration.java) |
| 替换/合并 | `backend/order-service/src/main/java/com/example/aftersales/order/OrderController.java` | [打开](./files/backend/order-service/src/main/java/com/example/aftersales/order/OrderController.java) |
| 新增 | `backend/order-service/src/main/java/com/example/aftersales/order/OrderDatabaseExceptionHandler.java` | [打开](./files/backend/order-service/src/main/java/com/example/aftersales/order/OrderDatabaseExceptionHandler.java) |
| 新增 | `backend/order-service/src/main/java/com/example/aftersales/order/OrderDtos.java` | [打开](./files/backend/order-service/src/main/java/com/example/aftersales/order/OrderDtos.java) |
| 新增 | `backend/order-service/src/main/java/com/example/aftersales/order/OrderRepository.java` | [打开](./files/backend/order-service/src/main/java/com/example/aftersales/order/OrderRepository.java) |
| 新增 | `backend/order-service/src/main/java/com/example/aftersales/order/OrderService.java` | [打开](./files/backend/order-service/src/main/java/com/example/aftersales/order/OrderService.java) |
| 新增 | `backend/order-service/src/main/java/com/example/aftersales/order/ProductController.java` | [打开](./files/backend/order-service/src/main/java/com/example/aftersales/order/ProductController.java) |
| 新增 | `backend/order-service/src/main/java/com/example/aftersales/order/ProductRepository.java` | [打开](./files/backend/order-service/src/main/java/com/example/aftersales/order/ProductRepository.java) |
| 替换/合并 | `backend/order-service/src/main/resources/application.yml` | [打开](./files/backend/order-service/src/main/resources/application.yml) |
| 新增 | `backend/order-service/src/test/java/com/example/aftersales/order/ActiveTicketClientTest.java` | [打开](./files/backend/order-service/src/test/java/com/example/aftersales/order/ActiveTicketClientTest.java) |
| 新增 | `backend/order-service/src/test/java/com/example/aftersales/order/EligibilityServiceTest.java` | [打开](./files/backend/order-service/src/test/java/com/example/aftersales/order/EligibilityServiceTest.java) |
| 新增 | `backend/order-service/src/test/java/com/example/aftersales/order/OrderApiIntegrationTest.java` | [打开](./files/backend/order-service/src/test/java/com/example/aftersales/order/OrderApiIntegrationTest.java) |
| 新增 | `backend/order-service/src/test/java/com/example/aftersales/order/OrderServiceTest.java` | [打开](./files/backend/order-service/src/test/java/com/example/aftersales/order/OrderServiceTest.java) |
| 新增 | `backend/order-service/src/test/resources/s1-orders.sql` | [打开](./files/backend/order-service/src/test/resources/s1-orders.sql) |
| 替换/合并 | `backend/service-common/src/main/java/com/example/aftersales/common/ApiResponses.java` | [打开](./files/backend/service-common/src/main/java/com/example/aftersales/common/ApiResponses.java) |
| 新增 | `backend/service-common/src/main/java/com/example/aftersales/platform/ServiceApiException.java` | [打开](./files/backend/service-common/src/main/java/com/example/aftersales/platform/ServiceApiException.java) |
| 替换/合并 | `backend/service-common/src/main/java/com/example/aftersales/platform/ServiceExceptionHandler.java` | [打开](./files/backend/service-common/src/main/java/com/example/aftersales/platform/ServiceExceptionHandler.java) |
| 替换/合并 | `compose.yaml` | [打开](./files/compose.yaml) |
| 替换/合并 | `deploy/microservices/nginx.conf` | [打开](./files/deploy/microservices/nginx.conf) |
| 替换/合并 | `frontend/vite.config.ts` | [打开](./files/frontend/vite.config.ts) |
| 新增 | `scripts/seed-s1-active-ticket.sql` | [打开](./files/scripts/seed-s1-active-ticket.sql) |
| 新增 | `scripts/seed-s1-orders.sql` | [打开](./files/scripts/seed-s1-orders.sql) |
| 新增 | `scripts/verify_s1_backend.py` | [打开](./files/scripts/verify_s1_backend.py) |
