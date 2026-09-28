# T06 本机文件处理运行说明

本地 review 实例继续使用独立 law_r2_review 数据库，不改变默认 Worker 开关，也不授予 R2 发布验收。

## 配置

API 进程读取以下环境变量：

- `OLS_MATERIAL_STORE_PATH`：API 服务身份专用的私有文件目录。对象版本是随机 UUID，create-new 写入，不接受客户端路径或覆盖写入；读取重新核对服务端 SHA-256。
- `OLS_CLAMD_HOST`：默认 127.0.0.1。
- `OLS_CLAMD_PORT`：默认 3310；当前本机 review 使用 19447。
- `OLS_CLAMD_TIMEOUT_MS`：默认 15000，网络扫描绝对时限。

配置缺失或扫描不可用时安全拒绝，不将文件标记通过。文件名和说明采用受保护的数据库密文。文件字节仅存 API 身份私有目录，下载逐次授权并记录读取审计，不发布外链。

本机 ClamAV 容器使用官方镜像 `clamav/clamav@sha256:9cb27d7660bdf66e9878c832cb433dd8aa152cfbe16f3c2c0084c80b04ae22b4`，只发布 `127.0.0.1:19447:3310`；专用签名卷 `ontology-law-r2-review-clamav-db`，签名自动更新。实际扫描通过 INSTREAM 发送字节并记录 VERSION 返回值。

实现依据：[ClamAV 协议](https://docs.clamav.net/manual/Usage/ClamdProtocol.html)、[官方 Docker 配置](https://docs.clamav.net/manual/Installing/Docker.html)、[PDFBox 3.0.8](https://pdfbox.apache.org/)。PDF 做结构解析，图片做实际解码和像素上限检查；首版原文件统一授权下载，不声称解析或杀毒等于文件内容真实有效。

## 验证与恢复

`ClamdLiveProbeTest` 默认跳过；以 `-Dt06.clamd.live=true` 显式运行时连接本机 19447，验证正常文件、无害 EICAR 测试样本，以及真实 PNG 的存储后准确读取。

技术检查与业务接收分离。CHECKING/UNKNOWN 只查询原状态，未过期前禁止新会话或其它会话重传绕开未知状态。SCAN_UNAVAILABLE 表示本次未保存，可稍后重新选择。过期后旧会话不能接收。正式接收结果未知时只查询原命令回执。

更新本机实例前保存当前 jar、配置、SPA 和数据库备份；失败时先根据备份与迁移状态核对，不直接覆盖历史文件或数据库事实。部署脚本和账号文件位于本机忽略目录，不纳入版本库。
