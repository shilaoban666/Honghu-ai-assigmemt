# 系统指标监控功能说明

## 功能概述

本项目集成了系统指标监控功能，可以在应用启动时自动收集并显示系统各项关键指标，同时也提供了REST API接口供实时查询。

## 启动时自动收集的指标

应用启动完成后会自动在日志中打印以下指标：

### 操作系统信息
- 操作系统名称和版本
- 系统架构
- 可用处理器数量

### 内存使用情况
- **堆内存**：初始大小、已使用、已提交、最大值、使用率
- **非堆内存**：初始大小、已使用、已提交、最大值、使用率
- **总体内存使用率**

### 线程信息
- 当前线程数
- 峰值线程数
- 守护线程数
- 总启动线程数

### CPU信息
- 可用处理器数
- 系统CPU负载
- JVM进程CPU负载
- JVM CPU时间

## REST API接口

### 1. 获取实时系统指标
```
GET /api/monitor/metrics
```

**响应示例：**
```json
{
  "availableProcessors": 20,
  "heapMemoryUsed": "34.84",
  "heapMemoryMax": "1984",
  "heapMemoryUsage": "1.76",
  "nonHeapMemoryUsed": "55.37",
  "nonHeapMemoryMax": "-0",
  "nonHeapMemoryUsage": "N/A",
  "threadCount": 24,
  "peakThreadCount": 24,
  "daemonThreadCount": 20,
  "totalStartedThreadCount": 27
}
```

### 2. 获取系统概览信息
```
GET /api/monitor/overview
```

**响应示例：**
```json
{
  "applicationName": "testDeepseekR1",
  "timestamp": "2026-02-22T12:10:10.5918486",
  "status": "RUNNING",
  "cpuProcessors": 20,
  "heapUsage": "1.91%",
  "activeThreads": 24,
  "daemonThreads": 20
}
```

### 3. 刷新并重新打印指标
```
GET /api/monitor/refresh
```

**响应：**
```
指标已重新收集并打印到日志
```

## 组件说明

### 核心组件

1. **SystemMetricsCollector** (`monitor/SystemMetricsCollector.java`)
   - 负责收集各种系统指标
   - 提供格式化打印功能
   - 返回结构化的指标数据

2. **ApplicationStartupListener** (`listener/ApplicationStartupListener.java`)
   - 监听Spring Boot应用启动完成事件
   - 应用启动后自动触发指标收集

3. **MonitorController** (`controller/MonitorController.java`)
   - 提供REST API接口
   - 支持实时查询系统指标

4. **SystemMetrics** (`monitor/SystemMetrics.java`)
   - 系统指标数据传输对象
   - 用于API响应的数据结构

## 使用示例

### 1. 查看启动日志
应用启动时会自动在控制台打印系统指标：
```
=====================================
     系统指标监控报告
=====================================
【操作系统信息】
  操作系统名称: Windows 11
  操作系统版本: 10.0
  操作系统架构: amd64
  可用处理器数: 20
【内存使用情况】
  堆内存:
    初始大小: 124 MB
    已使用: 31.84 MB
    已提交: 70 MB
    最大值: 1984 MB
    使用率: 1.6%
  ...
=====================================
```

### 2. 通过API查询
```bash
# 获取详细指标
curl http://localhost:8080/api/monitor/metrics

# 获取系统概览
curl http://localhost:8080/api/monitor/overview

# 刷新指标
curl http://localhost:8080/api/monitor/refresh
```

### 3. 在Swagger UI中查看
访问：http://localhost:8080/swagger-ui.html
找到"系统监控"分类查看相关接口

## 技术实现细节

- 使用Java Management Extensions (JMX) API收集系统信息
- 通过`OperatingSystemMXBean`获取CPU和系统信息
- 通过`MemoryMXBean`获取内存使用情况
- 通过`ThreadMXBean`获取线程信息
- 支持Sun JVM特有扩展获取更详细的CPU负载信息

## 注意事项

1. **CPU负载信息**：只有在Sun/Oracle JVM上才能获取详细的CPU负载信息
2. **内存使用率**：非堆内存的最大值可能显示为-0（表示无限制）
3. **线程状态**：标准ThreadMXBean不提供详细的线程状态统计，如需更详细信息需要额外实现
4. **性能影响**：指标收集过程对性能影响极小，可在生产环境安全使用

## 扩展建议

如需增强监控功能，可考虑：
- 集成Micrometer进行更专业的指标收集
- 添加历史数据存储和趋势分析
- 实现指标告警功能
- 集成Prometheus/Grafana进行可视化监控