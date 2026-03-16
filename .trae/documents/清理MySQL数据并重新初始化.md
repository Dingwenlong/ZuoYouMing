## 问题原因

MySQL 使用了数据卷挂载 `./docker/mysql/data:/var/lib/mysql`，数据库数据被持久化到本地。Docker MySQL 的初始化脚本只在数据目录为空时才会执行，所以即使重新运行 `run-system.bat`，旧的数据库结构仍然存在。

## 解决步骤

1. **停止 Docker 容器**
   ```bash
   docker-compose -p library-seat down
   ```

2. **删除本地 MySQL 数据目录**
   ```bash
   Remove-Item -Recurse -Force docker/mysql/data
   ```

3. **重新启动系统**
   ```bash
   ./run-system.bat
   ```

或者使用一键清理命令：
```bash
docker-compose -p library-seat down && Remove-Item -Recurse -Force docker/mysql/data && ./run-system.bat
```

## 预期结果

- MySQL 数据目录被清空
- Docker 重新创建容器并执行 `init.sql` 初始化脚本
- 新的 `sys_user` 表结构包含 `student_id` 字段
- 系统正常启动，不再报错

请确认后执行。