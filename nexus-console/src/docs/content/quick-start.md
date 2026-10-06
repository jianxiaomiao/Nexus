## 开始前确认

你需要一个 Nexus 账号，并能登录开发者控制台。本地示例假设后端运行在 `http://localhost:8080`；如果改过端口，请同步替换。这里的 `localhost` 指**发送请求的设备自身**，不是所有设备都能访问的公共地址。

控制台管理操作使用登录后的 Bearer JWT；下面的开放 API 调用使用 API Key。它们代表不同身份，不能互换。

## 1. 创建 Application

登录控制台，打开 **Applications → 创建 Application**，填写一个便于识别的名称。创建后进入该应用的详情页。Application 是 API Key 的归属：如果应用被禁用，其下仍启用的 Key 也暂时不能调用开放 API。

如果已经有一个正常运行的 Application，可以直接使用它，无需重复创建。

## 2. 创建并保存 API Key

在应用详情页打开 **API Keys** 标签，选择 **创建 API Key**，填写名称。创建成功弹窗会显示**完整 Key**，请在关闭前保存到安全位置。

完整 Key 只显示这一次。之后列表和详情页仅展示遮蔽预览；`publicId` 和 `keyPreview` 都不能代替完整 Key 发请求。若丢失，请创建新 Key 并移除旧 Key。

> 完整 Key 是机器凭证。不要提交到 Git、放在浏览器前端代码中，或出现在日志、截图与公开文档里。部署后仅通过 HTTPS 传输。

## 3. 发送第一条请求

下面调用 `GET /v1/utils/uuid`。请把占位符换成**创建时保存的完整 Key**。

macOS / Linux 终端：

```bash
curl -i 'http://localhost:8080/v1/utils/uuid' \
  -H 'Authorization: ApiKey <完整密钥>'
```

Windows PowerShell 可以先用 `Read-Host` 输入 Key，避免将真实 Key 直接写进命令历史：

```powershell
$apiKey = Read-Host '输入完整 API Key'
Invoke-RestMethod -Uri 'http://localhost:8080/v1/utils/uuid' `
  -Headers @{ Authorization = "ApiKey $apiKey" }
```

成功时 HTTP 状态为 `200`，响应示例：

```json
{
  "code": "SUCCESS",
  "message": "UUID 已生成",
  "data": {
    "uuid": "8ac09c20-5818-4978-a7e2-3e37602eaadb"
  }
}
```

每次请求会生成新的 UUID，示例值不会固定。PowerShell 的 `Invoke-RestMethod` 会直接显示解析后的响应对象。

## 如果没有成功

| 现象 | 先检查什么 |
| --- | --- |
| 无法连接 | 后端是否启动、端口是否正确；若在手机或另一台电脑请求，不要使用 `localhost` |
| HTTP 401 | 是否用了完整 Key，以及 `Authorization: ApiKey <完整密钥>` 格式是否正确 |
| HTTP 403 | Key、所属 Application 或账号是否处于禁用状态 |

接着可以阅读左侧的 **API Key 与认证**、**生成 UUID**、**计算 Hash** 和 **短链接**。开放 API 的具体错误码也收录在 **错误码与排查**。
