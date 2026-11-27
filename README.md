# KLEE Kernel Manager

Gerenciador Android especializado para controle e diagnóstico avançado do POCO X8 Pro (codinome `klee`, plataforma MediaTek **MT6899**, GPU Mali-G720).

## Funcionalidades (v0.2.0)

- **Dashboard ao Vivo**: telemetria rápida de CPU pico, GPU Mali, temperatura SoC, bateria, uso de RAM, status do Root (KernelSU) e troca rápida de perfis.
- **CPU Control**: grade de núcleos online em tempo real, controle por cluster com seletor travado na tabela real de frequências do kernel, seletor de governor com descrição, reset rápido para limites de hardware, verificação de read-back e rollback transacional.
- **GPU Control**: controle Mali-G720 via devfreq com seleção estrita sobre a tabela OPP, validação transacional e painel expansível de diagnóstico `gpufreqv2`.
- **Monitor Térmico**: resumo de pico/média e throttling ativo, filtro por categoria (Sensores, Cooling, MediaTek), busca instantânea, cores por severidade e barras de estado de `cooling_device`.
- **Bateria & Energia**: medidor de carga, tensão (V), corrente (mA), potência estimada (W), temperatura, saúde, ciclos, capacidades (mAh) e listagem de fontes (`/sys/class/power_supply`).
- **Monitor ao Vivo**: gráficos de linha em tempo real para uso de CPU (`%` via `/proc/stat`) e temperatura SoC (`°C`), barras de clocks por cluster/GPU, RAM/Swap, Load Average, Uptime, controle de pausa e intervalo (`1s` / `2s` / `5s`).
- **Perfis & Gaming**: 4 perfis inteligentes integrados (*Economia*, *Balanceado*, *Desempenho* e *Gaming* com opção de boost GPU), restauração de baseline original e criação/aplicação de perfis personalizados salvos localmente.
- **Memória, I/O & Rede**: monitoramento de RAM/ZRAM, ajuste seguro de `vm.swappiness`, `vm.vfs_cache_pressure` e `vm.dirty_ratio`, seleção de I/O scheduler e `read_ahead_kb`, controle de congestionamento TCP (`cubic`, `bbr`, etc.) e limpeza segura de caches.
- **Dispositivo, Relatório & Logs**: identidade completa do aparelho/kernel/SELinux, exportação de relatório técnico (copiar ou compartilhar) e histórico com timestamp das ações root executadas.

## Compilar

O GitHub Actions compila automaticamente o APK de debug em cada push para `main` e sob demanda, disponibilizando o APK como artefato do workflow e nas Releases do repositório.

Localmente, abra o projeto no Android Studio (JDK 17+, Android SDK 35, Gradle 8.9) e execute:

```bash
gradle --no-daemon assembleDebug
```

## Segurança

Todas as escritas em nós sysfs/procfs validam caminhos, limites de hardware/OPP e executam leitura de confirmação (*read-back*) com *rollback* automático em caso de falha ou divergência no kernel.

## Stack

- Kotlin 2.0.21 + Jetpack Compose (Material 3 Dark Obsidian)
- MVVM (`AndroidViewModel` + `StateFlow`)
- `libsu` 6.0.0 para execução root (KernelSU / Magisk)
- Android Gradle Plugin 8.7.3 / Android SDK 35
