# 管理列表分页

Application、API Key 和短链接的**现有 JWT 管理查询**已改为服务端分页；没有增加平行查询方法。机器端 `GET /v1/short-links` 保持原有契约。

| 查询 | 必填范围 | 可选精确过滤 |
| --- | --- | --- |
| `GET /api/application` | 当前登录用户 | `applicationId` |
| `GET /api/apiKey/{applicationId}` | 所属 Application | `apiKeyId` |
| `GET /api/short-links?apiKeyId=...` | 所属 API Key | — |

三个接口都接受 `current`（默认 1）和 `size`（默认 10，范围 1–100），返回 `data: {total,current,size,records}`。无效页码/每页条数返回 400、`INVALID_LIST_PAGE`。记录按创建时间倒序，同时间按 ID 倒序。Application 和 API Key 的可选 ID 过滤供详情页直达使用，过滤条件仍受原有归属和软删除限制；禁用状态不妨碍所有者查看列表。

前端三个主列表都提供翻页和自定义每页条数。短链接页的 Application/Key 选择器按每页 10 项浏览；从 Key 详情直达时用同一查询的 ID 过滤保留所选项。从调试台仅带 `apiKeyId` 跳转时，当前仍需按页遍历本人 Application 再定位 Key：这是现有入口缺少 Application ID 的代价，未来可通过让创建结果或入口携带 Application ID 消除该遍历，不应在本轮新增一个无授权边界的全局 Key 查询。

验证：`ManagementListPaginationIntegrationTests` 在真实 MySQL 中为三个列表各插入 11 条，覆盖第二页、自定义大小、权限隔离、ID 过滤和非法参数；`nexus-console/e2e/listPagination.spec.ts` 覆盖浏览器跨页及页外详情直达。
