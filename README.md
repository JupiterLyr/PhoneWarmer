# Phone Warmer

## Development Motivation
The application is designed to achieve rapid discharge and significant temperature rise through extensive computations, and to visualize a range of system parameters.

## Architecture

```text
PhoneWarmer/
├── MainActivity.java          // 主入口
├── monitor/                   // 监视器
│   ├── BatteryMonitor.java
│   ├── BatterySnapshot.java
│   ├── SystemMonitor.java
│   └── SystemStats.java
├── workload/                  // 高负载引擎
│   ├── WorkloadEngine.java
│   └── GPURenderEngine.java
└── service/
    └── BurnService.java
```

---

*Updated by* **JupiterLyr** *at* 2026-06-09 13:50 
