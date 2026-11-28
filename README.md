# KLEE Kernel Manager

Gerenciador Android completo, temático e especializado para o **POCO X8 Pro** (`2511FPC34G`, codinome `klee`, plataforma **MediaTek MT6899**, Mali-G720, HyperOS 3 / Android 16, Linux `6.6.89`, KernelSU).

## Funcionalidades (v0.4.0)

- **5 Temas Dinâmicos**: *Obsidian Cyber*, *AMOLED Pitch Black* (`#000000`), *Crimson Gaming*, *Emerald Matrix* e *Hyper Violet* — aplicados em tempo real no app e no HUD Flutuante.
- **Monitor Flutuante (HUD sobre outros apps)**:
  - Overlay arrastável (`FloatingMonitorService`) com modo **Compacto** e **HUD Completo** (toque simples para alternar), mostrando CPU %, pico MHz, GPU %/MHz, DRAM LPDDR5X, temperatura SoC/Bateria, potência em Watts/mA, RAM e taxa de atualização (Hz).
  - Métricas visíveis, opacidade e taxa de atualização totalmente configuráveis.
- **Perfis por Aplicativo (`PerAppEngineService`)**:
  - Motor automático em segundo plano que detecta o aplicativo em primeiro plano via root e aplica automaticamente **Perfil de Desempenho** (integrado ou personalizado), **Preset FPSGO/Metis**, **Taxa de Atualização de Tela (Hz)** e **Bypass Charging automático** por app, restaurando o padrão ao sair.
- **Controle de Taxa de Atualização de Tela (Hz)**:
  - Controle global e por aplicativo (Automática / 60 / 90 / 120 Hz conforme modos suportados pela tela) compatível com HyperOS 3 / Android 16.
- **Exportação Profissional de Logs (`.txt`)**:
  - Gera relatório completo com snapshot de hardware, regras Per-App, histórico de ações root, `logcat` do app e `dmesg` do kernel.
  - Salva diretamente em `/sdcard/Download/klee_log_*.txt`, compartilha o arquivo `.txt` via `FileProvider` ou copia para a área de transferência.
- **Controles Reais MT6899 / `klee`**:
  - **CPU & UCLAMP**: 3 clusters (`sugov_ext`), grade de núcleos online, seletores travados na tabela real e controle `top-app` UCLAMP.
  - **GPU Mali, MTK GED & DRAM LPDDR5X**: 49 OPPs Mali (`260–1508 MHz`), uso GPU `%` (GED), controles GED (`ged_smart_boost`, `boost_gpu_enable`, `is_GED_KPI_enabled`, `dcs_mode`, `dvfs_margin_value`) e DRAM `mtk-dvfsrc-devfreq` (`757–9600 MHz`).
  - **MTK FPSGO & Xiaomi Metis**: controles diretos de FPSGO e Metis com presets de 1 toque (*Gaming*, *Stock*, *Frio*).
  - **Thermal & Power**: sensores MT6899, throttling, telemetria elétrica completa e **Bypass Charging real** (`input_suspend`).
  - **Memória, ZRAM, MGLRU, UFS 4.0 & Rede**: taxa de compressão ZRAM (`mm_stat`), **MGLRU**, sliders VM, **UFS 4.0** (`wb_on`, `clkscale_enable`, `clkgate_enable`), I/O Schedulers + `iostats`, **TCP BBR** e **TCP Fast Open**.

## Segurança

Todas as escritas utilizam caminhos validados no kernel real com lista branca estrita, validação de faixas/OPPs e verificação *read-back* com *rollback*.
