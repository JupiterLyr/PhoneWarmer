# Phone Warmer

## Development Motivation
The application is designed to achieve rapid discharge and significant temperature rise through extensive computations, and to visualize a range of system parameters.

## Architecture

```text
PhoneWarmer/
├── MainActivity.java
├── MainViewBinder.java
├── monitor/
│   ├── BatteryMonitor.java
│   ├── BatterySnapshot.java
│   ├── CpuSource.java
│   ├── SystemMonitor.java
│   └── SystemStats.java
├── service/
│   ├── BurnService.java
│   └── BurnSession.java
└── workload/
    ├── GPURenderEngine.java
    └── WorkloadEngine.java
```

## Language
Chinese Simplified | 简体中文

---

*Updated by* **JupiterLyr** *at* 2026-06-16 00:50
