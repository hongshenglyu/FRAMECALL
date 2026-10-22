# 本机 Windows 部署说明

## 必装环境

- JDK 17
- Maven 3.9+
- MySQL 8（最终演示配置）
- FFmpeg（后续真实切片、抽帧、结果视频生成）
- Python 3.11
- Node.js 20+ / npm

## 端口

- Vue 前端：`5173`
- Spring Boot 后端：`8080`
- Python AI 服务：`8001`

## 存储目录

默认所有上传、任务结果、标注文件保存在项目根目录的 `storage/` 下：

- `storage/uploads`: 原始上传视频
- `storage/tasks/task-{id}`: 单个分析任务的结果
- `storage/annotations`: 标注任务导入文件

## MySQL 配置

创建数据库：

```sql
CREATE DATABASE football_ref_assist CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

修改 `backend/src/main/resources/application-mysql.yml` 中的用户名和密码，然后以 `mysql` profile 启动。

