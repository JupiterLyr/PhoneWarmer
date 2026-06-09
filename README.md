# Phone Warmer

## Development Motivation
The application is designed to achieve rapid discharge and significant temperature rise through extensive computations, and to visualize a range of system parameters.

## Architecture

```text
com.jupiterlyr.phonewarmer
├── MainActivity.java
├── MainViewModel.java
├── monitor
│   ├── BatteryMonitor.java
│   └── BatterySnapshot.java
├── monitor
│   └── BurnService.java
├── ui
│   └── MainUiState.java
└── workload
    └── WorkloadEngine.java

```